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
// ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
import com.demo.kotlindemo.viewmodel.UserViewModel

/**
 * 使用者（员工）管理页（第二版新增，仅租户管理员）
 *
 * 功能（对应内部需求文档 FR-USER-01/02/03）：
 *  - 员工列表（Customer）：名称 + 「分配可见范围」 + 「删除」
 *  - 新增员工：名称 + 邮箱 + 初始密码（激活后首登强制改密）
 *  - 分配可见范围：勾选田块/设备 → 调 TB 分配接口
 *  - 删除员工：二次确认
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
    // 进入页面加载员工列表 + 田块/设备列表（分配用）
    LaunchedEffect(Unit) {
        userViewModel.loadCustomers()
        farmViewModel.loadFields()
        farmViewModel.loadAllDevices()
    }

    // ── 弹窗状态 ──
    var showAddUser by remember { mutableStateOf(false) }        // 新增员工弹窗
    var assignTarget by remember { mutableStateOf<CustomerDto?>(null) } // 分配弹窗目标
    var deleteTarget by remember { mutableStateOf<CustomerDto?>(null) } // 删除确认目标
    var opMessage by remember { mutableStateOf<String?>(null) }   // 操作结果提示

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用者管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 新增员工入口
                    IconButton(onClick = { showAddUser = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新增员工")
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
            // 员工列表
            if (userViewModel.customers.isEmpty()) {
                item {
                    Text(
                        "暂无员工账号，点右上角 + 新增",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(userViewModel.customers, key = { it.id.id }) { customer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        // 员工名称
                        Text(
                            text = customer.name.ifEmpty { customer.title },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Customer ID: ${customer.id.id.take(8)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 分配可见范围
                            OutlinedButton(
                                onClick = { assignTarget = customer },
                                modifier = Modifier.weight(1f)
                            ) { Text("分配可见范围", style = MaterialTheme.typography.bodySmall) }
                            // 删除员工
                            OutlinedButton(
                                onClick = { deleteTarget = customer },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("删除", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }

    // ── 新增员工弹窗 ──
    if (showAddUser) {
        AddUserDialog(
            onDismiss = { showAddUser = false },
            onConfirm = { name, email ->
                userViewModel.createUser(name, email) { ok, msg ->
                    opMessage = msg
                    if (ok) showAddUser = false
                }
            }
        )
    }

    // ── 分配可见范围弹窗（勾选田块/设备）──
    assignTarget?.let { customer ->
        AssignScopeDialog(
            customer = customer,
            fields = farmViewModel.fields,
            devices = farmViewModel.devices,
            onDismiss = { assignTarget = null },
            onConfirm = { fieldIds, deviceIds ->
                userViewModel.assignScope(customer.id.id, fieldIds, deviceIds) { ok, msg ->
                    opMessage = msg
                }
                assignTarget = null
            }
        )
    }

    // ── 删除员工确认弹窗 ──
    deleteTarget?.let { customer ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除员工？") },
            text = { Text("将删除员工「${customer.name.ifEmpty { customer.title }}」及其账号，删除后该员工将无法登录。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    userViewModel.deleteUser(customer.id.id) { ok, msg -> opMessage = msg }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
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
 * 新增员工弹窗：名称 + 邮箱 + 初始密码
 */
@Composable
private fun AddUserDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit  // (名称, 邮箱)
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增员工账号") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("员工名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱（即登录账号）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "初始密码默认 123456，员工首次登录需修改密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && email.isNotBlank()) onConfirm(name.trim(), email.trim()) },
                enabled = name.isNotBlank() && email.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 分配可见范围弹窗：勾选田块/设备，确认后调 TB 分配接口
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
        title = { Text("分配可见范围 - ${customer.name.ifEmpty { customer.title }}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("田块", style = MaterialTheme.typography.titleSmall)
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
