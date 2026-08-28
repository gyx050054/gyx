// 声明包名：UI 状态管理（ViewModel）
package com.demo.kotlindemo.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.demo.kotlindemo.data.api.AlarmRepository
import com.demo.kotlindemo.data.api.ThingsBoardRepository
import com.demo.kotlindemo.data.dto.AlarmRecordDto
import com.demo.kotlindemo.data.dto.AlarmRuleDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 告警 ViewModel（自研告警引擎，第四版）
 *
 * 职责：
 *  - 未确认告警计数（顶栏铃铛红点）
 *  - 告警记录列表（按级别展示 + 确认）
 *  - 规则管理（列表/创建/更新/删除/启用停用，管理员专属）
 * 数据来源：微服务端告警接口（AlarmRepository）
 */
class AlarmViewModel : ViewModel() {

    private val repository = AlarmRepository()
    // 从 TB 获取当前登录者所属租户 id（与任务/成员一致，实现公司间隔离）
    private val thingsBoardRepository = ThingsBoardRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 未确认告警计数（顶栏红点）──
    var unreadCount by mutableStateOf(0L)
        private set

    // ── 告警记录列表 ──
    val alarms = mutableStateListOf<AlarmRecordDto>()

    // ── 规则列表（规则管理页）──
    val rules = mutableStateListOf<AlarmRuleDto>()

    // ── 状态与反馈 ──
    var isLoading by mutableStateOf(false)
        private set
    var lastMessage by mutableStateOf<String?>(null)
        private set
    var isAdmin by mutableStateOf(false)   // 规则管理仅管理员可见
        private set

    /** 当前租户 id（登录后首次调用时从 TB 拉取并缓存） */
    private var tenantId: String? = null

    private suspend fun ensureTenantId() {
        if (tenantId == null) {
            tenantId = thingsBoardRepository.myTenantId()
        }
    }

    /** 加载未确认计数 + 全部告警（下拉刷新/进入告警页调用） */
    fun loadAlarms() {
        scope.launch {
            isLoading = true
            try {
                ensureTenantId()
                unreadCount = repository.unreadCount(tenantId).count
                alarms.clear()
                alarms.addAll(repository.loadAlarms(tenantId))
            } catch (e: Exception) {
                lastMessage = "加载告警失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** 仅刷新未确认计数（顶栏红点，轻量调用） */
    fun refreshUnread() {
        scope.launch {
            try {
                ensureTenantId()
                unreadCount = repository.unreadCount(tenantId).count
            } catch (_: Exception) {
                // 静默失败，不打断主界面
            }
        }
    }

    /** 确认单条告警（红点计数相应减少） */
    fun ack(id: Long) {
        scope.launch {
            val resp = repository.ack(id)
            lastMessage = resp.message
            if (resp.success) {
                val idx = alarms.indexOfFirst { it.id == id }
                if (idx >= 0 && alarms[idx].status == "ACTIVE") {
                    alarms.removeAt(idx)
                    if (unreadCount > 0) unreadCount--
                }
            }
            refreshUnread()
        }
    }

    // ---------- 规则管理（管理员） ----------

    /** 加载规则列表 + 角色（管理员才显示规则管理入口） */
    fun loadRules() {
        scope.launch {
            isLoading = true
            try {
                ensureTenantId()
                isAdmin = try { thingsBoardRepository.isAdmin() } catch (_: Exception) { false }
                rules.clear()
                rules.addAll(repository.loadRules(tenantId))
            } catch (e: Exception) {
                lastMessage = "加载规则失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** 创建规则 */
    fun createRule(name: String, deviceType: String, metric: String, operator: String,
                   threshold: Double, severity: String, message: String,
                   onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        scope.launch {
            try {
                ensureTenantId()
                val resp = repository.createRule(mapOf(
                    "name" to name,
                    "deviceType" to deviceType,
                    "metric" to metric,
                    "operator" to operator,
                    "threshold" to threshold,
                    "severity" to severity,
                    "message" to message,
                    "enabled" to true,
                    "tenantId" to tenantId
                ))
                lastMessage = resp.message
                if (resp.success) loadRules()
                onResult(resp.success, resp.message)
            } catch (e: Exception) {
                lastMessage = "创建规则失败：${e.message}"
                onResult(false, lastMessage ?: "创建规则失败")
            }
        }
    }

    /** 启用/停用规则 */
    fun toggleRule(id: Long, enabled: Boolean) {
        scope.launch {
            val resp = repository.toggleRule(id, enabled)
            lastMessage = resp.message
            val idx = rules.indexOfFirst { it.id == id }
            if (idx >= 0) rules[idx] = rules[idx].copy(enabled = enabled)
        }
    }

    /** 删除规则 */
    fun deleteRule(id: Long) {
        scope.launch {
            val resp = repository.deleteRule(id)
            lastMessage = resp.message
            if (resp.success) {
                rules.removeAll { it.id == id }
            }
        }
    }

    /** 清除反馈 */
    fun clearMessage() {
        lastMessage = null
    }

    /** 退出登录时清空（切换账号不残留） */
    fun clear() {
        unreadCount = 0L
        alarms.clear()
        rules.clear()
        lastMessage = null
        tenantId = null
        thingsBoardRepository.clearCachedIdentity()
    }
}
