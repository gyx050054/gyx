package com.irrigation.task.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * ThingsBoard 连接配置（从 application.yml 读取）
 */
@Configuration
public class ThingsBoardProperties {

    @Value("${thingsboard.base-url}")
    private String baseUrl;

    @Value("${thingsboard.username}")
    private String username;

    @Value("${thingsboard.password}")
    private String password;

    /** RPC 下发超时（毫秒） */
    @Value("${thingsboard.rpc-timeout-ms:10000}")
    private long rpcTimeoutMs;

    public String getBaseUrl() { return baseUrl; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
}
