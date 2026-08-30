/**
 * 【文件职责】告警规则表（alarm_rules）。定义"什么设备类型、什么遥测键/指标、什么比较条件、什么级别、是否启用"
 *           来触发告警，按租户隔离；支持普通遥测键与特殊值"offline"（断连检测，threshold=分钟数）两类指标。
 * <p>
 * 【数据流】管理端写入或更新规则后，告警扫描器每 30 秒读取 enabled=true 的规则，拉取对应租户设备的实时遥测，
 *         按 operator 与 threshold 比对；满足条件即命中，据此生成一条 AlarmRecord（alarm_records）记录。
 */
package com.irrigation.task.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 告警规则实体（第四版新增）
 *
 * 定义"什么设备、什么指标、什么条件、什么级别"触发告警。
 * 规则按租户隔离（与 tasks 表一致），扫描器每 30 秒读取启用的规则检查一次。
 *
 * 支持两类指标（metric）：
 *  - 普通遥测键：batteryLevel / faultStatus / soilMoisture / temperature ...（与 TB timeseries key 一致）
 *  - 特殊值 "offline"：设备断连检测（N 分钟无最新遥测，threshold 即分钟数）
 */
@Entity
@Table(name = "alarm_rules", indexes = {
        @Index(name = "idx_rule_tenant", columnList = "tenantId"),
        @Index(name = "idx_rule_enabled", columnList = "enabled")
})
public class AlarmRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称（用户可读，如"阀门电量过低"） */
    @Column(nullable = false)
    private String name;

    /** 目标设备类型：VALVE / SOIL_MOISTURE / TEMPERATURE_HUMIDITY / ALL（全部） */
    @Column(nullable = false)
    private String deviceType = "ALL";

    /** 监控指标：遥测键 或 "offline"（断连检测，threshold=分钟数） */
    @Column(nullable = false)
    private String metric;

    /** 比较运算符：lt / gt / eq / ne */
    @Column(nullable = false)
    private String operator = "lt";

    /** 阈值（offline 指标时单位为分钟） */
    @Column(nullable = false)
    private Double threshold = 0.0;

    /** 告警级别：HIGH / MEDIUM / LOW */
    @Column(nullable = false)
    private String severity = "MEDIUM";

    /** 告警消息模板（支持 {deviceName} 占位） */
    @Column(length = 512)
    private String message;

    /** 是否启用（false=停用不扫描） */
    @Column(nullable = false)
    private Boolean enabled = true;

    /** 所属租户 ID（可空：演示/第一版数据不分租户） */
    private String tenantId;

    /** 创建时间 */
    private Long createdAt = Instant.now().toEpochMilli();

    // ---------- getter / setter ----------
    /** 主键 ID：规则唯一标识（自增） */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /** 规则名称（用户可读） */
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** 目标设备类型：VALVE / SOIL_MOISTURE / TEMPERATURE_HUMIDITY / ALL */
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    /** 监控指标：遥测键 或 "offline"（断连检测） */
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    /** 比较运算符：lt / gt / eq / ne */
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    /** 阈值（offline 指标时为分钟数） */
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }

    /** 告警级别：HIGH / MEDIUM / LOW */
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    /** 告警消息模板（支持 {deviceName} 占位） */
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    /** 是否启用（false=停用不扫描） */
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    /** 所属租户 ID */
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    /** 创建时间（毫秒） */
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
