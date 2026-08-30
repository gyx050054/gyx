/**
 * 【文件职责】告警仓库：封装调用微服务端自研告警引擎接口（/api/alarms），含告警规则管理（增删改/启停）、告警记录列表、未确认计数（顶栏红点）与确认。
 * 【数据流】ViewModel → AlarmRepository → ApiClient.taskService（微服务端无认证 client）→ TaskServiceApi（@GET/@POST/@PUT/@DELETE）→ HTTP 请求 → 响应 DTO（AlarmRuleDto / AlarmRecordDto / UnreadCountResponse / ServiceResponse）。
 */
// 声明包名：数据/网络层
package com.demo.kotlindemo.data.api

import com.demo.kotlindemo.data.dto.AlarmRecordDto
import com.demo.kotlindemo.data.dto.AlarmRuleDto
import com.demo.kotlindemo.data.dto.ServiceResponse
import com.demo.kotlindemo.data.dto.UnreadCountResponse

/**
 * 告警仓库（App 端，微服务端自研告警引擎的客户端封装）
 *
 * 封装调用微服务端告警接口（/api/alarms）：
 *  - 规则管理：列表/创建/更新/删除/启用停用
 *  - 告警记录：列表 / 未确认计数（顶栏红点） / 确认
 *
 * 高内聚低耦合：只依赖微服务端，与 ThingsBoard 数据（ThingsBoardRepository）彻底分离。
 */
class AlarmRepository {

    /** 微服务端 API（无认证 client）：告警与任务接口共用同一微服务端，故复用 taskService */
    private val taskApi = ApiClient.taskService

    // ---------- 规则 ----------

    /** 加载告警规则列表（规则管理页）；
     *  tenantId 用于按租户隔离，null 时微服务端返回全部（App 恒传当前租户）。 */
    suspend fun loadRules(tenantId: String? = null): List<AlarmRuleDto> =
        // 直接透传微服务端 GET /api/alarms/rules，返回规则列表
        taskApi.getAlarmRules(tenantId)

    /** 创建告警规则（新增规则表单提交，body 为各字段组成的 Map） */
    suspend fun createRule(rule: Map<String, Any?>): ServiceResponse =
        // POST /api/alarms/rules，success 表示创建成功
        taskApi.createAlarmRule(rule)

    /** 更新告警规则（按规则 id 更新，body 为各字段组成的 Map） */
    suspend fun updateRule(id: Long, rule: Map<String, Any?>): ServiceResponse =
        // PUT /api/alarms/rules/{id}
        taskApi.updateAlarmRule(id, rule)

    /** 删除告警规则（按规则 id 删除） */
    suspend fun deleteRule(id: Long): ServiceResponse =
        // DELETE /api/alarms/rules/{id}
        taskApi.deleteAlarmRule(id)

    /** 启用/停用告警规则（开关切换；enabled=true 启用，false 停用） */
    suspend fun toggleRule(id: Long, enabled: Boolean): ServiceResponse =
        // POST /api/alarms/rules/{id}/toggle?enabled=…
        taskApi.toggleAlarmRule(id, enabled)

    // ---------- 告警记录 ----------

    /** 加载告警记录列表（告警列表页；status 固定传 null 取全部状态，
     *  tenantId 按租户隔离，null 时微服务端返回全部）。 */
    suspend fun loadAlarms(tenantId: String? = null): List<AlarmRecordDto> =
        // GET /api/alarms?tenantId=…（status 不传 = 全部状态）
        taskApi.getAlarms(tenantId, null)

    /** 未确认告警计数（顶栏红点；返回 UnreadCountResponse.count） */
    suspend fun unreadCount(tenantId: String? = null): UnreadCountResponse =
        // GET /api/alarms/unread-count?tenantId=…
        taskApi.getAlarmUnreadCount(tenantId)

    /** 确认单条告警（ACTIVE → ACKNOWLEDGED，确认后红点计数减一） */
    suspend fun ack(id: Long): ServiceResponse =
        // POST /api/alarms/{id}/ack
        taskApi.ackAlarm(id)
}
