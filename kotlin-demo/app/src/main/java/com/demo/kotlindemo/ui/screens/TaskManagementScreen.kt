// 声明包名，这个文件属于页面层
package com.demo.kotlindemo.ui.screens

// 导入布局函数
import androidx.compose.foundation.layout.*
// 导入懒加载列表
import androidx.compose.foundation.lazy.LazyColumn
// 导入 items 扩展函数
import androidx.compose.foundation.lazy.items
// 导入返回箭头图标（自动镜像RTL）
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 导入删除图标
import androidx.compose.material.icons.filled.Delete
// 导入收件箱图标（空态用）
import androidx.compose.material.icons.filled.Inbox
// 导入 Material3 组件
import androidx.compose.material3.*
// 导入运行时
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
// 导入对齐
import androidx.compose.ui.Alignment
// 导入修饰符
import androidx.compose.ui.Modifier
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入 dp
import androidx.compose.ui.unit.dp
// 导入数据模型
import com.demo.kotlindemo.data.model.TaskStatus
import com.demo.kotlindemo.data.model.TimingTask
// 导入 ViewModel
import com.demo.kotlindemo.viewmodel.TaskViewModel
// 导入日期格式化
import com.demo.kotlindemo.util.TimeFormats
import java.text.SimpleDateFormat
// 导入日期类
import java.util.Date
// 导入 Locale
import java.util.Locale

/**
 * 任务管理页面
 *
 * 顶部标题栏，下方显示任务卡片列表
 * 支持空态显示
 *
 * @param taskViewModel 任务管理 ViewModel
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManagementScreen(
    taskViewModel: TaskViewModel,  // 任务 ViewModel
    onBack: () -> Unit             // 返回按钮回调
) {
    // Scaffold 页面骨架
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📋 任务管理") },  // 标题
                navigationIcon = {                 // 左侧返回按钮
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,  // 返回箭头
                            contentDescription = "返回"
                        )
                    }
                },
                // 标题栏颜色
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        // 进入页面立即从微服务端加载任务列表
        LaunchedEffect(Unit) {
            taskViewModel.loadTasks()
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 任务总数统计
            Text(
                "📋 共 ${taskViewModel.tasks.size} 条任务",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (taskViewModel.tasks.isEmpty()) {
                // 列表为空 → 显示空态页面
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                // 列表不为空 → 显示任务列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 80.dp),  // 列表整体边距
                    verticalArrangement = Arrangement.spacedBy(10.dp)  // 项间距
                ) {
                    // 遍历所有任务
                    items(taskViewModel.tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,                                   // 任务数据
                            onDelete = { taskViewModel.deleteTask(task.id) }  // 删除
                        )
                    }
                    // 底部留空
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

/**
 * 单个任务卡片
 * 显示设备名、任务ID、时间段、状态点、删除按钮
 * 说明：任务状态（等待/执行中/已完成）由微服务端维护，APP 只读展示；
 *      删除由微服务端处理（未开始直接删 / 已开始发暂停）。
 */
@Composable
private fun TaskCard(
    task: TimingTask,       // 任务数据
    onDelete: () -> Unit    // 删除回调
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 卡片背景颜色根据任务状态变化
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                // 执行中 → 三级容器色（醒目）
                TaskStatus.RUNNING   -> MaterialTheme.colorScheme.tertiaryContainer
                // 已完成 → 表面变体色（淡化）
                TaskStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
                // 已取消 → 错误容器色（淡化）
                TaskStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                // 待执行 → 表面色（默认）
                TaskStatus.PENDING   -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),  // 内边距
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态点：仅待执行/执行中显示（已完成/已取消不冒红点）
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                StatusDot(task.status)
                Spacer(Modifier.width(12.dp))  // 状态点和文字的间距
            }

            // 中间信息区域
            Column(modifier = Modifier.weight(1f)) {
                // 设备名称
                Text(
                    task.deviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                // 任务 ID（文档字段：任务 ID，展示前 8 位）
                Text(
                    "任务ID: ${task.id.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(2.dp))
                // 时间范围
                Text(
                    "⏰ ${formatTime(task.startTime)}  →  ${formatTime(task.endTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 状态文字
                Text(
                    statusText(task.status),       // 状态文本
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(task.status)  // 状态颜色
                )
            }

            // 操作按钮：仅 PENDING/RUNNING 可取消（已完成/已取消为终态，不显示按钮）
            if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,  // 取消图标
                        contentDescription = "取消",
                        tint = MaterialTheme.colorScheme.error  // 红色（错误色）
                    )
                }
            }
        }
    }
}

/**
 * 任务状态点（小方块）
 * 颜色随状态变化
 */
@Composable
private fun StatusDot(status: TaskStatus) {
    // 根据状态决定颜色（仅待执行/执行中被调用，统一用红色表示活跃任务）
    val color = when (status) {
        // 待执行 → 红色（未运行但有任务待处理）
        TaskStatus.PENDING   -> MaterialTheme.colorScheme.error
        // 执行中 → 红色（运行中）
        TaskStatus.RUNNING   -> MaterialTheme.colorScheme.error
        // 已完成/已取消：不显示状态点（由调用方控制）
        TaskStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    // 用 Surface 绘制一个小方块
    Surface(
        modifier = Modifier.size(12.dp),  // 12dp 大小
        shape = MaterialTheme.shapes.small,  // 小圆角
        color = color  // 状态颜色
    ) {}
}

/**
 * 空态页面 — 列表为空时显示
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,  // 水平居中
        verticalArrangement = Arrangement.Center              // 垂直居中
    ) {
        // 大图标
        Icon(
            Icons.Default.Inbox,  // 收件箱图标
            contentDescription = null,
            modifier = Modifier.size(64.dp),  // 64dp 大小
            tint = MaterialTheme.colorScheme.outline  // 灰色
        )
        Spacer(Modifier.height(12.dp))
        // 空态主提示
        Text(
            "暂无定时任务",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        // 操作引导文字
        Text(
            "在设备列表中点击「添加定时任务」即可创建",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ── 工具函数 ──

// 时间格式化器：把时间戳转成"MM-dd HH:mm"格式（统一用 TimeFormats 单例）
private val timeFormatter get() = TimeFormats.MONTH_DAY_TIME

// 格式化时间戳为可读字符串
private fun formatTime(ts: Long) = timeFormatter.format(Date(ts))

// 根据状态返回状态文字（带图标）
private fun statusText(s: TaskStatus) = when (s) {
    TaskStatus.PENDING   -> "⏳ 等待执行"    // 待执行
    TaskStatus.RUNNING   -> "🟢 执行中"     // 执行中
    TaskStatus.COMPLETED -> "✅ 已完成"     // 已完成
    TaskStatus.CANCELLED -> "❌ 已取消"     // 已取消
}

// 根据状态返回文字颜色
@Composable
private fun statusColor(s: TaskStatus) = when (s) {
    TaskStatus.PENDING   -> MaterialTheme.colorScheme.outline          // 灰色
    TaskStatus.RUNNING   -> MaterialTheme.colorScheme.primary          // 主色
    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant // 次要色
    TaskStatus.CANCELLED -> MaterialTheme.colorScheme.error            // 红色
}
