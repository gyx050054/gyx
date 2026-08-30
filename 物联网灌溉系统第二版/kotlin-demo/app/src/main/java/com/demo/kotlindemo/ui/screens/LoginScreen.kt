// 声明包名，这个文件属于页面层
package com.demo.kotlindemo.ui.screens

// 导入布局相关的 Compose 函数
import androidx.compose.foundation.layout.*
// 导入滚动状态
import androidx.compose.foundation.rememberScrollState
// 导入垂直滚动修饰符
import androidx.compose.foundation.verticalScroll
// 导入 Material3 组件
import androidx.compose.material3.*
// 导入 Compose 运行时核心（mutableStateOf 等）
import androidx.compose.runtime.*
// 导入对齐方式
import androidx.compose.ui.Alignment
// 导入修饰符
import androidx.compose.ui.Modifier
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入密码输入转换器（把密码显示为圆点）
import androidx.compose.ui.text.input.PasswordVisualTransformation
// 导入 dp 尺寸单位
import androidx.compose.ui.unit.dp
// 导入网络仓库（登录 API）
import com.demo.kotlindemo.data.api.ThingsBoardRepository
// 导入认证仓库（注册/改密标记检查，第二版新增）
import com.demo.kotlindemo.data.api.AuthRepository
// 导入协程
import kotlinx.coroutines.launch

/**
 * 登录页
 *
 * 对照设计图，从上到下：
 * ① 标题 Logo "🌱 智能灌溉" + "田块管理 APP"
 * ② 卡片内两个输入框（账号 / 密码）
 * ③ "登录" 主按钮
 *
 * 文档要求：
 *  - 用户未登录时只能访问登录界面
 *  - 登录直接发 API 给 ThingsBoard 服务端验证登录（POST /api/auth/login 获取 JWT）
 *  - 登录成功后自动查询所有电动阀工作状态（GET .../values/timeseries?keys=valveState）
 *
 * @param onLoginSuccess        登录成功且无需改密回调（进入主界面）
 * @param onNeedChangePassword  登录成功但需强制改密回调（携带邮箱，跳改密页）
 * @param onRegisterClick       点击「注册」回调
 * @param onFaqClick            点击「常见问题」回调
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,            // 登录成功进入主页
    onNeedChangePassword: (String) -> Unit, // 需强制改密（携带邮箱）
    onRegisterClick: () -> Unit,           // 注册入口
    onFaqClick: () -> Unit                 // 常见问题入口
) {
    // ── 表单状态 ──
    // remember 让变量在重组时保持值不变
    // mutableStateOf 创建可观察状态
    var username    by remember { mutableStateOf("") }   // 账号输入框的内容
    var password    by remember { mutableStateOf("") }   // 密码输入框的内容
    var loading     by remember { mutableStateOf(false) } // 是否正在登录中
    var loginError  by remember { mutableStateOf<String?>(null) } // 登录错误提示

    // ── 网络 ──
    val scope = rememberCoroutineScope()
    val repository = remember { ThingsBoardRepository() }
    val authRepo = remember { AuthRepository() } // 第二版：强制改密标记检查

    // Column 垂直排列所有子元素
    Column(
        // 修饰符链：填满父容器
        modifier = Modifier
            .fillMaxSize()           // 填满整个屏幕
            .verticalScroll(rememberScrollState())  // 内容超出时可滚动
            .padding(horizontal = 24.dp),  // 左右留 24dp 边距
        // 子元素水平居中
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ① 顶部 Logo 区域
        // 空占位，留出状态栏高度
        Spacer(Modifier.height(48.dp))
        // App 主标题
        Text(
            text = "🌱 智能灌溉",  // 显示的文字
            style = MaterialTheme.typography.headlineMedium,  // 使用中等标题样式
            fontWeight = FontWeight.Bold,  // 加粗
            color = MaterialTheme.colorScheme.primary  // 使用主题主色
        )
        // 标题和副标题之间的间距
        Spacer(Modifier.height(8.dp))
        // 副标题
        Text(
            text = "田块管理 APP",  // 副标题文字
            style = MaterialTheme.typography.titleMedium,  // 使用中级标题样式
            color = MaterialTheme.colorScheme.onSurfaceVariant  // 次要文本颜色
        )

        // 副标题和输入框之间的间距
        Spacer(Modifier.height(40.dp))

        // ② 输入框卡片
        Card(
            modifier = Modifier.fillMaxWidth(),  // 卡片填满宽度
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)  // 卡片阴影2dp
        ) {
            // 卡片内部的列布局
            Column(
                modifier = Modifier
                    .fillMaxWidth()     // 填满卡片宽度
                    .padding(20.dp),    // 内部边距20dp
                horizontalAlignment = Alignment.CenterHorizontally  // 子元素居中
            ) {
                // 第一个输入框：账号
                OutlinedTextField(
                    value = username,                    // 输入框的当前值
                    onValueChange = { username = it },   // 输入改变时更新状态
                    label = { Text("账号") },             // 输入框标签文字
                    modifier = Modifier.fillMaxWidth(),   // 填满列宽度
                    singleLine = true                     // 禁止换行
                )

                // 输入框之间的间距
                Spacer(Modifier.height(16.dp))

                // 第二个输入框：密码
                OutlinedTextField(
                    value = password,                                           // 当前值
                    onValueChange = { password = it },                           // 输入改变时更新
                    label = { Text("密码") },                                    // 标签
                    modifier = Modifier.fillMaxWidth(),                          // 填满
                    singleLine = true,                                           // 单行
                    visualTransformation = PasswordVisualTransformation()        // 密码显示为圆点
                )

                // 登录错误提示
                loginError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // ③ 主登录按钮之前的间距
        Spacer(Modifier.height(32.dp))

        // 「登录」主按钮
        Button(
            onClick = {
                loading = true   // 点击后设为加载态
                loginError = null
                // 文档：登录直接发 API 给 ThingsBoard 服务端验证登录（POST /api/auth/login）
                scope.launch {
                    try {
                        val resp = repository.login(username.trim(), password)
                        if (resp.token.isNotEmpty()) {
                            // 登录成功：查强制改密标记（首次登录强制改密，需求文档 3.2）
                            val must = authRepo.mustChangePassword(username.trim())
                            if (must) {
                                onNeedChangePassword(username.trim())  // 跳改密页
                            } else {
                                onLoginSuccess()                        // 直接进主页
                            }
                        } else {
                            loginError = "登录失败：服务端未返回 token"
                        }
                    } catch (e: Exception) {
                        // 登录失败时有明确的错误提示（需求文档 3.1：账号或密码错误）
                        loginError = "登录失败：${e.message ?: "网络错误"}"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading && username.isNotBlank() && password.isNotBlank(),  // 必填校验
            modifier = Modifier
                .fillMaxWidth()     // 填满宽度
                .height(50.dp)      // 高度50dp
        ) {
            // 按钮文字：加载中显示"登录中"，否则显示"登录"
            Text(if (loading) "登录中..." else "登录")
        }

        // ④ 注册 / 常见问题入口（第二版新增）
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onRegisterClick) { Text("没有账号？注册") }
            TextButton(onClick = onFaqClick) { Text("常见问题") }
        }

        // 把底部按钮推到底部
        Spacer(Modifier.weight(1f))
    }
}
