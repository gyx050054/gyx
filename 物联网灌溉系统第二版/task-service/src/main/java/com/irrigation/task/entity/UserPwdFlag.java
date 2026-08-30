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

    public UserPwdFlag() {
    }

    public UserPwdFlag(String email, Boolean mustChangePassword) {
        this.email = email;
        this.mustChangePassword = mustChangePassword;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }
}
