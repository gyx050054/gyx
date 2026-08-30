/**
 * 【文件职责】
 * 认证业务服务（注册 + 强制改密标记）。
 *  - 租户注册：编排 ThingsBoardAdminClient，按「建租户 → 建租户管理员 → 激活设默认密码 123456」执行，
 *    成功后登记租户 TB 凭证（真多租户隔离）并写入 user_pwd_flag（首次登录强制改密）；
 *  - 查询 / 清除 / 登记强制改密标记（App 首次登录改密流程调用）。
 *
 * 【数据流】
 *  - 入口：Controller 调用 register(email)、isMustChangePassword(email)、
 *    markPasswordChanged(email)、markMustChangePassword(email)。
 *  - 注册流程：邮箱格式校验 → createTenant(建租户,title=邮箱) → createTenantAdmin(建租户管理员)
 *    → activateUser(激活+默认密码123456) → saveTenantCredential(登记TB凭证) → pwdFlagRepository.save(true)。
 *  - 强制改密标记：UserPwdFlagRepository 存取 true/false；未登记账号默认返回 false（不强制改密）。
 *  - 默认密码收敛在本类常量 DEFAULT_PASSWORD="123456"；弱口令由首次改密流程立即覆盖，风险可接受。
 */
package com.irrigation.task.service;

import com.irrigation.task.entity.TenantCredential;
import com.irrigation.task.entity.UserPwdFlag;
import com.irrigation.task.repository.TenantCredentialRepository;
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
    private final TenantCredentialRepository tenantCredentialRepository;

    public AuthService(ThingsBoardAdminClient adminClient, UserPwdFlagRepository pwdFlagRepository,
                       TenantCredentialRepository tenantCredentialRepository) {
        this.adminClient = adminClient; // 注入 TB 管理端客户端，负责建租户/建管理员/激活（本类感知接口）
        this.pwdFlagRepository = pwdFlagRepository; // 注入强制改密标记仓库，用于读写 user_pwd_flag 表
        this.tenantCredentialRepository = tenantCredentialRepository; // 注入租户 TB 凭证仓库，用于登记真多租户凭证
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
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) { // 邮箱为空或不符合正则 → 拒绝
            throw new IllegalArgumentException("邮箱格式不正确"); // 抛参数非法异常（Controller 转 400）
        }
        email = email.trim(); // 去除首尾空白后作为规范邮箱使用

        // ① 创建租户（title 用邮箱，便于运营区分农户）
        String tenantId = adminClient.createTenant(email); // 调 TB 建租户，返回新租户 UUID（title=规范邮箱）
        log.info("注册：已创建租户 {}（title={}）", tenantId, email); // 记录建租户成功日志（含 UUID 与邮箱）
        // ② 创建租户管理员
        String userId = adminClient.createTenantAdmin(email, tenantId); // 在该租户下建 TENANT_ADMIN 用户，返回新用户 UUID
        log.info("注册：已创建租户管理员 userId={} email={}", userId, email); // 记录建管理员成功日志
        // ③ 激活并设置默认密码
        adminClient.activateUser(userId, DEFAULT_PASSWORD); // 设默认密码 123456，完成可登录

        // ③.5 登记租户 ThingsBoard 凭证（真多租户告警隔离：告警扫描要用该租户自己的账号）
        saveTenantCredential(tenantId, email, DEFAULT_PASSWORD); // 把该租户的 TB 账号/密码写入 tenant_credentials 表

        // ④ 登记强制改密标记（注册→改密窗口期有撞库风险，改密流程立即触发）
        pwdFlagRepository.save(new UserPwdFlag(email, true)); // 写 user_pwd_flag 表：mustChangePassword=true
        log.info("注册完成：{} 已登记强制改密", email); // 记录注册完成日志
    }

    /** 登记/更新租户 TB 凭证（真多租户隔离用） */
    private void saveTenantCredential(String tenantId, String email, String password) {
        TenantCredential cred = tenantCredentialRepository.findByTenantId(tenantId) // 先按租户 ID 查已有凭证
                .orElseGet(() -> tenantCredentialRepository.findByEmail(email).orElse(new TenantCredential())); // 没有则按邮箱查；仍没有则新建空对象
        cred.setTenantId(tenantId); // 写租户 ID
        cred.setEmail(email); // 写登录邮箱（TB 登录账号）
        cred.setPassword(password); // 写 TB 登录密码（默认密码）
        cred.setUpdatedAt(System.currentTimeMillis()); // 写更新时间戳（当前毫秒）
        tenantCredentialRepository.save(cred); // 落库（新增或更新）
    }

    /**
     * 查询邮箱是否仍需强制改密
     * 说明：未在表内登记的账号（老账号/未走注册接口）默认返回 false（不强制改密）
     */
    public boolean isMustChangePassword(String email) {
        if (email == null) { // 邮箱为空 → 无账号语义，直接返回 false
            return false;
        }
        return pwdFlagRepository.findByEmail(email.trim()) // 按规范邮箱查强制改密标记
                .map(UserPwdFlag::getMustChangePassword) // 存在则取其 mustChangePassword 布尔值
                .orElse(false); // 未在表内登记 → 默认 false（不强制改密）
    }

    /** 标记已完成改密（App 改密成功后调用，清除强制改密标记） */
    @Transactional
    public void markPasswordChanged(String email) {
        if (email == null || email.isBlank()) { // 邮箱为空 → 非法参数
            throw new IllegalArgumentException("邮箱不能为空"); // 抛异常（Controller 转 400）
        }
        email = email.trim(); // 规范化邮箱
        UserPwdFlag flag = pwdFlagRepository.findByEmail(email).orElse(new UserPwdFlag(email, false)); // 查标记，无则新建（默认已完成改密）
        flag.setMustChangePassword(false); // 置为不强制改密
        pwdFlagRepository.save(flag); // 落库
        log.info("邮箱 {} 已标记完成改密", email); // 记录日志
    }

    /**
     * 登记强制改密（第二版新增：App 直连 TB 创建员工账号后调用）
     * 用途：员工账号用初始密码登录后，与注册账号一样走首次强制改密流程
     */
    @Transactional
    public void markMustChangePassword(String email) {
        if (email == null || email.isBlank()) { // 邮箱为空 → 非法参数
            throw new IllegalArgumentException("邮箱不能为空"); // 抛异常（Controller 转 400）
        }
        email = email.trim(); // 规范化邮箱
        UserPwdFlag flag = pwdFlagRepository.findByEmail(email).orElse(new UserPwdFlag(email, true)); // 查标记，无则新建（默认强制改密）
        flag.setMustChangePassword(true); // 置为强制改密
        pwdFlagRepository.save(flag); // 落库
        log.info("邮箱 {} 已登记强制改密（员工账号创建）", email); // 记录日志
    }
}
