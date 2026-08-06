// 声明包名：数据仓库层（ThingsBoard 域）
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.DeviceInfoDto
import com.demo.kotlindemo.data.dto.LoginResponse
import com.demo.kotlindemo.data.dto.TelemetryItem
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
import com.demo.kotlindemo.data.model.applyTelemetry
import com.demo.kotlindemo.data.model.toDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ThingsBoard 数据仓库（由原 FarmRepository 拆分出的「TB 域」部分）
 *
 * 职责：封装所有 ThingsBoard REST 调用（登录/田块/设备/遥测/RPC/凭证），
 *      并把 DTO 转换为 APP 模型（转换扩展函数见 data/model 包）。
 *
 * 设计说明（高内聚低耦合）：
 *  - 本类只依赖 ThingsBoard，与微服务端（任务）彻底分离——微服务端调用见 TaskRepository；
 *  - 所有方法均为挂起函数，需在协程中调用；
 *  - 未来多租户/角色区分（第二版）只改本类内部接口选择，UI 层无感知。
 */
class ThingsBoardRepository {

    private val api = ApiClient.thingsboard

    /** 登录：成功则缓存 JWT 到 AuthInterceptor（后续请求自动携带） */
    suspend fun login(username: String, password: String): LoginResponse {
        val resp = api.login(mapOf("username" to username, "password" to password))
        AuthInterceptor.token = resp.token
        return resp
    }

    /** 退出登录：清空内存中的 JWT */
    fun logout() {
        AuthInterceptor.token = null
    }

    /** 获取所有田块（资产列表 + 每个田块的设备数） */
    suspend fun loadFields(): List<Field> = withContext(Dispatchers.IO) {
        val page = api.getAssets(pageSize = AppConfig.PAGE_SIZE, page = 0)
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
        val valves = api.getDevices(pageSize = AppConfig.PAGE_SIZE, page = 0, type = "VALVE").data
        val sensors = api.getDevices(pageSize = AppConfig.PAGE_SIZE, page = 0, type = "TEMPERATURE_HUMIDITY").data
        coroutineScope {
            (valves + sensors).map { info ->
                async { fetchDeviceWithTelemetry(info, null) }
            }.awaitAll()
        }
    }

    /** 读取单台设备（按 id，用于关系解析后的详情） */
    suspend fun fetchDevice(deviceId: String, fieldId: String?): Device = withContext(Dispatchers.IO) {
        val info = api.getDevice(deviceId)
        fetchDeviceWithTelemetry(info, fieldId)
    }

    /** 开关阀门（RPC oneway，不等待设备回执） */
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

    /**
     * 读取单台设备的完整信息（设备信息 + 最新遥测，一次性填充 UI 模型）
     * @param fieldId 所属田块 ID；未知时传 null（"设备"页全量列表场景）
     */
    private suspend fun fetchDeviceWithTelemetry(info: DeviceInfoDto, fieldId: String?): Device {
        val telemetry = api.getLatestTelemetry(
            deviceId = info.id.id,
            keys = AppConfig.TELEMETRY_KEYS,
            limit = 1
        )
        return info.toDevice(fieldId).applyTelemetry(telemetry)
    }
}
