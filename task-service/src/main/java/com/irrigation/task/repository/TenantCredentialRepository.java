/**
 * 【文件职责】
 * 租户凭证（TenantCredential）的 JPA 数据访问对象（Repository），继承 JpaRepository，对租户凭证实体提供
 * 「按租户 ID 查凭证、按邮箱查凭证、查询全部凭证」等持久化查询能力。
 * 租户凭证保存某个租户在外部系统（如设备/云平台）的认证凭据，是告警扫描器逐租户拉取数据、以及
 * 租户认证相关逻辑所依赖的配置载体。
 *
 * 【数据流】
 * Service（告警扫描器、租户认证等业务逻辑）调用本接口方法 → Spring Data JPA 依据方法名自动生成
 * JPA/SQL 语句 → 访问底层数据库表 → 查询结果映射为 TenantCredential 实体（或 Optional/列表）
 * 返回给调用方，由 Service 进一步加工为业务响应（如扫取凭证发起外部调用、按邮箱匹配租户）。
 */
package com.irrigation.task.repository;

import com.irrigation.task.entity.TenantCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantCredentialRepository extends JpaRepository<TenantCredential, Long> {

    /** 按租户 ID 查凭证 */
    Optional<TenantCredential> findByTenantId(String tenantId);

    /** 按邮箱查凭证 */
    Optional<TenantCredential> findByEmail(String email);

    /** 全部凭证（告警扫描器逐租户处理用） */
    List<TenantCredential> findAll();
}
