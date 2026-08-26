package com.demo.kotlindemo.data.dto

// Gson 字段名映射（TB 响应 customerId/tenantId 为嵌套对象，需 @SerializedName 指定）
import com.google.gson.annotations.SerializedName

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

// 资产配置信息（assetProfileInfos 返回项；新增田块需指定 FIELD profile）
data class AssetProfileDto(
    val id: EntityId = EntityId(),
    val name: String = "",
    val type: String = ""
)

// 设备配置信息（deviceProfileInfos 返回项；新增设备需指定 VALVE / TEMPERATURE_HUMIDITY profile）
data class DeviceProfileDto(
    val id: EntityId = EntityId(),
    val name: String = "",
    val type: String = ""
)

// 设备凭证（credentials 返回项）：响应含嵌套 id 对象，用 DTO 只取 credentialsId 避免 Gson 解析失败
data class DeviceCredentialsDto(
    val credentialsId: String = "",
    val credentialsType: String = ""
)

// 当前用户信息（/api/auth/user 返回项）：用于判断身份（TENANT_ADMIN / CUSTOMER_USER）
// TB 实体 ID（/api/auth/user 响应里的 customerId/tenantId 是 {entityType,id} 对象）
data class EntityRefDto(
    val entityType: String = "",
    val id: String = ""
)

data class CurrentUserDto(
    // 账号 id（成员列表删除用；/api/customer/{cid}/users 响应含 id 对象）
    val id: EntityId = EntityId(),
    val authority: String = "",
    val email: String = "",
    // TB 响应中 customerId/tenantId 为嵌套对象 {entityType,id}，用 @SerializedName 映射后暴露字符串属性
    @SerializedName("customerId") private val customerIdObj: EntityRefDto? = null,
    @SerializedName("tenantId") private val tenantIdObj: EntityRefDto? = null
) {
    /** 员工(CUSTOMER_USER)所属客户 ID（查询可见范围用） */
    val customerId: String get() = customerIdObj?.id ?: ""
    /** 所属租户 ID */
    val tenantId: String get() = tenantIdObj?.id ?: ""
}

// 客户（员工/使用者）信息：{id, name, ...}
data class CustomerDto(
    val id: EntityId = EntityId(),
    val name: String = "",
    val title: String = ""
)

// 成员信息（第三版：成员管理列表项）——一个家庭（客户）下的一个账号
data class MemberDto(
    val customerId: String = "",      // 所属家庭（客户）id
    val customerTitle: String = "",   // 所属家庭名称
    val userId: String = "",          // 账号 id（删除成员用）
    val email: String = "",           // 账号邮箱
    val authority: String = ""        // TENANT_ADMIN（管理员）/ CUSTOMER_USER（家庭成员）
)

// 用户激活信息（activationLinkInfo 返回项）：activateToken 在 value 的 URL 参数里
data class ActivationInfoDto(
    val value: String = "",
    val ttlMs: Long = 0
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
    val status: String = "PENDING",
    val repeatMode: String = "ONCE",      // ONCE / DAILY（第三天第一版 §2）
    val dailyHour: Int? = null,           // DAILY 每天开始小时
    val durationMinutes: Int? = null      // DAILY 持续时长（分钟）
)

// 微服务端每天任务执行流水（task_runs）
data class TaskRunDto(
    val id: Long = 0L,
    val taskId: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val runDate: String = "",
    val startTs: Long? = null,
    val endTs: Long? = null,
    val action: String = "on",
    val status: String = "PENDING"       // PENDING / COMPLETED / SKIPPED_WEATHER
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

// 微服务端告警规则（自研告警引擎）
data class AlarmRuleDto(
    val id: Long = 0L,
    val name: String = "",
    val deviceType: String = "ALL",
    val metric: String = "",
    val operator: String = "lt",
    val threshold: Double? = null,
    val severity: String = "MEDIUM",
    val message: String = "",
    val enabled: Boolean = true,
    val tenantId: String? = null,
    val createdAt: Long = 0L
)

// 微服务端告警记录
data class AlarmRecordDto(
    val id: Long = 0L,
    val tenantId: String? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val ruleId: Long = 0L,
    val severity: String = "MEDIUM",
    val message: String = "",
    val status: String = "ACTIVE",
    val firstAt: Long = 0L,
    val lastAt: Long = 0L,
    val resolvedAt: Long? = null
)

// 未确认告警计数响应：{success, message, count}
data class UnreadCountResponse(
    val success: Boolean = true,
    val message: String = "",
    val count: Long = 0L
)

// 天气响应：{success, message, weatherDesc, temperature, precipitation, precipProb1h}
data class WeatherDto(
    val success: Boolean = true,
    val message: String = "",
    val weatherDesc: String = "——",
    val temperature: Double = Double.NaN,
    val precipitation: Double = 0.0,
    val precipProb1h: Int? = null
)
