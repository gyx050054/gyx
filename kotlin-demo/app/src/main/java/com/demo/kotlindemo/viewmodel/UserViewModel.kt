// 包声明：ViewModel 层
package com.demo.kotlindemo.viewmodel

// Android ViewModel 基类
import androidx.lifecycle.ViewModel
// Compose 可观察列表/状态
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
// 数据模型
import com.demo.kotlindemo.data.api.ThingsBoardRepository
import com.demo.kotlindemo.data.api.TaskRepository
import com.demo.kotlindemo.data.dto.CurrentUserDto
import com.demo.kotlindemo.data.dto.CustomerDto
// 协程
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 用户（员工/使用者）管理 ViewModel（第二版新增）
 *
 * 职责：
 *  - 当前登录身份（TENANT_ADMIN / CUSTOMER_USER）——「我的」页展示用；
 *  - 员工（Customer）列表、创建员工（Customer+CUSTOMER_USER+激活）、删除员工；
 *  - 分配田块/设备可见范围给员工。
 *
 * 设计说明：只调 ThingsBoardRepository（TB 域）与 TaskRepository（微服务端域），
 *           UI 层不感知两套后端差异。
 */
class UserViewModel : ViewModel() {

    private val repository = ThingsBoardRepository()
    private val taskRepository = TaskRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 当前用户身份（登录后加载，「我的」页展示）──
    var currentUser by mutableStateOf<CurrentUserDto?>(null)
        private set

    // ── 员工列表 ──
    val customers = mutableStateListOf<CustomerDto>()

    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** 加载当前登录用户身份（GET /api/auth/user） */
    fun loadCurrentUser() {
        scope.launch {
            try {
                currentUser = repository.loadCurrentUser()
            } catch (e: Exception) {
                errorMessage = "获取身份失败：${e.message}"
            }
        }
    }

    /** 加载员工列表 */
    fun loadCustomers() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val list = repository.loadCustomers()
                customers.clear()
                customers.addAll(list)
            } catch (e: Exception) {
                errorMessage = "加载员工列表失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 创建员工（Customer + CUSTOMER_USER + 激活设初始密码）
     * 创建成功后登记微服务端强制改密标记（员工首登强制改密），并刷新列表
     */
    fun createUser(name: String, email: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val ok = repository.createCustomerUser(name.trim(), email.trim())
                if (ok) {
                    // 登记强制改密（失败不影响创建，仅日志）
                    runCatching { taskRepository.markMustChange(email.trim()) }
                    loadCustomers()
                    onResult(true, "员工「$name」创建成功")
                } else {
                    onResult(false, "创建员工失败（邮箱可能已被占用）")
                }
            } catch (e: Exception) {
                onResult(false, "创建员工失败：${e.message}")
            }
        }
    }

    /** 删除员工（Customer），成功后刷新列表 */
    fun deleteUser(customerId: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val ok = repository.deleteCustomer(customerId)
                if (ok) {
                    customers.removeAll { it.id.id == customerId }
                    onResult(true, "员工已删除")
                } else {
                    onResult(false, "删除员工失败")
                }
            } catch (e: Exception) {
                onResult(false, "删除员工失败：${e.message}")
            }
        }
    }

    /**
     * 分配田块/设备可见范围给员工（勾选后逐个分配）
     * @param customerId 员工 Customer ID
     * @param fieldIds   要分配的田块 ID 列表
     * @param deviceIds  要分配的设备 ID 列表
     */
    fun assignScope(customerId: String, fieldIds: List<String>, deviceIds: List<String>,
                    onResult: (Boolean, String) -> Unit) {
        scope.launch {
            var allOk = true
            // 分配田块
            for (fid in fieldIds) {
                try { if (!repository.assignAssetToCustomer(customerId, fid)) allOk = false }
                catch (e: Exception) { allOk = false }
            }
            // 分配设备
            for (did in deviceIds) {
                try { if (!repository.assignDeviceToCustomer(customerId, did)) allOk = false }
                catch (e: Exception) { allOk = false }
            }
            onResult(allOk, if (allOk) "分配成功" else "部分分配失败")
        }
    }

    /** 清除错误提示 */
    fun clearError() {
        errorMessage = null
    }
}
