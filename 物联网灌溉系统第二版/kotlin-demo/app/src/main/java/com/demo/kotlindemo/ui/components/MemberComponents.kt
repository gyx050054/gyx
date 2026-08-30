// 包声明：成员列表组件（第三版重构：从 UserManagementScreen.kt 拆出，成员域高内聚）
package com.demo.kotlindemo.ui.components

// ═══════════════════════════════════════════════════════════
// import 区（与原 UserManagementScreen.kt 一致，含本文件全部组件所需）
// ═══════════════════════════════════════════════════════════
// 布局
import androidx.compose.foundation.layout.*
// 点击（分配弹窗勾选行）
// 滚动
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
import androidx.compose.ui.text.font.FontWeight
// 尺寸
import androidx.compose.ui.unit.dp
// 协程
// 图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
// DTO
import com.demo.kotlindemo.data.dto.CustomerDto
import com.demo.kotlindemo.data.dto.MemberDto
// ViewModel

@Composable
/**
     * 家庭卡片：🏠 家庭名 + 成员行列表（邮箱/角色徽标/删除按钮）
     */
internal fun FamilyCard(
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

