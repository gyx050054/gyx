/**
 * 【文件职责】
 * ThingsBoard REST 客户端（设备网关实现，租户管理员权限）。
 *  - 以租户管理员身份登录获取 JWT 并缓存（快过期时自动刷新），支持按租户独立缓存 token（真多租户隔离）；
 *  - 下发 oneway RPC 指令（开/关/暂停阀门、设备/遥测查询）；
 *  - 对外供任务/告警业务使用：TaskService / TaskScanScheduler（设备控制下发）与 AlarmService（扫描读设备与遥测）。
 *
 * 【数据流】
 *  - 下游：ThingsBoard REST API（/api/auth/login、/api/rpc/oneway/{deviceId}、
 *    /api/tenant/deviceInfos、/api/plugins/telemetry/.../values/timeseries）。
 *  - 上游：业务层传入 deviceId / method / params 或 deviceType / key，
 *    本类构造 HTTP 请求 → 携带或复用缓存 token → 解析 JSON 响应 →
 *    返回下发结果（boolean）/ 设备列表（DeviceBrief）/ 遥测最新值（String）。
 *  - 凭证来源：TenantCredentialRepository（按租户凭证），若无凭证则回退全局默认账号（配置）。
 *  - token 缓存：volatile 字段 + ConcurrentHashMap（按租户），用 TOKEN_REFRESH_MARGIN_MS 余量提前刷新。
 *  - 失败降级：RPC 下发失败返回 false；遥测拉取失败返回 null（告警扫描将无遥测按 offline 处理）。
 */
package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.config.ThingsBoardProperties;
import com.irrigation.task.entity.TenantCredential;
import com.irrigation.task.repository.TenantCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ThingsBoard REST 客户端（设备网关实现）
 *
 * 职责：
 *  - 登录获取 JWT（缓存，快过期时刷新）
 *  - 下发 RPC（oneway：不等待设备回执，快速可靠）
 *
 * 设计说明（为未来铺垫）：
 *  - 本类封装了与 TB 的所有 HTTP 细节（认证、请求构造、超时）；
 *  - 未来若改用 WebClient（响应式）或经消息队列下发 RPC，只需替换本类内部实现，
 *    业务层（TaskService/TaskScanScheduler）调用的方法签名不变；
 *  - RPC 超时从配置读取（thingsboard.rpc-timeout-ms，默认 10 秒）。
 */
@Service
public class ThingsBoardClient {

    private static final Logger log = LoggerFactory.getLogger(ThingsBoardClient.class);

    /** JWT 有效期安全余量：TB token 默认 2 小时有效，提前 90 分钟刷新 */
    private static final long TOKEN_TTL_MS = 90 * 60_000L;
    /** 刷新阈值：token 剩余有效期不足 60 秒时强制重新登录 */
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000L;

    private final ThingsBoardProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantCredentialRepository tenantCredentialRepository;

    /** 缓存的有效 token 与过期时刻（volatile：多线程可见性） */
    private volatile String token;
    private volatile long tokenExpireAt; // 毫秒时间戳

    /** 按租户维护的独立 token 缓存（真多租户隔离：每租户用自己的 TB 账号登录） */
    private final Map<String, String> tenantTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> tenantTokenExpireAt = new ConcurrentHashMap<>();

    public ThingsBoardClient(ThingsBoardProperties props, TenantCredentialRepository tenantCredentialRepository) {
        this.props = props; // 保存 TB 基础配置（baseUrl/默认账号/超时），供本类所有 HTTP 方法与登录使用
        this.tenantCredentialRepository = tenantCredentialRepository; // 注入租户凭证查询仓库，供「真多租户」按租户取 TB 账号
        // 连接/读取超时统一从配置读取（原 rpcTimeoutMs 配置为死配置，此处让其真正生效）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); // 底层请求工厂：用于设置超时
        factory.setConnectTimeout((int) props.getRpcTimeoutMs()); // 连接超时 = rpcTimeoutMs（配置）：TCP 建连最长等待
        factory.setReadTimeout((int) props.getRpcTimeoutMs()); // 读取超时 = rpcTimeoutMs（配置）：响应体到达最长等待
        this.rest = RestClient.builder().requestFactory(factory).build(); // 用带超时的工厂构建 RestClient 单例，供全程复用
    }

    /**
     * 获取指定租户的有效 token（线程安全）。
     * 优先用该租户自己的 TB 凭证登录（真多租户隔离）；若该租户无凭证则回退到全局默认账号。
     */
    public synchronized String getTokenForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) { // 租户为空：无多租户语义，直接走全局默认账号
            return getToken(); // 复用全局 token（全局账号登录后缓存在 token/tokenExpireAt）
        }
        long expireAt = tenantTokenExpireAt.getOrDefault(tenantId, 0L); // 读该租户缓存的过期时刻；未缓存时默认 0（视为已过期）
        String cached = tenantTokens.get(tenantId); // 读该租户缓存的有效 token；可能为 null（从未登录过）
        if (cached == null || expireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) { // 无缓存或剩余有效期低于刷新余量 → 需（重新）登录
            loginAsTenant(tenantId); // 用该租户自身凭证登录，并把结果写入租户 token 缓存
        }
        return tenantTokens.get(tenantId); // 返回该租户缓存中的 token（登录成功后必然已写入）
    }

    /** 以指定租户的 TB 账号登录并缓存其 token（凭证来自 tenant_credentials 表） */
    private void loginAsTenant(String tenantId) {
        try {
            TenantCredential cred = tenantCredentialRepository.findByTenantId(tenantId) // 按租户 ID 查 TB 登录凭证
                    .orElse(null); // 未登记则返回 null（不抛异常）
            if (cred == null) {
                // 该租户未登记凭证：回退到全局默认账号（兼容已有演示数据）
                String g = getToken(); // 登录全局账号并返回其 token（同时写入全局缓存）
                tenantTokens.put(tenantId, g); // 把全局 token 存入该租户 token 缓存
                tenantTokenExpireAt.put(tenantId, tokenExpireAt); // 同步该租户过期时刻为全局 token 的过期时刻
                return;
            }
            ObjectNode body = mapper.createObjectNode(); // 构造登录请求体（JSON 对象）
            body.put("username", cred.getEmail()); // 登录账号 = 该租户凭证的邮箱
            body.put("password", cred.getPassword()); // 登录密码 = 该租户凭证的密码
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class); // 匿名 POST 登录接口，返回 JsonNode 响应体
            JsonNode data = resp.getBody(); // 取出响应体
            if (data == null || !data.has("token")) { // 响应体为空或缺少 token 字段 → 视为登录异常
                throw new IllegalStateException("租户 " + tenantId + " 登录响应缺少 token");
            }
            String t = data.get("token").asText(); // 提取 JWT 字符串
            long expAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS; // 估算过期时刻 = 当前时刻 + 预设 TTL（90 分钟）
            tenantTokens.put(tenantId, t); // 写入该租户 token 缓存
            tenantTokenExpireAt.put(tenantId, expAt); // 写入该租户过期时刻缓存
            log.info("ThingsBoard 租户 {} 登录成功，token 已缓存", cred.getEmail());
        } catch (Exception e) {
            log.warn("租户 {} 登录失败，回退全局账号: {}", tenantId, e.getMessage());
            String g = getToken(); // 登录失败 → 降级：改用全局默认账号
            tenantTokens.put(tenantId, g); // 全局 token 落入该租户缓存（保证不返回 null）
            tenantTokenExpireAt.put(tenantId, tokenExpireAt); // 同步过期时刻为全局 token 的过期时刻
        }
    }

    /**
     * 获取有效 token（线程安全）；token 为空或即将过期时自动重新登录
     */
    public synchronized String getToken() {
        if (token == null || tokenExpireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) { // 无 token 或剩余有效期不足刷新余量 → 需重新登录
            login(); // 登录全局账号并刷新 token/tokenExpireAt
        }
        return token; // 返回缓存的全局 token
    }

    /**
     * 登录 ThingsBoard 并缓存 JWT
     *
     * @throws IllegalStateException 登录失败（账号密码错误 / TB 不可达）
     */
    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode(); // 构造登录请求体（JSON 对象）
            body.put("username", props.getUsername()); // 全局默认账号用户名（来自配置）
            body.put("password", props.getPassword()); // 全局默认账号密码（来自配置）
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class); // 匿名 POST 登录接口，返回 JsonNode
            JsonNode data = resp.getBody(); // 取出响应体
            if (data == null || !data.has("token")) { // 响应体为空或缺少 token 字段 → 登录异常
                throw new IllegalStateException("登录响应缺少 token");
            }
            this.token = data.get("token").asText(); // 缓存 JWT 字符串
            this.tokenExpireAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS; // 记录过期时刻 = 当前时刻 + TTL
            log.info("ThingsBoard 登录成功，token 已缓存（有效期 {} 分钟）", TOKEN_TTL_MS / 60_000);
        } catch (Exception e) {
            throw new IllegalStateException("ThingsBoard 登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下发 oneway RPC 指令（核心方法）
     *
     * @param deviceId 设备 ID（ThingsBoard）
     * @param method   RPC 方法名：setValveState / pauseValve / getValveStatus
     * @param params   参数对象（如 {"state": true}）
     * @return true = 下发成功（2xx）；false = 失败（设备离线/网络异常等）
     */
    public boolean sendRpc(String deviceId, String method, JsonNode params) {
        try {
            ObjectNode body = mapper.createObjectNode(); // 构造 RPC 请求体（JSON 对象）
            body.put("method", method); // 写入 RPC 方法名（setValveState/pauseValve/getValveStatus）
            body.set("params", params); // 写入 RPC 参数对象（如 {"state":true}）
            ResponseEntity<String> resp = postJson( // POST 到 oneway RPC 端点，携带全局 token
                    props.getBaseUrl() + "/api/rpc/oneway/" + deviceId, getToken(), body, String.class);
            return resp.getStatusCode().is2xxSuccessful(); // 以 HTTP 状态是否为 2xx 判定下发成功（true=成功）
        } catch (Exception e) {
            log.error("RPC 下发失败 deviceId={} method={}: {}", deviceId, method, e.getMessage());
            return false; // 任何异常（设备离线/网络/解析错误）→ 下发失败返回 false
        }
    }

    // ---------- 便捷方法：按业务语义封装 RPC ----------

    /** 开启阀门（method=setValveState, state=true） */
    public boolean openValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode(); // 构造参数体
        p.put("state", true); // setValveState 的 state=true（开阀）
        return sendRpc(deviceId, "setValveState", p); // 复用 sendRpc 下发开启指令并返回结果
    }

    /** 关闭阀门（method=setValveState, state=false） */
    public boolean closeValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode(); // 构造参数体
        p.put("state", false); // setValveState 的 state=false（关阀）
        return sendRpc(deviceId, "setValveState", p); // 复用 sendRpc 下发关闭指令并返回结果
    }

    /** 暂停阀门（method=pauseValve，取消运行中任务时调用） */
    public boolean pauseValve(String deviceId) {
        return sendRpc(deviceId, "pauseValve", mapper.createObjectNode()); // 无参数，直接下发 pauseValve 指令并返回结果
    }

    // ---------- 私有工具：统一 HTTP 构造，消除重复代码 ----------

    /**
     * 发送 JSON POST 请求（统一构造 HttpHeaders/HttpEntity）
     *
     * @param url      目标地址
     * @param token    Bearer token；null 表示匿名请求（如登录）
     * @param bodyJson 请求体（JsonNode，序列化为 JSON 字符串）
     * @param respType 响应类型
     */
    private <T> ResponseEntity<T> postJson(String url, String token, JsonNode bodyJson, Class<T> respType) {
        // RestClient（Spring 6.1+，官方推荐替代 RestTemplate）：链式构建请求，语义清晰
        return rest.post() // 发起 POST 请求
                .uri(url) // 目标地址（由 data 流入：登录为 /api/auth/login、下发为 /api/rpc/oneway/...）
                .headers(h -> { // 设置请求头
                    h.setContentType(MediaType.APPLICATION_JSON); // Content-Type: application/json
                    if (token != null) { // 仅当携带 token 时
                        h.setBearerAuth(token); // 注入 Authorization: Bearer <token>
                    }
                })
                .body(bodyJson.toString()) // 请求体：JsonNode 序列化为 JSON 字符串后发送
                .retrieve() // 由响应提取/解码策略
                .toEntity(respType); // 反序列化为指定类型并包装成 ResponseEntity
    }

    // ---------- 告警引擎数据读取（自研告警引擎用） ----------

    /**
     * 发送 JSON GET 请求（统一构造请求头）
     * @param url      目标地址（TB REST）
     * @param token    Bearer token
     * @param respType 响应类型
     */
    private <T> ResponseEntity<T> getJson(String url, String token, Class<T> respType) {
        return rest.get() // 发起 GET 请求
                .uri(url) // 目标地址（含 query 串，如设备列表/遥测时间序列）
                .headers(h -> { // 设置请求头
                    if (token != null) { // 仅当携带 token 时
                        h.setBearerAuth(token); // 注入 Authorization: Bearer <token>
                    }
                })
                .retrieve() // 响应提取策略
                .toEntity(respType); // 反序列化为指定类型并包装成 ResponseEntity
    }

    /** 设备信息（扫描告警时按类型枚举设备用） */
    public record DeviceBrief(String id, String name, String type) {}

    /**
     * 按设备类型分页拉取租户下设备（告警扫描：规则按 deviceType 匹配设备）
     * @param deviceType TB 的设备 Profile 类型名，如 VALVE / TEMPERATURE_HUMIDITY / SOIL_MOISTURE；null=全部
     * @return 设备列表（id/name/type）
     */
    public List<DeviceBrief> listDevicesByType(String deviceType) {
        return listDevicesByType(deviceType, null); // 未指定租户 → 委托二参版本并用全局默认账号
    }

    /**
     * 按设备类型分页拉取指定租户下设备（真多租户隔离：用该租户自己的 TB token）
     * @param deviceType TB 的设备 Profile 类型名；null=全部
     * @param tenantId   租户 ID；null=用全局默认账号
     * @return 设备列表（id/name/type）
     */
    public List<DeviceBrief> listDevicesByType(String deviceType, String tenantId) {
        List<DeviceBrief> result = new ArrayList<>(); // 结果容器：收集所有页的设备
        int page = 0; // 当前页码（TB 从 0 开始）
        int pageSize = 100; // 每页条数（TB 单页上限为 100）
        String authToken = (tenantId == null || tenantId.isBlank()) ? getToken() : getTokenForTenant(tenantId); // 按租户取 token：无租户走全局账号
        while (true) { // 分页循环：逐页拉取直到最后一页
            String q = "pageSize=" + pageSize + "&page=" + page + "&sortProperty=name&sortOrder=ASC"; // 分页+按名称升序排序的查询串
            if (deviceType != null && !deviceType.isBlank()) { // 指定了设备类型才追加过滤条件
                q += "&type=" + deviceType; // 追加设备 Profile 类型过滤
            }
            ResponseEntity<JsonNode> resp = getJson( // GET 租户设备列表接口
                    props.getBaseUrl() + "/api/tenant/deviceInfos?" + q, authToken, JsonNode.class);
            JsonNode body = resp.getBody(); // 取出响应体
            if (body == null) { // 响应体为空 → 无法继续分页，直接结束
                break;
            }
            JsonNode data = body.get("data"); // 取 TB 分页结果的 data 数组（当前页设备）
            if (data == null || !data.isArray()) { // 缺 data 或不是数组 → 视为未取到数据，结束
                break;
            }
            for (JsonNode d : data) { // 遍历当前页每台设备
                result.add(new DeviceBrief( // 提取 id/name/type 组装为轻量 DTO 后加入结果
                        d.path("id").path("id").asText(),
                        d.path("name").asText(),
                        d.path("type").asText()));
            }
            int totalPages = body.path("totalPages").asInt(-1); // 读取总页数；字段缺失时默认 -1
            if (totalPages < 0 || page >= totalPages - 1) { // 无总页数或已到最后一页 → 结束分页
                break;
            }
            page++; // 翻到下一页继续循环
        }
        return result; // 返回聚合后的全部设备列表
    }

    /**
     * 查询某设备某遥测键的最新值（告警扫描：读实时值判规则）
     * @param deviceId ThingsBoard 设备 ID
     * @param key      遥测键名，如 soilSalinity / temperature / batteryLevel
     * @return 最新值字符串；无数据返回 null
     */
    public String latestTelemetry(String deviceId, String key) {
        return latestTelemetry(deviceId, key, null); // 未指定租户 → 委托三参版本并用全局默认账号
    }

    /**
     * 查询某设备某遥测键的最新值（指定租户 token）
     * @param deviceId ThingsBoard 设备 ID
     * @param key      遥测键名
     * @param tenantId 租户 ID；null=用全局默认账号
     * @return 最新值字符串；无数据返回 null
     */
    public String latestTelemetry(String deviceId, String key, String tenantId) {
        try {
            String authToken = (tenantId == null || tenantId.isBlank()) ? getToken() : getTokenForTenant(tenantId); // 按租户取 token：无租户走全局账号
            ResponseEntity<JsonNode> resp = getJson( // GET 设备最新遥测时间序列接口
                    props.getBaseUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId
                            + "/values/timeseries?keys=" + key,
                    authToken, JsonNode.class);
            JsonNode node = resp.getBody(); // 取出响应体
            if (node == null || node.isNull()) { // 响应体为空/JSON null → 视为无遥测
                return null;
            }
            JsonNode arr = node.get(key); // 取其 key 对应的时间序列值数组
            if (arr == null || arr.size() == 0) { // 无该键或数组为空 → 无遥测
                return null;
            }
            return arr.get(0).path("value").asText(); // 取最新一条（数组第 0 项）的 value 字符串
        } catch (Exception e) {
            log.warn("拉取遥测失败 deviceId={} key={}: {}", deviceId, key, e.getMessage());
            return null; // 失败降级：返回 null（告警扫描将无遥测视为 offline/无数据）
        }
    }
}
