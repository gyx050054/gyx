package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.DeviceInfoDto
import com.demo.kotlindemo.data.dto.LoginResponse
import com.demo.kotlindemo.data.dto.ServiceTask
import com.demo.kotlindemo.data.dto.TaskCreateResponse
import com.demo.kotlindemo.data.dto.TelemetryItem
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 数据仓库：封装 ThingsBoard / 微服务端 API 调用，并把 DTO 转换为 APP 模型
 * 所有方法都是挂起函数，需在协程中调用。
 */
class FarmRepository {

    private val api = ApiClient.thingsboard
    private val taskApi = ApiClient.taskService

    /** 登录：成功则缓存 JWT 到 AuthInterceptor */
    suspend fun login(username: String, password: String): LoginResponse {
        val resp = api.login(mapOf("username" to username, "password" to password))
        AuthInterceptor.token = resp.token
        return resp
    }

    fun logout() {
        AuthInterceptor.token = null
    }

    /** 获取所有田块（资产列表 + 每个田块的设备数） */
    suspend fun loadFields(): List<Field> = withContext(Dispatchers.IO) {
        val page = api.getAssets(pageSize = 200, page = 0)
        coroutineScope {
            page.data.map { asset ->
                async {
                    // 每个田块查 Contains 关系，统计设备数
                    val relations = api.getAssetRelations(asset.id.id)
                    Field(
                        id = asset.id.id,
                        name = asset.name,
                        deviceCount = relations.size,
                        activeCount = relations.size // 设备在线数由详情页实时查，这里先用总数
                    )
                }
            }.awaitAll()
        }
    }

    /** 获取某个田块下的所有设备（关系 → 设备信息 → 最新遥测） */
    suspend fun loadFieldDevices(fieldId: String): List<Device> = withContext(Dispatchers.IO) {
        val relations = api.getAssetRelations(fieldId)
        val deviceIds = relations.filter { it.to.entityType == "DEVICE" }.map { it.to.id }
        coroutineScope {
            deviceIds.map { id ->
                async { fetchDeviceWithTelemetry(api.getDevice(id), fieldId) }
            }.awaitAll()
        }
    }

    /** 获取全部设备（type=VALVE 或 TEMPERATURE_HUMIDITY，用于"设备"页） */
    suspend fun loadAllDevices(): List<Device> = withContext(Dispatchers.IO) {
        val valves = api.getDevices(pageSize = 200, page = 0, type = "VALVE").data
        val sensors = api.getDevices(pageSize = 200, page = 0, type = "TEMPERATURE_HUMIDITY").data
        coroutineScope {
            (valves + sensors).map { info ->
                async { fetchDeviceWithTelemetry(info, null) }
            }.awaitAll()
        }
    }

    /** 读取单台设备的完整信息（信息 + 最新遥测） */
    private suspend fun fetchDeviceWithTelemetry(info: DeviceInfoDto, fieldId: String?): Device {
        val telemetry = api.getLatestTelemetry(
            deviceId = info.id.id,
            keys = "temperature,humidity,valveState,batteryLevel,instantFlow,totalWaterUsage,waterPressure,faultStatus",
            limit = 1
        )
        val d = info.toDevice(fieldId)
        return d.applyTelemetry(telemetry)
    }

    /** 读取单台设备（按 id，用于关系解析后的详情） */
    suspend fun fetchDevice(deviceId: String, fieldId: String?): Device = withContext(Dispatchers.IO) {
        val info = api.getDevice(deviceId)
        fetchDeviceWithTelemetry(info, fieldId)
    }

    /** 开关阀门（RPC oneway） */
    suspend fun toggleValve(deviceId: String, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val resp = api.sendRpc(
            deviceId = deviceId,
            body = mapOf("method" to "setValveState", "params" to mapOf("state" to on))
        )
        resp.isSuccessful
    }

    /** 历史遥测（温度/湿度曲线数据） */
    suspend fun loadHistory(
        deviceId: String,
        keys: String,
        startTs: Long,
        endTs: Long,
        interval: Long
    ): Map<String, List<TelemetryItem>> = withContext(Dispatchers.IO) {
        api.getTelemetryHistory(deviceId, keys, startTs, endTs, interval)
    }

    // ================= 微服务端任务 =================

    /** 创建定时任务（单个） */
    suspend fun createTask(deviceId: String, deviceName: String, startTime: Long, endTime: Long, action: String = "on"): TaskCreateResponse {
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

    /** 批量创建定时任务（多选设备） */
    suspend fun createTasksBatch(devices: List<Pair<String, String>>, startTime: Long, endTime: Long): TaskCreateResponse {
        val list = devices.map { (id, name) ->
            mapOf("deviceId" to id, "deviceName" to name, "startTime" to startTime, "endTime" to endTime, "action" to "on")
        }
        return taskApi.createTask(mapOf("devices" to list))
    }

    /** 查询全部任务 */
    suspend fun loadTasks(): List<ServiceTask> = taskApi.getTasks()

    /** 删除任务 */
    suspend fun deleteTask(taskId: Long): TaskCreateResponse = taskApi.deleteTask(taskId)
}

// ================= DTO → 模型 转换 =================

/** 设备类型映射：ThingsBoard type → APP DeviceType */
fun DeviceInfoDto.toDevice(fieldId: String?): Device {
    val type = when (this.type) {
        "TEMPERATURE_HUMIDITY" -> DeviceType.SENSOR
        "VALVE" -> DeviceType.VALVE
        else -> DeviceType.VALVE
    }
    return Device(
        id = id.id,
        name = name,
        type = type,
        isOnline = active,
        isOn = false,   // 由遥测 valveState 覆盖
        fieldId = fieldId ?: "",
        battery = 100
    )
}

/** 遥测数据填充到设备模型 */
fun Device.applyTelemetry(telemetry: Map<String, List<TelemetryItem>>): Device {
    fun latest(key: String): String? = telemetry[key]?.firstOrNull()?.value

    val valveState = latest("valveState")
    val battery = latest("batteryLevel")?.toIntOrNull() ?: battery
    val temp = latest("temperature")?.toDoubleOrNull()
    val hum = latest("humidity")?.toDoubleOrNull()
    val flow = latest("instantFlow")?.toDoubleOrNull()
    val usage = latest("totalWaterUsage")?.toDoubleOrNull()
    val pressure = latest("waterPressure")?.toDoubleOrNull()
    val fault = latest("faultStatus")?.toBooleanStrictOrNull() ?: false
    val ts = telemetry.values.firstOrNull()?.firstOrNull()?.ts ?: lastReportAt

    return copy(
        isOn = valveState == "WORKING",
        valveState = valveState ?: "",
        battery = battery,
        temperature = temp ?: temperature,
        humidity = hum ?: humidity,
        instantFlow = flow ?: instantFlow,
        totalWaterUsage = usage ?: totalWaterUsage,
        waterPressure = pressure ?: waterPressure,
        faultStatus = fault,
        lastReportAt = ts
    )
}
