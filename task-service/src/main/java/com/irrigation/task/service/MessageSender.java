/**
 * 【文件职责】
 * 短信发送抽象接口（统一短信入口）。
 *  - 定义发送短信契约 send(phone, text)；本版由 LogSmsSender 桩实现（只打日志不真发、不花钱）。
 *
 * 【数据流】
 *  - 上游：业务层（如告警通知）注入 MessageSender 并调用 send(phone, text)。
 *  - 下游：由具体实现决定 —— 本版 {@link LogSmsSender} 打日志；上线时替换为阿里云/腾讯云短信实现，业务层无感。
 */
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
