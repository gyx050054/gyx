// 声明包名
package com.demo.kotlindemo.ui.screens

// 导入布局
import androidx.compose.foundation.layout.*
// 导入点击
import androidx.compose.foundation.clickable
// 导入懒加载列表
import androidx.compose.foundation.lazy.LazyColumn
// 导入 items 扩展
import androidx.compose.foundation.lazy.items
// 导入返回箭头
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 导入图标
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WaterDrop
// 导入 Material3
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
// 导入数据模型
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.isSensor
// 导入弹窗
import com.demo.kotlindemo.ui.components.BatchControlDialog
import com.demo.kotlindemo.ui.components.SwitchDialog
import com.demo.kotlindemo.ui.components.TimeRangeDialog
import com.demo.kotlindemo.ui.components.FieldMapView
// 导入 ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
import com.demo.kotlindemo.viewmodel.TaskViewModel
// 导入协程
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
// 导入日期格式化（显示最近上报时间）
import com.demo.kotlindemo.util.TimeFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 田块详情页
 *
 * 点击首页的田块卡片后进入
 * 显示田块信息和该田块下的所有设备
 *
 * @param fieldId 田块ID
 * @param farmViewModel 农田 ViewModel
 * @param taskViewModel 任务 ViewModel（预留）
 * @param onTaskManageClick 点击任务管理回调
 * @param onDeviceHistoryClick 点击温湿度计查看历史数据回调
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
     * 田块详情页：信息卡 + 批量操作/任务管理/挂载入口 + 设备列表（墒情/温湿度/阀门）10 秒刷新
     */
fun FieldDetailScreen(
    fieldId: String,              // 田块ID，从路由参数取
    farmViewModel: FarmViewModel,  // 农田 ViewModel
    taskViewModel: TaskViewModel,  // 任务 ViewModel
    onTaskManageClick: () -> Unit, // 任务管理回调
    onDeviceHistoryClick: (String, String, String) -> Unit, // 历史数据回调（deviceId, name, type）
    onBack: () -> Unit             // 返回回调
) {
    // 根据 fieldId 查找田块
    val field = remember(fieldId) { farmViewModel.fields.firstOrNull { it.id == fieldId } }
    // 获取该田块下的设备列表
    // 注意：不能包 remember(fieldId)，否则 devices 数据更新时不会重新计算，
    //      导致进入详情页看不到设备（需要手动触发重组才显示）。
    val devices = farmViewModel.devicesInField(fieldId)

    // 开关弹窗目标：null=不显示，非null=要操作的设备
    var switchTarget by remember { mutableStateOf<Device?>(null) }
    // 批量操作弹窗：true=显示
    var showBatchDialog by remember { mutableStateOf(false) }
    // 定时任务弹窗目标：null=不显示，非null=要设置定时任务的设备
    var showTimeDialog by remember { mutableStateOf<Device?>(null) }
    // 挂载自由设备弹窗：true=显示（第二版：田块详情挂自由设备）
    var showMountDevicesDialog by remember { mutableStateOf(false) }
    // 田块地图模式：true=顶部显示 Leaflet 地图（第三代第一版 §6）
    var mapMode by remember { mutableStateOf(false) }

    // 每 10 秒自动刷新设备状态（文档 3.7）：
    // 进入详情页先按田块加载设备（relations → 遥测），之后轮询该田块设备
    LaunchedEffect(fieldId) {
        farmViewModel.loadFieldDevices(fieldId)
        while (true) {
            delay(10_000)
            farmViewModel.loadFieldDevices(fieldId)  // 真实 API 轮询该田块设备
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(field?.name ?: "田块详情") },  // 标题用田块名
                navigationIcon = {  // 返回按钮
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {  // 地图/列表切换（第三代第一版 §6）
                    TextButton(onClick = { mapMode = !mapMode }) {
                        Text(if (mapMode) "📋 列表" else "🗺️ 地图")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 田块地图（第三代第一版 §6，通过顶部按钮切换显示）
            if (mapMode) {
                item {
                    FieldMapView(
                        fieldName = field?.name ?: "田块",
                        centerLat = field?.lat ?: 0.0,
                        centerLon = field?.lon ?: 0.0,
                        devices = devices,
                        onDeviceClick = { name ->
                            devices.firstOrNull { it.name == name }?.let { switchTarget = it }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    )
                }
            }
            // ① 田块信息卡片
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer  // 主色容器
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // 田块名称大标题
                        Text(
                            field?.name ?: "未知田块",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        // 作物 + 面积信息
                        Text(
                            "🌱 ${field?.cropType ?: "-"}  ·  面积 ${field?.areaSqm ?: 0.0} ㎡",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        // 统计 chips
                        Row {
                            // 总设备数 chip
                            AssistChip(
                                onClick = {},
                                label = { Text("总设备 ${field?.deviceCount ?: 0}") }
                            )
                            Spacer(Modifier.width(8.dp))
                            // 运行中设备数 chip
                            AssistChip(
                                onClick = {},
                                label = { Text("运行中 ${field?.activeCount ?: 0}") }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // 操作入口按钮行（对齐原型图：批量操作 + 任务管理）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 批量操作：对田块下所有可操作设备批量开关/定时
                            OutlinedButton(
                                onClick = { showBatchDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("批量操作", style = MaterialTheme.typography.bodyMedium) }
                            // 任务管理：跳转到任务管理页
                            OutlinedButton(
                                onClick = onTaskManageClick,
                                modifier = Modifier.weight(1f)
                            ) { Text("任务管理", style = MaterialTheme.typography.bodyMedium) }
                        }
                        // 挂载自由设备入口（第二版：方式一，仅租户管理员可见）
                        if (farmViewModel.isAdmin) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showMountDevicesDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("挂载自由设备", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }

            // ② 设备列表标题（对齐原型图"设备详细信息"）
            item {
                Text(
                    "📋 设备详细信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            // ③ 设备列表 / 空态
            if (devices.isEmpty()) {
                // 无设备时显示提示
                item {
                    Text(
                        "本块暂无设备",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                // 遍历该田块下所有设备
                items(devices, key = { it.id }) { device ->
                    FieldDeviceRow(
                        device = device,               // 设备数据
                        onToggle = { farmViewModel.toggleDevice(device.id) },  // 切换开关
                        onMore = { switchTarget = device },  // 弹出更多弹窗
                        onTiming = { showTimeDialog = device },  // 弹出定时任务弹窗
                        onHistory = { onDeviceHistoryClick(device.id, device.name, device.type.name) }  // 查看历史
                    )
                }
            }

            // 底部留空
            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    // 开关弹窗
    switchTarget?.let { device ->
        SwitchDialog(
            device = device,                         // 要操作的设备
            onDismiss = { switchTarget = null },      // 关闭弹窗
            onConfirm = {                             // 确认操作
                farmViewModel.toggleDevice(device.id) // 切换开关
                switchTarget = null                   // 关闭弹窗
            }
        )
    }

    // 定时任务弹窗（对齐原型图"添加定时任务"）
    showTimeDialog?.let { device ->
        TimeRangeDialog(
            device = device,                             // 要设置的设备
            onDismiss = { showTimeDialog = null },        // 关闭弹窗
            onConfirm = { start, end, isDaily, dailyHour, durationMinutes ->   // 确认时间/日期/每天
                taskViewModel.addTask(                    // 添加定时任务（支持每天 DAILY）
                    deviceId = device.id,                 // 设备ID
                    deviceName = device.name,             // 设备名称
                    startTime = if (isDaily) 0L else start,
                    endTime = if (isDaily) 0L else end,
                    repeatMode = if (isDaily) "DAILY" else "ONCE",
                    dailyHour = if (isDaily) dailyHour else null,
                    durationMinutes = if (isDaily) durationMinutes else null
                ) { ok, msg ->                            // 第三版：失败且因冲突 → 记录冲突设备，UI 弹清理确认
                    if (!ok && msg.contains("冲突")) {
                        taskViewModel.setConflict(device.name, device.id)
                    }
                }
                showTimeDialog = null                      // 关闭弹窗
            }
        )
    }

    // 批量操作弹窗：勾选田块内设备后对选中设备批量操作
    if (showBatchDialog) {
        val fieldDevices = devices.filter { !it.type.isSensor }
        BatchControlDialog(
            devices = fieldDevices,                             // 本田块可操作设备
            onDismiss = { showBatchDialog = false },            // 关闭
            onTurnOn = { list ->                                 // 一键开启（勾选设备）
                list.forEach { farmViewModel.toggleDevice(it.id, forceOn = true) }
                showBatchDialog = false
            },
            onTurnOff = { list ->                                // 一键关闭（勾选设备）
                list.forEach { farmViewModel.toggleDevice(it.id, forceOn = false) }
                showBatchDialog = false
            },
            onAddTiming = { list, start, end ->                  // 批量添加定时任务（勾选设备）
                taskViewModel.addTasksBatch(
                    deviceIds = list.map { it.id to it.name },
                    startTime = start,
                    endTime = end
                ) { ok, msg ->                             // 第三版：批量冲突 → 记录冲突（无法定位单设备）
                    if (!ok && msg.contains("冲突")) {
                        taskViewModel.setConflict("所选设备", "")
                    }
                }
                showBatchDialog = false
            }
        )
    }

    // ── 挂载自由设备弹窗（第二版：方式一，田块详情挂载）──
    if (showMountDevicesDialog) {
        MountDevicesDialog(
            freeDevices = farmViewModel.devices.filter { it.fieldId.isNullOrEmpty() },  // 自由设备
            onDismiss = { showMountDevicesDialog = false },
            onConfirm = { deviceId ->
                farmViewModel.mountDevice(deviceId, fieldId) { ok, msg ->
                    if (ok) {
                        // 挂载成功：刷新本田块设备 + 全局设备 + 田块设备数
                        farmViewModel.loadFieldDevices(fieldId)
                        farmViewModel.loadAllDevices()
                        farmViewModel.loadFields()
                    }
                }
                showMountDevicesDialog = false
            }
        )
    }

    // ── 冲突清理确认弹窗（第三版：创建任务冲突时，提示清除该设备的任务）──
    taskViewModel.conflictDeviceName?.let { devName ->
        AlertDialog(
            onDismissRequest = { taskViewModel.clearConflict() },
            title = { Text("存在冲突的任务") },
            text = {
                Text("设备「$devName」此时段已有任务进行中，是否清除该设备的任务？清除后可重新添加。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val devId = taskViewModel.conflictDeviceId
                    taskViewModel.clearConflict()
                    // 跳任务管理页并预勾选该设备未完成任务（批量冲突时全选待清理项）
                    if (devId.isNullOrEmpty()) taskViewModel.selectAllActive()
                    else taskViewModel.preSelectDeviceTasks(devId)
                    onTaskManageClick()
                }) { Text("去清理", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { taskViewModel.clearConflict() }) { Text("取消") } }
        )
    }
}

/**
 * 单个设备行
 * 显示图标、名称、状态、开关
 */
@Composable
/**
     * 田块详情单设备行：图标/名称/状态/开关（阀门）/历史入口（传感器）
     */
private fun FieldDeviceRow(
    device: Device,          // 设备数据
    onToggle: () -> Unit,    // 开关切换回调
    onMore: () -> Unit,      // 更多操作回调
    onTiming: () -> Unit,     // 添加定时任务回调
    onHistory: () -> Unit     // 查看历史回调
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (device.type.isSensor) Modifier.clickable { onHistory() } else Modifier)  // 传感器点击看历史
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设备类型图标
            Icon(
                imageVector = when (device.type) {
                    // 电动阀 → 水滴
                    DeviceType.VALVE -> Icons.Default.WaterDrop
                    // 传感器/墒情检测器 → 信号
                    DeviceType.SENSOR, DeviceType.SOIL_SENSOR -> Icons.Default.Sensors
                },
                contentDescription = null,
                tint = if (device.isOn) MaterialTheme.colorScheme.primary  // 开启=主色
                else MaterialTheme.colorScheme.outline                      // 关闭=灰色
            )
            Spacer(Modifier.width(12.dp))

            // 中间：名称 + 状态
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)  // 设备名
                Text(
                    // 传感器显示温湿度/墒情，其他显示运行状态
                    when (device.type) {
                        DeviceType.SENSOR -> "🌡 ${device.temperature}℃  💧 ${device.humidity}%RH"
                        DeviceType.SOIL_SENSOR -> "🌱 盐分 ${device.soilSalinity}ppm  pH ${device.soilPh}"
                        else -> (if (device.isOn) "🟢 工作中" else "⚪ 未工作") + "  🔋${device.battery}%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 设备 ID + 最近上报时间（文档字段：设备id、记录的时间）
                if (device.lastReportAt > 0L) {
                    Text(
                        text = "ID: ${device.id}  ·  🕐 ${fieldReportTimeFormatter.format(Date(device.lastReportAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 非传感器设备显示开关
            if (!device.type.isSensor) {
                Switch(checked = device.isOn, onCheckedChange = { onToggle() })
            }

            // 更多操作按钮
            IconButton(onClick = onMore) {
                Text("…", style = MaterialTheme.typography.titleMedium)
            }
        }
        // 非传感器设备：添加定时任务按钮（对齐原型图）
        if (!device.type.isSensor && device.isOnline) {
            OutlinedButton(
                onClick = onTiming,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) { Text("⏰ 添加定时任务", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * 挂载自由设备弹窗（第二版：方式一）
 * 列出全部自由设备（未归属田块），点选后挂载到当前田块
 */
@Composable
/**
     * 挂载自由设备弹窗：列出未挂载设备勾选挂入本田块
     */
private fun MountDevicesDialog(
    freeDevices: List<Device>,   // 自由设备列表
    onDismiss: () -> Unit,       // 取消
    onConfirm: (String) -> Unit  // 确认（设备 ID）
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("挂载自由设备到本田块") },
        text = {
            if (freeDevices.isEmpty()) {
                Text("暂无自由设备（可在「设备」页新增设备）")
            } else {
                Column {
                    freeDevices.forEach { d ->
                        TextButton(
                            onClick = { onConfirm(d.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(d.name, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// 田块详情页用的上报时间格式化器：HH:mm:ss（统一用 TimeFormats 单例）
private val fieldReportTimeFormatter get() = TimeFormats.TIME_HHMMSS
