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
// 尺寸
import androidx.compose.ui.unit.dp
// 认证仓库（注册/自动登录）
import com.demo.kotlindemo.data.api.AuthRepository
// 协程
import kotlinx.coroutines.launch

/**
 * 注册页（第二版新增）
 *
 * 流程（与《内部需求文档》注册链路一致）：
 * ① 用户输入邮箱 → ② 调微服务端 POST /api/auth/register（SysAdmin 代建租户+租户管理员）
 * ③ 注册成功后自动登录（邮箱 + 默认密码 123456）→ ④ 跳转改密页（首登强制改密）
 *
 * 设计说明：UI 只调 AuthRepository 语义化方法，不感知 TB / 微服务端差异。
 *
 * @param onRegisterSuccess 注册+自动登录成功回调（携带邮箱，供改密页使用）
 * @param onBack 返回登录页
 */
@Composable
fun RegisterScreen(
    onRegisterSuccess: (String) -> Unit,  // 参数：注册邮箱
    onBack: () -> Unit
) {
    // ── 表单状态 ──
    var email   by remember { mutableStateOf("") }   // 注册邮箱
    var loading by remember { mutableStateOf(false) } // 是否注册中
    var error   by remember { mutableStateOf<String?>(null) } // 错误提示

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
            text = "注册新农户",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "注册后自动创建农田管理账号",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        // 邮箱输入卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱（即登录账号）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
            }
        }

        Spacer(Modifier.height(32.dp))

        // 注册按钮
        Button(
            onClick = {
                loading = true
                error = null
                scope.launch {
                    try {
                        // ① 注册（微服务端 SysAdmin 代建租户 + 租户管理员）
                        val (ok, msg) = authRepo.register(email.trim())
                        if (ok) {
                            // ② 注册成功 → 自动登录（默认密码 123456，用户无感）
                            authRepo.autoLogin(email.trim())
                            // ③ 跳转改密页（首登强制改密）
                            onRegisterSuccess(email.trim())
                        } else {
                            error = "注册失败：$msg"
                        }
                    } catch (e: Exception) {
                        error = "注册失败：${e.message ?: "网络错误"}"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (loading) "注册中..." else "注册")
        }

        Spacer(Modifier.height(12.dp))

        // 返回登录
        TextButton(onClick = onBack) {
            Text("已有账号？返回登录")
        }

        Spacer(Modifier.weight(1f))
    }
}
