package com.irrigation.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ThingsBoard 连接配置（从 application.yml 读取，前缀 thingsboard.*）
 *
 * 重构说明：原实现用 @Value 逐字段注入，字段一多就散乱；
 * 改用 @ConfigurationProperties 绑定，配置项集中管理且支持默认值与校验，
 * 后续新增配置（如重试次数、队列名）只需在此类加字段。
 */
@Component
@ConfigurationProperties(prefix = "thingsboard")
public class ThingsBoardProperties {

    /** TB 服务地址，如 http://localhost:8080 */
    private String baseUrl;

    /** TB 登录账号（租户管理员） */
    private String username;

    /** TB 登录密码 */
    private String password;

    /** RPC 下发超时（毫秒），默认 10 秒；同时用作 RestTemplate 连接/读取超时 */
    private long rpcTimeoutMs = 10_000L;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
    public void setRpcTimeoutMs(long rpcTimeoutMs) { this.rpcTimeoutMs = rpcTimeoutMs; }
}
