package com.demo.kotlindemo.data.dto

/**
 * ThingsBoard REST API 响应数据模型（Gson 解析用）
 * 字段与 ThingsBoard 4.x 返回的 JSON 对应。
 */

// 登录响应：{"token":"...","refreshToken":"..."}
data class LoginResponse(
    val token: String = "",
    val refreshToken: String = ""
)

// 分页数据包装：{"data":[...],"totalElements":N,...}
data class PageData<T>(
    val data: List<T> = emptyList(),
    val totalElements: Long = 0,
    val totalPages: Int = 0
)

// 实体 ID：{"entityType":"DEVICE","id":"..."}
data class EntityId(
    val entityType: String = "",
    val id: String = ""
)

// 设备信息（tenant/deviceInfos 返回项）
data class DeviceInfoDto(
    val id: EntityId = EntityId(),
    val name: String = "",
    val type: String = "",
    val label: String? = null,
    val active: Boolean = false,
    val lastActivityTime: Long? = null,
    val deviceProfileId: EntityId? = null
)

// 资产信息（tenant/assetInfos 返回项，对应"田块"）
data class AssetInfoDto(
    val id: EntityId = EntityId(),
    val name: String = "",
    val type: String = "",
    val label: String? = null
)

// 实体关系（GET /api/relations/from/... 返回项）
data class EntityRelationDto(
    val from: EntityId = EntityId(),
    val to: EntityId = EntityId(),
    val type: String = ""
)

// 遥测查询响应：{"temperature":[{"ts":...,"value":"25.3"}], ...}
data class TelemetryItem(
    val ts: Long = 0L,
    val value: String = ""
)

// 任务创建响应（微服务端）
data class TaskCreateResponse(
    val success: Boolean = false,
    val message: String = "",
    val taskId: Long? = null,
    val count: Int? = null
)

// 微服务端任务
data class ServiceTask(
    val id: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val action: String = "on",
    val status: String = "PENDING"
)

// 微服务端通用响应：{success, message}（注册/改密标记等接口）
data class ServiceResponse(
    val success: Boolean = false,
    val message: String = ""
)

// 强制改密标记查询响应：{success, message, mustChange}
data class MustChangeResponse(
    val success: Boolean = false,
    val message: String = "",
    val mustChange: Boolean = false
)
