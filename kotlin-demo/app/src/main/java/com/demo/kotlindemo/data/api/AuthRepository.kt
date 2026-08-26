// 包声明：网络层
package com.demo.kotlindemo.data.api

// 登录响应 DTO
import com.demo.kotlindemo.data.dto.LoginResponse

/**
 * 认证仓库（第二版新增：注册 / 自动登录 / 强制改密 流程编排）
 *
 * 职责：把「注册 → 自动登录 → 改密」整条链路的 HTTP 调用收敛在一个类里，
 *       UI 层只调用语义化方法，不感知 TB / 微服务端两套后端的差异。
 *
 * 流程说明（与《内部需求文档》注册流程一致）：
 *  - 注册：微服务端 POST /api/auth/register（SysAdmin 代建租户+管理员，默认密码 123456）
 *  - 自动登录：注册成功后 App 用「邮箱 + 默认密码 123456」直连 TB 登录（无需用户再输）
 *  - 改密：TB POST /api/auth/changePassword（App 代填当前密码 = 默认密码，体验上"不需要旧密码"）
 *  - 改密标记：微服务端 user_pwd_flag（查询/清除强制改密状态）
 */
class AuthRepository {

    // ThingsBoard API（登录/改密）
    private val tb = ApiClient.thingsboard
    // 微服务端 API（注册/改密标记）
    private val task = ApiClient.taskService

    /**
     * 注册新租户（App 首页「注册」入口）
     * @param email 注册邮箱（即登录账号）
     * @return true=注册成功；false=失败（message 供 UI 提示）
     */
    suspend fun register(email: String): Pair<Boolean, String> {
        val resp = task.register(mapOf("email" to email))
        return resp.success to resp.message
    }

    /**
     * 自动登录（注册成功后调用）：用邮箱 + 默认密码直连 TB，并持久化 JWT
     * @return 登录响应（token 非空即成功）
     */
    suspend fun autoLogin(email: String, password: String = "123456"): LoginResponse {
        val resp = tb.login(mapOf("username" to email, "password" to password))
        // 登录成功：写入内存拦截器 + 持久化存储（重启不掉线）
        AuthInterceptor.token = resp.token
        if (resp.token.isNotEmpty()) {
            TokenStore.save(resp.token)
        }
        return resp
    }

    /**
     * 改密后重新登录（修复：TB changePassword 会异步使旧 JWT 全部失效，
     * 必须用新密码重新登录拿新 token，否则改密后进主页的请求全部 401）
     * @return true=重新登录成功（token 非空）
     */
    suspend fun reloginAfterPasswordChange(email: String, newPassword: String): Boolean {
        val resp = tb.login(mapOf("username" to email, "password" to newPassword))
        AuthInterceptor.token = resp.token
        if (resp.token.isNotEmpty()) {
            TokenStore.save(resp.token)
            return true
        }
        return false
    }

    /**
     * 查询邮箱是否仍需强制改密（首次登录流程判断用）
     */
    suspend fun mustChangePassword(email: String): Boolean =
        task.mustChangePassword(email).mustChange

    /**
     * 修改密码（TB 校验当前密码，App 代填默认密码实现"不需要旧密码"体验）
     * @param currentPassword 当前密码（注册场景由 App 代填 123456）
     * @param newPassword     用户输入的新密码
     * @return true=改密成功
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Boolean =
        tb.changePassword(mapOf(
            "currentPassword" to currentPassword,
            "newPassword" to newPassword
        )).isSuccessful

    /**
     * 标记已完成改密（改密成功后调用，清除 user_pwd_flag 强制改密标记）
     */
    suspend fun markPasswordChanged(email: String) {
        task.pwdChanged(mapOf("email" to email))
    }
}
