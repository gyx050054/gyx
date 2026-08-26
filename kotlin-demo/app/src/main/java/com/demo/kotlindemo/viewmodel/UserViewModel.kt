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
import com.demo.kotlindemo.data.dto.MemberDto
// 协程
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 成员管理 ViewModel（第三版：替代原"员工管理"）
 *
 * 职责：
 *  - 当前登录身份（TENANT_ADMIN / CUSTOMER_USER）——「我的」页展示用；
 *  - 成员列表（家庭成员 CUSTOMER_USER）+ 家庭（客户）列表；
 *  - 创建成员（管理员=加入本公司 / 使用者=新建或加入已有家庭）；
 *  - 删除成员（只删账号）/ 删除家庭（级联删成员账号，设备任务保留）；
 *  - 分配田块/设备可见范围给家庭（成员共享）。
 */
class UserViewModel : ViewModel() {

    private val repository = ThingsBoardRepository()
    private val taskRepository = TaskRepository()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── 当前用户身份（登录后加载，「我的」页展示）──
    var currentUser by mutableStateOf<CurrentUserDto?>(null)
        private set

    // ── 成员列表（家庭成员，含所属家庭名与账号 id）──
    val members = mutableStateListOf<MemberDto>()

    // ── 本公司管理员列表（第三版增强：成员管理页顶部展示，含当前登录者；可删除）──
    val admins = mutableStateListOf<CurrentUserDto>()

    // ── 家庭（客户）列表（新增成员"加入已有家庭"下拉 + 删除家庭入口用）──
    val families = mutableStateListOf<CustomerDto>()

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

    /** 加载成员列表 + 家庭列表（新增成员下拉用） */
    fun loadMembers() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val list = repository.loadMembers()
                members.clear()
                members.addAll(list)
                val fams = repository.loadCustomers()
                families.clear()
                families.addAll(fams)
                // 本公司管理员列表（第三版增强：成员管理页顶部展示）
                val adminList = repository.loadTenantAdmins()
                admins.clear()
                admins.addAll(adminList)
            } catch (e: Exception) {
                errorMessage = "加载成员列表失败：${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * 创建成员（第三版：角色 + 归属）
     * @param role       "ADMIN"=管理员（加入本公司）/ "USER"=使用者（家庭成员）
     * @param familyId   已有家庭 id；null=新建家庭
     * @param familyName 新建家庭名称（familyId 为 null 且 role=USER 时必填）
     * @param email      账号邮箱
     */
    fun createMember(role: String, familyId: String?, familyName: String, email: String,
                     onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val ok = repository.createMember(role, familyId, familyName.trim(), email.trim())
                if (ok) {
                    // 登记强制改密（失败不影响创建，仅日志）
                    runCatching { taskRepository.markMustChange(email.trim()) }
                    loadMembers()
                    onResult(true, if (role == "ADMIN") "管理员创建成功" else "成员创建成功")
                } else {
                    onResult(false, "创建失败（邮箱可能已被占用）")
                }
            } catch (e: Exception) {
                onResult(false, "创建失败：${e.message}")
            }
        }
    }

    /** 删除管理员账号（第三版：不能删除自己，UI 层已控制） */
    fun deleteAdmin(userId: String, email: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                if (repository.deleteMember(userId)) {
                    loadMembers()
                    onResult(true, "管理员 $email 已删除")
                } else {
                    onResult(false, "删除管理员失败")
                }
            } catch (e: Exception) {
                onResult(false, "删除管理员失败：${e.message}")
            }
        }
    }

    /** 删除成员（只删账号，家庭/设备/其他成员不受影响） */
    fun deleteMember(userId: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                if (repository.deleteMember(userId)) {
                    loadMembers()
                    onResult(true, "成员已删除")
                } else {
                    onResult(false, "删除成员失败")
                }
            } catch (e: Exception) {
                onResult(false, "删除成员失败：${e.message}")
            }
        }
    }

    /** 删除家庭（删客户，其下成员账号级联删除；田块/设备/任务保留） */
    fun deleteFamily(customerId: String, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                if (repository.deleteCustomer(customerId)) {
                    loadMembers()
                    onResult(true, "家庭已删除")
                } else {
                    onResult(false, "删除家庭失败")
                }
            } catch (e: Exception) {
                onResult(false, "删除家庭失败：${e.message}")
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

    /**
     * 退出登录时清空（第三版：修复切换账号残留）
     * 清空成员/家庭/身份，避免下一个账号看到上个账号的数据
     */
    fun clear() {
        members.clear()
        families.clear()
        admins.clear()
        currentUser = null
        errorMessage = null
        // 清掉本实例缓存的租户/角色，避免切换账号后用上个公司的租户建账号
        repository.clearCachedIdentity()
    }
}
