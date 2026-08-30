/**
 * AlarmViewModel（告警 ViewModel，自研告警引擎，第四版）
 *
 * 【文件职责】
 *  - 持有"告警"域的全部 UI 状态：未确认告警计数（顶栏铃铛红点）、告警记录列表、规则列表、加载状态、反馈消息、是否管理员。
 *  - 调用的 Repository：AlarmRepository（微服务端告警引擎：unreadCount/loadAlarms/ack/规则 CRUD）+ ThingsBoardRepository（查询并缓存当前租户 id）。
 *  - 规则管理入口仅管理员可见。
 *
 * 【数据流】
 *  - UI 状态（Compose 可观察）：unreadCount(mutableStateOf<Long>)、alarms(mutableStateListOf)、rules(mutableStateListOf)、
 *    isLoading、lastMessage、isAdmin(private set，仅类内可写)。
 *  - 加载告警：进入告警页/下拉刷新 → loadAlarms() 在 scope.launch(Dispatchers.Main) 中执行，先 ensureTenantId() 缓存租户 id，
 *    再调 repository.unreadCount() 填充红点、repository.loadAlarms() 填充列表（先 clear 再 addAll，避免叠加脏数据）；
 *    异常写入 lastMessage，finally 复位 isLoading。
 *  - 轻量刷新红点：refreshUnread() 只调 unreadCount()，失败静默不打断主界面。
 *  - 确认单条：ack(id) 调 repository.ack()，成功后从 alarms 移除该条并 unreadCount--，最后 refreshUnread() 与服务端对齐。
 *  - 规则管理：loadRules()/createRule()/toggleRule()/deleteRule() 调 repository 对应方法，写回 rules 与 lastMessage。
 *  - 退出登录：clear() 清空 unreadCount/alarms/rules/lastMessage/tenantId 并清 TB 缓存，避免切换账号残留。
 */
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

    // 微服务端告警引擎仓库：所有告警/规则接口（unreadCount/loadAlarms/ack/loadRules/createRule/toggleRule/deleteRule）
    private val repository = AlarmRepository()
    // 从 TB 获取当前登录者所属租户 id（与任务/成员一致，实现公司间隔离）
    private val thingsBoardRepository = ThingsBoardRepository()
    // 主线程作用域：SupervisorJob 使某个协程失败不会取消整棵协程树；所有 UI 状态更新都发生在 Main 线程
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
        // 数据流：进入告警页/下拉刷新触发 → 协程拉取计数与列表 → 写回 unreadCount / alarms（先清再填，带错误与 finally 复位）
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
        // 轻量刷新：只更新顶栏红点计数，不重载整表；失败静默不打断主界面
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
        // 数据流：确认单条 → 调服务端 → 成功则本地移除该条并递减红点 → 最后 refreshUnread 校对
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
        // 数据流：进入规则管理页 → 先判定管理员 → 拉取规则列表写回 rules；管理员判断失败时保守为 false
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
        // 数据流：表单参数组装成规则对象 → 提交微服务端创建 → 成功则刷新规则列表并经 onResult 回调 UI
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
        // 数据流：切换启用/停用 → 调服务端 → 成功后本地 copy 更新该条规则的 enabled 状态
        scope.launch {
            val resp = repository.toggleRule(id, enabled)
            lastMessage = resp.message
            val idx = rules.indexOfFirst { it.id == id }
            if (idx >= 0) rules[idx] = rules[idx].copy(enabled = enabled)
        }
    }

    /** 删除规则 */
    fun deleteRule(id: Long) {
        // 数据流：删除规则 → 调服务端 → 成功后从 rules 中移除该条，提示信息取服务端返回
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
