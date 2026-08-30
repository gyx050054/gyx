/**
 * 【文件职责】
 * 告警记录（AlarmRecord）的 JPA 数据访问对象（Repository），继承 JpaRepository，对告警记录实体提供
 * 「按规则+设备查询、按租户列表、按租户+状态筛选、未确认计数、按租户查未恢复记录」等持久化查询能力。
 * 告警记录描述某条告警规则在某个设备上的触发状态（ACTIVE / ACKNOWLEDGED / RECOVERED 等），
 * 是整个告警系统的落库载体，供告警触发、App 红点计数、扫描器恢复检测等业务使用。
 *
 * 【数据流】
 * Service（告警业务逻辑）调用本接口方法 → Spring Data JPA 依据方法名或 @Query 自动生成 JPA/SQL 语句
 * → 访问底层数据库表 → 查询结果映射为 AlarmRecord 实体或计数/可选值返回给调用方，
 * 由 Service 进一步加工为业务响应（如组装成 App 告警列表、计算未确认红点数、判断是否可恢复）。
 */
package com.irrigation.task.repository;

import com.irrigation.task.entity.AlarmRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmRecordRepository extends JpaRepository<AlarmRecord, Long> {

    /** 查询某设备某规则「未恢复」的告警（存在则刷新 lastAt，不重复新建） */
    Optional<AlarmRecord> findFirstByRuleIdAndDeviceIdAndStatusIn(
            Long ruleId, String deviceId, List<AlarmRecord.Status> statuses);

    /** 按租户查询告警记录（新触发在前） */
    List<AlarmRecord> findByTenantIdOrderByFirstAtDesc(String tenantId);

    /** 按租户+状态过滤（App 按状态筛选用） */
    List<AlarmRecord> findByTenantIdAndStatusOrderByFirstAtDesc(String tenantId, AlarmRecord.Status status);

    /** 未确认告警计数（App 红点：ACTIVE + ACKNOWLEDGED 之外的未处理数） */
    long countByTenantIdAndStatus(String tenantId, AlarmRecord.Status status);

    /** 某租户全部未恢复告警（扫描器恢复检测用） */
    List<AlarmRecord> findByTenantIdAndStatusIn(String tenantId, List<AlarmRecord.Status> statuses);
}
