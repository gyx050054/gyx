/**
 * 【文件职责】
 * 强制改密标记（UserPwdFlag）的 JPA 数据访问对象（Repository），继承 JpaRepository，对改密标记实体提供
 * 「按邮箱查询改密标记」的持久化查询能力。
 * 改密标记以邮箱为主键（String），记录某个用户是否被强制要求修改密码，
 * 是登录/鉴权流程中判断"是否需强制改密"的数据来源。
 *
 * 【数据流】
 * Service（登录、鉴权等业务逻辑）调用本接口方法 → Spring Data JPA 依据方法名自动生成 JPA/SQL 语句
 * → 访问底层数据库表 → 查询结果映射为 UserPwdFlag 实体（未登记时 Optional 为空）返回给调用方，
 * 由 Service 进一步加工为业务响应（如判断"无需改密/需强制改密"）。
 */
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
