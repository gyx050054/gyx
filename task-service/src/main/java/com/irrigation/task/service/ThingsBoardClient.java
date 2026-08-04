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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

/**
 * ThingsBoard REST 客户端
 * - 登录获取 JWT（缓存，快过期时刷新）
 * - 下发 RPC（oneway：不等待设备回执，快速可靠）
 */
@Service
public class ThingsBoardClient {

    private static final Logger log = LoggerFactory.getLogger(ThingsBoardClient.class);

    private final ThingsBoardProperties props;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String token;
    private volatile long tokenExpireAt;   // 毫秒

    public ThingsBoardClient(ThingsBoardProperties props) {
        this.props = props;
    }

    /** 获取有效 token（过期前 60 秒刷新） */
    public synchronized String getToken() {
        if (token == null || tokenExpireAt - 60_000 < Instant.now().toEpochMilli()) {
            login();
        }
        return token;
    }

    private void login() {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("username", props.getUsername());
            body.put("password", props.getPassword());
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<JsonNode> resp = rest.postForEntity(
                    props.getBaseUrl() + "/api/auth/login",
                    new HttpEntity<>(body.toString(), h), JsonNode.class);
            JsonNode data = resp.getBody();
            if (data == null || !data.has("token")) {
                throw new IllegalStateException("登录响应缺少 token");
            }
            this.token = data.get("token").asText();
            // JWT 有效期默认 2 小时，安全起见 90 分钟刷新
            this.tokenExpireAt = Instant.now().toEpochMilli() + 90 * 60_000;
            log.info("ThingsBoard 登录成功，token 已缓存");
        } catch (Exception e) {
            throw new IllegalStateException("ThingsBoard 登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下发 oneway RPC 指令
     *
     * @param deviceId 设备 ID（ThingsBoard）
     * @param method   RPC 方法名：setValveState / pauseValve / getValveStatus
     * @param params   参数对象（如 {"state": true}）
     * @return true=下发成功
     */
    public boolean sendRpc(String deviceId, String method, JsonNode params) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("method", method);
            body.set("params", params);

            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.setBearerAuth(getToken());
            ResponseEntity<String> resp = rest.postForEntity(
                    props.getBaseUrl() + "/api/rpc/oneway/" + deviceId,
                    new HttpEntity<>(body.toString(), h), String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("RPC 下发失败 deviceId={} method={}: {}", deviceId, method, e.getMessage());
            return false;
        }
    }

    /** 便捷方法：开启阀门 */
    public boolean openValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode();
        p.put("state", true);
        return sendRpc(deviceId, "setValveState", p);
    }

    /** 便捷方法：关闭阀门 */
    public boolean closeValve(String deviceId) {
        ObjectNode p = mapper.createObjectNode();
        p.put("state", false);
        return sendRpc(deviceId, "setValveState", p);
    }

    /** 便捷方法：暂停阀门（删除运行中任务时用） */
    public boolean pauseValve(String deviceId) {
        return sendRpc(deviceId, "pauseValve", mapper.createObjectNode());
    }
}
