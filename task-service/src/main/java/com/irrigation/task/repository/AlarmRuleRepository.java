/**
 * 【文件职责】
 * 告警规则（AlarmRule）的 JPA 数据访问对象（Repository），继承 JpaRepository，对告警规则实体提供
 * 「查询全部启用规则、按租户查询规则列表」等持久化查询能力。
 * 告警规则定义告警的触发条件（如阈值、设备、租户归属等），是告警判定的配置来源，
 * 供告警扫描器加载启用规则、App 规则管理页展示规则列表使用。
 *
 * 【数据流】
 * Service（告警规则相关逻辑）调用本接口方法 → Spring Data JPA 依据方法名自动生成 JPA/SQL 语句
 * → 访问底层数据库表 → 查询结果映射为 AlarmRule 实体列表返回给调用方，
 * 由 Service 进一步加工为业务响应（如扫描器批量遍历启用规则做告警判定、App 按租户渲染规则页）。
 */
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
