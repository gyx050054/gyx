/**
 * 【文件职责】
 * 任务执行流水（TaskRun）的 JPA 数据访问对象（Repository），继承 JpaRepository，对任务流水实体提供
 * 「按任务+日期查询单笔流水、按任务查询全部流水」等持久化查询能力。
 * 任务流水记录某次任务在某天的实际执行结果（是否执行、是否因雨跳过、执行时间等），
 * 用于同一 DAILY 任务同一天的去重、以及 App 端展示"昨天浇没浇/是否因雨跳过"。
 *
 * 【数据流】
 * Service（灌溉执行记录、App 流水展示等业务逻辑）调用本接口方法 → Spring Data JPA 依据方法名
 * 自动生成 JPA/SQL 语句 → 访问底层数据库表 → 查询结果映射为 TaskRun 实体（或 Optional）返回给调用方，
 * 由 Service 进一步加工为业务响应（如去重判断、按时间倒序渲染流水列表）。
 */
package com.irrigation.task.repository;

import com.irrigation.task.entity.TaskRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

    /** 查某天某任务的流水（去重：同一天同一 DAILY 只执行一次） */
    Optional<TaskRun> findByTaskIdAndRunDate(Long taskId, String runDate);

    /** 查某任务的全部流水（App 展示"昨天浇没浇/是否因雨跳过"） */
    List<TaskRun> findByTaskIdOrderByRunDateDesc(Long taskId);
}
