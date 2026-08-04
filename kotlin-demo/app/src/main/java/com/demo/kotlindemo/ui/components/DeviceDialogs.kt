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
 * 时间范围设置弹窗
 * 用于添加单设备的定时任务，或批量定时
 *
 * @param device 关联设备（可选，为null时用于批量操作）
 * @param title 弹窗标题，默认"⏰ 添加定时任务"
 * @param onDismiss 关闭回调
 * @param onConfirm 确认回调，返回开始和结束时间戳
 */
// @OptIn 标记使用实验性 Material3 API（OutlinedTextField）
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
    // 开始时间默认 = 现在 + 1 分钟
    var startText by remember { mutableStateOf(formatTime(now + 60_000)) }
    // 结束时间默认 = 现在 + 10 分钟
    var endText   by remember { mutableStateOf(formatTime(now + 600_000)) }

    AlertDialog(
        onDismissRequest = onDismiss,  // 关闭
        title = {
            // 如果传了设备，标题显示设备名
            Text(if (device != null) "$title · ${device.name}" else title)
        },
        text = {
            // 两个时间输入框
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 开始时间输入框
                OutlinedTextField(
                    value = startText,                // 当前值
                    onValueChange = { startText = it }, // 更新值
                    label = { Text("开始时间（默认现在）") },
                    placeholder = { Text("格式：yyyy-MM-dd HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // 结束时间输入框
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("结束时间") },
                    placeholder = { Text("格式：yyyy-MM-dd HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                // 格式提示
                Text(
                    "💡 时间格式：2026-07-30 14:00",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            // 确定按钮：解析时间字符串并回调
            TextButton(onClick = {
                // 解析失败就用默认值（现在+1分 / 现在+10分）
                val s = parseTime(startText) ?: (now + 60_000)
                val e = parseTime(endText)   ?: (now + 600_000)
                onConfirm(s, e)  // 回调返回两个时间戳
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
    // 预填默认时间
    val now = remember { System.currentTimeMillis() }
    var startText by remember { mutableStateOf(formatTime(now + 60_000)) }
    var endText   by remember { mutableStateOf(formatTime(now + 600_000)) }

    // 勾选对应的设备列表
    val selectedDevices = devices.filter { it.id in selectedIds }

    // 切换某个设备的勾选状态
    fun toggleSelect(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎛 批量操作") },
        text = {
            if (!showTimingForm) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                // 子表单：为勾选的设备选择时间范围
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 提示文字
                    Text(
                        "为选中的 ${selectedIds.size} 台设备添加定时任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 开始时间输入
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("开始时间（默认现在）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    // 结束时间输入
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束时间") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            if (showTimingForm) {
                // 子表单模式：确定按钮
                TextButton(onClick = {
                    val s = parseTime(startText) ?: (now + 60_000)
                    val e = parseTime(endText)   ?: (now + 600_000)
                    onAddTiming(selectedDevices, s, e)  // 回调勾选设备 + 时间戳
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
// 工具函数
// ═══════════════════════════════════════════════════════════

// 显示用的时间格式器：yyyy-MM-dd HH:mm
private val displayFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

// 解析用的时间格式器列表（按优先级排列）
private val parseFormatters = listOf(
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),  // 完整格式
    SimpleDateFormat("MM-dd HH:mm",      Locale.getDefault()),  // 月日时分
    SimpleDateFormat("HH:mm",            Locale.getDefault())   // 仅时分
)

/**
 * 把时间戳格式化为 "yyyy-MM-dd HH:mm" 字符串
 *
 * @param ts 毫秒时间戳
 * @return 格式化后的时间字符串
 */
private fun formatTime(ts: Long): String = displayFormatter.format(Date(ts))

/**
 * 把时间字符串解析为毫秒时间戳
 * 支持三种格式：
 *   - yyyy-MM-dd HH:mm
 *   - MM-dd HH:mm
 *   - HH:mm
 *
 * @param text 时间字符串
 * @return 解析成功返回 Long 毫秒时间戳，失败返回 null
 */
private fun parseTime(text: String): Long? {
    // 空字符串直接返回 null
    if (text.isBlank()) return null
    // 获取当前时间的 Calendar 实例
    val now = Calendar.getInstance()
    // 依次尝试每种格式
    for (fmt in parseFormatters) {
        try {
            // 尝试解析
            val parsed = fmt.parse(text) ?: continue
            val cal = Calendar.getInstance()
            // 把解析结果设置到 Calendar 中
            cal.time = parsed

            // 如果格式不含年份（没有-或只有1个-），补上今年
            if (!text.contains("-") || text.count { it == '-' } == 1) {
                cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
            }
            // 如果格式只含 HH:mm（没有-），补上今天日期
            if (!text.contains("-")) {
                cal.set(Calendar.MONTH, now.get(Calendar.MONTH))
                cal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
            }

            // 返回毫秒时间戳
            return cal.timeInMillis
        } catch (_: Exception) {
            // 当前格式解析失败，继续尝试下一个格式
        }
    }
    // 所有格式都失败，返回 null
    return null
}
