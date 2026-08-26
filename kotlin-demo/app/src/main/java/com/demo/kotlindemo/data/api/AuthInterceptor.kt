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

    @Volatile
    var token: String? = null

/**
     * 拦截请求并附加 Bearer token（内存优先，冷启动从 TokenStore 恢复）
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 内存没有 token 时从持久化存储恢复（App 冷启动后首次请求）
        val t = token ?: TokenStore.load()
        val newRequest = if (t != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $t")
                .build()
        } else {
            request
        }
        return chain.proceed(newRequest)
    }
}
