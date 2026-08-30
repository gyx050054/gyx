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
}
