/**
 * 【文件职责】用户强制改密标记表（user_pwd_flag）。以邮箱为主键，记录该登录账号是否仍需强制改密，
 *           用于消除 TB 统一默认密码（123456）带来的弱口令窗口期。
 * <p>
 * 【数据流】TB 注册/建号使用统一默认密码时写入本表（mustChangePassword=true）；App 首次登录读取该标记，
 *         若为 true 则跳转改密页；用户改密成功后（且回写 TenantCredential 的新密码）将标记置为 false。
 */
package com.irrigation.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 用户强制改密标记表（第二版新增）
 *
 * 背景：TB 注册/建号统一使用默认密码（123456），存在弱口令窗口期；
 * 本表记录「该邮箱是否仍需强制改密」，App 首次登录时据此决定是否跳转改密页。
 */
@Entity
@Table(name = "user_pwd_flag")
public class UserPwdFlag {

    /** 用户邮箱（登录账号，作为主键） */
    @Id
    @Column(length = 128, nullable = false)
    private String email;

    /** 是否需要强制改密（true=首次登录必须改密后才能进入主界面） */
    @Column(nullable = false)
    private Boolean mustChangePassword = true;

    /** 无参构造：供 JPA 反射使用 */
    public UserPwdFlag() {
    }

    /** 全参构造：初始化邮箱与强制改密标记 */
    public UserPwdFlag(String email, Boolean mustChangePassword) {
        this.email = email;
        this.mustChangePassword = mustChangePassword;
    }

    /** 用户邮箱（登录账号，主键） */
    public String getEmail() {
        return email;
    }

    /** 设置用户邮箱（登录账号，主键） */
    public void setEmail(String email) {
        this.email = email;
    }

    /** 是否需要强制改密（true=首次登录必须改密后才能进入主界面） */
    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    /** 设置是否需要强制改密（true=首次登录必须改密后才能进入主界面） */
    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }
}
