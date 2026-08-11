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
// 更多菜单图标（第三版：设备操作收进菜单）
import androidx.compose.material.icons.filled.MoreVert
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
// 大号开关缩放（第三版：阀门卡片升级）
import androidx.compose.ui.draw.scale
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
 * 显示设备图标、名称、状态、大号开关、数据行；所有操作收进右上角「更多」菜单
 * 第三版升级：① 阀门卡片大开关 + 流量/水压/累计用水数据行；② 五个按钮收进菜单
 */
@Composable
internal fun DeviceCard(
    device: Device,          // 设备对象
    onToggle: () -> Unit,    // 开关切换回调
    onTiming: () -> Unit,     // 定时任务回调
    onHistory: () -> Unit,    // 查看历史回调
    onMount: () -> Unit,      // 挂载到田块回调（自由设备）
    onUnmount: () -> Unit,    // 取下设备回调（已挂载→自由）
    onRemount: () -> Unit,    // 改挂到别的田块回调
    onDelete: () -> Unit,     // 删除设备回调
    isAdmin: Boolean          // 是否租户管理员（员工隐藏管理操作）
) {
    // 「更多」菜单展开状态
    var menuExpanded by remember { mutableStateOf(false) }

    // 是否可操作设备（阀门且在线）
    val operable = device.type != DeviceType.SENSOR && device.isOnline

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (device.type == DeviceType.SENSOR) Modifier.clickable { onHistory() } else Modifier),  // 传感器点击看历史

        // 卡片颜色：开启时用二级容器色（醒目），关闭时用表面色
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
            // ── 第一行：图标 + 名称 + 状态 + 大开关 + 更多菜单 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设备类型图标
                Icon(
                    imageVector = deviceIcon(device.type),  // 根据类型显示图标
                    contentDescription = null,
                    tint = if (device.isOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(12.dp))

                // 中间：设备名称 + 状态
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = deviceSubtitle(device),  // 状态/温度湿度等
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 第三版：设备 ID 移入「更多」菜单，卡片不再显示（保持简洁）
                }

                // 大号开关（第三版：放大 + 开启时主题色，更醒目）
                Switch(
                    checked = device.isOn,
                    onCheckedChange = { onToggle() },
                    enabled = operable,   // 离线和传感器不可操作
                    modifier = Modifier.scale(1.25f),  // 放大 1.25 倍
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                // 「更多」菜单入口（第三版：所有操作收进菜单）
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // 设备信息（第三版：ID 与最近上报时间收进菜单，卡片保持简洁）
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "设备ID: ${device.id}" +
                                            (if (device.lastReportAt > 0L) "  ·  🕐 ${formatReportTime(device.lastReportAt)}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            },
                            onClick = { menuExpanded = false },
                            enabled = false  // 纯展示，不可点
                        )
                        // 温湿度计：查看历史
                        if (device.type == DeviceType.SENSOR) {
                            DropdownMenuItem(
                                text = { Text("查看历史") },
                                onClick = { menuExpanded = false; onHistory() }
                            )
                        }
                        // 阀门在线：添加定时任务
                        if (operable) {
                            DropdownMenuItem(
                                text = { Text("添加定时任务") },
                                onClick = { menuExpanded = false; onTiming() }
                            )
                        }
                        // 管理员：挂载管理（挂载/取下/改挂）+ 删除
                        if (isAdmin) {
                            if (device.fieldId.isNullOrEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("挂载到田块") },
                                    onClick = { menuExpanded = false; onMount() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("取下（变自由设备）") },
                                    onClick = { menuExpanded = false; onUnmount() }
                                )
                                DropdownMenuItem(
                                    text = { Text("改挂到其他田块") },
                                    onClick = { menuExpanded = false; onRemount() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("删除设备", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
            }

            // ── 数据行（第三版：阀门卡片升级——工作中显示流量/水压/累计用水/电量）──
            if (device.type != DeviceType.SENSOR) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricItem("瞬时流量", if (device.isOn) "%.1f".format(device.instantFlow) else "--", "L/min", Modifier.weight(1f))
                    MetricItem("水压", if (device.isOn) "%.2f".format(device.waterPressure) else "--", "MPa", Modifier.weight(1f))
                    MetricItem("累计用水", "%.2f".format(device.totalWaterUsage), "m³", Modifier.weight(1f))
                    MetricItem("电量", "${device.battery}%", "", Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 数据指标项（阀门卡片升级用）：指标名 + 数值 + 单位，小号展示
 */
@Composable
private fun MetricItem(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
        Text(
            text = "$value$unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
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

