package com.irrigation.task.repository;

import com.irrigation.task.entity.UserPwdFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 强制改密标记数据访问（JPA，主键为邮箱）
 */
public interface UserPwdFlagRepository extends JpaRepository<UserPwdFlag, String> {

    /** 按邮箱查询改密标记（未登记时返回 empty，调用方按「无需改密」处理） */
    Optional<UserPwdFlag> findByEmail(String email);
}
