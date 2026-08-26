package com.irrigation.task.service;

import com.irrigation.task.entity.Task;
import com.irrigation.task.repository.TaskRepository;
import com.irrigation.task.service.executor.TaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务扫描调度器（由原 TaskSchedulerService 拆分出的「调度」部分）
 *
 * 职责（对应需求文档「微服务端定时任务完整执行流程」④⑤⑥）：
 *  ④ 每 10 秒扫描一次任务表（周期可通过配置 task.scan-interval-ms 调整）
 *  ⑤ 到点任务（PENDING 且 startTime <= now）→ 交给 TaskExecutor 执行 → 置 RUNNING
 *  ⑥ 超时任务（RUNNING 且 endTime <= now）→ 交给 TaskExecutor 收尾 → 置 COMPLETED（保留记录）
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只负责「何时扫描、扫描到什么、状态如何流转」；
 *  - 「怎么执行」委托给 {@link TaskExecutor}（接口），不直接依赖 ThingsBoard；
 *  - 未来引入 Quartz/ShedLock 或多实例部署时，仅需改造本类的调度注解，业务不变。
 */
@Service
public class TaskScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScanScheduler.class);

    private final TaskRepository taskRepository;
    private final TaskExecutor taskExecutor;
    private final DailyTaskService dailyTaskService;

    public TaskScanScheduler(TaskRepository taskRepository, TaskExecutor taskExecutor,
                             DailyTaskService dailyTaskService) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
        this.dailyTaskService = dailyTaskService;
    }

    /**
     * 定时扫描（默认每 10 秒一次）。
     *
     * fixedDelayString 用 SpEL 读取配置 task.scan-interval-ms（默认 10000），
     * 无需改代码即可调整扫描频率。
     */
    @Scheduled(fixedDelayString = "${task.scan-interval-ms:10000}")
    @Transactional
    public void scanAndExecute() {
        long now = Instant.now().toEpochMilli();
        log.debug("任务扫描开始，当前时间戳 {}", now);

        // ① 执行到点任务：PENDING 且 startTime <= now
        List<Task> dueTasks = taskRepository.findByStatusAndStartTimeLessThanEqual(Task.Status.PENDING, now);
        for (Task t : dueTasks) {
            boolean ok = taskExecutor.executeStart(t);
            log.info("执行任务 {}：action={} 设备 {} -> {}", t.getId(), t.getAction(), t.getDeviceId(), ok ? "成功" : "失败");
            if (ok) {
                t.setStatus(Task.Status.RUNNING); // 执行成功才置 RUNNING
                taskRepository.save(t);
            }
            // 执行失败的任务保持 PENDING，下一轮自动重试（当前策略：无限重试，后续可加次数上限）
        }

        // ② 到期自动完成：RUNNING 且 endTime <= now
        List<Task> expired = taskRepository.findByStatusAndEndTimeLessThanEqual(Task.Status.RUNNING, now);
        for (Task t : expired) {
            taskExecutor.executeFinish(t); // 到期自动关闭阀门（收尾）
            t.setStatus(Task.Status.COMPLETED);
            taskRepository.save(t);        // 保留记录（已完成状态供任务管理展示，不物理删除）
            log.info("任务 {} 到期，关闭设备 {}，状态置 COMPLETED", t.getId(), t.getDeviceId());
        }

        // ③ 每天任务调度（第三代第一版 §2）：到点开浇/到时关浇/天气跳过/按日去重
        try {
            dailyTaskService.processDaily();
        } catch (Exception e) {
            log.warn("每天任务调度失败：{}", e.getMessage());
        }
    }
}
