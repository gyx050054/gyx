/**
 * FarmViewModel（农田 ViewModel）
 *
 * 【文件职责】
 *  - 统一管理"田块"与"设备"两块 UI 状态：田块列表、设备列表、管理员标志、加载状态与错误提示。
 *  - 调用 Repository：ThingsBoardRepository（TB REST API：田块/设备/开关 RPC/增删挂载/角色）+ TaskRepository（删除设备前取消其未完成任务）。
 *  - 设备开关采用"本地乐观更新 + 后台 RPC"，失败仅提示不回滚；设备数据按真实 API 做 10 秒轮询刷新。
 *
 * 【数据流】
 *  - UI 状态：fields(mutableStateListOf<Field>)、devices(mutableStateListOf<Device>)、isAdmin(mutableStateOf<Boolean>)、isLoading、errorMessage。
 *  - 加载田块：loadFields() 拉取资产列表写回 fields，并顺带刷新管理员标志。
 *  - 加载设备：loadAllDevices() 拉取全部设备写回 devices（亦是 10 秒轮询 refreshFromApi() 与各增删/挂载后的刷新落点）。
 *  - 田块过滤：deviceById()/devicesInField() 仅查本地缓存，无网络请求；loadFieldDevices() 从 API 拉取并合并进 devices（按 id 去重）。
 *  - 开关控制：toggleDevice() 先本地乐观写 isOn/valveState，再发 RPC；非电动阀跳过，失败写 errorMessage。
 *  - 增删/挂载：createField()/deleteFields()/createDevice()/mountDevice()/unmountDevice()/remountDevice()/deleteDevice()
 *    调 repository 对应方法，成功后 loadFields()/loadAllDevices() 刷新；删除设备前先取消其未完成任务。
 *  - 退出登录：logout() 清 token/角色缓存并清空 fields/devices，避免切换账号残留。
 */
// 声明包名，这个文件属于 ViewModel 层
package com.demo.kotlindemo.viewmodel

// 导入 Android 的 ViewModel 基类
import androidx.lifecycle.ViewModel
// 导入 Compose 的可观察列表（修改后自动刷新 UI）
import androidx.compose.runtime.mutableStateListOf
// 导入 Compose 状态
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
// 导入数据模型
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
// 导入网络仓库
import com.demo.kotlindemo.data.api.ThingsBoardRepository
// 导入微服务端任务仓库（删除设备联动取消任务用）
import com.demo.kotlindemo.data.api.TaskRepository
// 导入协程
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 农田 ViewModel
 *
 * 统一管理：田块列表 + 设备列表
 * 数据来源：ThingsBoard REST API（通过 ThingsBoardRepository）
 */
class FarmViewModel : ViewModel() {

    private val repository = ThingsBoardRepository()
    // 微服务端任务仓库：删除设备时先取消其未完成任务（第二版）
    private val taskRepository = TaskRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当前登录身份是否为租户管理员（第二版：员工 CUSTOMER_USER 隐藏管理功能）
    var isAdmin by mutableStateOf(false)  // 安全默认：身份确认前不显示管理按钮
        private set

    // ── 田块列表（API 加载后填充）──
    val fields = mutableStateListOf<Field>()

    // ── 设备列表（全部设备，"设备"页用）──
    val devices = mutableStateListOf<Device>()

    // ── 加载状态 ──
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 加载田块总览（资产列表 + 设备数） */
    fun loadFields() {
        // 数据流：加载田块资产列表 → 写回 fields → 顺带刷新管理员标志；无论成败最后都会校正 isAdmin（异常保守为 false）
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val list = repository.loadFields()
                fields.clear()
                fields.addAll(list)
                // 顺带刷新角色（员工隐藏管理按钮用；首次会拉取身份并缓存）
                isAdmin = repository.isAdmin()
            } catch (e: Exception) {
                errorMessage = "加载田块失败：${e.message}"
            } finally {
                // 修复：无论田块加载成败都按真实角色刷新管理员标志（失败时保守为 false）
                try { isAdmin = repository.isAdmin() } catch (_: Exception) { isAdmin = false }
                isLoading = false
            }
        }
    }

    /** 加载全部设备（设备页 + 10 秒刷新） */
    fun loadAllDevices() {
        // 数据流：拉取全部设备 → 写回 devices（先清后填）；亦为 10 秒轮询与各增删/挂载后的刷新落点
        scope.launch {
            try {
                val list = repository.loadAllDevices()
                devices.clear()
                devices.addAll(list)
            } catch (e: Exception) {
                errorMessage = "加载设备失败：${e.message}"
            }
        }
    }

    // ── 按设备 ID 查找单个设备 ──
/**
     * 按设备 ID 查找单个设备（本地缓存查询，无网络请求）
     */
    fun deviceById(id: String): Device? = devices.firstOrNull { it.id == id }

    // ── 按田块 ID 查询该田块下所有设备（本地缓存；远程加载见 loadFieldDevices）──
/**
     * 按田块 ID 过滤本地缓存中的设备（远端加载见 loadFieldDevices）
     */
    fun devicesInField(fieldId: String): List<Device> = devices.filter { it.fieldId == fieldId }

    /** 从 API 加载某个田块下的设备，并合并进 devices 缓存 */
    fun loadFieldDevices(fieldId: String, onLoaded: (List<Device>) -> Unit = {}) {
        // 数据流：从 API 拉取某田块设备 → 先按同 id 移除旧缓存，再合并进 devices（避免重复 key），结果经 onLoaded 回传
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val list = repository.loadFieldDevices(fieldId)
                // 合并进全局缓存：先移除同 id 的旧设备（含 fieldId 为空的全量版），避免重复 key
                val ids = list.map { it.id }.toSet()
                devices.removeAll { it.id in ids }
                devices.addAll(list)
                onLoaded(list)
            } catch (e: Exception) {
                errorMessage = "加载田块设备失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // ── 切换某个设备的开关状态（通过 ThingsBoard RPC）──
/**
     * 切换设备开关（电动阀）：先本地乐观更新 UI，再发 RPC 到 TB；失败回滚提示
     */
    fun toggleDevice(id: String, forceOn: Boolean? = null) {
        // 数据流：先本地乐观更新设备开关（UI 即时反馈）→ 再发 RPC 到 TB；失败仅写 errorMessage，不回滚乐观值
        // 先本地乐观更新
        val idx = devices.indexOfFirst { it.id == id }
        // 目标状态：forceOn 指定则用之，否则取当前状态的相反值
        val newOn = if (idx >= 0) (forceOn ?: !devices[idx].isOn) else (forceOn ?: false)
        if (idx >= 0) {
            devices[idx] = devices[idx].copy(isOn = newOn, valveState = if (newOn) "WORKING" else "IDLE")
        }
        // 再发 RPC 到 ThingsBoard（电动阀才可操作）
        // 注意：必须发送目标状态 newOn（此前误用乐观更新后的 !d.isOn，导致开关方向反了）
        val d = devices.getOrNull(idx)
        if (d != null && d.type == DeviceType.VALVE) {
            scope.launch(Dispatchers.IO) {
                val ok = repository.toggleValve(id, newOn)
                if (!ok) {
                    errorMessage = "控制失败：设备 $id 未响应"
                }
            }
        }
    }

    /** 每 10 秒自动刷新（真实 API 轮询） */
    fun refreshFromApi() {
        // 轮询落点：复用 loadAllDevices() 做全量真 API 刷新（而非本地状态翻转），保证设备状态不漂移
        loadAllDevices()
    }

    // ── 田块管理（第二版新增：新增/删除田块）──

    /**
     * 新增田块（租户管理员）：成功后自动刷新田块列表
     * @param name 田块名称
     * @param onResult 回调 (是否成功, 提示信息)
     */
    fun createField(name: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：新增田块（租户管理员）→ 成功后刷新田块列表并经 onResult 回调 UI
        scope.launch {
            try {
                val ok = repository.createField(name.trim())
                if (ok) {
                    loadFields()  // 新增成功立即刷新列表
                    onResult(true, "田块「$name」新增成功")
                } else {
                    onResult(false, "新增田块失败")
                }
            } catch (e: Exception) {
                onResult(false, "新增田块失败：${e.message}")
            }
        }
    }

    /**
     * 删除田块（租户管理员，支持多选批量）：删除后田块下设备自动变为自由设备
     * @param ids 要删除的田块 ID 列表
     * @param onResult 回调 (是否全部成功, 提示信息)
     */
    fun deleteFields(ids: List<String>, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：逐个删除田块（多选批量，单条失败不中断）→ 汇总成败 → 成功后刷新列表；被删田块下设备变为自由设备
        scope.launch {
            var allOk = true
            for (id in ids) {
                try {
                    val ok = repository.deleteField(id)
                    if (!ok) allOk = false
                } catch (e: Exception) {
                    allOk = false
                }
            }
            loadFields()  // 删除后刷新列表（被删田块消失，设备变自由设备）
            onResult(allOk, if (allOk) "已删除 ${ids.size} 块田块" else "部分田块删除失败")
        }
    }

    // ── 设备管理（第二版新增：新增/挂载/删除设备）──

    /**
     * 新增设备（租户管理员）：创建后为自由设备，返回 accessToken 供凭证弹窗展示
     * @param name 设备名称
     * @param type 设备类型：VALVE / TEMPERATURE_HUMIDITY
     * @param onResult 回调 (是否成功, 提示信息, accessToken?)
     */
    fun createDevice(name: String, type: String, onResult: (Boolean, String, String?) -> Unit) {
        // 数据流：创建设备（管理员）→ 成功后刷新设备列表，并回传 accessToken 供凭证弹窗展示
        scope.launch {
            try {
                val token = repository.createDevice(name.trim(), type)
                if (token != null) {
                    loadAllDevices()  // 刷新设备列表（新设备出现在"全部设备"）
                    onResult(true, "设备创建成功", token)
                } else {
                    onResult(false, "创建设备失败（设备类型配置未找到）", null)
                }
            } catch (e: Exception) {
                onResult(false, "创建设备失败：${e.message}", null)
            }
        }
    }

    /**
     * 挂载设备到田块（租户管理员，两种方式共用）
     * @param deviceId 设备 ID
     * @param fieldId  目标田块 ID
     */
    fun mountDevice(deviceId: String, fieldId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：挂载设备到田块 → 成功后刷新设备（自由→已挂载）与田块设备数
        scope.launch {
            try {
                val ok = repository.mountDevice(deviceId, fieldId)
                if (ok) {
                    loadAllDevices()  // 挂载后刷新：该设备从自由设备变为已挂载
                    loadFields()      // 田块设备数变化
                }
                onResult(ok, if (ok) "挂载成功" else "挂载失败")
            } catch (e: Exception) {
                onResult(false, "挂载失败：${e.message}")
            }
        }
    }

    /**
     * 取下设备（第三版）：设备从田块取下，变为自由设备
     */
    fun unmountDevice(deviceId: String, fieldId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：设备从田块取下 → 成功后刷新设备与田块设备数，设备变为自由设备
        scope.launch {
            try {
                val ok = repository.unmountDevice(deviceId, fieldId)
                if (ok) {
                    loadAllDevices()  // 刷新：该设备变为自由设备
                    loadFields()      // 田块设备数变化
                }
                onResult(ok, if (ok) "已取下，设备变为自由设备" else "取下失败")
            } catch (e: Exception) {
                onResult(false, "取下失败：${e.message}")
            }
        }
    }

    /**
     * 改挂设备到别的田块（第三版）：先取下再挂到新田块
     */
    fun remountDevice(deviceId: String, oldFieldId: String, newFieldId: String,
                      onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：设备从旧田块取下再挂到新田块（服务端一个接口完成）→ 成功后刷新设备与田块列表
        scope.launch {
            try {
                val ok = repository.remountDevice(deviceId, oldFieldId, newFieldId)
                if (ok) {
                    loadAllDevices()
                    loadFields()
                }
                onResult(ok, if (ok) "改挂成功" else "改挂失败")
            } catch (e: Exception) {
                onResult(false, "改挂失败：${e.message}")
            }
        }
    }

    /**
     * 删除设备（租户管理员）：先取消该设备未完成任务（微服务端），再删除 TB 设备
     * @param deviceId 设备 ID
     */
    fun deleteDevice(deviceId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        // 数据流：先取消该设备未完成任务（微服务端，失败也继续）→ 删除 TB 设备 → 成功后本地移除并刷新田块
        scope.launch {
            try {
                // ① 取消该设备所有未完成任务（微服务端 DELETE /api/tasks/device/{deviceId}）
                //    失败也继续：任务可能已不存在，不影响设备删除
                runCatching { taskRepository.deleteDeviceTasks(deviceId) }
                // ② 删除 TB 设备（同时自动清理挂载关系）
                val ok = repository.deleteDevice(deviceId)
                if (ok) {
                    devices.removeAll { it.id == deviceId }  // 本地移除
                    loadFields()  // 田块设备数更新
                }
                onResult(ok, if (ok) "设备已删除，其未完成任务已取消" else "删除设备失败")
            } catch (e: Exception) {
                onResult(false, "删除设备失败：${e.message}")
            }
        }
    }

    /** 清除错误提示 */
    fun clearError() {
        errorMessage = null
    }

    /**
     * 退出登录：清空 JWT 与本地缓存（第二版：修复切换账号 token 串号）
     * 调用方（MainScreen 退出按钮）在导航回登录页前调用
     */
    fun logout() {
        repository.logout()   // 清 token（内存+持久化）+ 角色缓存
        fields.clear()        // 清空本地数据，避免下一个账号看到旧数据
        devices.clear()
    }
}
