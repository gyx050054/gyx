package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.config.ThingsBoardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 缓存的 SysAdmin token 与过期时刻（volatile：多线程可见性） */
    private volatile String token;
    private volatile long tokenExpireAt;

    public ThingsBoardAdminClient(ThingsBoardProperties props) {
        this.props = props;
        // 超时从配置读取（与 ThingsBoardClient 保持一致）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getRpcTimeoutMs());
        factory.setReadTimeout((int) props.getRpcTimeoutMs());
        this.rest = new RestTemplate(factory);
    }

    /** 获取有效 SysAdmin token（线程安全；为空或即将过期时自动重新登录） */
    public synchronized String getToken() {
        if (token == null || tokenExpireAt - TOKEN_REFRESH_MARGIN_MS < Instant.now().toEpochMilli()) {
            login();
        }
        return token;
    }

    /** 以 SysAdmin 身份登录并缓存 JWT */
    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("username", props.getSysadminUsername());
            body.put("password", props.getSysadminPassword());
            ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/auth/login", null, body, JsonNode.class);
            JsonNode data = resp.getBody();
            if (data == null || !data.has("token")) {
                throw new IllegalStateException("SysAdmin 登录响应缺少 token");
            }
            this.token = data.get("token").asText();
            this.tokenExpireAt = Instant.now().toEpochMilli() + TOKEN_TTL_MS;
            log.info("ThingsBoard SysAdmin 登录成功，token 已缓存");
        } catch (Exception e) {
            throw new IllegalStateException("ThingsBoard SysAdmin 登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建租户（注册流程第①步）
     *
     * @param title 租户标题（本系统用注册邮箱，便于运营区分农户）
     * @return 新租户 ID
     */
    public String createTenant(String title) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", title);
        ResponseEntity<JsonNode> resp = postJson(props.getBaseUrl() + "/api/tenant", getToken(), body, JsonNode.class);
        return resp.getBody().get("id").get("id").asText();
    }

    /**
     * 在指定租户下创建租户管理员（注册流程第②步）
     *
     * @param email    管理员邮箱（即登录账号）
     * @param tenantId 目标租户 ID
     * @return 新用户 ID
     */
    public String createTenantAdmin(String email, String tenantId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("email", email);
        body.put("authority", "TENANT_ADMIN");
        ObjectNode tenantIdNode = mapper.createObjectNode();
        tenantIdNode.put("entityType", "TENANT");
        tenantIdNode.put("id", tenantId);
        body.set("tenantId", tenantIdNode);
        // sendActivationMail=false：不发激活邮件，改为后续 noauth/activate 直接设默认密码
        ResponseEntity<JsonNode> resp = postJson(
                props.getBaseUrl() + "/api/user?sendActivationMail=false", getToken(), body, JsonNode.class);
        return resp.getBody().get("id").get("id").asText();
    }

    /**
     * 激活用户并设置初始密码（注册流程第③步）
     * 步骤：取激活 token（activationLinkInfo）→ noauth/activate 设置默认密码
     */
    public void activateUser(String userId, String password) {
        // ① 获取激活 token
        ResponseEntity<JsonNode> info = getJson(
                props.getBaseUrl() + "/api/user/" + userId + "/activationLinkInfo", getToken(), JsonNode.class);
        String activateToken = info.getBody().get("activateToken").asText();
        // ② 激活并设置默认密码
        ObjectNode body = mapper.createObjectNode();
        body.put("activateToken", activateToken);
        body.put("password", password);
        postJson(props.getBaseUrl() + "/api/noauth/activate", null, body, JsonNode.class);
        log.info("用户 {} 已激活并设置初始密码", userId);
    }

    // ---------- 私有工具：统一 HTTP 构造，消除重复代码 ----------

    private <T> ResponseEntity<T> postJson(String url, String token, JsonNode bodyJson, Class<T> respType) {
        HttpHeaders headers = baseHeaders(token);
        return rest.postForEntity(url, new HttpEntity<>(bodyJson.toString(), headers), respType);
    }

    private <T> ResponseEntity<T> getJson(String url, String token, Class<T> respType) {
        HttpHeaders headers = baseHeaders(token);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), respType);
    }

    private HttpHeaders baseHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }
}
