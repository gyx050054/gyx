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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val list = repository.loadFields()
                fields.clear()
                fields.addAll(list)
            } catch (e: Exception) {
                errorMessage = "加载田块失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** 加载全部设备（设备页 + 10 秒刷新） */
    fun loadAllDevices() {
        scope.launch {
            try {
                val list = repository.loadAllDevices()
                android.util.Log.d("FarmVM",
                    "loadAllDevices size=${list.size} | ${list.firstOrNull()?.let {
                        "name=${it.name} on=${it.isOn} vs=${it.valveState} bat=${it.battery}"
                    }}")
                devices.clear()
                devices.addAll(list)
            } catch (e: Exception) {
                android.util.Log.w("FarmVM", "loadAllDevices FAIL: ${e.message}", e)
                errorMessage = "加载设备失败：${e.message}"
            }
        }
    }

    // ── 按设备 ID 查找单个设备 ──
    fun deviceById(id: String): Device? = devices.firstOrNull { it.id == id }

    // ── 按田块 ID 查询该田块下所有设备（本地缓存；远程加载见 loadFieldDevices）──
    fun devicesInField(fieldId: String): List<Device> = devices.filter { it.fieldId == fieldId }

    /** 从 API 加载某个田块下的设备，并合并进 devices 缓存 */
    fun loadFieldDevices(fieldId: String, onLoaded: (List<Device>) -> Unit = {}) {
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
    fun toggleDevice(id: String, forceOn: Boolean? = null) {
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

    // ── 模拟实时数据刷新（演示模式，无 API 时用；真实轮询见 refreshFromApi）──
    fun refreshDeviceData() {
        // 未接入时保留：真实项目由 loadAllDevices()/loadFieldDevices() 每 10 秒调用
    }

    /** 每 10 秒自动刷新（真实 API 轮询） */
    fun refreshFromApi() {
        loadAllDevices()
    }

    /** 清除错误提示 */
    fun clearError() {
        errorMessage = null
    }
}
