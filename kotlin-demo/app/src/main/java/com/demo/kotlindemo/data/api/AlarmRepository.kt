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

    private val taskApi = ApiClient.taskService

    // ---------- 规则 ----------
    suspend fun loadRules(tenantId: String? = null): List<AlarmRuleDto> =
        taskApi.getAlarmRules(tenantId)

    suspend fun createRule(rule: Map<String, Any?>): ServiceResponse =
        taskApi.createAlarmRule(rule)

    suspend fun updateRule(id: Long, rule: Map<String, Any?>): ServiceResponse =
        taskApi.updateAlarmRule(id, rule)

    suspend fun deleteRule(id: Long): ServiceResponse =
        taskApi.deleteAlarmRule(id)

    suspend fun toggleRule(id: Long, enabled: Boolean): ServiceResponse =
        taskApi.toggleAlarmRule(id, enabled)

    // ---------- 告警记录 ----------
    suspend fun loadAlarms(tenantId: String? = null): List<AlarmRecordDto> =
        taskApi.getAlarms(tenantId, null)

    suspend fun unreadCount(tenantId: String? = null): UnreadCountResponse =
        taskApi.getAlarmUnreadCount(tenantId)

    suspend fun ack(id: Long): ServiceResponse =
        taskApi.ackAlarm(id)
}
