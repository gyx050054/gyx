// 包声明：页面层
package com.demo.kotlindemo.ui.screens

// 布局
import androidx.compose.foundation.layout.*
// 滚动
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// Material3 组件
import androidx.compose.material3.*
// Compose 运行时
import androidx.compose.runtime.Composable
// 修饰符
import androidx.compose.ui.Modifier
// 对齐
import androidx.compose.ui.Alignment
// 尺寸
import androidx.compose.ui.unit.dp

/**
 * 常见问题页（第二版新增：静态文案，登录页入口）
 *
 * 文案与《内部需求文档》3.2 一致，向用户说明注册规则与账号体系。
 *
 * @param onBack 返回登录页
 */
@Composable
fun FaqScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = "常见问题",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        // 问题列表（静态文案，对应需求文档 3.2）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = "如何注册使用？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "如果用户没使用过，可以注册农田，并默认成为农田管理员（老板），" +
                        "不能直接成为农田使用者（员工）。注册成功后自动登录，" +
                        "默认密码首次登录需修改。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "为什么需要联系系统？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "客户端无法直接新增 ThingsBoard 租户管理员，须经系统（微服务端）" +
                        "代为创建；租户管理员可直接在 App 内新增租户使用者（员工）。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "首次登录为什么要改密码？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "为保证账号安全，注册后使用统一默认密码，首次登录必须修改为" +
                        "自己的密码后才能进入系统。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
