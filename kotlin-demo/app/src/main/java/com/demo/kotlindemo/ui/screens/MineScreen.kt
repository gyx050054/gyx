/**
 * 【文件职责】MineScreen —— 「我的」页面（第二版新增，底部导航第三个 Tab）。
 *   展示当前身份（租户管理员 / 员工，读 TB /api/auth/user 的 authority）与账号邮箱；「成员管理」入口仅租户管理员可见（isAdmin == TENANT_ADMIN）；
 *   底部「退出登录」按钮。
 *
 * 【数据流】进入页面用 LaunchedEffect(Unit) 触发 userViewModel.loadCurrentUser() 拉取当前用户；user = userViewModel.currentUser 驱动邮箱与身份徽标显示，
 *   isAdmin 由 authority 派生（员工 CUSTOMER_USER 看不到管理入口），控制「成员管理」按钮可见性；点「退出登录」走 onLogout 回登录页。
 */
// 包声明：页面层
package com.demo.kotlindemo.ui.screens

// 布局
import androidx.compose.foundation.layout.*
// 滚动
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
// 协程（首次加载身份）
import androidx.compose.runtime.LaunchedEffect
// ViewModel
import com.demo.kotlindemo.viewmodel.UserViewModel

/**
 * 「我的」页面（第二版新增，底部导航第三个 Tab）
 *
 * 内容（对应内部需求文档 doc 需求 1）：
 *  - 当前身份展示（租户管理员 / 员工，读取 TB /api/auth/user 的 authority）
 *  - 「成员管理」入口（仅租户管理员可见）
 *  - 「退出登录」按钮
 *
 * @param userViewModel 用户 ViewModel
 * @param onUserManageClick 成员管理入口回调（仅管理员）
 * @param onLogout 退出登录回调
 */
@Composable
fun MineScreen(
    userViewModel: UserViewModel,
    onUserManageClick: () -> Unit,
    onLogout: () -> Unit
) {
    // 进入页面加载当前身份（登录后才有 token）
    LaunchedEffect(Unit) {
        userViewModel.loadCurrentUser()
    }

    // 当前用户（可能尚未加载完成）
    val user = userViewModel.currentUser
    // 是否管理员：TENANT_ADMIN（员工 CUSTOMER_USER 看不到管理入口）
    val isAdmin = user?.authority == "TENANT_ADMIN"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        // 标题
        Text(
            text = "我的",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(32.dp))

        // 身份卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = "当前身份",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                // 账号邮箱
                Text(
                    text = user?.email ?: "加载中...",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(4.dp))
                // 身份徽标
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                isAdmin -> "租户管理员（老板）"
                                user?.authority == "CUSTOMER_USER" -> "员工（使用者）"
                                else -> "未知身份"
                            }
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 成员管理入口（仅租户管理员可见：新增管理员/家庭成员、分配可见范围）
        if (isAdmin) {
            OutlinedButton(
                onClick = onUserManageClick,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("成员管理（管理员/家庭成员）")
            }
            Spacer(Modifier.height(12.dp))
        }

        // 退出登录
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("退出登录")
        }

        Spacer(Modifier.weight(1f))
    }
}
