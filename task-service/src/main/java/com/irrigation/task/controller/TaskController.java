package com.irrigation.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.entity.Task;
import com.irrigation.task.entity.TaskRun;
import com.irrigation.task.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务 REST API（供 APP 调用）
 *
 * POST   /api/tasks         创建任务（单个或批量）
 * GET    /api/tasks         查询任务（默认全部；可带 tenantId 参数按租户过滤，第二版多租户）
 * DELETE /api/tasks/{id}    取消任务（软删除：置 CANCELLED；运行中先发暂停）
 *
 * 重构说明（高内聚低耦合）：
 *  - 本类只做「HTTP 入参解析 + 响应拼装」，业务逻辑全部委托 {@link TaskService}；
 *  - 不再直接注入 TaskRepository（修复原实现绕过 Service 直接查库的分层破坏）；
 *  - 响应结构保持与原版完全一致（success/message/taskId/count/taskIds），APP 无需改动。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建任务。支持两种请求体：
     * 单条: {"deviceId":"...","deviceName":"...","startTime":...,"endTime":...,"action":"on"}
     * 批量: {"devices":[{"deviceId":"...","startTime":...,"endTime":...}, ...]}
     */
    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body) {
        if (body.has("devices")) {
            return createBatch(body.get("devices"));
        }
        return createSingle(body);
    }

    /** 单个创建（需求文档 ④）：参数解析 → TaskService 创建 → 拼响应 */
    private ResponseEntity<JsonNode> createSingle(JsonNode body) {
        String deviceId = body.path("deviceId").asText("");
        String deviceName = body.path("deviceName").asText(deviceId);
        Long start = body.has("startTime") ? body.get("startTime").asLong() : null;
        Long end = body.has("endTime") ? body.get("endTime").asLong() : null;
        String action = body.path("action").asText(); // null 时由 TaskService 收敛为默认 on
        String tenantId = body.path("tenantId").asText(null); // 第二版多租户：可选，APP 从 JWT 解析提交
        // 每日任务（第三代第一版 §2）：可选参数
        String repeatModeStr = body.path("repeatMode").asText("ONCE"); // ONCE / DAILY
        Integer dailyHour = body.has("dailyHour") ? body.get("dailyHour").asInt() : null;
        Integer durationMinutes = body.has("durationMinutes") ? body.get("durationMinutes").asInt() : null;
        Task.RepeatMode repeatMode = "DAILY".equalsIgnoreCase(repeatModeStr)
                ? Task.RepeatMode.DAILY : Task.RepeatMode.ONCE;

        // DAILY 任务：时长用 dailyHour+durationMinutes；ONCE：start/end 必填
        if (repeatMode == Task.RepeatMode.DAILY) {
            if (dailyHour == null || dailyHour < 0 || dailyHour > 23 || durationMinutes == null || durationMinutes <= 0) {
                return ResponseEntity.badRequest().body(error("每日任务参数非法：需要 dailyHour(0-23) 与 durationMinutes(>0)"));
            }
            if (deviceId.isEmpty()) {
                return ResponseEntity.badRequest().body(error("参数非法：deviceId 必填"));
            }
        } else {
            if (deviceId.isEmpty() || start == null || end == null || end <= start) {
                return ResponseEntity.badRequest().body(error("参数非法：deviceId/startTime/endTime 必填，endTime 需大于 startTime"));
            }
        }
        Task t = taskService.createTask(deviceId, deviceName, start, end, action, tenantId,
                repeatMode, dailyHour, durationMinutes);
        if (t == null) {
            ObjectNode resp = ok(false, "设备 " + deviceId + " 此时段已有任务，添加失败（冲突）");
            return ResponseEntity.ok(resp);
        }
        ObjectNode resp = ok(true, "添加成功");
        resp.put("taskId", t.getId());
        resp.put("repeatMode", t.getRepeatMode() == null ? "ONCE" : t.getRepeatMode().name());
        return ResponseEntity.ok(resp);
    }

    /**
     * 批量创建（需求文档 ⑤，多选设备）：
     * 先整体冲突预检（任一冲突全部拒绝），预检通过后逐个创建
     */
    private ResponseEntity<JsonNode> createBatch(JsonNode devices) {
        List<TaskService.TaskDraft> drafts = new ArrayList<>();
        for (JsonNode d : devices) {
            Long s = d.has("startTime") ? d.get("startTime").asLong() : null;
            Long e = d.has("endTime") ? d.get("endTime").asLong() : null;
            String id = d.path("deviceId").asText("");
            if (s == null || e == null || e <= s || id.isEmpty()) {
                return ResponseEntity.badRequest().body(error("批量任务参数非法（deviceId/startTime/endTime 必填）"));
            }
            drafts.add(new TaskService.TaskDraft(
                    id, d.path("deviceName").asText(id), s, e, d.path("action").asText(),
                    d.path("tenantId").asText(null)));
        }
        // TaskService 内部完成整体冲突预检：任一冲突返回空列表（全部拒绝）
        List<Task> created = taskService.createTasks(drafts);
        if (created.isEmpty()) {
            return ResponseEntity.ok(ok(false, "存在设备时间冲突，全部任务拒绝添加"));
        }
        ObjectNode resp = ok(true, "批量添加成功，共 " + created.size() + " 条");
        resp.put("count", created.size());
        ArrayNode ids = resp.putArray("taskIds");
        created.forEach(t -> ids.add(t.getId()));
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询任务列表（任务管理：含已完成/已取消）
     * 可选参数 tenantId：第二版多租户隔离（各租户只见自己的任务）
     */
    @GetMapping
    public List<Task> list(@RequestParam(required = false) String tenantId) {
        return taskService.listAll(tenantId);
    }

    /** 查询每天任务的全部执行流水（task_runs）：GET /api/tasks/{id}/runs */
    @GetMapping("/{id}/runs")
    public JsonNode runs(@PathVariable Long id) {
        return mapper.valueToTree(taskService.listRuns(id));
    }

    /** 取消任务（软删除：置 CANCELLED；运行中先发 pauseValve 暂停） */
    @DeleteMapping("/{id}")
    public ResponseEntity<JsonNode> delete(@PathVariable Long id) {
        boolean ok = taskService.cancelTask(id);
        ObjectNode resp = ok(ok, ok ? "任务已取消" : "任务不存在或不可取消");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    /**
     * 删除设备时取消其全部未完成任务（APP 删除设备前调用，需求文档 5.3）
     * 路径与 /{id} 不冲突：本路径为两段（device/{deviceId}），/{id} 为单段
     */
    @DeleteMapping("/device/{deviceId}")
    public ResponseEntity<JsonNode> cancelByDevice(@PathVariable String deviceId) {
        int n = taskService.cancelTasksByDevice(deviceId);
        ObjectNode resp = ok(true, "已取消该设备 " + n + " 条未完成任务");
        return ResponseEntity.ok(resp);
    }

    // ---------- 私有工具：统一响应结构（与原版完全一致） ----------

    /** 标准成功/失败响应：{success, message} */
    private ObjectNode ok(boolean success, String message) {
        ObjectNode n = mapper.createObjectNode();
        n.put("success", success);
        n.put("message", message);
        return n;
    }

    /** 参数错误响应（400）：{success:false, message} */
    private JsonNode error(String msg) {
        return ok(false, msg);
    }
}
