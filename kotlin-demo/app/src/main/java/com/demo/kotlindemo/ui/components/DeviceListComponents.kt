// 包声明：设备列表组件（第三版重构：从 MainScreen.kt 拆出，设备域高内聚）
package com.demo.kotlindemo.ui.components

// ═══════════════════════════════════════════════════════════
// import 区（与原 MainScreen.kt 一致，含本文件全部组件所需）
// ═══════════════════════════════════════════════════════════
// 导入背景绘制
// 导入点击
import androidx.compose.foundation.clickable
// 导入布局函数
import androidx.compose.foundation.layout.*
// 导入网格布局：指定列数和网格条目
// 导入网格条目跨度（全宽提示条用）
// 导入懒加载网格
// 导入网格的 items 扩展函数
import androidx.compose.foundation.lazy.grid.items
// 导入懒加载列（列表）
import androidx.compose.foundation.lazy.LazyColumn
// 导入列表的 items 扩展函数
import androidx.compose.foundation.lazy.items
// 导入圆角形状
// 导入 Material 图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WaterDrop
// 导入 Material3 组件
import androidx.compose.material3.*
// 导入运行时核心
import androidx.compose.runtime.*
// 导入对齐方式
import androidx.compose.ui.Alignment
// 导入修饰符
import androidx.compose.ui.Modifier
// 导入绘制裁剪
// 导入边框（选中田块高亮用）
// 导入颜色类
// 导入剪贴板（复制设备凭证用）
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入文字溢出处理
import androidx.compose.ui.text.style.TextOverflow
// 导入 dp
import androidx.compose.ui.unit.dp
// 导入数据模型
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.Field
// 导入「我的」页与用户 ViewModel（第二版）
// 导入弹窗组件
// 导入 ViewModel
// 导入 TokenStore（任务红点状态，第二版）
import com.demo.kotlindemo.data.api.TokenStore
// 导入协程
// 导入日期格式化（显示最近上报时间）
import com.demo.kotlindemo.util.TimeFormats
import java.util.Date

@Composable
internal fun DevicesListContent(
    devices: List<Device>,            // 设备列表
    onToggle: (Device) -> Unit,       // 切换开关回调
    onLongPress: (Device) -> Unit,    // 长按/更多回调
    onTimingTask: (Device) -> Unit,   // 添加定时任务回调
    onBatchClick: () -> Unit,          // 批量操作回调
    onHistoryClick: (String, String) -> Unit,  // 查看历史回调
    onMount: (Device) -> Unit,         // 挂载到田块（自由设备，第二版）
    onUnmount: (Device) -> Unit,       // 取下设备（已挂载→自由，第三版）
    onRemount: (Device) -> Unit,       // 改挂到别的田块（第三版）
    onDelete: (Device) -> Unit,        // 删除设备（第二版）
    isAdmin: Boolean                   // 是否租户管理员（员工隐藏管理操作）
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),  // 列表边距
        verticalArrangement = Arrangement.spacedBy(10.dp)  // 项间距
    ) {
        // 顶部「批量操作」按钮
        item {
            OutlinedButton(
                onClick = onBatchClick,      // 点击弹出批量操作弹窗
                modifier = Modifier.fillMaxWidth()  // 填满宽度
            ) {
                Icon(Icons.Default.Power, contentDescription = null)  // 电源图标
                Spacer(Modifier.width(8.dp))   // 图标和文字间距
                Text("批量操作（多选设备）")     // 按钮文字
            }
        }

        // 遍历设备列表，每个设备生成一张卡片
        items(devices, key = { it.id }) { device ->
            DeviceCard(
                device = device,              // 设备数据
                onToggle = { onToggle(device) },   // 切换开关
                onMore = { onLongPress(device) },  // 更多操作
                onTiming = { onTimingTask(device) }, // 添加定时任务
                onHistory = { onHistoryClick(device.id, device.name) }, // 查看历史
                onMount = { onMount(device) },      // 挂载到田块（自由设备）
                onUnmount = { onUnmount(device) },   // 取下设备（已挂载→自由）
                onRemount = { onRemount(device) },   // 改挂到别的田块
                onDelete = { onDelete(device) },    // 删除设备
                isAdmin = isAdmin                   // 管理员才显示管理按钮
            )
        }

        // 底部留空，防止内容被底部导航栏挡住
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/**
 * 单个设备卡片
 * 显示设备图标、名称、状态、开关；温湿度计点击卡片可查看历史数据
 * 第二版新增：自由设备可「挂载到田块」，所有设备可「删除」（管理员操作）
 */
@Composable
internal fun DeviceCard(
    device: Device,          // 设备对象
    onToggle: () -> Unit,    // 开关切换回调
    onMore: () -> Unit,      // 更多操作回调
    onTiming: () -> Unit,     // 定时任务回调
    onHistory: () -> Unit,    // 查看历史回调
    onMount: () -> Unit,      // 挂载到田块回调（自由设备）
    onUnmount: () -> Unit,    // 取下设备回调（已挂载→自由）
    onRemount: () -> Unit,    // 改挂到别的田块回调
    onDelete: () -> Unit,     // 删除设备回调
    isAdmin: Boolean          // 是否租户管理员（员工隐藏挂载/删除管理按钮）
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (device.type == DeviceType.SENSOR) Modifier.clickable { onHistory() } else Modifier),  // 传感器点击看历史

        // 卡片颜色：开启时用secondaryContainer，关闭时用surface
        colors = CardDefaults.cardColors(
            containerColor = if (device.isOn)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)  // 内边距14dp
        ) {
            // ── 第一行：图标 + 名称 + 开关 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设备类型图标：根据类型显示不同图标
                Icon(
                    imageVector = deviceIcon(device.type),  // 获取图标
                    contentDescription = null,               // 无描述
                    tint = if (device.isOn) MaterialTheme.colorScheme.primary  // 开启=主色
                    else MaterialTheme.colorScheme.outline   // 关闭=轮廓色
                )
                Spacer(Modifier.width(12.dp))  // 图标和文字的间距

                // 中间：设备名称 + 状态文字
                Column(modifier = Modifier.weight(1f)) {  // weight(1f)填充剩余空间
                    Text(
                        device.name,                                        // 设备名称
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,                                        // 最多1行
                        overflow = TextOverflow.Ellipsis                     // 超出显示省略号
                    )
                    // 第一行副标题：实时数据/状态（文档字段：温度湿度/状态电量）
                    Text(
                        text = deviceSubtitle(device),                       // 设备状态文字
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 第二行副标题：设备 ID + 最近上报时间（文档字段：设备id、记录的时间）
                    if (device.lastReportAt > 0L) {
                        Text(
                            text = "ID: ${device.id}  ·  🕐 ${formatReportTime(device.lastReportAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // 右侧：开关（Switch）
                Switch(
                    checked = device.isOn,              // 当前开关状态
                    onCheckedChange = { onToggle() },    // 点击切换
                    enabled = device.isOnline && device.type != DeviceType.SENSOR  // 离线和传感器不可操作
                )
            }

            // ── 第二行：操作按钮（只有在线且非传感器才显示）──
            if (device.isOnline && device.type != DeviceType.SENSOR) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)  // 按钮间距
                ) {
                    OutlinedButton(
                        onClick = onMore,   // 更多操作
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("更多", style = MaterialTheme.typography.bodySmall) }

                    OutlinedButton(
                        onClick = onTiming,  // 添加定时任务
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) { Text("添加定时任务", style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ── 第三行：设备管理操作（第二版：仅管理员显示 挂载自由设备/删除）──
            if (isAdmin) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 自由设备（未归属田块）显示「挂载到田块」
                    if (device.fieldId.isNullOrEmpty()) {
                        OutlinedButton(
                            onClick = onMount,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("挂载到田块", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        // 已挂载设备（第三版）：可取下变自由 / 改挂到别的田块
                        OutlinedButton(
                            onClick = onUnmount,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("取下", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(
                            onClick = onRemount,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("改挂", style = MaterialTheme.typography.bodySmall) }
                    }
                    // 删除设备（管理员操作）
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════
// 设备管理弹窗（第二版新增）
// ═══════════════════════════════════════════════════════════

/**
 * 新增设备弹窗（第二版）：名称 + 类型单选（电动阀/温湿度计）
 * 创建后为自由设备（不归属任何田块），接入凭证在下一步弹窗展示
 */
@Composable
internal fun AddDeviceDialog(
    onDismiss: () -> Unit,             // 取消
    onConfirm: (String, String) -> Unit // 确认（名称, 类型）
) {
    // 输入状态
    var name by remember { mutableStateOf("") }
    // 类型单选：默认电动阀
    var type by remember { mutableStateOf("VALVE") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设备名称（必填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("设备类型", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                // 类型单选：仅支持电动阀 / 温度湿度计（需求文档：类型限定）
                Row {
                    listOf(
                        "VALVE" to "电动阀",
                        "TEMPERATURE_HUMIDITY" to "温度湿度计"
                    ).forEach { (v, label) ->
                        FilterChip(
                            selected = type == v,
                            onClick = { type = v },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "创建后为自由设备，需挂载到田块；接入凭证将在下一步展示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), type) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 设备凭证弹窗（第二版）：展示 accessToken + 一键复制
 * 提示用户将凭证配置到真设备（需求文档：凭证展示 + 复制按钮）
 */
@Composable
internal fun TokenDialog(token: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备接入凭证") },
        text = {
            Column {
                Text(
                    "请复制下面的接入凭证（Access Token），配置到你的设备后，" +
                            "设备数据会自动出现在系统里：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                // 凭证内容（只读，可全选复制）
                OutlinedTextField(
                    value = token,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(token))  // 复制到剪贴板
                onDismiss()
            }) { Text("复制并关闭") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 挂载到田块弹窗（第二版）：为自由设备选择目标田块
 * 对应需求文档「挂载方式二：设备页选田块」
 */
@Composable
internal fun MountFieldDialog(
    fields: List<Field>,         // 可选田块列表
    onDismiss: () -> Unit,       // 取消
    onConfirm: (String) -> Unit  // 确认（田块 ID）
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("挂载到田块") },
        text = {
            if (fields.isEmpty()) {
                Text("暂无可挂载的田块，请先新增田块")
            } else {
                Column {
                    fields.forEach { f ->
                        TextButton(
                            onClick = { onConfirm(f.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${f.name}（${f.deviceCount} 台设备）",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

/**
 * 根据设备类型返回对应的 Material 图标
 * 电动阀/施肥泵 → 水滴，传感器 → 信号，通风扇 → 灯泡
 */
internal fun deviceIcon(type: DeviceType) = when (type) {
    // VALVE（电动阀）→ WaterDrop（水滴图标）
    DeviceType.VALVE -> Icons.Default.WaterDrop
    // SENSOR（传感器）→ Sensors（信号图标）
    DeviceType.SENSOR -> Icons.Default.Sensors
}

/**
 * 根据设备类型/状态生成副标题文字
 * 传感器显示温度湿度；其他设备显示在线/运行状态 + 电量
 */
internal fun deviceSubtitle(device: Device): String = when (device.type) {
    // 传感器显示温度和湿度
    DeviceType.SENSOR -> "🌡 ${device.temperature}℃  💧 ${device.humidity}%RH"
    // 其他设备显示在线/运行状态 + 电量
    else -> when {
        !device.isOnline -> "❌ 离线"               // 离线
        device.isOn -> "🟢 工作中  🔋${device.battery}%"  // 工作状态+电量
        else -> "⚪ 未工作  🔋${device.battery}%"     // 待机+电量
    }
}

// 上报时间格式化器：HH:mm:ss（统一用 TimeFormats 单例）
internal val reportTimeFormatter get() = TimeFormats.TIME_HHMMSS

// 把上报时间戳格式化为可读时间字符串
internal fun formatReportTime(ts: Long) = reportTimeFormatter.format(Date(ts))

