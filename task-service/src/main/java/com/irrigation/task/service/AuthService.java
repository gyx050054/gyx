package com.irrigation.task.service;

import com.irrigation.task.entity.UserPwdFlag;
import com.irrigation.task.repository.UserPwdFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * 认证业务服务（注册 + 强制改密标记，第二版新增）
 *
 * 职责：
 *  - 租户注册：编排 {@link ThingsBoardAdminClient}（建租户 → 建租户管理员 → 激活设默认密码），
 *    成功后登记 user_pwd_flag（首次登录强制改密）；
 *  - 查询 / 清除强制改密标记（App 首次登录改密流程调用）。
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类不感知 TB 接口细节（全部委托 ThingsBoardAdminClient）；
 *  - 默认密码只在本类收敛（常量），与需求文档「注册不设密码、默认 123456」一致；
 *  - 未来若引入验证码 / 注册限流，只需在入口（Controller 或本类）追加，不影响其它模块。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 注册默认密码（弱口令，首次登录强制改密流程立即触发，风险可接受） */
    public static final String DEFAULT_PASSWORD = "123456";

    /** 简单邮箱格式校验（非严谨 RFC，够用即可） */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ThingsBoardAdminClient adminClient;
    private final UserPwdFlagRepository pwdFlagRepository;

    public AuthService(ThingsBoardAdminClient adminClient, UserPwdFlagRepository pwdFlagRepository) {
        this.adminClient = adminClient;
        this.pwdFlagRepository = pwdFlagRepository;
    }

    /**
     * 租户注册（App 首页「注册」入口）
     *
     * 流程（与需求文档一致）：
     *  ① 建租户（title=邮箱）→ ② 建租户管理员（TENANT_ADMIN）
     *  → ③ 激活并设默认密码 123456 → ④ 登记 user_pwd_flag（首次登录强制改密）
     *
     * @param email 注册邮箱（即登录账号）
     * @throws IllegalArgumentException 邮箱格式非法（Controller 转 400）
     * @throws IllegalStateException    TB 侧创建失败（Controller 转失败响应）
     */
    @Transactional
    public void register(String email) {
        // 邮箱格式校验：客户端可绕过，服务端必须再校验一次
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        email = email.trim();

        // ① 创建租户（title 用邮箱，便于运营区分农户）
        String tenantId = adminClient.createTenant(email);
        log.info("注册：已创建租户 {}（title={}）", tenantId, email);
        // ② 创建租户管理员
        String userId = adminClient.createTenantAdmin(email, tenantId);
        log.info("注册：已创建租户管理员 userId={} email={}", userId, email);
        // ③ 激活并设置默认密码
        adminClient.activateUser(userId, DEFAULT_PASSWORD);

        // ④ 登记强制改密标记（注册→改密窗口期有撞库风险，改密流程立即触发）
        pwdFlagRepository.save(new UserPwdFlag(email, true));
        log.info("注册完成：{} 已登记强制改密", email);
    }

    /**
     * 查询邮箱是否仍需强制改密
     * 说明：未在表内登记的账号（老账号/未走注册接口）默认返回 false（不强制改密）
     */
    public boolean isMustChangePassword(String email) {
        if (email == null) {
            return false;
        }
        return pwdFlagRepository.findByEmail(email.trim())
                .map(UserPwdFlag::getMustChangePassword)
                .orElse(false);
    }

    /** 标记已完成改密（App 改密成功后调用，清除强制改密标记） */
    @Transactional
    public void markPasswordChanged(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        email = email.trim();
        UserPwdFlag flag = pwdFlagRepository.findByEmail(email).orElse(new UserPwdFlag(email, false));
        flag.setMustChangePassword(false);
        pwdFlagRepository.save(flag);
        log.info("邮箱 {} 已标记完成改密", email);
    }
}
