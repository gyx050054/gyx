// 包声明：页面层
package com.demo.kotlindemo.ui.screens

// 布局
import androidx.compose.foundation.layout.*
// 点击（分配弹窗勾选行）
import androidx.compose.foundation.clickable
// 滚动
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * 家庭卡片：家庭名 + 成员列表（邮箱+角色徽标+删除成员）+ 家庭级操作（分配/删除家庭）
 */
@Composable
private fun FamilyCard(
    family: CustomerDto,
    members: List<MemberDto>,
    onAssign: () -> Unit,
    onDeleteMember: (MemberDto) -> Unit,
    onDeleteFamily: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // 家庭名 + 成员数
            Text(
                text = "🏠 ${family.title.ifEmpty { family.name }}（${members.size} 名成员）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            // 成员列表
            if (members.isEmpty()) {
                Text(
                    "（该家庭暂无成员账号）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            members.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 邮箱
                    Text(
                        text = m.email,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // 角色徽标（第三版：家庭成员 CUSTOMER_USER）
                    AssistChip(
                        onClick = {},
                        label = { Text("家庭成员", style = MaterialTheme.typography.labelSmall) },
                        enabled = false  // 纯展示，不可点
                    )
                    // 删除成员（只删账号）
                    IconButton(onClick = { onDeleteMember(m) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除成员 ${m.email}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 家庭级操作
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onAssign,
                    modifier = Modifier.weight(1f)
                ) { Text("分配可见范围", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = onDeleteFamily,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除家庭", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/**
 * 新增成员弹窗（第三版核心）：角色单选 + 归属选择（新建家庭/加入已有家庭）+ 邮箱
 * @param onConfirm (角色, 家庭id或null, 新家庭名, 邮箱)
 */
@Composable
private fun AddMemberDialog(
    families: List<CustomerDto>,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String, String) -> Unit
) {
    // 角色：ADMIN=管理员 / USER=使用者（家庭成员）
    var role by remember { mutableStateOf("USER") }
    // 归属方式：new=新建家庭 / join=加入已有家庭
    var joinMode by remember { mutableStateOf("new") }
    var familyName by remember { mutableStateOf("") }
    var joinFamilyId by remember { mutableStateOf<String?>(families.firstOrNull()?.id?.id) }
    var email by remember { mutableStateOf("") }

    // 校验：邮箱必填；使用者+新建家庭需填家庭名；使用者+加入已有家庭需有可选的客户
    val valid = email.isNotBlank() &&
            (role == "ADMIN" ||
                    (joinMode == "new" && familyName.isNotBlank()) ||
                    (joinMode == "join" && joinFamilyId != null))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增成员") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // ── 角色单选 ──
                Text("角色", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == "USER",
                        onClick = { role = "USER" },
                        label = { Text("使用者（家庭成员）") }
                    )
                    FilterChip(
                        selected = role == "ADMIN",
                        onClick = { role = "ADMIN" },
                        label = { Text("管理员（公司合伙人）") }
                    )
                }
                if (role == "ADMIN") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "管理员将加入本公司（租户管理员），可管理全部田块/设备/成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))

                // ── 归属选择（仅使用者）──
                if (role == "USER") {
                    Text("归属家庭", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = joinMode == "new",
                            onClick = { joinMode = "new" },
                            label = { Text("新建家庭") }
                        )
                        FilterChip(
                            selected = joinMode == "join",
                            onClick = { joinMode = "join" },
                            label = { Text("加入已有家庭") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (joinMode == "new") {
                        OutlinedTextField(
                            value = familyName,
                            onValueChange = { familyName = it },
                            label = { Text("家庭名称（如：张三农场）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // 已有家庭下拉（简化：单选用单选列表；家庭少，直接列 RadioButton）
                        if (families.isEmpty()) {
                            Text(
                                "还没有家庭，请先选「新建家庭」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        families.forEach { f ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    joinFamilyId = f.id.id
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = joinFamilyId == f.id.id,
                                    onClick = { joinFamilyId = f.id.id }
                                )
                                Text(f.title.ifEmpty { f.name }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // ── 邮箱 ──
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱（即登录账号）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "初始密码默认 123456，首次登录需修改密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 组装参数：管理员=ADMIN/家庭id=null；使用者=USER/家庭id 或 null+新家庭名
                    if (role == "ADMIN") {
                        onConfirm("ADMIN", null, "", email.trim())
                    } else if (joinMode == "join") {
                        onConfirm("USER", joinFamilyId, "", email.trim())
                    } else {
                        onConfirm("USER", null, familyName.trim(), email.trim())
                    }
                },
                enabled = valid
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 分配可见范围弹窗：勾选田块/设备，确认后调 TB 分配接口（按家庭分配，成员共享）
 */
@Composable
private fun AssignScopeDialog(
    customer: CustomerDto,
    fields: List<com.demo.kotlindemo.data.model.Field>,
    devices: List<com.demo.kotlindemo.data.model.Device>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>, List<String>) -> Unit  // (田块ID列表, 设备ID列表)
) {
    // 勾选状态
    var checkedFields by remember { mutableStateOf(setOf<String>()) }
    var checkedDevices by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分配可见范围 - ${customer.title.ifEmpty { customer.name }}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("田块（家庭内成员共享）", style = MaterialTheme.typography.titleSmall)
                fields.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            checkedFields = if (f.id in checkedFields) checkedFields - f.id else checkedFields + f.id
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = f.id in checkedFields,
                            onCheckedChange = {
                                checkedFields = if (f.id in checkedFields) checkedFields - f.id else checkedFields + f.id
                            }
                        )
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("设备", style = MaterialTheme.typography.titleSmall)
                devices.take(20).forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            checkedDevices = if (d.id in checkedDevices) checkedDevices - d.id else checkedDevices + d.id
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = d.id in checkedDevices,
                            onCheckedChange = {
                                checkedDevices = if (d.id in checkedDevices) checkedDevices - d.id else checkedDevices + d.id
                            }
                        )
                        Text(d.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(checkedFields.toList(), checkedDevices.toList()) }) {
                Text("确定分配")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
