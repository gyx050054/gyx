/**
 * 【文件职责】每天任务的按日执行流水表（task_runs）。"每天任务"本身是常驻模板，本表记录它"某一天到底有没有执行、
 *           执行结果如何"；taskId + runDate 唯一去重，用于实现"同一天只执行/只记录一次"。
 * <p>
 * 【数据流】每日到点由调度器先按 taskId+runDate 查本表去重，未执行则创建 PENDING 流水；开始浇水时写 startTs，
 *         关浇写 endTs 并置为 COMPLETED；若因预报降雨概率≥80% 被自动跳过则置为 SKIPPED_WEATHER；
 *         前端据此展示"昨天浇没浇/是否因雨跳过"。
 */
package com.irrigation.task.entity;

import jakarta.persistence.*;

/**
 * 每天任务的执行流水（task_runs，第三代第一版 §2.2）
 *
 * 「每天任务」本身是常驻模板（tasks.repeatMode=DAILY），每天的实际浇水记一条流水：
 * runDate + taskId 唯一，用于「同一天只执行一次」去重，也用于查看"昨天浇没浇/是否因雨跳过"。
 *
 * 状态：
 *  - PENDING          等待当天到点开浇
 *  - COMPLETED        当天已执行完成
 *  - SKIPPED_WEATHER  当天因预报降雨概率≥80%跳过（自动任务天气联动）
 */
@Entity
@Table(name = "task_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uq_run_task_date", columnNames = {"taskId", "runDate"})
}, indexes = {
        @Index(name = "idx_run_date", columnList = "runDate"),
        @Index(name = "idx_run_task", columnList = "taskId")
})
public class TaskRun {

    /** 流水状态 */
    public enum Status {
        PENDING,
        COMPLETED,
        SKIPPED_WEATHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对应 tasks 的 id（闹钟模板） */
    @Column(nullable = false)
    private Long taskId;

    /** 执行设备 */
    @Column(nullable = false)
    private String deviceId;

    private String deviceName;

    /** 执行日期（yyyy-MM-dd，当天一条） */
    @Column(nullable = false)
    private String runDate;

    /** 计划/实际开浇时间戳（毫秒） */
    private Long startTs;

    /** 关浇时间戳（毫秒，可空） */
    private Long endTs;

    /** 动作 on/off */
    private String action = "on";

    /** 流水状态 */
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    // ---------- getter / setter ----------
    /** 主键 ID：流水唯一标识（自增） */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /** 对应 tasks 的 id（闹钟模板） */
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }

    /** 执行设备 ID */
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    /** 执行设备名称（冗余，便于展示） */
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    /** 执行日期（yyyy-MM-dd，当天一条） */
    public String getRunDate() { return runDate; }
    public void setRunDate(String runDate) { this.runDate = runDate; }

    /** 计划/实际开浇时间戳（毫秒） */
    public Long getStartTs() { return startTs; }
    public void setStartTs(Long startTs) { this.startTs = startTs; }

    /** 关浇时间戳（毫秒，可空） */
    public Long getEndTs() { return endTs; }
    public void setEndTs(Long endTs) { this.endTs = endTs; }

    /** 动作 on/off */
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    /** 流水状态：PENDING/COMPLETED/SKIPPED_WEATHER */
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
