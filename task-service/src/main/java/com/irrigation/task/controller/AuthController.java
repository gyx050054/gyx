/**
 * 【文件职责】认证 REST 控制器（第二版）：负责接收 APP 的租户注册、强制改密状态查询/登记/已完成标记请求，参数解析后委托 AuthService，返回统一 {success,message} 响应。
 * 【数据流】APP → HTTP /api/auth/** → 本控制器解析请求体 email → AuthService 注册/改密标记 → 返回 {success,message,mustChange...} 或 400/409/500。
 */
package com.irrigation.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST API（第二版新增，供 App 注册 / 强制改密流程调用）
 *
 * POST /api/auth/register              租户注册（默认密码 123456 + 登记强制改密）
 * GET  /api/auth/must-change-password  查询指定邮箱是否需强制改密
 * POST /api/auth/pwd-changed           标记指定邮箱已完成改密
 *
 * 响应结构：统一 {success, message}（与任务接口一致，APP 无需适配两套格式）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 日志记录注册/改密流程；authService 承载注册与改密标记业务；mapper 用于请求体/响应体节点转换。
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造注入 AuthService，由 Spring 容器装配。 */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 租户注册：body {"email": "..."} */
    @PostMapping("/register")
    public ResponseEntity<JsonNode> register(@RequestBody JsonNode body) {
        // 从请求体读取 email（缺省空串）并去除首尾空白，作为下方注册入参与日志标识。
        String email = body.path("email").asText("").trim();
        try {
            // 委托 AuthService 执行租户注册（默认密码 123456 + 登记强制改密）。
            authService.register(email);
            // 注册成功：记录 info 日志（含 email）。
            log.info("注册成功 email={}", email);
            // 返回 HTTP 200 成功响应。
            return ResponseEntity.ok(ok(true, "注册成功，请登录"));
        } catch (IllegalArgumentException e) {          // 邮箱格式错
            // 参数非法（如邮箱格式错误）：记录 warn 日志，返回 HTTP 400。
            log.warn("注册参数非法 email={}: {}", email, e.getMessage());
            return ResponseEntity.badRequest().body(ok(false, "邮箱格式不正确"));
        } catch (IllegalStateException e) {             // 邮箱重复（TB 已存在）
            // 邮箱已注册（TokenBucket 中已存在）：记录 warn 日志，返回 HTTP 409 冲突。
            log.warn("注册冲突 email={}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ok(false, "该邮箱已注册"));
        } catch (Exception e) {                         // TB 不可达/其它
            // 其它未预期异常（存储不可达等）：记录 error 日志（含堆栈），返回 HTTP 500。
            log.error("注册失败 email={}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ok(false, "注册失败，请稍后重试"));
        }
    }

    /** 查询是否需强制改密：GET /api/auth/must-change-password?email=... */
    @GetMapping("/must-change-password")
    public JsonNode mustChangePassword(@RequestParam String email) {
        // 委托 AuthService 查询该邮箱是否处于强制改密状态。
        boolean must = authService.isMustChangePassword(email);
        // 拼装基础成功响应，message 随 must 给“需要改密/无需改密”文案。
        ObjectNode resp = ok(true, must ? "需要改密" : "无需改密");
        // 把强制改密布尔值写入 mustChange 字段返回给 App。
        resp.put("mustChange", must);
        return resp;
    }

    /** 登记强制改密（员工账号创建后）：POST /api/auth/mark-must-change {"email": "..."} */
    @PostMapping("/mark-must-change")
    public ResponseEntity<JsonNode> markMustChange(@RequestBody JsonNode body) {
        // 从请求体读取 email（缺省空串）。
        String email = body.path("email").asText("");
        try {
            // 委托 AuthService 登记该邮箱为需强制改密。
            authService.markMustChangePassword(email);
            // 成功：返回 HTTP 200。
            return ResponseEntity.ok(ok(true, "已登记强制改密"));
        } catch (IllegalArgumentException e) {
            // 参数非法（如 email 缺失/格式错误）：返回 HTTP 400 并把异常消息作为 message。
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        }
    }

    /** 标记已完成改密：POST /api/auth/pwd-changed {"email": "..."} */
    @PostMapping("/pwd-changed")
    public ResponseEntity<JsonNode> pwdChanged(@RequestBody JsonNode body) {
        // 从请求体读取 email（缺省空串）。
        String email = body.path("email").asText("");
        try {
            // 委托 AuthService 清除该邮箱的强制改密标记。
            authService.markPasswordChanged(email);
            // 成功：返回 HTTP 200。
            return ResponseEntity.ok(ok(true, "改密标记已清除"));
        } catch (IllegalArgumentException e) {
            // 参数非法：返回 HTTP 400 并把异常消息作为 message。
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        }
    }

    // ---------- 私有工具：统一响应结构（与任务接口一致） ----------

    private ObjectNode ok(boolean success, String message) {
        // 新建空的 JSON 对象节点。
        ObjectNode n = mapper.createObjectNode();
        // 写入 success 布尔标志。
        n.put("success", success);
        // 写入 message 提示文案。
        n.put("message", message);
        // 返回拼装好的统一响应节点。
        return n;
    }
}
