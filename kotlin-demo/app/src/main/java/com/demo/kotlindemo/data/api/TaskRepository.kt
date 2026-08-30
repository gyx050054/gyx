/**
 * 【文件职责】微服务端任务仓库：封装定时任务调度服务（:9300）的全部调用——创建单个/批量任务、查询任务列表、删除（取消）任务、查询执行流水、天气查询等。
 * 【数据流】任务页/调度页 ViewModel → TaskRepository → ApiClient.taskService（无认证 client）→ TaskServiceApi（@POST/@GET/@DELETE）→ HTTP → 响应 DTO（TaskCreateResponse / ServiceTask / TaskRunDto / WeatherDto / ServiceResponse）。
 */
// 声明包名：数据仓库层（任务域）
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import com.demo.kotlindemo.data.dto.ServiceResponse
import com.demo.kotlindemo.data.dto.WeatherDto
import com.demo.kotlindemo.data.dto.TaskRunDto

/**
 * 微服务端任务仓库（由原 FarmRepository 拆分出的「任务域」部分）
 *
 * 职责：封装微服务端（定时任务调度服务 :9300）全部调用：
 *  创建单个/批量任务、查询任务列表、删除（取消）任务。
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只依赖微服务端，与 ThingsBoard 数据（ThingsBoardRepository）彻底分离；
 *  - 多租户上线（第二版）后，任务接口需携带 tenantId，本类是集中改造点。
 */
class TaskRepository {

    /** 微服务端任务 API（无认证 client，与告警共用同一微服务端） */
    private val taskApi = ApiClient.taskService

    /** 创建定时任务（单个设备；action 默认 on=开启；第三版：带当前租户 tenantId；第三/四版：可指定每天 DAILY） */
    suspend fun createTask(
        deviceId: String,
        deviceName: String,
        startTime: Long,
        endTime: Long,
        action: String = "on",
        tenantId: String? = null,
        repeatMode: String = "ONCE",
        dailyHour: Int? = null,
        durationMinutes: Int? = null
    ): TaskCreateResponse {
        return taskApi.createTask(
            mapOf(
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "startTime" to startTime,
                "endTime" to endTime,
                "action" to action,
                "tenantId" to tenantId,
                "repeatMode" to repeatMode,
                "dailyHour" to dailyHour,
                "durationMinutes" to durationMinutes
            )
        )
    }

    /** 查询每天任务的执行流水（task_runs）：看昨天浇没浇/是否因雨跳过 */
    suspend fun getTaskRuns(taskId: Long): List<TaskRunDto> = taskApi.getTaskRuns(taskId)

    /** 批量创建定时任务（多选设备；统一起止时间，全部 action=on；第三版：带当前租户） */
    suspend fun createTasksBatch(
        devices: List<Pair<String, String>>,
        startTime: Long,
        endTime: Long,
        tenantId: String? = null
    ): TaskCreateResponse {
        val list = devices.map { (id, name) ->
            mapOf("deviceId" to id, "deviceName" to name, "startTime" to startTime, "endTime" to endTime, "action" to "on", "tenantId" to tenantId)
        }
        return taskApi.createTask(mapOf("devices" to list))
    }

    /** 查询任务（第三版：按当前租户过滤，各公司只见自己的任务） */
    suspend fun loadTasks(tenantId: String? = null): List<ServiceTask> = taskApi.getTasks(tenantId)

    /** 删除（取消）任务：未开始直接取消；已开始由微服务端先发暂停 */
    suspend fun deleteTask(taskId: Long): TaskCreateResponse = taskApi.deleteTask(taskId)

    /** 删除设备时取消其全部未完成任务（第二版：APP 删除设备前调用） */
    suspend fun deleteDeviceTasks(deviceId: String): ServiceResponse = taskApi.deleteDeviceTasks(deviceId)

    /** 登记员工账号强制改密（第二版：创建员工后调用，首登走改密流程） */
    suspend fun markMustChange(email: String): ServiceResponse = taskApi.markMustChange(mapOf("email" to email))

    /** 查询天气（第三代第一版 §4：微服务端 Open-Meteo 网关） */
    suspend fun getWeather(lat: String, lon: String): WeatherDto = taskApi.getWeather(lat, lon)
}
