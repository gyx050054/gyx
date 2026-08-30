/**
 * 【文件职责】任务 REST 控制器：负责接收 APP 的创建/查询/取消任务请求（单个或批量），参数解析后委托 TaskService，返回统一 {success,message,taskId/count/taskIds} 响应。
 * 【数据流】APP → HTTP /api/tasks/** → 本控制器解析路径/查询/请求体参数 → TaskService 业务校验+落库 → 返回 taskId/count/taskIds 或 400/404。
 */
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

    // 任务业务服务（创建/查询/取消均委托它）；mapper 用于请求体与响应体的 JsonNode 转换。
    private final TaskService taskService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 构造注入 TaskService，由 Spring 容器装配。 */
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
        // 判断请求体是否为批量模式：存在 devices 数组字段即视为批量创建。
        if (body.has("devices")) {
            // 批量分支：取出 devices 数组，交给 createBatch 先整体冲突预检再逐个创建。
            return createBatch(body.get("devices"));
        }
        // 单条分支：无 devices 字段，视为单设备任务，交给 createSingle 解析参数并创建。
        return createSingle(body);
    }

    /** 单个创建（需求文档 ④）：参数解析 → TaskService 创建 → 拼响应 */
    private ResponseEntity<JsonNode> createSingle(JsonNode body) {
        // 从请求体读取设备编号，缺省空串（后续据此校验必填）。
        String deviceId = body.path("deviceId").asText("");
        // 从请求体读取设备名称；未传时回退为 deviceId，保证展示名不为空。
        String deviceName = body.path("deviceName").asText(deviceId);
        // 从请求体读取开始时间（毫秒时间戳）；字段缺失时置 null，后续校验必填。
        Long start = body.has("startTime") ? body.get("startTime").asLong() : null;
        // 从请求体读取结束时间（毫秒时间戳）；字段缺失时置 null，后续校验必填。
        Long end = body.has("endTime") ? body.get("endTime").asLong() : null;
        String action = body.path("action").asText(); // null 时由 TaskService 收敛为默认 on
        String tenantId = body.path("tenantId").asText(null); // 第二版多租户：可选，APP 从 JWT 解析提交
        // 每日任务（第三代第一版 §2）：可选参数
        String repeatModeStr = body.path("repeatMode").asText("ONCE"); // ONCE / DAILY
        // 读取每日任务的触发小时（0-23），缺失置 null。
        Integer dailyHour = body.has("dailyHour") ? body.get("dailyHour").asInt() : null;
        // 读取每日任务时长（分钟），缺失置 null。
        Integer durationMinutes = body.has("durationMinutes") ? body.get("durationMinutes").asInt() : null;
        // 把重复模式字符串映射为枚举：大小写不敏感，非 "DAILY" 一律归为 ONCE。
        Task.RepeatMode repeatMode = "DAILY".equalsIgnoreCase(repeatModeStr)
                ? Task.RepeatMode.DAILY : Task.RepeatMode.ONCE;

        // DAILY 任务：时长用 dailyHour+durationMinutes；ONCE：start/end 必填
        if (repeatMode == Task.RepeatMode.DAILY) {
            // 每日任务参数校验：dailyHour 须在 0-23 且 durationMinutes 须为正数，否则拒绝。
            if (dailyHour == null || dailyHour < 0 || dailyHour > 23 || durationMinutes == null || durationMinutes <= 0) {
                return ResponseEntity.badRequest().body(error("每日任务参数非法：需要 dailyHour(0-23) 与 durationMinutes(>0)"));
            }
            // 每日任务同样要求设备编号非空。
            if (deviceId.isEmpty()) {
                return ResponseEntity.badRequest().body(error("参数非法：deviceId 必填"));
            }
        } else {
            // 单次任务参数校验：设备号/起止时间必填，且 endTime 须晚于 startTime，否则拒绝。
            if (deviceId.isEmpty() || start == null || end == null || end <= start) {
                return ResponseEntity.badRequest().body(error("参数非法：deviceId/startTime/endTime 必填，endTime 需大于 startTime"));
            }
        }

        // 委托 TaskService 创建任务；返回 null 表示该时段已有任务（冲突）。
        Task t = taskService.createTask(deviceId, deviceName, start, end, action, tenantId, repeatMode, dailyHour, durationMinutes);
        if (t == null) {
            // 冲突分支：固定成功结构但 success:false，指明是此时段冲突，HTTP 200 返回给 APP。
            ObjectNode resp = ok(false, "设备 " + deviceId + " 此时段已有任务，添加失败（冲突）");
            return ResponseEntity.ok(resp);
        }
        // 成功分支：基础响应置 success:true。
        ObjectNode resp = ok(true, "添加成功");
        // 把新任务主键写入 taskId 字段返回给 APP。
        resp.put("taskId", t.getId());
        // 回写任务实际重复模式（枚举为 null 时按 ONCE），供 APP 展示。
        resp.put("repeatMode", t.getRepeatMode() == null ? "ONCE" : t.getRepeatMode().name());
        // 以 HTTP 200 返回创建成功响应。
        return ResponseEntity.ok(resp);
    }

    /**
     * 批量创建（需求文档 ⑤，多选设备）：
     * 先整体冲突预检（任一冲突全部拒绝），预检通过后逐个创建
     */
    private ResponseEntity<JsonNode> createBatch(JsonNode devices) {
        // 收集每条设备的待创建草稿，供 TaskService 先整体冲突预检再批量落库。
        List<TaskService.TaskDraft> drafts = new ArrayList<>();
        // 遍历请求体中的每个设备节点。
        for (JsonNode d : devices) {
            // 读取该设备开始时间（毫秒时间戳），缺失置 null。
            Long s = d.has("startTime") ? d.get("startTime").asLong() : null;
            // 读取该设备结束时间（毫秒时间戳），缺失置 null。
            Long e = d.has("endTime") ? d.get("endTime").asLong() : null;
            // 读取该设备编号，缺省空串。
            String id = d.path("deviceId").asText("");
            // 批量条目校验：设备号必填、起止时间必填且 endTime 晚于 startTime，否则整批拒绝。
            if (s == null || e == null || e <= s || id.isEmpty()) {
                return ResponseEntity.badRequest().body(error("批量任务参数非法（deviceId/startTime/endTime 必填）"));
            }
            // 把一条设备构造成 TaskDraft 加入草稿集（deviceName 未传时回退为 id）。
            drafts.add(new TaskService.TaskDraft(
                    id, d.path("deviceName").asText(id), s, e, d.path("action").asText(),
                    d.path("tenantId").asText(null)));
        }
        // TaskService 内部完成整体冲突预检：任一冲突返回空列表（全部拒绝）
        List<Task> created = taskService.createTasks(drafts);
        if (created.isEmpty()) {
            return ResponseEntity.ok(ok(false, "存在设备时间冲突，全部任务拒绝添加"));
        }
        // 成功分支：基础响应置 success:true，并注明成功条数。
        ObjectNode resp = ok(true, "批量添加成功，共 " + created.size() + " 条");
        // 写入成功条数 count 字段。
        resp.put("count", created.size());
        // 新建 taskIds 数组节点，用于回填每个新任务主键。
        ArrayNode ids = resp.putArray("taskIds");
        // 逐条把新任务的主键追加进 taskIds 数组。
        created.forEach(t -> ids.add(t.getId()));
        // 以 HTTP 200 返回批量创建成功响应。
        return ResponseEntity.ok(resp);
    }

    /**
     * 查询任务列表（任务管理：含已完成/已取消）
     * 可选参数 tenantId：第二版多租户隔离（各租户只见自己的任务）
     */
    @GetMapping
    public List<Task> list(@RequestParam(required = false) String tenantId) {
        // 把可选的 tenantId 透传给 TaskService 查询任务列表；为空则返回全部（第二版多租户隔离）。
        return taskService.listAll(tenantId);
    }

    /** 查询每天任务的全部执行流水（task_runs）：GET /api/tasks/{id}/runs */
    @GetMapping("/{id}/runs")
    public JsonNode runs(@PathVariable Long id) {
        // 用路径上的任务 id 查询其全部执行流水；把返回的 List 经 ObjectMapper 转换为 JsonNode 输出。
        return mapper.valueToTree(taskService.listRuns(id));
    }

    /** 取消任务（软删除：置 CANCELLED；运行中先发 pauseValve 暂停） */
    @DeleteMapping("/{id}")
    public ResponseEntity<JsonNode> delete(@PathVariable Long id) {
        // 委托 TaskService 取消任务（软删除置 CANCELLED，运行中先发暂停）；返回是否成功。
        boolean ok = taskService.cancelTask(id);
        // 按取消结果拼装统一响应：成功给“任务已取消”，失败给“任务不存在或不可取消”。
        ObjectNode resp = ok(ok, ok ? "任务已取消" : "任务不存在或不可取消");
        // 成功返回 HTTP 200，失败返回 HTTP 404。
        return ok ? ResponseEntity.ok(resp) : ResponseEntity.status(404).body(resp);
    }

    /**
     * 删除设备时取消其全部未完成任务（APP 删除设备前调用，需求文档 5.3）
     * 路径与 /{id} 不冲突：本路径为两段（device/{deviceId}），/{id} 为单段
     */
    @DeleteMapping("/device/{deviceId}")
    public ResponseEntity<JsonNode> cancelByDevice(@PathVariable String deviceId) {
        // 委托 TaskService 取消该设备全部未完成任务，返回取消条数 n。
        int n = taskService.cancelTasksByDevice(deviceId);
        // 按取消条数拼装成功响应文案。
        ObjectNode resp = ok(true, "已取消该设备 " + n + " 条未完成任务");
        // 以 HTTP 200 返回取消响应。
        return ResponseEntity.ok(resp);
    }

    // ---------- 私有工具：统一响应结构（与原版完全一致） ----------

    /** 标准成功/失败响应：{success, message} */
    private ObjectNode ok(boolean success, String message) {
        // 新建空的 JSON 对象节点，作为响应体容器。
        ObjectNode n = mapper.createObjectNode();
        // 写入 success 布尔标志（入参 success）。
        n.put("success", success);
        // 写入 message 提示文案（入参 message）。
        n.put("message", message);
        // 返回拼装好的统一响应节点。
        return n;
    }

    /** 参数错误响应（400）：{success:false, message} */
    private JsonNode error(String msg) {
        // 复用 ok() 生成 success:false + message:msg 的错误响应节点。
        return ok(false, msg);
    }
}
