/**
 * 【文件职责】
 * 定时任务扫描调度器（由原 TaskSchedulerService 拆分出的「调度」部分）：
 *  每 10 秒扫描一次任务表（周期可通过配置 task.scan-interval-ms 调整），驱动状态流转：
 *   - 到点任务：PENDING 且 startTime <= now → 交 TaskExecutor.executeStart → 成功置 RUNNING（失败留 PENDING 下轮重试）
 *   - 超时任务：RUNNING 且 endTime <= now → 交 TaskExecutor.executeFinish 收尾 → 置 COMPLETED
 *   - 每天任务：每轮调用 DailyTaskService.processDaily() 处理 DAILY 任务（到点开浇/到时关浇/天气跳过/按日去重）
 * 只负责「何时扫描、扫描到什么、状态如何流转」；「怎么执行」委托给 TaskExecutor 接口，不直接依赖 ThingsBoard。
 *
 * 【数据流】
 *  task 表(PENDING) --扫描--> executeStart(发开/关阀 RPC) --成功--> task 表(RUNNING)
 *  task 表(RUNNING) --到期--> executeFinish(关阀收尾) --> task 表(COMPLETED)
 *  DAILY 常驻任务 --每轮--> DailyTaskService.processDaily() --> task_runs 表（开浇/关浇/跳过）
 *  每轮以 Instant.now() 为基准时间戳，到点/到期判断均以当前时间与任务 startTime/endTime 比较。
 */
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
        // 取当前系统时间戳（毫秒）作为本轮扫描的基准时间（数据流：系统时钟 → now），后续到点/到期判断均与其比较
        long now = Instant.now().toEpochMilli();
        log.debug("任务扫描开始，当前时间戳 {}", now); // 记录本轮扫描入口（debug 级，便于排查定时触发情况）

        // ① 执行到点任务：PENDING 且 startTime <= now
        // 读库：查所有「PENDING 且开始时间 ≤ 当前时间」的任务 → 列表（数据流：task 表 → 内存）
        List<Task> dueTasks = taskRepository.findByStatusAndStartTimeLessThanEqual(Task.Status.PENDING, now);
        // 遍历到点任务列表（内存循环），逐个执行开启动作
        for (Task t : dueTasks) {
            // 委托执行器下发开启/关闭阀门 RPC（数据流：task → TaskExecutor.executeStart → ThingsBoard），返回是否成功
            boolean ok = taskExecutor.executeStart(t);
            // 记录执行结果日志：任务 ID、动作、设备、成功/失败（ok 由执行器返回）
            log.info("执行任务 {}：action={} 设备 {} -> {}", t.getId(), t.getAction(), t.getDeviceId(), ok ? "成功" : "失败");
            // 判断：执行成功 → 状态流转为 RUNNING（数据流：内存对象状态变更）
            if (ok) {
                t.setStatus(Task.Status.RUNNING); // 执行成功才置 RUNNING
                // 落库：save 把新状态写回 task 表（数据流：对象 → task 表）
                taskRepository.save(t);
            }
            // 执行失败的任务保持 PENDING，下一轮自动重试（当前策略：无限重试，后续可加次数上限）
        }

        // ② 到期自动完成：RUNNING 且 endTime <= now
        // 读库：查所有「RUNNING 且结束时间 ≤ 当前时间」的任务 → 列表（数据流：task 表 → 内存）
        List<Task> expired = taskRepository.findByStatusAndEndTimeLessThanEqual(Task.Status.RUNNING, now);
        // 遍历到期任务列表（内存循环），逐个执行收尾动作
        for (Task t : expired) {
            taskExecutor.executeFinish(t); // 到期自动关闭阀门（收尾）
            // 状态流转：到期任务置 COMPLETED（数据流：内存对象状态变更）
            t.setStatus(Task.Status.COMPLETED);
            taskRepository.save(t);        // 保留记录（已完成状态供任务管理展示，不物理删除）
            // 记录收尾日志：任务 ID、设备 ID、状态置 COMPLETED
            log.info("任务 {} 到期，关闭设备 {}，状态置 COMPLETED", t.getId(), t.getDeviceId());
        }

        // ③ 每天任务调度（第三代第一版 §2）：到点开浇/到时关浇/天气跳过/按日去重
        // 用 try-catch 包裹：每天任务调度失败不回滚/阻断本轮扫描，仅记录 warn 日志（数据流：本类 → DailyTaskService.processDaily）
        try {
            dailyTaskService.processDaily();
        } catch (Exception e) {
            log.warn("每天任务调度失败：{}", e.getMessage());
        }
    }
}
