package com.irrigation.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.entity.AlarmRecord;
import com.irrigation.task.entity.AlarmRule;
import com.irrigation.task.service.AlarmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 告警 REST API（自研告警引擎，第四版）
 *
 * GET    /api/alarms/rules                规则列表（可带 tenantId）
 * POST   /api/alarms/rules                创建规则
 * PUT    /api/alarms/rules/{id}           更新规则
 * DELETE /api/alarms/rules/{id}           删除规则
 * POST   /api/alarms/rules/{id}/toggle    启用/停用（?enabled=true|false）
 * GET    /api/alarms                      告警记录列表（?tenantId=&status=）
 * GET    /api/alarms/unread-count        未确认告警计数（App 顶栏红点）
 * POST   /api/alarms/{id}/ack             确认告警
 *
 * 响应结构统一 {success, message, ...}，与任务/认证接口一致。
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    // ---------- 规则管理 ----------

    /** 规则列表（App 规则管理页；可带 tenantId） */
    @GetMapping("/rules")
    public List<AlarmRule> listRules(@RequestParam(required = false) String tenantId) {
        return alarmService.listRules(tenantId);
    }

    /** 创建规则：body 为规则字段 JSON */
    @PostMapping("/rules")
    public ResponseEntity<JsonNode> createRule(@RequestBody JsonNode body) {
        try {
            AlarmRule rule = mapper.convertValue(body, AlarmRule.class);
            AlarmRule saved = alarmService.createRule(rule);
            ObjectNode resp = ok(true, "规则创建成功");
            resp.put("ruleId", saved.getId());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ok(false, "规则参数非法"));
        }
    }

    /** 更新规则（部分更新：仅更新 body 中显式出现的字段，未传字段保持不变） */
    @PutMapping("/rules/{id}")
    public ResponseEntity<JsonNode> updateRule(@PathVariable Long id, @RequestBody JsonNode body) {
        try {
            AlarmRule patch = new AlarmRule();
            if (body.has("name")) patch.setName(body.get("name").asText());
            if (body.has("deviceType")) patch.setDeviceType(body.get("deviceType").asText());
            if (body.has("metric")) patch.setMetric(body.get("metric").asText());
            if (body.has("operator")) patch.setOperator(body.get("operator").asText());
            if (body.has("threshold") && body.get("threshold").isNumber())
                patch.setThreshold(body.get("threshold").asDouble());
            if (body.has("severity")) patch.setSeverity(body.get("severity").asText());
            if (body.has("message") && !body.get("message").isNull())
                patch.setMessage(body.get("message").asText());
            if (body.has("enabled") && body.get("enabled").isBoolean())
                patch.setEnabled(body.get("enabled").asBoolean());
            Optional<AlarmRule> updated = alarmService.updateRule(id, patch);
            if (updated.isEmpty()) {
                return ResponseEntity.status(404).body(ok(false, "规则不存在"));
            }
            return ResponseEntity.ok(ok(true, "规则已更新"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ok(false, "规则参数非法"));
        }
    }

    /** 删除规则 */
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<JsonNode> deleteRule(@PathVariable Long id) {
        boolean ok = alarmService.deleteRule(id);
        return ok ? ResponseEntity.ok(ok(true, "规则已删除"))
                : ResponseEntity.status(404).body(ok(false, "规则不存在"));
    }

    /** 启用/停用规则：POST /api/alarms/rules/{id}/toggle?enabled=false */
    @PostMapping("/rules/{id}/toggle")
    public ResponseEntity<JsonNode> toggleRule(@PathVariable Long id,
                                               @RequestParam(defaultValue = "true") boolean enabled) {
        Optional<AlarmRule> updated = alarmService.toggleRule(id, enabled);
        if (updated.isEmpty()) {
            return ResponseEntity.status(404).body(ok(false, "规则不存在"));
        }
        return ResponseEntity.ok(ok(true, enabled ? "规则已启用" : "规则已停用"));
    }

    // ---------- 告警记录 ----------

    /** 告警记录列表（App 告警页；可带 tenantId、status 过滤） */
    @GetMapping
    public List<AlarmRecord> listRecords(@RequestParam(required = false) String tenantId,
                                         @RequestParam(required = false) String status) {
        AlarmRecord.Status st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = AlarmRecord.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                st = null;
            }
        }
        return alarmService.listRecords(tenantId, st);
    }

    /** 未确认告警计数（App 顶栏红点） */
    @GetMapping("/unread-count")
    public JsonNode unreadCount(@RequestParam(required = false) String tenantId) {
        long count = alarmService.unreadCount(tenantId);
        ObjectNode resp = ok(true, "success");
        resp.put("count", count);
        return resp;
    }

    /** 确认告警：POST /api/alarms/{id}/ack（红点消失） */
    @PostMapping("/{id}/ack")
    public ResponseEntity<JsonNode> ack(@PathVariable Long id) {
        boolean ok = alarmService.ack(id);
        return ok ? ResponseEntity.ok(ok(true, "已确认"))
                : ResponseEntity.status(404).body(ok(false, "告警不存在"));
    }

    // ---------- 私有工具：统一响应 ----------

    private ObjectNode ok(boolean success, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("success", success);
        n.put("message", message);
        return n;
    }
}
