// 声明包名，这个文件属于 UI 组件层
package com.demo.kotlindemo.ui.components

// 导入布局函数
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
// 导入懒加载列表（批量选择设备列表用）
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
// 导入裁剪
import androidx.compose.ui.draw.clip
// 导入 Material 图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Schedule
// 导入 Material3 组件
import androidx.compose.material3.*
// 导入运行时
import androidx.compose.runtime.*
// 导入对齐
import androidx.compose.ui.Alignment
// 导入修饰符
import androidx.compose.ui.Modifier
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入 dp
import androidx.compose.ui.unit.dp
// 导入设备模型（按名称导入防止冲突）
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
// 导入日期类
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ═══════════════════════════════════════════════════════════
// ① 开关弹窗 — 设备状态控制
// 触发：点击设备行的「更多」按钮
// 内容：显示设备当前状态 + 开启/关闭按钮
// ═══════════════════════════════════════════════════════════

/**
 * 设备开关控制弹窗
 * 同时操作单个设备的开启或关闭
 *
 * @param device 要操作的设备对象
 * @param onDismiss 关闭弹窗的回调
 * @param onConfirm 确认操作的回调
 */
@Composable
fun SwitchDialog(
    device: Device,        // 目标设备
    onDismiss: () -> Unit, // 关闭回调
    onConfirm: () -> Unit  // 确认回调
) {
    // AlertDialog：Material3 对话框
    AlertDialog(
        onDismissRequest = onDismiss,  // 点击外部区域或返回键时触发
        title = { Text("⚙ ${device.name} — 状态控制") },  // 弹窗标题
        text = {
            // 弹窗内容：垂直排列
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 显示当前状态
                Text(
                    "当前状态：${if (device.isOn) "🟢 开启" else "⚪ 关闭"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                // 显示电量
                Text(
                    "电量：🔋 ${device.battery}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 操作按钮组
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 开启/关闭按钮（文字根据当前状态取反）
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (device.isOn) "关闭" else "开启") }
                }
            }
        },
        confirmButton = {
            // 底部确认按钮
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// ② 时间范围弹窗 — 添加定时任务 / 设备管理
// 触发：设备行的「添加定时任务」按钮 / 田块详情中的「设备管理」
// 内容：开始时间输入框 + 结束时间输入框 + 确定
// ═══════════════════════════════════════════════════════════

/**
 * 时间范围设置弹窗（需求文档 3.6.1）
 * 用于添加单设备的定时任务，或批量定时
 *
 * 开始时间：默认「立即开始」，可选「定时」→ 点击选择日期 + 时间
 * 持续时长：点击选择预设（15/30 分钟、1/2/4 小时），结束时间自动计算
 *
 * @param device 关联设备（可选，为null时用于批量操作）
 * @param title 弹窗标题，默认"⏰ 添加定时任务"
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调，返回开始和结束时间戳
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeDialog(
    device: Device? = null,                    // 可选设备参数
    title: String = "⏰ 添加定时任务",          // 弹窗标题
    onDismiss: () -> Unit,                     // 关闭回调
    onConfirm: (startTime: Long, endTime: Long) -> Unit  // 时间确认回调
) {
    // 获取当前时间（毫秒），remember 让它在重组时不变
    val now = remember { System.currentTimeMillis() }
    // 开始时间：默认立即开始
    var startTime by remember { mutableStateOf(now) }
    var immediate by remember { mutableStateOf(true) }
    // 持续时长：默认 30 分钟（需求：间隔 1 分钟起步）
    var durationMs by remember { mutableStateOf(30 * 60_000L) }

    AlertDialog(
        onDismissRequest = onDismiss,  // 关闭
        title = {
            // 如果传了设备，标题显示设备名
            Text(if (device != null) "$title · ${device.name}" else title)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TimingPicker(
                    now = now,
                    immediate = immediate,
                    onImmediateChange = { immediate = it },
                    startTime = startTime,
                    onStartTimeChange = { startTime = it },
                    durationMs = durationMs,
                    onDurationChange = { durationMs = it }
                )
            }
        },
        confirmButton = {
            // 确定按钮：立即 = now；定时 = 选择的日期+时间；结束 = 开始 + 时长
            TextButton(onClick = {
                val start = if (immediate) now else startTime
                onConfirm(start, start + durationMs)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// ③ 批量操作弹窗 — 勾选设备后批量操作
// 触发：设备列表顶部的「批量操作」按钮
// 内容：设备复选框列表 + 一键开启/一键关闭/添加定时任务（仅对勾选设备生效）
// 点击「添加定时任务」后展开子表单输入时间
// ═══════════════════════════════════════════════════════════

/**
 * 批量操作弹窗
 * 先勾选设备，再对勾选的设备执行一键开启、一键关闭、批量添加定时任务。
 *
 * @param devices 可批量操作的设备列表（不含传感器等不可操作设备）
 * @param onDismiss 关闭回调
 * @param onTurnOn 一键开启回调，参数为勾选的设备列表
 * @param onTurnOff 一键关闭回调，参数为勾选的设备列表
 * @param onAddTiming 批量添加定时任务回调，参数为勾选的设备列表和起止时间戳
 */
@Composable
fun BatchControlDialog(
    devices: List<Device>,                                                  // 可选设备列表
    onDismiss: () -> Unit,                                                  // 关闭回调
    onTurnOn: (List<Device>) -> Unit,                                       // 一键开启（勾选设备）
    onTurnOff: (List<Device>) -> Unit,                                      // 一键关闭（勾选设备）
    onAddTiming: (List<Device>, startTime: Long, endTime: Long) -> Unit     // 批量定时（勾选设备）
) {
    // 勾选的设备 ID 集合
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 是否在「添加定时任务」子表单中
    var showTimingForm by remember { mutableStateOf(false) }
    // 时间选择：默认立即开始 + 30 分钟时长（点击选择）
    val now = remember { System.currentTimeMillis() }
    var startTime by remember { mutableStateOf(now) }
    var immediate by remember { mutableStateOf(true) }
    var durationMs by remember { mutableStateOf(30 * 60_000L) }

    // 勾选对应的设备列表
    val selectedDevices = devices.filter { it.id in selectedIds }

    // 切换某个设备的勾选状态
    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // 全选/取消全选
    fun toggleSelectAll() {
        selectedIds = if (selectedIds.size == devices.size && devices.isNotEmpty()) {
            emptySet()
        } else {
            devices.map { it.id }.toSet()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎛 批量操作") },
        text = {
            if (!showTimingForm) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 全选行（一键勾选/取消全部设备）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { toggleSelectAll() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedIds.size == devices.size && devices.isNotEmpty(),
                            onCheckedChange = { toggleSelectAll() }
                        )
                        Text(
                            "全选（共 ${devices.size} 台）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // 设备勾选列表（可滚动，限制高度）
                    Text(
                        "勾选要操作的设备（已选 ${selectedIds.size} 台）：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(devices, key = { it.id }) { device ->
                            // 每行：复选框 + 设备名称 + 状态
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { toggleSelect(device.id) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = device.id in selectedIds,   // 是否勾选
                                    onCheckedChange = { toggleSelect(device.id) }  // 切换勾选
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                    Text(
                                        // 状态副标题：传感器显示温湿度，其他显示状态+电量
                                        if (device.type == DeviceType.SENSOR)
                                            "🌡 ${device.temperature}℃  💧 ${device.humidity}%RH"
                                        else
                                            (if (device.isOn) "🟢 工作中" else "⚪ 未工作") + "  🔋${device.battery}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    // 操作按钮：只对勾选的设备生效
                    Button(
                        onClick = { onTurnOn(selectedDevices) },   // 一键开启
                        enabled = selectedIds.isNotEmpty(),         // 未勾选时禁用
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Power, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("一键开启（${selectedIds.size}）")
                    }
                    OutlinedButton(
                        onClick = { onTurnOff(selectedDevices) },  // 一键关闭
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("一键关闭（${selectedIds.size}）")
                    }
                    OutlinedButton(
                        onClick = { showTimingForm = true },       // 添加定时任务
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加定时任务")
                    }
                }
            } else {
                // 子表单：为勾选的设备选择时间范围（点击选择）
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 提示文字
                    Text(
                        "为选中的 ${selectedIds.size} 台设备添加定时任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 时间选择器：立即/定时 + 日期/时间点击选择 + 时长预设
                    TimingPicker(
                        now = now,
                        immediate = immediate,
                        onImmediateChange = { immediate = it },
                        startTime = startTime,
                        onStartTimeChange = { startTime = it },
                        durationMs = durationMs,
                        onDurationChange = { durationMs = it }
                    )
                }
            }
        },
        confirmButton = {
            if (showTimingForm) {
                // 子表单模式：确定按钮
                TextButton(onClick = {
                    val s = if (immediate) now else startTime
                    onAddTiming(selectedDevices, s, s + durationMs)  // 回调勾选设备 + 时间戳
                }) { Text("确定") }
            } else {
                // 主界面模式：关闭按钮
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            // 子表单模式：返回主界面
            if (showTimingForm) {
                TextButton(onClick = { showTimingForm = false }) { Text("返回") }
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════
// ④ 定时任务时间选择器 — 点击选择，替代手动输入（需求 3.6.1）
// 开始时间：立即 / 定时（DatePicker + TimePicker 点击选择）
// 持续时长：预设选项点击选择，结束时间自动计算
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimingPicker(
    now: Long,                              // 当前时间
    immediate: Boolean,                     // 是否立即开始
    onImmediateChange: (Boolean) -> Unit,   // 切换立即/定时
    startTime: Long,                        // 当前选择的开始时间（定时模式）
    onStartTimeChange: (Long) -> Unit,      // 更新开始时间
    durationMs: Long,                       // 持续时长
    onDurationChange: (Long) -> Unit        // 更新持续时长
) {
    // 定时模式下弹出的日期/时间选择器开关
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()
    val timePickerState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)

    // 时长预设（需求：间隔 1 分钟起步，提供常用档位）
    val durations = listOf(
        15 * 60_000L to "15 分钟",
        30 * 60_000L to "30 分钟",
        60 * 60_000L to "1 小时",
        120 * 60_000L to "2 小时",
        240 * 60_000L to "4 小时"
    )

    // ① 开始方式：立即 / 定时
    Text("开始时间：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = immediate,
            onClick = { onImmediateChange(true) },
            label = { Text("⚡ 立即开始") }
        )
        FilterChip(
            selected = !immediate,
            onClick = { onImmediateChange(false) },
            label = { Text("📅 定时开始") }
        )
    }

    if (immediate) {
        // 立即：展示当前时间
        Text(
            "开始时间：${formatTime(now)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // 定时：点击选日期 + 时间
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text("📅 ${formatDate(startTime)}")
            }
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text("🕐 ${formatTimeOfDay(startTime)}")
            }
        }
    }

    // ② 持续时长
    Text("持续时长：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        durations.forEach { (ms, label) ->
            FilterChip(
                selected = durationMs == ms,
                onClick = { onDurationChange(ms) },
                label = { Text(label) }
            )
        }
    }

    // ③ 结束时间预览（自动计算）
    val end = (if (immediate) now else startTime) + durationMs
    Text(
        "结束时间：${formatTime(end)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary
    )

    // 日期选择弹窗（Material3 DatePicker，注意 UTC 转本地时区）
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { utcMidnight ->
                        val offset = TimeZone.getDefault().getOffset(utcMidnight)
                        val localDate = utcMidnight + offset   // 本地时区当天 00:00
                        onStartTimeChange(combineDateTime(localDate, startTime))
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 时间选择弹窗（Material3 TimePicker）
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onStartTimeChange(
                        Calendar.getInstance().apply {
                            timeInMillis = startTime
                            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            set(Calendar.MINUTE, timePickerState.minute)
                        }.timeInMillis
                    )
                    showTimePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            },
            text = { TimePicker(state = timePickerState) }
        )
    }
}

// ═══════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════

// 显示用的时间格式器：yyyy-MM-dd HH:mm
private val displayFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

// 日期格式器：yyyy-MM-dd（日期选择按钮显示）
private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

// 时分格式器：HH:mm（时间选择按钮显示）
private val timeOfDayFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

// 用新日期（dateMillis 本地零点）替换原时间的日期部分，时分秒保留
private fun combineDateTime(dateMillis: Long, timeMillis: Long): Long {
    val t = Calendar.getInstance().apply { timeInMillis = timeMillis }
    return Calendar.getInstance().apply {
        timeInMillis = dateMillis
        set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
        set(Calendar.MINUTE, t.get(Calendar.MINUTE))
        set(Calendar.SECOND, t.get(Calendar.SECOND))
        set(Calendar.MILLISECOND, t.get(Calendar.MILLISECOND))
    }.timeInMillis
}

// 格式化日期（yyyy-MM-dd）
private fun formatDate(ts: Long): String = dateFormatter.format(Date(ts))

// 格式化时分（HH:mm）
private fun formatTimeOfDay(ts: Long): String = timeOfDayFormatter.format(Date(ts))

/**
 * 把时间戳格式化为 "yyyy-MM-dd HH:mm" 字符串
 *
 * @param ts 毫秒时间戳
 * @return 格式化后的时间字符串
 */
private fun formatTime(ts: Long): String = displayFormatter.format(Date(ts))
