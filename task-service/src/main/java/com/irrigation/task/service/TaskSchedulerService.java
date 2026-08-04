package com.irrigation.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.irrigation.task.entity.Task;
import com.irrigation.task.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务调度服务
 *
 * 对应文档「微服务端定时任务完整执行流程」：
 *  ① 用户创建任务（APP → POST /api/tasks）
 *  ② 冲突检测：同一设备时间段交集则拒绝（s1<e2 && e1>s2）
 *  ③ 无冲突写入数据库
 *  ④ 每 10 秒定时扫描
 *  ⑤ 执行任务（发 RPC 开/关阀门）
 *  ⑥ 到达结束时间自动完成（置 COMPLETED，保留记录供任务管理展示）
 *  ⑦ 手动取消：置 CANCELLED（已开始先发暂停），不物理删除
 * 状态：PENDING(等待) / RUNNING(执行中) / COMPLETED(已完成) / CANCELLED(已取消)
 */
@Service
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    private final TaskRepository taskRepository;
    private final ThingsBoardClient tbClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskSchedulerService(TaskRepository taskRepository, ThingsBoardClient tbClient) {
        this.taskRepository = taskRepository;
        this.tbClient = tbClient;
    }

    /**
     * 校验单个设备任务是否冲突
     * 冲突条件：该设备已有 PENDING/RUNNING 任务，且时间段有交集
     */
    public boolean hasConflict(String deviceId, Long startTime, Long endTime) {
        return !taskRepository.findConflicts(deviceId, startTime, endTime).isEmpty();
    }

    /** 创建单个任务；冲突时返回 null（由调用方决定如何提示） */
    @Transactional
    public Task createTask(String deviceId, String deviceName, Long startTime, Long endTime, String action) {
        if (startTime == null || endTime == null || endTime <= startTime) {
            throw new IllegalArgumentException("时间参数非法：startTime/endTime 必填且 endTime > startTime");
        }
        if (hasConflict(deviceId, startTime, endTime)) {
            log.warn("任务冲突被拒：deviceId={} [{},{}]", deviceId, startTime, endTime);
            return null;
        }
        Task t = new Task();
        t.setDeviceId(deviceId);
        t.setDeviceName(deviceName);
        t.setStartTime(startTime);
        t.setEndTime(endTime);
        t.setAction(action == null ? "on" : action);
        t.setStatus(Task.Status.PENDING);
        return taskRepository.save(t);
    }

    /** 取消任务（软删除，用户需求 3.6.3）：置 CANCELLED，不物理删除；运行中先发暂停 */
    @Transactional
    public boolean cancelTask(Long taskId) {
        Task t = taskRepository.findById(taskId).orElse(null);
        if (t == null) {
            return false;
        }
        // 已完成/已取消为终态，不可再取消（文档：保持原状态）
        if (t.getStatus() == Task.Status.COMPLETED || t.getStatus() == Task.Status.CANCELLED) {
            log.info("任务 {} 状态为 {}（终态），不可取消", taskId, t.getStatus());
            return false;
        }
        if (t.getStatus() == Task.Status.RUNNING) {
            // 文档 ⑦：任务在进行就直接发暂停
            tbClient.pauseValve(t.getDeviceId());
            log.info("取消运行中任务 {} -> 已发 pauseValve 暂停设备 {}", taskId, t.getDeviceId());
        }
        t.setStatus(Task.Status.CANCELLED);
        taskRepository.save(t);
        log.info("任务 {} 已取消（状态 CANCELLED）", taskId);
        return true;
    }

    /**
     * 定时扫描（每 10 秒）：
     * 1. 到点的 PENDING 任务 → 执行 RPC 开/关 → 置 RUNNING
     * 2. 超时的 RUNNING 任务 → 执行关闭 → 置 COMPLETED（保留记录，文档 ⑥）
     */
    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void scanAndExecute() {
        long now = Instant.now().toEpochMilli();

        // ① 执行到点任务
        List<Task> dueTasks = taskRepository.findByStatusAndStartTimeLessThanEqual(Task.Status.PENDING, now);
        for (Task t : dueTasks) {
            boolean ok;
            if ("off".equalsIgnoreCase(t.getAction())) {
                ok = tbClient.closeValve(t.getDeviceId());
            } else {
                ok = tbClient.openValve(t.getDeviceId());
            }
            log.info("执行任务 {}：{} 设备 {} -> {} ({})",
                    t.getId(), t.getAction(), t.getDeviceId(), ok ? "成功" : "失败", ok);
            if (ok) {
                t.setStatus(Task.Status.RUNNING);
                taskRepository.save(t);
            }
            // RPC 失败的任务保留 PENDING，下轮重试
        }

        // ② 到期自动完成
        List<Task> expired = taskRepository.findByStatusAndEndTimeLessThanEqual(Task.Status.RUNNING, now);
        for (Task t : expired) {
            tbClient.closeValve(t.getDeviceId());   // 到点自动关闭
            t.setStatus(Task.Status.COMPLETED);
            taskRepository.save(t);                 // 保留记录（已完成状态供任务管理展示）
            log.info("任务 {} 到期，关闭设备 {}，状态置 COMPLETED", t.getId(), t.getDeviceId());
        }
    }

    /** 供 Controller 使用的 JSON 参数工具 */
    public JsonNode objectNode() {
        return mapper.createObjectNode();
    }
}
