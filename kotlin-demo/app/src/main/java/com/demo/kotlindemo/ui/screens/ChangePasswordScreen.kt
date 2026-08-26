// 包声明：页面层
package com.demo.kotlindemo.ui.screens

// 布局
import androidx.compose.foundation.layout.*
// 滚动
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// Material3 组件
import androidx.compose.material3.*
// Compose 状态
import androidx.compose.runtime.*
// 对齐
import androidx.compose.ui.Alignment
// 修饰符
import androidx.compose.ui.Modifier
// 字重
import androidx.compose.ui.text.font.FontWeight
// 密码输入（圆点显示）
import androidx.compose.ui.text.input.PasswordVisualTransformation
// 尺寸
import androidx.compose.ui.unit.dp
// 认证仓库（改密/标记）
import com.demo.kotlindemo.data.api.AuthRepository
// 协程
import kotlinx.coroutines.launch

/**
 * 修改密码页（第二版新增：首次登录强制改密）
 *
 * 交互（与《内部需求文档》一致，体验上"不需要当前密码"）：
 *  - 当前密码由 App 代填（默认密码 123456，用户不可见/无需输入）；
 *  - 用户只需输入「新密码 + 确认」→ 调 TB changePassword → 成功后清除强制改密标记。
 *
 * @param email    当前登录邮箱（只读展示 + 改密标记清除用）
 * @param onSuccess 改密完成回调（进入主界面）
 * @param onBack    返回上一页
 */
@Composable
fun ChangePasswordScreen(
    email: String,
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    // ── 表单状态 ──
    var newPassword by remember { mutableStateOf("") } // 新密码
    var confirm     by remember { mutableStateOf("") } // 确认新密码
    var loading     by remember { mutableStateOf(false) } // 是否提交中
    var error       by remember { mutableStateOf<String?>(null) } // 错误提示

    // ── 网络 ──
    val scope = rememberCoroutineScope()
    val authRepo = remember { AuthRepository() }

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
            text = "修改密码",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        // 说明文案：首次登录需修改默认密码
        Text(
            text = "首次登录需修改默认密码，修改后才能进入系统",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        // 当前账号（只读展示）
        OutlinedTextField(
            value = email,
            onValueChange = {},
            label = { Text("账号") },
            enabled = false,  // 只读：账号不可改
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        // 新密码
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text("新密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(Modifier.height(16.dp))

        // 确认新密码
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("确认新密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        // 错误提示
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(32.dp))

        // 确认修改按钮
        Button(
            onClick = {
                error = null
                // 本地校验：非空 / 长度 / 两次一致
                if (newPassword.length < 6) {
                    error = "密码至少 6 位"
                    return@Button
                }
                if (newPassword != confirm) {
                    error = "两次输入的密码不一致"
                    return@Button
                }
                loading = true
                scope.launch {
                    try {
                        // 代填当前密码 = 默认密码 123456（体验上"不需要旧密码"）
                        val ok = authRepo.changePassword("123456", newPassword)
                        if (ok) {
                            // 改密成功 → 清除服务端强制改密标记
                            authRepo.markPasswordChanged(email)
                            // 修复：TB changePassword 会使旧 JWT 失效，必须用新密码重新登录拿新 token
                            authRepo.reloginAfterPasswordChange(email, newPassword)
                            onSuccess()  // 进入主界面
                        } else {
                            error = "修改密码失败，请稍后重试"
                        }
                    } catch (e: Exception) {
                        error = "修改密码失败：${e.message ?: "网络错误"}"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && newPassword.isNotBlank() && confirm.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (loading) "提交中..." else "确认修改")
        }

        Spacer(Modifier.height(12.dp))

        // 返回（注册流程中不建议回退，保留入口便于误入）
        TextButton(onClick = onBack) {
            Text("返回")
        }

        Spacer(Modifier.weight(1f))
    }
}
