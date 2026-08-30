/**
 * 【文件职责】租户 ThingsBoard 凭证表（tenant_credentials）。为每个租户保存其专属 TB 登录账号（邮箱）与密码，
 *           使告警扫描器能用"每个租户自己的 TB 账号"去查该租户设备、比对遥测，实现真正的多租户隔离。
 * <p>
 * 【数据流】租户注册时写入（tenantId+email 各自唯一），改密时更新 password 与 updatedAt；密码仅存服务端、绝不下发 App。
 *         告警扫描器按租户读取本表，持各自凭证访问 TB；弱口令场景下配合 UserPwdFlag 强制首登改密，改密成功后回写新密码。
 */
package com.irrigation.task.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 租户 ThingsBoard 凭证（真多租户告警隔离，第五版新增）
 *
 * 用途：告警扫描器需要「用每个租户自己的 TB 账号」去查该租户设备、比对遥测，
 *      才能实现真正的租户隔离（而非全局一个固定账号 → 数据串租户）。
 *      本表存每个租户的 TB 登录凭证，注册时写入，改密时更新。
 *
 * 安全：密码仅存于服务端，绝不下发 App；且注册默认密码为弱口令，
 *      用户首次登录强制改密，改密后此处同步更新为新密码。
 */
@Entity
@Table(name = "tenant_credentials", indexes = {
        @Index(name = "idx_tc_tenant", columnList = "tenantId", unique = true),
        @Index(name = "idx_tc_email", columnList = "email", unique = true)
})
public class TenantCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ThingsBoard 租户 ID（UUID 字符串） */
    @Column(nullable = false, unique = true)
    private String tenantId;

    /** 该租户管理员登录邮箱（即 TB 登录账号） */
    @Column(nullable = false, unique = true)
    private String email;

    /** 该租户管理员 TB 登录密码 */
    @Column(nullable = false)
    private String password;

    /** 创建时间 */
    private Long createdAt = Instant.now().toEpochMilli();

    /** 最近更新时间 */
    private Long updatedAt = Instant.now().toEpochMilli();

    // ---------- getter / setter ----------
    /** 主键 ID：凭证唯一标识（自增） */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    /** ThingsBoard 租户 ID（UUID 字符串） */
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    /** 该租户管理员登录邮箱（即 TB 登录账号） */
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    /** 该租户管理员 TB 登录密码 */
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    /** 创建时间（毫秒） */
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    /** 最近更新时间（毫秒） */
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
