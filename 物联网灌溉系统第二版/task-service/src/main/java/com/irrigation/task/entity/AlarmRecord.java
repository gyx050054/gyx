package com.irrigation.task.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 告警记录实体（第四版新增）
 *
 * 规则命中后生成一条记录，同一设备同一规则"未恢复"期间不重复新建（只刷新 lastAt）。
 * 状态机：ACTIVE（触发）→ ACKNOWLEDGED（App 已确认）→ RESOLVED（条件恢复自动结束）
 */
@Entity
@Table(name = "alarm_records", indexes = {
        @Index(name = "idx_alarm_device", columnList = "deviceId"),
        @Index(name = "idx_alarm_status", columnList = "status"),
        @Index(name = "idx_alarm_tenant", columnList = "tenantId")
})
public class AlarmRecord {

    /** 告警状态 */
    public enum Status {
        ACTIVE,        // 触发中（未确认）
        ACKNOWLEDGED,  // 已确认（App 点击确认，红点消失）
        RESOLVED       // 已恢复（条件不再满足，自动结束）
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属租户 ID */
    private String tenantId;

    /** 触发设备 ID（ThingsBoard deviceId） */
    @Column(nullable = false)
    private String deviceId;

    /** 设备名称（冗余，便于展示） */
    private String deviceName;

    /** 命中的规则 ID */
    private Long ruleId;

    /** 告警级别：HIGH / MEDIUM / LOW（冗余自规则，便于查询展示） */
    private String severity;

    /** 告警消息（已填充设备名的最终文案） */
    @Column(length = 512)
    private String message;

    /** 告警状态 */
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    /** 首次触发时间（毫秒） */
    private Long firstAt = Instant.now().toEpochMilli();

    /** 最近触发时间（毫秒） */
    private Long lastAt = firstAt;

    /** 恢复时间（毫秒，可空） */
    private Long resolvedAt;

    // ---------- getter / setter ----------
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Long getFirstAt() { return firstAt; }
    public void setFirstAt(Long firstAt) { this.firstAt = firstAt; }

    public Long getLastAt() { return lastAt; }
    public void setLastAt(Long lastAt) { this.lastAt = lastAt; }

    public Long getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Long resolvedAt) { this.resolvedAt = resolvedAt; }
}
