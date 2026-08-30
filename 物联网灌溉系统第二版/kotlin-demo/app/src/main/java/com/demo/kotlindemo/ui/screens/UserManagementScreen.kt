// 包声明：页面层
package com.demo.kotlindemo.ui.screens

// 布局
import androidx.compose.foundation.layout.*
// 点击（分配弹窗勾选行）
// 滚动
// 懒加载列表
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
// Material3
import androidx.compose.material3.*
// 运行时
import androidx.compose.runtime.*
// 对齐
import androidx.compose.ui.Alignment
// 修饰符
import androidx.compose.ui.Modifier
// 字重
import androidx.compose.ui.text.font.FontWeight
// 尺寸
import androidx.compose.ui.unit.dp
// 协程
import androidx.compose.runtime.LaunchedEffect
// 图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
// DTO
import com.demo.kotlindemo.data.dto.CustomerDto
import com.demo.kotlindemo.data.dto.MemberDto
import com.demo.kotlindemo.data.dto.CurrentUserDto
// ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
import com.demo.kotlindemo.viewmodel.UserViewModel
// 第三版重构：家庭卡片/成员弹窗拆到 ui.components
import com.demo.kotlindemo.ui.components.FamilyCard
import com.demo.kotlindemo.ui.components.AddMemberDialog
import com.demo.kotlindemo.ui.components.AssignScopeDialog


/**
 * 成员管理页（第三版：替代原"使用者管理"，仅租户管理员）
 *
 * 功能（对应《成员管理设计方案》3.x）：
 *  - 家庭（客户）列表：每个家庭卡片内列出成员账号（邮箱 + 角色徽标）
 *  - 新增成员：角色单选（管理员=加入本公司 / 使用者=家庭成员）
 *             使用者可选"新建家庭"或"加入已有家庭"
 *  - 分配可见范围：按家庭勾选田块/设备（家庭内成员共享）
 *  - 删除区分：删除成员（只删账号）/ 删除家庭（级联删成员，设备任务保留）
 *
 * @param userViewModel 用户 ViewModel
 * @param farmViewModel 农田 ViewModel（分配时取田块/设备列表）
 * @param onBack 返回
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    userViewModel: UserViewModel,
    farmViewModel: FarmViewModel,
    onBack: () -> Unit
) {
    // 进入页面加载成员/家庭列表 + 田块/设备列表（分配用）
    LaunchedEffect(Unit) {
        userViewModel.loadMembers()
        farmViewModel.loadFields()
        farmViewModel.loadAllDevices()
    }

    // ── 弹窗状态 ──
    var showAddMember by remember { mutableStateOf(false) }         // 新增成员弹窗
    var assignTarget by remember { mutableStateOf<CustomerDto?>(null) }   // 分配弹窗目标（家庭）
    var deleteMemberTarget by remember { mutableStateOf<MemberDto?>(null) } // 删除成员确认
    var deleteFamilyTarget by remember { mutableStateOf<CustomerDto?>(null) } // 删除家庭确认
    var deleteAdminTarget by remember { mutableStateOf<CurrentUserDto?>(null) } // 删除管理员确认（第三版）
    var opMessage by remember { mutableStateOf<String?>(null) }      // 操作结果提示

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成员管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 新增成员入口
                    IconButton(onClick = { showAddMember = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新增成员")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 错误提示（定位加载失败原因用，正常情况不显示）
            userViewModel.errorMessage?.let { err ->
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            // 本公司卡片（第三版增强：展示本公司所有管理员，含当前登录者）
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            text = "🏢 本公司（${userViewModel.admins.size} 名管理员）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        if (userViewModel.admins.isEmpty()) {
                            Text(
                                "（暂无管理员）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        userViewModel.admins.forEach { admin ->
                            val isMe = admin.id.id == userViewModel.currentUser?.id?.id
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isMe) "👑 ${admin.email}（我）" else "👑 ${admin.email}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                // 不能删除自己；其他管理员可删除（第三版）
                                if (!isMe) {
                                    IconButton(onClick = { deleteAdminTarget = admin }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除管理员 ${admin.email}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 家庭（客户）列表：一个家庭一张卡片，卡片内含成员
            if (userViewModel.families.isEmpty()) {
                item {
                    Text(
                        "暂无家庭，点右上角 + 新增成员",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(userViewModel.families, key = { it.id.id }) { family ->
                FamilyCard(
                    family = family,
                    members = userViewModel.members.filter { it.customerId == family.id.id },
                    onAssign = { assignTarget = family },
                    onDeleteMember = { deleteMemberTarget = it },
                    onDeleteFamily = { deleteFamilyTarget = family }
                )
            }
        }
    }

    // ── 新增成员弹窗（角色 + 归属）──
    if (showAddMember) {
        AddMemberDialog(
            families = userViewModel.families,
            onDismiss = { showAddMember = false },
            onConfirm = { role, familyId, familyName, email ->
                userViewModel.createMember(role, familyId, familyName, email) { ok, msg ->
                    opMessage = msg
                    if (ok) showAddMember = false
                }
            }
        )
    }

    // ── 分配可见范围弹窗（按家庭勾选田块/设备）──
    assignTarget?.let { family ->
        AssignScopeDialog(
            customer = family,
            fields = farmViewModel.fields,
            devices = farmViewModel.devices,
            onDismiss = { assignTarget = null },
            onConfirm = { fieldIds, deviceIds ->
                userViewModel.assignScope(family.id.id, fieldIds, deviceIds) { ok, msg ->
                    opMessage = msg
                }
                assignTarget = null
            }
        )
    }

    // ── 删除管理员确认弹窗（第三版：不能删除自己）──
    deleteAdminTarget?.let { admin ->
        AlertDialog(
            onDismissRequest = { deleteAdminTarget = null },
            title = { Text("确认删除管理员？") },
            text = { Text("将删除管理员账号「${admin.email}」，删除后该账号将无法登录。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    val uid = admin.id.id
                    val em = admin.email
                    deleteAdminTarget = null
                    userViewModel.deleteAdmin(uid, em) { ok, msg -> opMessage = msg }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteAdminTarget = null }) { Text("取消") } }
        )
    }

    // ── 删除成员确认弹窗（只删账号）──
    deleteMemberTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { deleteMemberTarget = null },
            title = { Text("确认删除成员？") },
            text = {
                Text("将删除账号「${member.email}」，该家庭与其他成员不受影响，田块/设备分配保留。是否继续？")
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteMemberTarget = null
                    userViewModel.deleteMember(member.userId) { ok, msg -> opMessage = msg }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteMemberTarget = null }) { Text("取消") } }
        )
    }

    // ── 删除家庭确认弹窗（级联删成员，设备任务保留）──
    deleteFamilyTarget?.let { family ->
        AlertDialog(
            onDismissRequest = { deleteFamilyTarget = null },
            title = { Text("确认删除家庭？") },
            text = {
                Text(
                    "将删除家庭「${family.title.ifEmpty { family.name }}」及其下所有成员账号，" +
                            "该家庭的田块/设备分配会解除但实体保留（可重新分配）。是否继续？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFamilyTarget = null
                    userViewModel.deleteFamily(family.id.id) { ok, msg -> opMessage = msg }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFamilyTarget = null }) { Text("取消") } }
        )
    }

    // ── 操作结果提示 ──
    opMessage?.let { msg ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = { TextButton(onClick = { opMessage = null }) { Text("知道了") } }
        ) { Text(msg) }
    }
}

