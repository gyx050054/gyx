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
