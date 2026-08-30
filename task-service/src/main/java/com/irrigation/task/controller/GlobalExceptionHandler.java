/**
 * 【文件职责】全局异常处理器（统一 REST 错误响应）：捕获 Controller 层抛出的异常并转换为 {success:false,message} 的统一错误结构，避免内部细节泄露给前端。
 * 【数据流】Controller/Service 抛异常 → @RestControllerAdvice 拦截 → IllegalArgumentException 转 400、其余未预期异常转 500 → 返回统一错误体 {success:false,message}。
 */
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

    // 全局异常日志，记录参数校验失败与未预期异常（后者含完整堆栈）。
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数非法 → 400 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        // 记录参数校验失败日志（只记消息，不记堆栈，避免刷屏）。
        log.warn("参数校验失败: {}", e.getMessage());
        // 新建可变 Map 作为统一错误响应体（success:false + message）。
        Map<String, Object> body = new HashMap<>();
        // 写入 success=false 标志。
        body.put("success", false);
        // 把异常消息原样写入 message，便于前端据此提示。
        body.put("message", e.getMessage());
        // 返回 HTTP 400 Bad Request 与错误体。
        return ResponseEntity.badRequest().body(body);
    }

    /** 其他未预期异常 → 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleServerError(Exception e) {
        // 记录未预期异常日志（含完整堆栈），供后端排查，不返回给前端。
        log.error("服务端未预期异常", e);
        // 新建可变 Map 作为统一错误响应体。
        Map<String, Object> body = new HashMap<>();
        // 写入 success=false 标志。
        body.put("success", false);
        // 写入固定兜底提示，不暴露内部异常细节。
        body.put("message", "服务器内部错误");
        // 返回 HTTP 500 Internal Server Error 与错误体。
        return ResponseEntity.internalServerError().body(body);
    }
}
