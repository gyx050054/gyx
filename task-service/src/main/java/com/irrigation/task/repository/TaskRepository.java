/**
 * 【文件职责】
 * 灌溉任务（Task）的 JPA 数据访问对象（Repository），继承 JpaRepository，是任务系统的核心查询入口，
 * 负责任务的持久化与各种业务查询：冲突检测、未完成任务、到期待执行、超时运行、按租户隔离、
 * 按设备查询、按重复模式筛查等。
 * 任务描述一次灌溉计划的执行安排（含计划时间、状态 PENDING/RUNNING/...、重复模式、所属设备与租户等），
 * 是所有定时灌溉调度与设备联动逻辑的落库载体。
 *
 * 【数据流】
 * Service（任务调度/冲突判定/租户隔离等业务逻辑）调用本接口方法 → Spring Data JPA 依据方法名或
 * @Query 自动生成 JPA/SQL 语句 → 访问底层数据库表 → 查询结果映射为 Task 实体列表返回给调用方，
 * 由 Service 进一步加工为业务响应（如判断计划是否冲突、选取待执行任务、按租户或设备过滤任务等）。
 *
 * 关键查询说明：
 * - findConflicts(...)：设备上「时间区间有交集且未结束」的任务（核心冲突检测）。
 * - findByStatusIn(...)：查询所有未完成任务。
 * - findByStatusAndStartTimeLessThanEqual(...)：到期待执行任务（startTime<=now 且 PENDING）。
 * - findByStatusAndEndTimeLessThanEqual(...)：已超时的运行中任务（endTime<=now 且 RUNNING）。
 * - findByTenantId(...)：按租户隔离查询（多租户预留）。
 * - findByDeviceIdAndStatusIn(...)：设备上未完成任务（删除设备时级联取消用）。
 * - findByRepeatModeAndStatus(...)：按重复模式+状态筛查（DAILY 闹钟扫描用）。
 * - findByDeviceId(...)：设备上全部任务（DAILY 同设备去重与冲突判定用）。
 */
package com.irrigation.task.repository;

import com.irrigation.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 查询某个设备上「时间区间有交集」且「尚未结束」的任务（冲突检测核心）
     * 交集条件：newStart < oldEnd && newEnd > oldStart
     */
    @Query("SELECT t FROM Task t WHERE t.deviceId = :deviceId " +
            "AND t.status IN ('PENDING', 'RUNNING') " +
            "AND t.startTime < :endTime AND t.endTime > :startTime")
    List<Task> findConflicts(@Param("deviceId") String deviceId,
                             @Param("startTime") Long startTime,
                             @Param("endTime") Long endTime);

    /** 查询所有未完成任务 */
    List<Task> findByStatusIn(List<Task.Status> statuses);

    /** 查询到期待执行的任务（startTime <= now 且 PENDING） */
    List<Task> findByStatusAndStartTimeLessThanEqual(Task.Status status, Long now);

    /** 查询已超时的运行中任务（endTime <= now 且 RUNNING） */
    List<Task> findByStatusAndEndTimeLessThanEqual(Task.Status status, Long now);

    /**
     * 按租户查询任务（第二版多租户隔离预留）
     * 多租户上线后，各租户的任务列表/取消操作均以本方法隔离，避免跨租户访问
     */
    List<Task> findByTenantId(String tenantId);

    /** 查询某设备上所有未完成任务（PENDING/RUNNING）——删除设备时级联取消用 */
    List<Task> findByDeviceIdAndStatusIn(String deviceId, List<Task.Status> statuses);

    /** 按重复模式查询指定状态的任务（扫描 DAILY 闹钟用） */
    List<Task> findByRepeatModeAndStatus(Task.RepeatMode repeatMode, Task.Status status);

    /** 查询某设备上所有任务（含各类重复模式），用于 DAILY 同设备去重与冲突判定 */
    List<Task> findByDeviceId(String deviceId);
}
