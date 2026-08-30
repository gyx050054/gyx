/**
 * 【文件职责】OkHttp 认证拦截器：拦截每个请求并自动附加 `Authorization: Bearer <token>` 头，token 采用「内存优先 + 持久化兜底」策略。
 * 【数据流】请求链 → AuthInterceptor.intercept → 内存 token 为空则从 TokenStore.load() 恢复 → 有 token 则重写请求头 → chain.proceed → 继续后置拦截器/发出请求。
 */
package com.demo.kotlindemo.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器：自动为请求附加 Authorization: Bearer <token>
 *
 * 第二版改动：token 来源改为「内存优先 + 持久化兜底」——
 *  - 登录后写入内存（快速路径）并持久化到 TokenStore；
 *  - 内存为空时从 TokenStore 恢复（App 重启后保持登录，第一版修复点）。
 */
object AuthInterceptor : Interceptor {

    /** 内存中的 JWT（@Volatile 保证多线程可见性；登录成功写入，退出登录置空） */
    @Volatile
    var token: String? = null

    /** 拦截请求并附加 Bearer token（内存优先，冷启动从 TokenStore 恢复） */
    override fun intercept(chain: Interceptor.Chain): Response {
        // 取出原始请求（尚未附加认证头）
        val request = chain.request()
        // 内存没有 token 时从持久化存储恢复（App 冷启动后首次请求）
        val t = token ?: TokenStore.load()
        // 有 token 则复制请求并写入 Authorization 头；否则原样转发（未登录态）
        val newRequest = if (t != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $t")
                .build()
        } else {
            request
        }
        // 交给下一级（后续拦截器或实际 HTTP 发送）
        return chain.proceed(newRequest)
    }
}
