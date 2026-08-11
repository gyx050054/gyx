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

    /** 加载当前公司（租户）的任务（GET /api/tasks?tenantId=当前租户） */
    fun loadTasks() {
        scope.launch {
            try {
                ensureTenantId()
                val list = repository.loadTasks(tenantId)
                tasks.clear()
                tasks.addAll(list.map { it.toTimingTask() })
            } catch (e: Exception) {
                lastMessage = "加载任务失败：${e.message}"
            }
        }
    }

    /** 添加单条定时任务；返回是否成功（冲突时微服务端会拒绝） */
    fun addTask(deviceId: String, deviceName: String, startTime: Long, endTime: Long): Boolean {
        var ok = false
        scope.launch {
            try {
                ensureTenantId()
                val resp = repository.createTask(deviceId, deviceName, startTime, endTime, tenantId = tenantId)
                ok = resp.success
                lastMessage = resp.message
                if (ok) loadTasks()
            } catch (e: Exception) {
                lastMessage = "添加任务失败：${e.message}"
            }
        }
        return ok
    }

    /** 批量添加定时任务（多选设备）；返回是否全部成功 */
    fun addTasksBatch(deviceIds: List<Pair<String, String>>, startTime: Long, endTime: Long): Boolean {
        var ok = false
        scope.launch {
            try {
                ensureTenantId()
                val resp = repository.createTasksBatch(deviceIds, startTime, endTime, tenantId)
                ok = resp.success
                lastMessage = resp.message
                if (ok) loadTasks()
            } catch (e: Exception) {
                lastMessage = "批量添加任务失败：${e.message}"
            }
        }
        return ok
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

    /**
     * 退出登录时清空（第三版：修复切换账号残留）
     * 清空任务列表与租户缓存，避免下一个账号看到上个账号的任务
     */
    fun clear() {
        tasks.clear()
        lastMessage = null
        tenantId = null
    }
}
