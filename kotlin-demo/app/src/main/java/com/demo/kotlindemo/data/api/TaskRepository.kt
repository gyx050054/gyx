// 声明包名：数据仓库层（任务域）
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import com.demo.kotlindemo.data.dto.ServiceResponse

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

    private val taskApi = ApiClient.taskService

    /** 创建定时任务（单个设备；action 默认 on=开启） */
    suspend fun createTask(
        deviceId: String,
        deviceName: String,
        startTime: Long,
        endTime: Long,
        action: String = "on"
    ): TaskCreateResponse {
        return taskApi.createTask(
            mapOf(
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "startTime" to startTime,
                "endTime" to endTime,
                "action" to action
            )
        )
    }

    /** 批量创建定时任务（多选设备；统一起止时间，全部 action=on） */
    suspend fun createTasksBatch(
        devices: List<Pair<String, String>>,
        startTime: Long,
        endTime: Long
    ): TaskCreateResponse {
        val list = devices.map { (id, name) ->
            mapOf("deviceId" to id, "deviceName" to name, "startTime" to startTime, "endTime" to endTime, "action" to "on")
        }
        return taskApi.createTask(mapOf("devices" to list))
    }

    /** 查询全部任务（含已完成/已取消，供任务管理页展示） */
    suspend fun loadTasks(): List<ServiceTask> = taskApi.getTasks()

    /** 删除（取消）任务：未开始直接取消；已开始由微服务端先发暂停 */
    suspend fun deleteTask(taskId: Long): TaskCreateResponse = taskApi.deleteTask(taskId)

    /** 删除设备时取消其全部未完成任务（第二版：APP 删除设备前调用） */
    suspend fun deleteDeviceTasks(deviceId: String): ServiceResponse = taskApi.deleteDeviceTasks(deviceId)
}
