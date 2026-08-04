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
 * GET    /api/tasks         查询全部任务
 * DELETE /api/tasks/{id}    删除任务（未开始直接删 / 已开始发暂停）
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

    /** 查询全部任务（文档 6：任务管理返回所有任务表里剩下的数据） */
    @GetMapping
    public List<Task> list() {
        return taskRepository.findAll();
    }

    /** 删除任务 */
    @DeleteMapping("/{id}")
    public ResponseEntity<JsonNode> delete(@PathVariable Long id) {
        ObjectNode resp = mapper.createObjectNode();
        boolean ok = scheduler.deleteTask(id);
        resp.put("success", ok);
        resp.put("message", ok ? "删除成功" : "任务不存在");
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    private JsonNode error(String msg) {
        ObjectNode n = mapper.createObjectNode();
        n.put("success", false);
        n.put("message", msg);
        return n;
    }
}
