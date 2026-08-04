package com.demo.kotlindemo.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器：自动为请求附加 Authorization: Bearer <token>
 * 登录后设置 token；退出登录时清空
 */
object AuthInterceptor : Interceptor {

    @Volatile
    var token: String? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val t = token
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
