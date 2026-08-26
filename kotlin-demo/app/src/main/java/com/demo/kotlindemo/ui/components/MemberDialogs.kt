// 包声明：成员管理弹窗（第三版重构：从 UserManagementScreen.kt 拆出，成员域弹窗高内聚）
package com.demo.kotlindemo.ui.components

// ═══════════════════════════════════════════════════════════
// import 区（与原 UserManagementScreen.kt 一致，含本文件全部组件所需）
// ═══════════════════════════════════════════════════════════
// 布局
import androidx.compose.foundation.layout.*
// 点击（分配弹窗勾选行）
import androidx.compose.foundation.clickable
// 滚动
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// 懒加载列表
// Material3
import androidx.compose.material3.*
// 运行时
import androidx.compose.runtime.*
// 对齐
import androidx.compose.ui.Alignment
// 修饰符
import androidx.compose.ui.Modifier
// 字重
// 尺寸
import androidx.compose.ui.unit.dp
// 协程
// 图标
// DTO
import com.demo.kotlindemo.data.dto.CustomerDto
// ViewModel

@Composable
/**
     * 新增成员弹窗：角色单选（使用者/管理员）+ 归属（新建/加入家庭）+ 邮箱
     */
internal fun AddMemberDialog(
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
/**
     * 分配可见范围弹窗：勾选田块/设备分配给家庭（成员共享）
     */
internal fun AssignScopeDialog(
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

