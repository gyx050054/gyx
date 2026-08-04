package com.demo.kotlindemo.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 网络层单例
 * - baseUrl：ThingsBoard 服务地址（模拟器用 10.0.2.2 访问宿主机，真机用局域网 IP）
 * - AuthInterceptor 自动携带 JWT
 */
object ApiClient {

    // 服务端地址（按实际环境修改）
    //  - Android 模拟器访问宿主机：http://10.0.2.2:8080
    //  - 真机（同一局域网）：http://<电脑局域网IP>:8080
    const val THINGSBOARD_BASE_URL = "http://10.0.2.2:8080/"
    const val TASK_SERVICE_BASE_URL = "http://10.0.2.2:9091/"

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor)      // 自动附加 Bearer token
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val thingsboard: ThingsBoardApi by lazy {
        Retrofit.Builder()
            .baseUrl(THINGSBOARD_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ThingsBoardApi::class.java)
    }

    // 微服务端不需要 JWT，用独立的无认证 client（但共用也安全）
    val taskService: TaskServiceApi by lazy {
        Retrofit.Builder()
            .baseUrl(TASK_SERVICE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TaskServiceApi::class.java)
    }
}
