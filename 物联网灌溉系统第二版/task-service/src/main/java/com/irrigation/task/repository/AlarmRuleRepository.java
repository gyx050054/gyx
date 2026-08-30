package com.irrigation.task.repository;

import com.irrigation.task.entity.AlarmRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmRuleRepository extends JpaRepository<AlarmRule, Long> {

    /** 查询所有启用规则（扫描器用） */
    List<AlarmRule> findByEnabledTrue();

    /** 按租户查询全部规则（App 规则管理页） */
    List<AlarmRule> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
