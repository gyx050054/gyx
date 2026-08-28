package com.irrigation.task.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 短信桩实现（第三代第一版 §4.4）
 *
 * 仅打印日志，不实际发送短信、不花钱。注释明确标注「未接入真实短信」，
 * 避免被误当已真发。上线时以真实短信服务替换实现即可。
 */
@Component
public class LogSmsSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    @Override
    public void send(String phone, String text) {
        // 桩实现：只记录，不发送
        log.info("[短信桩·未接入真实短信] 收件人={} 内容={}", phone, text);
    }
}
