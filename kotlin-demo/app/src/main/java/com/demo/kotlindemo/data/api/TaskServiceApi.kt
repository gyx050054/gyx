package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import com.demo.kotlindemo.data.dto.ServiceResponse
import com.demo.kotlindemo.data.dto.MustChangeResponse
import com.demo.kotlindemo.data.dto.AlarmRuleDto
import com.demo.kotlindemo.data.dto.AlarmRecordDto
import com.demo.kotlindemo.data.dto.UnreadCountResponse
import com.demo.kotlindemo.data.dto.WeatherDto
import com.demo.kotlindemo.data.dto.TaskRunDto
import retrofit2.http.*

/**
 * 微服务端（定时任务调度服务）REST API
 * 对应文档「微服务端定时任务完整执行流程」
 */
interface TaskServiceApi {

    // 创建任务：单个 {deviceId,deviceName,startTime,endTime,action} 或批量 {devices:[...]}
    @POST("api/tasks")
/**
     * 创建任务：单个 {deviceId,deviceName,startTime,endTime,action} 或批量 {devices:[...]}
     */
    suspend fun createTask(@Body body: Any): TaskCreateResponse

    // 查询任务（第三版修复：按租户过滤，公司间任务互不可见；tenantId 为空时微服务端返回全部，App 恒传当前租户）
    @GET("api/tasks")
/**
     * 查询任务列表（第三版：按租户隔离，App 恒传当前租户）
     */
    suspend fun getTasks(@Query("tenantId") tenantId: String? = null): List<ServiceTask>

    // 查询每天任务执行流水：GET /api/tasks/{id}/runs
    @GET("api/tasks/{id}/runs")
/**
     * 查询每天任务的执行流水（task_runs：昨天浇没浇/是否因雨跳过）
     */
    suspend fun getTaskRuns(@Path("id") id: Long): List<TaskRunDto>

    // 删除任务
    @DELETE("api/tasks/{id}")
/**
     * 删除（取消）任务：未开始直接取消 / 运行中先暂停
     */
    suspend fun deleteTask(@Path("id") id: Long): TaskCreateResponse

    // 删除设备时取消其未完成任务（第二版）：DELETE /api/tasks/device/{deviceId}
    @DELETE("api/tasks/device/{deviceId}")
/**
     * 删除设备时取消其全部未完成任务
     */
    suspend fun deleteDeviceTasks(@Path("deviceId") deviceId: String): ServiceResponse

    // ---------- 认证（第二版新增：注册 / 强制改密标记） ----------

    // 租户注册：{email} → {success, message}
    @POST("api/auth/register")
/**
     * 租户注册：{email} → 服务端代建租户+管理员，默认密码 123456
     */
    suspend fun register(@Body body: Any): ServiceResponse

    // 查询是否需强制改密：GET ?email=... → {success, message, mustChange}
    @GET("api/auth/must-change-password")
/**
     * 查询邮箱是否需强制改密
     */
    suspend fun mustChangePassword(@Query("email") email: String): MustChangeResponse

    // 标记已完成改密：{email} → {success, message}
    @POST("api/auth/pwd-changed")
/**
     * 标记已完成改密（清除强制改密标记）
     */
    suspend fun pwdChanged(@Body body: Any): ServiceResponse

    // 登记强制改密（员工账号创建后）：{email} → {success, message}
    @POST("api/auth/mark-must-change")
/**
     * 登记强制改密（创建员工账号后调用）
     */
    suspend fun markMustChange(@Body body: Any): ServiceResponse

    // ---------- 告警（自研告警引擎，第四版） ----------

    // 告警记录列表：GET /api/alarms?tenantId=&status=
    @GET("api/alarms")
/**
     * 告警记录列表（可带 tenantId、status=ACTIVE|ACKNOWLEDGED|RESOLVED 过滤）
     */
    suspend fun getAlarms(
        @Query("tenantId") tenantId: String? = null,
        @Query("status") status: String? = null
    ): List<AlarmRecordDto>

    // 未确认告警计数：GET /api/alarms/unread-count?tenantId=（顶栏红点）
    @GET("api/alarms/unread-count")
/**
     * 未确认告警计数（App 顶栏红点）
     */
    suspend fun getAlarmUnreadCount(@Query("tenantId") tenantId: String? = null): UnreadCountResponse

    // 确认告警：POST /api/alarms/{id}/ack（红点消失）
    @POST("api/alarms/{id}/ack")
/**
     * 确认单条告警（ACTIVE → ACKNOWLEDGED）
     */
    suspend fun ackAlarm(@Path("id") id: Long): ServiceResponse

    // 规则列表：GET /api/alarms/rules?tenantId=
    @GET("api/alarms/rules")
/**
     * 告警规则列表（规则管理页）
     */
    suspend fun getAlarmRules(@Query("tenantId") tenantId: String? = null): List<AlarmRuleDto>

    // 创建规则：POST /api/alarms/rules
    @POST("api/alarms/rules")
/**
     * 创建告警规则
     */
    suspend fun createAlarmRule(@Body body: Any): ServiceResponse

    // 更新规则：PUT /api/alarms/rules/{id}
    @PUT("api/alarms/rules/{id}")
/**
     * 更新告警规则
     */
    suspend fun updateAlarmRule(@Path("id") id: Long, @Body body: Any): ServiceResponse

    // 删除规则：DELETE /api/alarms/rules/{id}
    @DELETE("api/alarms/rules/{id}")
/**
     * 删除告警规则
     */
    suspend fun deleteAlarmRule(@Path("id") id: Long): ServiceResponse

    // 启用/停用规则：POST /api/alarms/rules/{id}/toggle?enabled=
    @POST("api/alarms/rules/{id}/toggle")
/**
     * 启用/停用告警规则
     */
    suspend fun toggleAlarmRule(@Path("id") id: Long, @Query("enabled") enabled: Boolean): ServiceResponse

    // ---------- 天气（第三代第一版 §4） ----------

    // 天气查询：GET /api/weather?lat=&lon=（微服务端 Open-Meteo 网关）
    @GET("api/weather")
/**
     * 查询天气（微服务端网关，内部调 Open-Meteo，含 10 分钟缓存）
     */
    suspend fun getWeather(
        @Query("lat") lat: String,
        @Query("lon") lon: String
    ): WeatherDto
}
