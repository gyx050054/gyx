/**
 * 【文件职责】告警 REST 控制器（自研告警引擎·第四版）：负责接收 APP 的告警规则 CRUD、告警记录列表、未确认计数与告警确认请求，参数解析后委托 AlarmService，返回统一 {success,message} 响应。
 * 【数据流】APP → HTTP /api/alarms/** → 本控制器解析路径/查询参数与请求体 → AlarmService 业务校验+落库 → 返回 {success,message,ruleId/count...} 或 400/404。
 */
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

    // 告警业务服务（规则与记录的查询/校验/落库均委托它）；mapper 用于请求体与响应体的 JsonNode 转换。
    private final AlarmService alarmService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造注入 AlarmService，由 Spring 容器装配。 */
    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    // ---------- 规则管理 ----------

    /** 规则列表（App 规则管理页；可带 tenantId） */
    @GetMapping("/rules")
    public List<AlarmRule> listRules(@RequestParam(required = false) String tenantId) {
        // 直接把可选的 tenantId 透传给 AlarmService，由其按租户（为空则全部）查询规则列表并返回。
        return alarmService.listRules(tenantId);
    }

    /** 创建规则：body 为规则字段 JSON */
    @PostMapping("/rules")
    public ResponseEntity<JsonNode> createRule(@RequestBody JsonNode body) {
        try {
            // 请求体 JSON 反序列化为 AlarmRule 实体（字段名与规则字段一一对应）。
            AlarmRule rule = mapper.convertValue(body, AlarmRule.class);
            // 委托 AlarmService 校验并落库；返回带主键的新规则。
            AlarmRule saved = alarmService.createRule(rule);
            // 拼装基础成功响应 {success:true, message}。
            ObjectNode resp = ok(true, "规则创建成功");
            // 把新规则主键 ruleId 写入响应，供 App 定位该规则。
            resp.put("ruleId", saved.getId());
            // 返回 HTTP 200 成功响应体。
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            // 业务校验异常（如规则字段非法）：把异常消息作为 message 返回 HTTP 400。
            return ResponseEntity.badRequest().body(ok(false, e.getMessage()));
        } catch (Exception e) {
            // 其它未预期异常：统一兜底，返回 HTTP 400 与固定提示，不泄露内部细节。
            return ResponseEntity.badRequest().body(ok(false, "规则参数非法"));
        }
    }

    /** 更新规则（部分更新：仅更新 body 中显式出现的字段，未传字段保持不变） */
    @PutMapping("/rules/{id}")
    public ResponseEntity<JsonNode> updateRule(@PathVariable Long id, @RequestBody JsonNode body) {
        try {
            // 新建空补丁实体，后续仅对显式出现的字段赋值，实现部分更新。
            AlarmRule patch = new AlarmRule();
            // body 中出现 name 字段则取值并写入补丁。
            if (body.has("name")) patch.setName(body.get("name").asText());
            // body 中出现 deviceType 字段则取值并写入补丁。
            if (body.has("deviceType")) patch.setDeviceType(body.get("deviceType").asText());
            // body 中出现 metric 字段则取值并写入补丁。
            if (body.has("metric")) patch.setMetric(body.get("metric").asText());
            // body 中出现 operator 字段则取值并写入补丁。
            if (body.has("operator")) patch.setOperator(body.get("operator").asText());
            // threshold 仅在字段存在且为数字时才取值（小数），否则跳过以避免类型转换异常。
            if (body.has("threshold") && body.get("threshold").isNumber())
                patch.setThreshold(body.get("threshold").asDouble());
            // body 中出现 severity 字段则取值并写入补丁。
            if (body.has("severity")) patch.setSeverity(body.get("severity").asText());
            // message 仅在字段存在且非 JSON null 时取文本，避免用 "null" 覆盖已有消息。
            if (body.has("message") && !body.get("message").isNull())
                patch.setMessage(body.get("message").asText());
            // enabled 仅在字段存在且为布尔时取值，避免类型不匹配。
            if (body.has("enabled") && body.get("enabled").isBoolean())
                patch.setEnabled(body.get("enabled").asBoolean());
            // 委托 AlarmService 执行部分更新；返回 Optional 以区分“已更新”与“规则不存在”。
            Optional<AlarmRule> updated = alarmService.updateRule(id, patch);
            if (updated.isEmpty()) {
                // 规则不存在分支：返回 HTTP 404 与固定提示。
                return ResponseEntity.status(404).body(ok(false, "规则不存在"));
            }
            // 更新成功分支：返回 HTTP 200 成功响应。
            return ResponseEntity.ok(ok(true, "规则已更新"));
        } catch (Exception e) {
            // 其它未预期异常：统一兜底返回 HTTP 400。
            return ResponseEntity.badRequest().body(ok(false, "规则参数非法"));
        }
    }

    /** 删除规则 */
    @DeleteMapping("/rules/{id}")
    public ResponseEntity<JsonNode> deleteRule(@PathVariable Long id) {
        // 委托 AlarmService 删除规则；返回布尔值表示删除是否生效（false=规则不存在）。
        boolean ok = alarmService.deleteRule(id);
        // 按删除结果三目拼装：true 返回 200 成功，false 返回 404 不存在。
        return ok ? ResponseEntity.ok(ok(true, "规则已删除"))
                : ResponseEntity.status(404).body(ok(false, "规则不存在"));
    }

    /** 启用/停用规则：POST /api/alarms/rules/{id}/toggle?enabled=false */
    @PostMapping("/rules/{id}/toggle")
    public ResponseEntity<JsonNode> toggleRule(@PathVariable Long id,
                                               @RequestParam(defaultValue = "true") boolean enabled) {
        // 委托 AlarmService 切换规则启停状态；返回 Optional 表示是否存在该规则。
        Optional<AlarmRule> updated = alarmService.toggleRule(id, enabled);
        if (updated.isEmpty()) {
            // 规则不存在分支：返回 HTTP 404。
            return ResponseEntity.status(404).body(ok(false, "规则不存在"));
        }
        // 成功分支：按 enabled 布尔值给出“已启用/已停用”文案并返回 HTTP 200。
        return ResponseEntity.ok(ok(true, enabled ? "规则已启用" : "规则已停用"));
    }

    // ---------- 告警记录 ----------

    /** 告警记录列表（App 告警页；可带 tenantId、status 过滤） */
    @GetMapping
    public List<AlarmRecord> listRecords(@RequestParam(required = false) String tenantId,
                                         @RequestParam(required = false) String status) {
        // 定义最终用于过滤的枚举状态，默认 null（不过滤）。
        AlarmRecord.Status st = null;
        // 仅当 status 非空且非空白时才尝试解析为枚举。
        if (status != null && !status.isBlank()) {
            try {
                // 忽略大小写转大写后解析为状态枚举。
                st = AlarmRecord.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 状态字符串非法：捕获并置回 null（等同不过滤），避免脏参数抛异常。
                st = null;
            }
        }
        // 把 tenantId 与解析出的状态枚举透传给 AlarmService 查询记录列表。
        return alarmService.listRecords(tenantId, st);
    }

    /** 未确认告警计数（App 顶栏红点） */
    @GetMapping("/unread-count")
    public JsonNode unreadCount(@RequestParam(required = false) String tenantId) {
        // 委托 AlarmService 统计未确认告警数量。
        long count = alarmService.unreadCount(tenantId);
        // 拼装基础成功响应 {success:true, message:"success"}。
        ObjectNode resp = ok(true, "success");
        // 将计数写入 count 字段返回给 App。
        resp.put("count", count);
        return resp;
    }

    /** 确认告警：POST /api/alarms/{id}/ack（红点消失） */
    @PostMapping("/{id}/ack")
    public ResponseEntity<JsonNode> ack(@PathVariable Long id) {
        // 委托 AlarmService 确认告警；返回布尔表示是否确认成功（false=告警不存在）。
        boolean ok = alarmService.ack(id);
        // 按结果三目拼装：true 返回 200 已确认，false 返回 404 不存在。
        return ok ? ResponseEntity.ok(ok(true, "已确认"))
                : ResponseEntity.status(404).body(ok(false, "告警不存在"));
    }

    // ---------- 私有工具：统一响应 ----------

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
