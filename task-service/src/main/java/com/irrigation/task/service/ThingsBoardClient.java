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
        this.props = props;
        this.tenantCredentialRepository = tenantCredentialRepository;
        // 连接/读取超时统一从配置读取（原 rpcTimeoutMs 配置为死配置，此处让其真正生效）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getRpcTimeoutMs());
        factory.setReadTimeout((int) props.getRpcTimeoutMs());
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 获取指定租户的有效 token（线程安全）。
     * 优先用该租户自己的 TB 凭证登录（真多租户隔离）；若该租户无凭证则回退到全局默认账号。
     */
    public synchronized String getTokenForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return getToken();
        }
        long expireAt = tenantTokenExpireAt.getOrDefault(tenantId, 0L);
        String cached = tenantTokens.get(tenantId);
        if (cached == null || expireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) {
            loginAsTenant(tenantId);
        }
        return tenantTokens.get(tenantId);
    }

    /** 以指定租户的 TB 账号登录并缓存其 token（凭证来自 tenant_credentials 表） */
    private void loginAsTenant(String tenantId) {
        try {
            TenantCredential cred = tenantCredentialRepository.findByTenantId(tenantId)
                    .orElse(null);
            if (cred == null) {
                // 该租户未登记凭证：回退到全局默认账号（兼容已有演示数据）
                String g = getToken();
                tenantTokens.put(tenantId, g);
                tenantTokenExpireAt.put(tenantId, tokenExpireAt);
                return;
            }
            ObjectNode body = mapper.createObjectNode();
            body.put("username", cred.getEmail());
            body.put("password", cred.getPassword());
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class);
            JsonNode data = resp.getBody();
            if (data == null || !data.has("token")) {
                throw new IllegalStateException("租户 " + tenantId + " 登录响应缺少 token");
            }
            String t = data.get("token").asText();
            long expAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS;
            tenantTokens.put(tenantId, t);
            tenantTokenExpireAt.put(tenantId, expAt);
            log.info("ThingsBoard 租户 {} 登录成功，token 已缓存", cred.getEmail());
        } catch (Exception e) {
            log.warn("租户 {} 登录失败，回退全局账号: {}", tenantId, e.getMessage());
            String g = getToken();
            tenantTokens.put(tenantId, g);
            tenantTokenExpireAt.put(tenantId, tokenExpireAt);
        }
    }

    /**
     * 获取有效 token（线程安全）；token 为空或即将过期时自动重新登录
     */
    public synchronized String getToken() {
        if (token == null || tokenExpireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) {
            login();
        }
        return token;
    }

    /**
     * 登录 ThingsBoard 并缓存 JWT
     *
     * @throws IllegalStateException 登录失败（账号密码错误 / TB 不可达）
     */
    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("username", props.getUsername());
            body.put("password", props.getPassword());
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class);
            JsonNode data = resp.getBody();
            if (data == null || !data.has("token")) {
                throw new IllegalStateException("登录响应缺少 token");
            }
            this.token = data.get("token").asText();
            this.tokenExpireAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS;
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
            ObjectNode body = mapper.createObjectNode();
            body.put("method", method);
            body.set("params", params);
            ResponseEntity<String> resp = postJson(
                    props.getBaseUrl() + "/api/rpc/oneway/" + deviceId, getToken(), body, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("RPC 下发失败 deviceId={} method={}: {}", deviceId, method, e.getMessage());
            return false;
        }
    }

    // ---------- 便捷方法：按业务语义封装 RPC ----------

    /** 开启阀门（method=setValveState, state=true） */
    public boolean openValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode();
        p.put("state", true);
        return sendRpc(deviceId, "setValveState", p);
    }

    /** 关闭阀门（method=setValveState, state=false） */
    public boolean closeValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode();
        p.put("state", false);
        return sendRpc(deviceId, "setValveState", p);
    }

    /** 暂停阀门（method=pauseValve，取消运行中任务时调用） */
    public boolean pauseValve(String deviceId) {
        return sendRpc(deviceId, "pauseValve", mapper.createObjectNode());
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
        return rest.post()
                .uri(url)
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .body(bodyJson.toString())
                .retrieve()
                .toEntity(respType);
    }

    // ---------- 告警引擎数据读取（自研告警引擎用） ----------

    /**
     * 发送 JSON GET 请求（统一构造请求头）
     * @param url      目标地址（TB REST）
     * @param token    Bearer token
     * @param respType 响应类型
     */
    private <T> ResponseEntity<T> getJson(String url, String token, Class<T> respType) {
        return rest.get()
                .uri(url)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .retrieve()
                .toEntity(respType);
    }

    /** 设备信息（扫描告警时按类型枚举设备用） */
    public record DeviceBrief(String id, String name, String type) {}

    /**
     * 按设备类型分页拉取租户下设备（告警扫描：规则按 deviceType 匹配设备）
     * @param deviceType TB 的设备 Profile 类型名，如 VALVE / TEMPERATURE_HUMIDITY / SOIL_MOISTURE；null=全部
     * @return 设备列表（id/name/type）
     */
    public List<DeviceBrief> listDevicesByType(String deviceType) {
        return listDevicesByType(deviceType, null);
    }

    /**
     * 按设备类型分页拉取指定租户下设备（真多租户隔离：用该租户自己的 TB token）
     * @param deviceType TB 的设备 Profile 类型名；null=全部
     * @param tenantId   租户 ID；null=用全局默认账号
     * @return 设备列表（id/name/type）
     */
    public List<DeviceBrief> listDevicesByType(String deviceType, String tenantId) {
        List<DeviceBrief> result = new ArrayList<>();
        int page = 0;
        int pageSize = 100;
        String authToken = (tenantId == null || tenantId.isBlank()) ? getToken() : getTokenForTenant(tenantId);
        while (true) {
            String q = "pageSize=" + pageSize + "&page=" + page + "&sortProperty=name&sortOrder=ASC";
            if (deviceType != null && !deviceType.isBlank()) {
                q += "&type=" + deviceType;
            }
            ResponseEntity<JsonNode> resp = getJson(
                    props.getBaseUrl() + "/api/tenant/deviceInfos?" + q, authToken, JsonNode.class);
            JsonNode body = resp.getBody();
            if (body == null) {
                break;
            }
            JsonNode data = body.get("data");
            if (data == null || !data.isArray()) {
                break;
            }
            for (JsonNode d : data) {
                result.add(new DeviceBrief(
                        d.path("id").path("id").asText(),
                        d.path("name").asText(),
                        d.path("type").asText()));
            }
            int totalPages = body.path("totalPages").asInt(-1);
            if (totalPages < 0 || page >= totalPages - 1) {
                break;
            }
            page++;
        }
        return result;
    }

    /**
     * 查询某设备某遥测键的最新值（告警扫描：读实时值判规则）
     * @param deviceId ThingsBoard 设备 ID
     * @param key      遥测键名，如 soilSalinity / temperature / batteryLevel
     * @return 最新值字符串；无数据返回 null
     */
    public String latestTelemetry(String deviceId, String key) {
        return latestTelemetry(deviceId, key, null);
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
            String authToken = (tenantId == null || tenantId.isBlank()) ? getToken() : getTokenForTenant(tenantId);
            ResponseEntity<JsonNode> resp = getJson(
                    props.getBaseUrl() + "/api/plugins/telemetry/DEVICE/" + deviceId
                            + "/values/timeseries?keys=" + key,
                    authToken, JsonNode.class);
            JsonNode node = resp.getBody();
            if (node == null || node.isNull()) {
                return null;
            }
            JsonNode arr = node.get(key);
            if (arr == null || arr.size() == 0) {
                return null;
            }
            return arr.get(0).path("value").asText();
        } catch (Exception e) {
            log.warn("拉取遥测失败 deviceId={} key={}: {}", deviceId, key, e.getMessage());
            return null;
        }
    }
}
