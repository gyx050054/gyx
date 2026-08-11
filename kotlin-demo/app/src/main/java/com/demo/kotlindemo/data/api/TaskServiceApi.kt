package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import com.demo.kotlindemo.data.dto.ServiceResponse
import com.demo.kotlindemo.data.dto.MustChangeResponse
import retrofit2.http.*

/**
 * 微服务端（定时任务调度服务）REST API
 * 对应文档「微服务端定时任务完整执行流程」
 */
interface TaskServiceApi {

    // 创建任务：单个 {deviceId,deviceName,startTime,endTime,action} 或批量 {devices:[...]}
    @POST("api/tasks")
    suspend fun createTask(@Body body: Any): TaskCreateResponse

    // 查询任务（第三版修复：按租户过滤，公司间任务互不可见；tenantId 为空时微服务端返回全部，App 恒传当前租户）
    @GET("api/tasks")
    suspend fun getTasks(@Query("tenantId") tenantId: String? = null): List<ServiceTask>

    // 删除任务
    @DELETE("api/tasks/{id}")
    suspend fun deleteTask(@Path("id") id: Long): TaskCreateResponse

    // 删除设备时取消其未完成任务（第二版）：DELETE /api/tasks/device/{deviceId}
    @DELETE("api/tasks/device/{deviceId}")
    suspend fun deleteDeviceTasks(@Path("deviceId") deviceId: String): ServiceResponse

    // ---------- 认证（第二版新增：注册 / 强制改密标记） ----------

    // 租户注册：{email} → {success, message}
    @POST("api/auth/register")
    suspend fun register(@Body body: Any): ServiceResponse

    // 查询是否需强制改密：GET ?email=... → {success, message, mustChange}
    @GET("api/auth/must-change-password")
    suspend fun mustChangePassword(@Query("email") email: String): MustChangeResponse

    // 标记已完成改密：{email} → {success, message}
    @POST("api/auth/pwd-changed")
    suspend fun pwdChanged(@Body body: Any): ServiceResponse

    // 登记强制改密（员工账号创建后）：{email} → {success, message}
    @POST("api/auth/mark-must-change")
    suspend fun markMustChange(@Body body: Any): ServiceResponse
}
