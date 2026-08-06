package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.config.ThingsBoardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

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
    private final RestTemplate rest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 缓存的有效 token 与过期时刻（volatile：多线程可见性） */
    private volatile String token;
    private volatile long tokenExpireAt; // 毫秒时间戳

    public ThingsBoardClient(ThingsBoardProperties props) {
        this.props = props;
        // 连接/读取超时统一从配置读取（原 rpcTimeoutMs 配置为死配置，此处让其真正生效）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.getRpcTimeoutMs());
        factory.setReadTimeout((int) props.getRpcTimeoutMs());
        this.rest = new RestTemplate(factory);
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(url, new HttpEntity<>(bodyJson.toString(), headers), respType);
    }
}
