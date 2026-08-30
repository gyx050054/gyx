/**
 * 【文件职责】
 * ThingsBoard 管理端客户端（SysAdmin 权限）。
 *  - 以 SysAdmin 身份登录获取 JWT 并缓存（凭证只存服务端配置，绝不下发 App）；
 *  - 创建租户（一个注册用户 = 一个租户 + 一个租户管理员）；
 *  - 在租户下创建租户管理员（TENANT_ADMIN）；
 *  - 激活用户并设置默认密码（对应 noauth/activate 流程，不发激活邮件）。
 *  - 仅供「自助注册」链路使用；与 ThingsBoardClient（租户管理员权限、负责 RPC 下发）职责分离，避免权限混用。
 *
 * 【数据流】
 *  - 下游：ThingsBoard REST API（/api/auth/login、/api/tenant、/api/user、/api/user/{id}/activationLinkInfo、
 *    /api/noauth/activate）。
 *  - 上游：AuthService.register 按序调用 createTenant → createTenantAdmin → activateUser；
 *    分别返回新租户 ID、新用户 ID；激活成功后无返回值。
 *  - token 缓存：volatile 字段 + TOKEN_REFRESH_MARGIN_MS 余量提前刷新（与 ThingsBoardClient 一致）。
 *  - 激活流程：activationLinkInfo 返回的 value 为激活 URL → 从中解析 activateToken →
 *    再调 noauth/activate 设置默认密码；解析失败（缺 activateToken）抛 IllegalStateException。
 */
package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.config.ThingsBoardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ThingsBoard 管理端客户端（SysAdmin 权限，第二版新增）
 *
 * 职责（仅用于「TB 做不到的自助注册」链路）：
 *  - 以 SysAdmin 身份登录并缓存 JWT（凭证只存在服务端配置，绝不下发 App）；
 *  - 创建租户（一个注册用户 = 一个租户 + 一个租户管理员）；
 *  - 在租户下创建租户管理员（TENANT_ADMIN）；
 *  - 激活用户并设置默认密码（对应 noauth/activate 流程）。
 *
 * 设计说明（高内聚低耦合）：
 *  - 与 {@link ThingsBoardClient}（租户管理员权限、负责 RPC 下发）职责分离，
 *    各自持有独立 token 缓存，避免权限混用；
 *  - 所有 TB HTTP 细节收敛在本类，业务层（AuthService）不感知 TB 接口；
 *  - 未来若替换为 WebClient（响应式）或消息驱动，仅需改本类内部实现。
 */
@Service
public class ThingsBoardAdminClient {

    private static final Logger log = LoggerFactory.getLogger(ThingsBoardAdminClient.class);

    /** JWT 有效期安全余量：TB token 默认 2 小时，提前 90 分钟刷新 */
    private static final long TOKEN_TTL_MS = 90 * 60_000L;
    /** 刷新阈值：剩余有效期不足 60 秒时强制重新登录 */
    private static final long TOKEN_REFRESH_MARGIN_MS = 60_000L;

    private final ThingsBoardProperties props;
    private final RestClient rest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 缓存的 SysAdmin token 与过期时刻（volatile：多线程可见性） */
    private volatile String token;
    private volatile long tokenExpireAt;

    public ThingsBoardAdminClient(ThingsBoardProperties props) {
        this.props = props; // 保存 TB 基础配置（baseUrl/SysAdmin 账号/超时），供后续 HTTP 方法与登录使用
        // 超时从配置读取（与 ThingsBoardClient 保持一致）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory(); // 底层请求工厂：用于设置连接/读取超时
        factory.setConnectTimeout((int) props.getRpcTimeoutMs()); // 连接超时 = rpcTimeoutMs（配置）：TCP 建连最长等待
        factory.setReadTimeout((int) props.getRpcTimeoutMs()); // 读取超时 = rpcTimeoutMs（配置）：响应体到达最长等待
        this.rest = RestClient.builder().requestFactory(factory).build(); // 用带超时的工厂构建 RestClient 单例，供全程复用
    }

    /** 获取有效 SysAdmin token（线程安全；为空或即将过期时自动重新登录） */
    public synchronized String getToken() {
        if (token == null || tokenExpireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) { // 无缓存 token，或剩余有效期不足刷新余量（60 秒）→ 需重新登录
            login(); // 用 SysAdmin 账号重新登录并刷新 token/tokenExpireAt
        }
        return token; // 返回缓存的 SysAdmin token
    }

    /** 以 SysAdmin 身份登录并缓存 JWT */
    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode(); // 构造登录请求体（JSON 对象）
            body.put("username", props.getSysadminUsername()); // 登录账号 = 配置的 SysAdmin 用户名
            body.put("password", props.getSysadminPassword()); // 登录密码 = 配置的 SysAdmin 密码
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class); // 匿名 POST 登录接口（不带 token），返回 JsonNode 响应体
            JsonNode data = resp.getBody(); // 取出响应体
            if (data == null || !data.has("token")) { // 响应体为空或缺少 token 字段 → 视为登录异常
                throw new IllegalStateException("SysAdmin 登录响应缺少 token"); // 抛异常，由外层统一包装为登录失败
            }
            this.token = data.get("token").asText(); // 缓存 SysAdmin JWT 字符串
            this.tokenExpireAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS; // 记录过期时刻 = 当前时刻 + 预设 TTL（90 分钟）
            log.info("ThingsBoard SysAdmin 登录成功，token 已缓存");
        } catch (Exception e) {
            throw new IllegalStateException("ThingsBoard SysAdmin 登录失败: " + e.getMessage(), e); // 任何异常（账号密码错误/TB 不可达）→ 抛登录失败
        }
    }

    /**
     * 创建租户（注册流程第①步）
     *
     * @param title 租户标题（本系统用注册邮箱，便于运营区分农户）
     * @return 新租户 ID
     */
    public String createTenant(String title) {
        ObjectNode body = mapper.createObjectNode(); // 构造创建租户请求体（JSON 对象）
        body.put("title", title); // 租户标题 = 传入的 title（本系统用注册邮箱）
        ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/tenant", getToken(), body, JsonNode.class); // 携带 SysAdmin token POST 创建租户接口
        return resp.getBody().get("id").get("id").asText(); // 从响应体取新租户的 UUID（嵌套 id.id）并返回，作为后续步骤的 tenantId
    }

    /**
     * 在指定租户下创建租户管理员（注册流程第②步）
     *
     * @param email    管理员邮箱（即登录账号）
     * @param tenantId 目标租户 ID
     * @return 新用户 ID
     */
    public String createTenantAdmin(String email, String tenantId) {
        ObjectNode body = mapper.createObjectNode(); // 构造创建用户请求体（JSON 对象）
        body.put("email", email); // 管理员邮箱（即登录账号）
        body.put("authority", "TENANT_ADMIN"); // 权限角色 = 租户管理员（TENANT_ADMIN）
        ObjectNode tenantIdNode = mapper.createObjectNode(); // 构造嵌套的 tenantId 对象
        tenantIdNode.put("entityType", "TENANT"); // 实体类型 = 租户
        tenantIdNode.put("id", tenantId); // 目标租户 UUID（上一步 createTenant 的返回值）
        body.set("tenantId", tenantIdNode); // 把 tenantId 对象挂到请求体
        // sendActivationMail=false：不发激活邮件，改为后续 noauth/activate 直接设默认密码
        ResponseEntity<JsonNode> resp = postJson(
                props.getBaseUrl() + "/api/user?sendActivationMail=false", getToken(), body, JsonNode.class); // 携带 SysAdmin token 创建用户，且不发送激活邮件
        return resp.getBody().get("id").get("id").asText(); // 从响应体取新用户的 UUID（嵌套 id.id）并返回
    }

    /**
     * 激活用户并设置初始密码（注册流程第③步）
     * 步骤：取激活 token（activationLinkInfo）→ noauth/activate 设置默认密码
     */
    public void activateUser(String userId, String password) {
        // ① 获取激活信息：TB 4.x 的 activationLinkInfo 响应结构为
        //    {"value":"http://.../api/noauth/activate?activateToken=xxx","ttlMs":...}
        //    激活 token 嵌在 value 的 query 参数中（无独立 activateToken 字段），需解析提取
        ResponseEntity<JsonNode> info = getJson(
                props.getBaseUrl() + "/api/user/" + userId + "/activationLinkInfo", getToken(), JsonNode.class); // GET 该用户的激活链接信息接口，携带 SysAdmin token
        String activateUrl = info.getBody().get("value").asText(); // 取出激活相关 URL 字符串（value 字段）
        String activateToken = extractQueryParam(activateUrl, "activateToken"); // 从 URL 的 query 中解析出 activateToken
        if (activateToken == null || activateToken.isEmpty()) { // 提取不到激活 token → 无法走 noauth/activate 流程
            throw new IllegalStateException("激活 URL 缺少 activateToken 参数: " + activateUrl); // 抛异常终止注册流程
        }
        // ② 激活并设置默认密码
        ObjectNode body = mapper.createObjectNode(); // 构造激活请求体（JSON 对象）
        body.put("activateToken", activateToken); // 写入激活 token（上一行从 URL 解析得到）
        body.put("password", password); // 写入要设置的默认密码（本系统为 DEFAULT_PASSWORD）
        postJson(props.getBaseUrl() + "/api/noauth/activate", null, body, JsonNode.class); // 匿名 POST 激活接口（noauth 无需登录），设置初始密码
        log.info("用户 {} 已激活并设置初始密码", userId);
    }

    /**
     * 从 URL 中提取指定 query 参数（自动处理 URL 编码）
     *
     * @param url  完整 URL（形如 http://host/api/noauth/activate?activateToken=xxx）
     * @param name 参数名
     * @return 参数值；不存在时返回 null
     */
    private String extractQueryParam(String url, String name) {
        try {
            URI uri = new URI(url); // 把 URL 字符串解析成 URI 对象
            String query = uri.getRawQuery(); // 取原始 query 串（未解码，形如 activateToken=xxx&y=z）
            if (query == null) { // 没有 query 部分 → 参数必然不存在
                return null;
            }
            for (String pair : query.split("&")) { // 按 & 拆分每一对「参数名=值」对
                int eq = pair.indexOf('='); // 找「参数名=值」分隔符 = 的位置
                if (eq > 0 && pair.substring(0, eq).equals(name)) { // 分隔符存在且参数名等于目标名 → 命中
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8); // URL 解码后返回参数值
                }
            }
        } catch (Exception e) {
            log.warn("解析激活 URL 失败: {}", e.getMessage()); // URI 解析异常等 → 记日志并继续
        }
        return null; // 未找到参数或解析异常 → 返回 null
    }

    // ---------- 私有工具：统一 HTTP 构造，消除重复代码 ----------

    /**
     * 发送 JSON POST 请求（统一构造请求头 + 请求体）
     * @param url      目标地址（TB REST）
     * @param token    Bearer token；null 表示匿名请求（如登录）
     * @param bodyJson 请求体（JsonNode，序列化为 JSON 字符串）
     * @param respType 响应类型
     */

    private <T> ResponseEntity<T> postJson(String url, String token, JsonNode bodyJson, Class<T> respType) {
        return rest.post() // 发起 POST 请求
                .uri(url) // 目标地址（登录 /api/auth/login、建租户 /api/tenant、激活 /api/noauth/activate 等）
                .headers(h -> copyHeaders(h, token)) // 设置请求头（Content-Type: application/json + 可选 Bearer token）
                .body(bodyJson.toString()) // 请求体：JsonNode 序列化为 JSON 字符串后发送
                .retrieve() // 响应提取/解码策略
                .toEntity(respType); // 反序列化为指定类型并包装成 ResponseEntity
    }

    /**
     * 发送 JSON GET 请求（带统一请求头）
     * @param url      目标地址（TB REST）
     * @param token    Bearer token；null 表示匿名请求
     * @param respType 响应类型
     */
    private <T> ResponseEntity<T> getJson(String url, String token, Class<T> respType) {
        return rest.get() // 发起 GET 请求
                .uri(url) // 目标地址（含 query 串，如 activationLinkInfo）
                .headers(h -> copyHeaders(h, token)) // 设置请求头（Content-Type + 可选 Bearer token）
                .retrieve() // 响应提取策略
                .toEntity(respType); // 反序列化为指定类型并包装成 ResponseEntity
    }

    /**
     * 统一请求头注入（Content-Type + 可选 Bearer token）
     * @param target 目标 HttpHeaders
     * @param token  Bearer token；null 表示匿名请求
     */
    private void copyHeaders(HttpHeaders target, String token) {
        target.setContentType(MediaType.APPLICATION_JSON); // 设置 Content-Type: application/json
        if (token != null) { // 仅当携带 token 时
            target.setBearerAuth(token); // 注入 Authorization: Bearer <token>
        }
    }
}
