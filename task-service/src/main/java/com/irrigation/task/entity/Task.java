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
        COMPLETED,   // 已完成（执行完自动删除前）
        CANCELLED    // 已取消
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 目标设备 ID（ThingsBoard deviceId） */
    @Column(nullable = false)
    private String deviceId;

    /** 设备名称（冗余，便于 APP 展示） */
    private String deviceName;

    /** 开始时间戳（毫秒） */
    @Column(nullable = false)
    private Long startTime;

    /** 结束时间戳（毫秒） */
    @Column(nullable = false)
    private Long endTime;

    /** 动作：on=开启（默认）/ off=关闭 */
    private String action = "on";

    /** 任务状态 */
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    /** 创建时间 */
    private Long createdAt = Instant.now().toEpochMilli();

    // ---------- getter / setter ----------
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
