package com.irrigation.task.service;

/**
 * 短信发送抽象（第三代第一版 §4.4 短信桩）
 *
 * 统一短信发送入口，本版用 {@link LogSmsSender} 桩实现（只打日志不真发、不花钱）；
 * 上线时替换为阿里云短信 / 腾讯云短信实现即可，业务层无感。
 */
public interface MessageSender {

    /**
     * 发送短信
     * @param phone 手机号（本版桩实现不校验格式）
     * @param text  短信内容
     */
    void send(String phone, String text);
}
