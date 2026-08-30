package com.irrigation.task.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器（统一 REST 错误响应）
 *
 * 说明：
 *  - IllegalArgumentException（如任务时间参数非法）→ 400 Bad Request，响应统一 {success:false, message}
 *  - 未预期异常 → 500，响应统一结构（日志记录堆栈，响应体不泄露内部细节）
 *  - 原实现中 TaskService 抛出的参数异常会落成 500，此处修复为规范的 400
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数非法 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 其他未预期异常 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleServerError(Exception e) {
        log.error("服务端未预期异常", e);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "服务器内部错误");
        return ResponseEntity.internalServerError().body(body);
    }
}
