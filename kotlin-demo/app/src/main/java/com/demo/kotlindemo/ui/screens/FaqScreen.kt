/**
 * 【文件职责】FaqScreen —— 常见问题页（第二版新增：静态文案，登录页入口）。
 *   顶部「返回」+ 标题，下方一张卡片逐条列出常见问题（如何注册、为何不能自建账号、为何首次登录要改密码），文案与内部需求文档 3.2 一致。
 *
 * 【数据流】纯静态展示，无网络请求、无可变状态；onBack 返回上一页（登录页）。
 */
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

        // 问题列表（通俗化文案，第三版：让不熟悉的人也能看懂）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = "怎么注册？注册了我是干嘛的？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "直接点登录页的「注册」，用邮箱注册一个账号就行。\n\n" +
                        "注册成功后，你就是一个独立的「农户/公司老板」，可以自己建田块、加设备、" +
                        "给家人或员工开账号。注意：注册只能当老板，不能直接当员工。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "为什么我不能自己随便创建账号？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "为了保证安全，新「公司」的老板账号由系统自动创建（你注册就是）；" +
                        "其他的账号——不管是公司的另一个老板，还是家里的成员——" +
                        "都由现有老板在 App 的「成员管理」里添加，外人进不来。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "为什么第一次登录要改密码？",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "新账号默认用统一的初始密码（123456），这个密码大家都知道，不安全。" +
                        "所以第一次登录必须改成只有你自己知道的密码，才能进入系统。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
