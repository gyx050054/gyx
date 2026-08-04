package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import retrofit2.http.*

/**
 * 微服务端（定时任务调度服务）REST API
 * 对应文档「微服务端定时任务完整执行流程」
 */
interface TaskServiceApi {

    // 创建任务：单个 {deviceId,deviceName,startTime,endTime,action} 或批量 {devices:[...]}
    @POST("api/tasks")
    suspend fun createTask(@Body body: Any): TaskCreateResponse

    // 查询全部任务
    @GET("api/tasks")
    suspend fun getTasks(): List<ServiceTask>

    // 删除任务
    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Long): TaskCreateResponse
}
