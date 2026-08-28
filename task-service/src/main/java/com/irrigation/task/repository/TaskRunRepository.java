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
