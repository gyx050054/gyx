package com.demo.kotlindemo.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络层单例
 *
 * 职责：统一构建 Retrofit 客户端并暴露两个 API 接口对象：
 *  - thingsboard：ThingsBoard 服务（:8080），自动携带 JWT（AuthInterceptor）
 *  - taskService：微服务端（:9300），独立 client，不携带 JWT（微服务端不需要 TB 的 token）
 *
 * 设计说明（高内聚低耦合）：
 *  - baseUrl 等常量集中在 {@link AppConfig}，换环境只改一处；
 *  - 两个后端各自独立 client，互不干扰（原实现共用带认证 client 是隐患，
 *    微服务端不需要 TB 的 JWT，不应收到无关的 Authorization 头）；
 *  - 未来多租户（第二版）需要"每会话一个 token"，改造点集中在 AuthInterceptor 与
 *    ApiClient 的 client 构造，本文件是唯一入口。
 */
object ApiClient {

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    /** 共用连接超时配置（秒） */
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    /** ThingsBoard client：带 JWT 认证拦截器 */
    private val tbClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor) // 自动附加 Bearer token
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** 微服务端 client：无认证拦截器（微服务端不需要 TB token） */
    private val taskClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** ThingsBoard REST API（地址见 AppConfig.THINGSBOARD_BASE_URL） */
    val thingsboard: ThingsBoardApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.THINGSBOARD_BASE_URL)
            .client(tbClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ThingsBoardApi::class.java)
    }

    /** 微服务端 REST API（地址见 AppConfig.TASK_SERVICE_BASE_URL） */
    val taskService: TaskServiceApi by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.TASK_SERVICE_BASE_URL)
            .client(taskClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TaskServiceApi::class.java)
    }
}
