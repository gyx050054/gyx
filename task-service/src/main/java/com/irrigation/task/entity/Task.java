/**
 * 【文件职责】定时任务表（tasks）。描述"什么设备、什么时间段、做什么动作"的调度模板，持有状态机
 *            PENDING/RUNNING/COMPLETED/CANCELLED，并由 tenantId 做多租户隔离；支持 ONCE 一次性
 *            与 DAILY 每天重复两种模式（DAILY 的"每天参数"由 dailyHour/durationMinutes 驱动）。
 * <p>
 * 【数据流】APP 创建任务时按 JWT 解析出 tenantId 一并写入本表；调度器到 startTime 将状态推进为 RUNNING，
 *         到 endTime（或 DAILY 的 durationMinutes 耗尽）后自动置为 COMPLETED，用户手动取消则置为 CANCELLED；
 *         DAILY 任务本身常驻不消失，其每天的实际执行由 TaskRun（task_runs）按日记录流水。
 */
package com.irrigation.task.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 定时任务实体（对应文档任务表：id、设备id、开始时间、结束时间）
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_device", columnList = "deviceId"),
        @Index(name = "idx_status", columnList = "status")
})
public class Task {

    /** 任务状态 */
    public enum Status {
        PENDING,     // 等待执行（未到开始时间）
        RUNNING,     // 执行中
        COMPLETED,   // 已完成（到达结束时间自动完成，保留记录）
        CANCELLED    // 已取消（用户手动取消，软删除）
    }

    /** 重复模式：ONCE=一次性（现有） / DAILY=每天重复（第三代第一版 §2） */
    public enum RepeatMode {
        ONCE, DAILY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标设备 ID（ThingsBoard deviceId） */
    @Column(nullable = false)
    private String deviceId;

    /** 设备名称（冗余，便于 APP 展示） */
    private String deviceName;

    /** 开始时间戳（毫秒）。ONCE 任务=计划开始；DAILY 任务=初始化首日时间（真正的重复由 dailyHour 驱动） */
    @Column(nullable = false)
    private Long startTime;

    /** 结束时间戳（毫秒）。ONCE 任务=计划结束；DAILY 任务可空，实际时长由 durationMinutes 决定 */
    @Column(nullable = false)
    private Long endTime;

    /** 动作：on=开启（默认）/ off=关闭 */
    private String action = "on";

    /** 重复模式：ONCE / DAILY（第三代第一版 §2.1） */
    @Enumerated(EnumType.STRING)
    private RepeatMode repeatMode = RepeatMode.ONCE;

    /** 每天开始小时（0-23，仅 DAILY 用） */
    private Integer dailyHour;

    /** 每天持续时长（分钟，仅 DAILY 用） */
    private Integer durationMinutes;

    /** 任务状态 */
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    /**
     * 所属租户 ID（第二版多租户：可空，第一版数据为 null 表示不分租户）
     * 类型为 String：TB 的 tenantId 是 UUID（如 46f343a0-90a4-11f1-...），非数字
     * 创建任务时由 APP 从 JWT 解析 tenantId 一并提交，查询/取消按此字段隔离
     */
    private String tenantId;

    /** 创建时间 */
    private Long createdAt = Instant.now().toEpochMilli();

    // ---------- getter / setter ----------
    /** 主键 ID：任务唯一标识（自增） */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /** 目标设备 ID（ThingsBoard deviceId） */
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    /** 设备名称（冗余，便于 APP 展示） */
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    /** 开始时间戳（毫秒）：ONCE=计划开始；DAILY=初始化首日时间 */
    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    /** 结束时间戳（毫秒）：ONCE=计划结束；DAILY 可空，实际由 durationMinutes 决定 */
    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }

    /** 动作：on=开启（默认）/ off=关闭 */
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    /** 任务状态：PENDING/RUNNING/COMPLETED/CANCELLED */
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    /** 所属租户 ID：可空，null 表示第一版不分租户 */
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    /** 创建时间（毫秒） */
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    /** 重复模式：ONCE=一次性 / DAILY=每天重复 */
    public RepeatMode getRepeatMode() { return repeatMode; }
    public void setRepeatMode(RepeatMode repeatMode) { this.repeatMode = repeatMode; }

    /** 每天开始小时（0-23，仅 DAILY 用） */
    public Integer getDailyHour() { return dailyHour; }
    public void setDailyHour(Integer dailyHour) { this.dailyHour = dailyHour; }

    /** 每天持续时长（分钟，仅 DAILY 用） */
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }
}
