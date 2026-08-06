package com.irrigation.task.service;

import com.irrigation.task.entity.Task;
import com.irrigation.task.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务业务服务（由原 TaskSchedulerService 拆分出的「业务」部分）
 *
 * 职责（对应需求文档「微服务端定时任务完整执行流程」①②③⑥⑦）：
 *  ① 创建任务（单个/批量，含冲突检测）
 *  ② 取消任务（软删除：置 CANCELLED；运行中先发 pauseValve 暂停）
 *  ③ 查询任务列表
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只做「任务数据 + 业务规则」，不关心「何时执行」；
 *  - 「执行」由 {@link TaskScanScheduler}（定时扫描）+ {@link executor.TaskExecutor}（执行动作）负责；
 *  - 未来若引入消息队列/线程池，只需替换 TaskExecutor 实现，本类与 Controller 均无需改动。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** 默认动作：on = 开启阀门（动作默认值只在本类收敛，避免散落多处） */
    public static final String DEFAULT_ACTION = "on";

    private final TaskRepository taskRepository;
    private final ThingsBoardClient tbClient;

    public TaskService(TaskRepository taskRepository, ThingsBoardClient tbClient) {
        this.taskRepository = taskRepository;
        this.tbClient = tbClient;
    }

    /**
     * 校验单个设备在 [startTime, endTime) 时段内是否已有任务（冲突检测）
     * 冲突条件：该设备存在 PENDING/RUNNING 任务，且时间段有交集（newStart < oldEnd && newEnd > oldStart）
     *
     * @return true = 存在冲突（不能创建）
     */
    public boolean hasConflict(String deviceId, Long startTime, Long endTime) {
        return !taskRepository.findConflicts(deviceId, startTime, endTime).isEmpty();
    }

    /**
     * 创建单个任务
     *
     * @param deviceId   ThingsBoard 设备 ID（必填）
     * @param deviceName 设备名称（冗余字段，供 APP 展示；缺省用 deviceId）
     * @param startTime  开始时间戳（毫秒，必填）
     * @param endTime    结束时间戳（毫秒，必填，须大于 startTime）
     * @param action     on=开启 / off=关闭；null 时取默认 {@link #DEFAULT_ACTION}
     * @return 新建任务；若时间冲突则返回 null（调用方据此提示"冲突拒绝"）
     * @throws IllegalArgumentException 时间参数非法时抛出（由全局异常处理转 400）
     */
    @Transactional
    public Task createTask(String deviceId, String deviceName, Long startTime, Long endTime, String action) {
        // 参数校验：时间必填且区间合法（endTime > startTime，文档要求间隔 ≥1 分钟由 APP 保证）
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new IllegalArgumentException("时间参数非法：startTime/endTime 必填且 endTime > startTime");
        }
        // 冲突检测：同一设备同一时段已有任务则拒绝创建（文档 ②）
        if (hasConflict(deviceId, startTime, endTime)) {
            log.warn("任务冲突被拒：deviceId={} [{},{}]", deviceId, startTime, endTime);
            return null;
        }
        Task t = new Task();
        t.setDeviceId(deviceId);
        t.setDeviceName(deviceName == null ? deviceId : deviceName);
        t.setStartTime(startTime);
        t.setEndTime(endTime);
        t.setAction(action == null ? DEFAULT_ACTION : action);
        t.setStatus(Task.Status.PENDING); // 新建任务固定为等待执行
        return taskRepository.save(t);
    }

    /**
     * 批量创建任务（多选设备场景，需求文档 ⑤）
     *
     * 规则：先对全部设备做整体冲突预检，任一设备冲突则整体拒绝（全部不创建）；
     *      预检通过后逐个创建，返回成功创建的列表。
     *
     * @param tasks 每个元素为单条任务的五元组（deviceId/deviceName/startTime/endTime/action）
     * @return 成功创建的任务列表（长度 ≤ 入参数量）
     */
    @Transactional
    public List<Task> createTasks(List<TaskDraft> tasks) {
        // 整体冲突预检：任一设备冲突 → 全部拒绝（文档 ⑤「有冲突则所有任务不让添加」）
        for (TaskDraft d : tasks) {
            if (hasConflict(d.deviceId(), d.startTime(), d.endTime())) {
                log.warn("批量任务冲突被拒：设备 {} 时段 [{},{}]", d.deviceId(), d.startTime(), d.endTime());
                return List.of();
            }
        }
        // 预检通过，逐个创建
        List<Task> created = new ArrayList<>();
        for (TaskDraft d : tasks) {
            created.add(createTask(d.deviceId(), d.deviceName(), d.startTime(), d.endTime(), d.action()));
        }
        return created;
    }

    /**
     * 取消任务（软删除，需求文档 ⑥）：置 CANCELLED，不物理删除
     *  - PENDING（未开始）：直接置 CANCELLED
     *  - RUNNING（进行中）：先发 pauseValve 暂停设备，再置 CANCELLED
     *  - COMPLETED / CANCELLED：终态，不可再取消
     *
     * @return true = 取消成功；false = 任务不存在或处于终态
     */
    @Transactional
    public boolean cancelTask(Long taskId) {
        Task t = taskRepository.findById(taskId).orElse(null);
        if (t == null) {
            return false;
        }
        // 终态不可取消（文档：保持原状态）
        if (t.getStatus() == Task.Status.COMPLETED || t.getStatus() == Task.Status.CANCELLED) {
            log.info("任务 {} 状态为 {}（终态），不可取消", taskId, t.getStatus());
            return false;
        }
        // 运行中任务先发暂停指令，避免设备继续工作（文档 ⑦）
        if (t.getStatus() == Task.Status.RUNNING) {
            tbClient.pauseValve(t.getDeviceId());
            log.info("取消运行中任务 {} -> 已发 pauseValve 暂停设备 {}", taskId, t.getDeviceId());
        }
        t.setStatus(Task.Status.CANCELLED);
        taskRepository.save(t);
        log.info("任务 {} 已取消（状态 CANCELLED）", taskId);
        return true;
    }

    /**
     * 查询全部任务（任务管理页：含已完成/已取消，保留记录供展示）
     *
     * @param tenantId 租户 ID；非 null 时只返回该租户的任务（第二版多租户隔离，第一版传 null 查全部）
     */
    public List<Task> listAll(Long tenantId) {
        if (tenantId == null) {
            return taskRepository.findAll();
        }
        return taskRepository.findByTenantId(tenantId);
    }

    /**
     * 批量创建任务的入参（轻量 DTO：避免 Controller 直接依赖 JsonNode）
     * 使用 record：Java 17 简洁不可变数据结构
     */
    public record TaskDraft(String deviceId, String deviceName, Long startTime, Long endTime, String action) {
    }
}
