// 声明包名，这个文件属于 ViewModel 层
package com.demo.kotlindemo.viewmodel

// 导入 ViewModel 基类
import androidx.lifecycle.ViewModel
// 导入 Compose 的可观察列表
import androidx.compose.runtime.mutableStateListOf
// 导入 Compose 状态
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
// 导入任务状态枚举
import com.demo.kotlindemo.data.model.TaskStatus
// 导入定时任务数据模型
import com.demo.kotlindemo.data.model.TimingTask
// 导入网络仓库
import com.demo.kotlindemo.data.api.TaskRepository
// 第三版：任务按租户隔离，需要当前登录者所属公司（租户）id
import com.demo.kotlindemo.data.api.ThingsBoardRepository
// 导入任务 DTO→模型 转换
import com.demo.kotlindemo.data.model.toTimingTask
// 导入协程
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 任务管理 ViewModel
 *
 * 数据来源：微服务端（定时任务调度服务）REST API
 * 文档：任务管理返回所有任务表里剩下的数据；
 *      删除没开始的任务直接删；删除已开始的任务发暂停。
 */
class TaskViewModel : ViewModel() {

    private val repository = TaskRepository()
    // 第三版：从 TB 获取当前登录者的租户（公司）id，任务创建/查询均带租户，实现公司间隔离
    private val thingsBoardRepository = ThingsBoardRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 任务列表（微服务端数据）──
    val tasks = mutableStateListOf<TimingTask>()

    // ── 操作反馈 ──
    var lastMessage by mutableStateOf<String?>(null)
        private set

    /** 当前租户（公司）id；登录后首次调用时从 TB 拉取并缓存 */
    private var tenantId: String? = null

    /** 确保租户 id 已加载（登录态下调用 /api/auth/user 获取） */
    private suspend fun ensureTenantId() {
        if (tenantId == null) {
            tenantId = thingsBoardRepository.myTenantId()
        }
    }

    /**
     * 加载当前公司（租户）的任务（GET /api/tasks?tenantId=当前租户）
     * 排序规则（用户需求）：执行中(RUNNING)最上 → 未开始(PENDING) → 已完成/已取消沉底，
     * 同状态按开始时间倒序（新任务在前）
     */
    fun loadTasks() {
        scope.launch {
            try {
                ensureTenantId()
                val list = repository.loadTasks(tenantId)
                tasks.clear()
                // 状态优先级：RUNNING=0 > PENDING=1 > COMPLETED=2 > CANCELLED=3
                val statusOrder = mapOf(
                    TaskStatus.RUNNING to 0,
                    TaskStatus.PENDING to 1,
                    TaskStatus.COMPLETED to 2,
                    TaskStatus.CANCELLED to 3
                )
                tasks.addAll(
                    list.map { it.toTimingTask() }
                        .sortedWith(compareBy({ statusOrder[it.status] ?: 9 }, { -it.startTime }))
                )
            } catch (e: Exception) {
                lastMessage = "加载任务失败：${e.message}"
            }
        }
    }

    /** 添加单条定时任务；通过回调返回结果（第三版：冲突时回调携带 message，UI 弹清理确认） */
    fun addTask(deviceId: String, deviceName: String, startTime: Long, endTime: Long,
                onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            try {
                ensureTenantId()
                val resp = repository.createTask(deviceId, deviceName, startTime, endTime, tenantId = tenantId)
                lastMessage = resp.message
                if (resp.success) {
                    loadTasks()
                    onResult?.invoke(true, resp.message)
                } else {
                    // 失败（通常是时间冲突）：回调交给 UI 决定是否弹“清除冲突任务”确认
                    onResult?.invoke(false, resp.message)
                }
            } catch (e: Exception) {
                lastMessage = "添加任务失败：${e.message}"
                onResult?.invoke(false, "添加任务失败：${e.message}")
            }
        }
    }

    /** 批量添加定时任务（多选设备）；通过回调返回结果 */
    fun addTasksBatch(deviceIds: List<Pair<String, String>>, startTime: Long, endTime: Long,
                      onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            try {
                ensureTenantId()
                val resp = repository.createTasksBatch(deviceIds, startTime, endTime, tenantId)
                lastMessage = resp.message
                if (resp.success) {
                    loadTasks()
                    onResult?.invoke(true, resp.message)
                } else {
                    onResult?.invoke(false, resp.message)
                }
            } catch (e: Exception) {
                lastMessage = "批量添加任务失败：${e.message}"
                onResult?.invoke(false, "批量添加任务失败：${e.message}")
            }
        }
    }

    /** 删除任务（微服务端处理：未开始直接删 / 已开始发暂停） */
    fun deleteTask(taskId: String) {
        // 本地任务 id 存的是 "svc_<long>"，解析出微服务端 id
        val svcId = taskId.removePrefix("svc_").toLongOrNull()
        if (svcId == null) {
            tasks.removeAll { it.id == taskId }
            return
        }
        scope.launch {
            try {
                val resp = repository.deleteTask(svcId)
                lastMessage = resp.message
                loadTasks()
            } catch (e: Exception) {
                lastMessage = "删除任务失败：${e.message}"
            }
        }
    }

    /** 清除提示 */
    fun clearMessage() {
        lastMessage = null
    }

    // ───────────────── 任务勾选与一键停止（第三版：冲突清理/批量停止用）─────────────────

    /** 当前勾选的任务 id 集合（仅 PENDING/RUNNING 可勾选） */
    val selectedTaskIds = mutableStateListOf<String>()

    /** 勾选/取消勾选单个任务 */
    fun toggleSelect(taskId: String) {
        if (taskId in selectedTaskIds) selectedTaskIds.remove(taskId)
        else selectedTaskIds.add(taskId)
    }

    /** 全选所有未完成任务（PENDING/RUNNING） */
    fun selectAllActive() {
        selectedTaskIds.clear()
        selectedTaskIds.addAll(
            tasks.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
                .map { it.id }
        )
    }

    /** 清除勾选 */
    fun clearSelection() {
        selectedTaskIds.clear()
    }

    /**
     * 预勾选指定设备的所有未完成任务（冲突清理流程：弹窗确认后跳任务页自动勾选）
     * @param deviceId 设备 id
     */
    fun preSelectDeviceTasks(deviceId: String) {
        selectedTaskIds.clear()
        selectedTaskIds.addAll(
            tasks.filter {
                it.deviceId == deviceId &&
                        (it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING)
            }.map { it.id }
        )
    }

    /** 一键停止所选任务（批量取消；逐个调微服务端取消接口） */
    fun deleteSelected() {
        val ids = selectedTaskIds.toList()
        if (ids.isEmpty()) return
        scope.launch {
            var okCount = 0
            for (taskId in ids) {
                val svcId = taskId.removePrefix("svc_").toLongOrNull()
                if (svcId == null) continue
                try {
                    if (repository.deleteTask(svcId).success) okCount++
                } catch (e: Exception) {
                    // 单条失败不中断，继续下一条
                }
            }
            selectedTaskIds.clear()
            lastMessage = "已停止 $okCount 条任务"
            loadTasks()
        }
    }

    // ───────────────── 冲突提示状态（第三版：创建任务冲突时弹窗）─────────────────

    /** 冲突设备名称（创建失败且因冲突时设置，UI 弹窗展示） */
    var conflictDeviceName by mutableStateOf<String?>(null)
        private set

    /** 冲突设备 id（确认清除后跳任务页预勾选用） */
    var conflictDeviceId by mutableStateOf<String?>(null)
        private set

    /** 记录冲突设备（addTask/addTasksBatch 失败且消息含"冲突"时调用） */
    fun setConflict(deviceName: String, deviceId: String) {
        conflictDeviceName = deviceName
        conflictDeviceId = deviceId
    }

    /** 清除冲突提示 */
    fun clearConflict() {
        conflictDeviceName = null
        conflictDeviceId = null
    }

    /**
     * 退出登录时清空（第三版：修复切换账号残留）
     * 清空任务列表与租户缓存，避免下一个账号看到上个账号的任务
     */
    fun clear() {
        tasks.clear()
        selectedTaskIds.clear()
        conflictDeviceName = null
        conflictDeviceId = null
        lastMessage = null
        tenantId = null
        // 关键：清掉本实例缓存的租户 id，否则切换账号后用上个公司的租户查任务
        thingsBoardRepository.clearCachedIdentity()
    }
}
