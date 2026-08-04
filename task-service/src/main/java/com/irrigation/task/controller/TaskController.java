package com.irrigation.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.irrigation.task.entity.Task;
import com.irrigation.task.repository.TaskRepository;
import com.irrigation.task.service.TaskSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务 REST API（供 APP 调用）
 *
 * POST   /api/tasks         创建任务（单个或批量）
 * GET    /api/tasks         查询全部任务（含已完成/已取消）
 * DELETE /api/tasks/{id}    取消任务（软删除：置 CANCELLED；运行中先发暂停）
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskSchedulerService scheduler;
    private final TaskRepository taskRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskController(TaskSchedulerService scheduler, TaskRepository taskRepository) {
        this.scheduler = scheduler;
        this.taskRepository = taskRepository;
    }

    /**
     * 创建任务。支持两种请求体：
     * 单条: {"deviceId":"...","deviceName":"...","startTime":...,"endTime":...,"action":"on"}
     * 批量: {"devices":[{"deviceId":"...","startTime":...,"endTime":...}, ...]}
     */
    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body) {
        ObjectNode resp = mapper.createObjectNode();

        if (body.has("devices")) {
            // ---------- 批量创建（文档 5：多选设备）----------
            // 先整体冲突检测：任一设备冲突则全部拒绝
            ArrayNode devices = (ArrayNode) body.get("devices");
            List<Task> created = new ArrayList<>();
            for (JsonNode d : devices) {
                Long s = d.has("startTime") ? d.get("startTime").asLong() : null;
                Long e = d.has("endTime") ? d.get("endTime").asLong() : null;
                String id = d.path("deviceId").asText("");
                if (s == null || e == null || e <= s || id.isEmpty()) {
                    return ResponseEntity.badRequest().body(error("批量任务参数非法（deviceId/startTime/endTime 必填）"));
                }
                if (scheduler.hasConflict(id, s, e)) {
                    resp.put("success", false);
                    resp.put("message", "设备 " + id + " 存在时间冲突，全部任务拒绝添加");
                    return ResponseEntity.ok(resp);
                }
            }
            for (JsonNode d : devices) {
                Task t = scheduler.createTask(
                        d.path("deviceId").asText(),
                        d.path("deviceName").asText(d.path("deviceId").asText()),
                        d.get("startTime").asLong(),
                        d.get("endTime").asLong(),
                        d.path("action").asText("on"));
                if (t != null) created.add(t);
            }
            resp.put("success", true);
            resp.put("message", "批量添加成功，共 " + created.size() + " 条");
            resp.put("count", created.size());
            ArrayNode ids = resp.putArray("taskIds");
            created.forEach(t -> ids.add(t.getId()));
            return ResponseEntity.ok(resp);
        }

        // ---------- 单个创建（文档 4）----------
        String deviceId = body.path("deviceId").asText("");
        String deviceName = body.path("deviceName").asText(deviceId);
        Long start = body.has("startTime") ? body.get("startTime").asLong() : null;
        Long end = body.has("endTime") ? body.get("endTime").asLong() : null;
        String action = body.path("action").asText("on");

        if (deviceId.isEmpty() || start == null || end == null || end <= start) {
            return ResponseEntity.badRequest().body(error("参数非法：deviceId/startTime/endTime 必填，endTime 需大于 startTime"));
        }
        Task t = scheduler.createTask(deviceId, deviceName, start, end, action);
        if (t == null) {
            resp.put("success", false);
            resp.put("message", "设备 " + deviceId + " 在此时段已有任务，添加失败（冲突）");
            return ResponseEntity.ok(resp);
        }
        resp.put("success", true);
        resp.put("message", "添加成功");
        resp.put("taskId", t.getId());
        return ResponseEntity.ok(resp);
    }

    /** 查询全部任务（任务管理：返回任务表所有数据，含 COMPLETED/CANCELLED） */
    @GetMapping
    public List<Task> list() {
        return taskRepository.findAll();
    }

    /** 取消任务（软删除：置 CANCELLED，用户需求 3.6.3；运行中先发暂停） */
    @DeleteMapping("/{id}")
    public ResponseEntity<JsonNode> delete(@PathVariable Long id) {
        ObjectNode resp = mapper.createObjectNode();
        boolean ok = scheduler.cancelTask(id);
        resp.put("success", ok);
        resp.put("message", ok ? "任务已取消" : "任务不存在或不可取消");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    private JsonNode error(String msg) {
        ObjectNode n = mapper.createObjectNode();
        n.put("success", false);
        n.put("message", msg);
        return n;
    }
}
