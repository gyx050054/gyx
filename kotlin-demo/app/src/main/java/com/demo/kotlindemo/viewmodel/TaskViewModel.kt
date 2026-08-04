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
import com.demo.kotlindemo.data.api.FarmRepository
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

    private val repository = FarmRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 任务列表（微服务端数据）──
    val tasks = mutableStateListOf<TimingTask>()

    // ── 操作反馈 ──
    var lastMessage by mutableStateOf<String?>(null)
        private set

    /** 加载全部任务（微服务端 GET /api/tasks） */
    fun loadTasks() {
        scope.launch {
            try {
                val list = repository.loadTasks()
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
                val resp = repository.createTask(deviceId, deviceName, startTime, endTime)
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
                val resp = repository.createTasksBatch(deviceIds, startTime, endTime)
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
}

/** 微服务端任务 → 本地 TimingTask 模型 */
private fun com.demo.kotlindemo.data.dto.ServiceTask.toTimingTask(): TimingTask {
    val status = when (this.status) {
        "RUNNING" -> TaskStatus.RUNNING
        "COMPLETED" -> TaskStatus.COMPLETED
        "CANCELLED" -> TaskStatus.COMPLETED
        else -> TaskStatus.PENDING
    }
    return TimingTask(
        id = "svc_$id",
        deviceId = deviceId,
        deviceName = deviceName,
        startTime = startTime,
        endTime = endTime,
        action = action,
        status = status
    )
}
