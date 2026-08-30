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

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 租户注册：body {"email": "..."} */
    @PostMapping("/register")
    public ResponseEntity<JsonNode> register(@RequestBody JsonNode body) {
        String email = body.path("email").asText("").trim();
        try {
            authService.register(email);
            log.info("注册成功 email={}", email);
            return ResponseEntity.ok(ok(true, "注册成功，请登录"));
        } catch (IllegalArgumentException e) {          // 邮箱格式错
            log.warn("注册参数非法 email={}: {}", email, e.getMessage());
            return ResponseEntity.badRequest().body(ok(false, "邮箱格式不正确"));
        } catch (IllegalStateException e) {             // 邮箱重复（TB 已存在）
            log.warn("注册冲突 email={}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ok(false, "该邮箱已注册"));
        } catch (Exception e) {                         // TB 不可达/其它
            log.error("注册失败 email={}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ok(false, "注册失败，请稍后重试"));
        }
    }

    /** 查询是否需强制改密：GET /api/auth/must-change-password?email=... */
    @GetMapping("/must-change-password")
    public JsonNode mustChangePassword(@RequestParam String email) {
        boolean must = authService.isMustChangePassword(email);
        ObjectNode resp = ok(true, must ? "需要改密" : "无需改密");
        resp.put("mustChange", must);
        return resp;
    }

    /** 登记强制改密（员工账号创建后）：POST /api/auth/mark-must-change {"email": "..."} */
    @PostMapping("/mark-must-change")
    public ResponseEntity<JsonNode> markMustChange(@RequestBody JsonNode body) {
        String email = body.path("email").asText("");
        try {
            authService.markMustChangePassword(email);
            return ResponseEntity.ok(ok(true, "已登记强制改密"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        }
    }

    /** 标记已完成改密：POST /api/auth/pwd-changed {"email": "..."} */
    @PostMapping("/pwd-changed")
    public ResponseEntity<JsonNode> pwdChanged(@RequestBody JsonNode body) {
        String email = body.path("email").asText("");
        try {
            authService.markPasswordChanged(email);
            return ResponseEntity.ok(ok(true, "改密标记已清除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        }
    }

    // ---------- 私有工具：统一响应结构（与任务接口一致） ----------

    private ObjectNode ok(boolean success, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("success", success);
        n.put("message", message);
        return n;
    }
}
