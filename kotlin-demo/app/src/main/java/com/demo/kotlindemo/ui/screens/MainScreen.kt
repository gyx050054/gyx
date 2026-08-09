// 声明包名，这个文件属于页面层
package com.demo.kotlindemo.ui.screens

// 导入背景绘制
import androidx.compose.foundation.background
// 导入点击
import androidx.compose.foundation.clickable
// 导入布局函数
import androidx.compose.foundation.layout.*
// 导入网格布局：指定列数和网格条目
import androidx.compose.foundation.lazy.grid.GridCells
// 导入网格条目跨度（全宽提示条用）
import androidx.compose.foundation.lazy.grid.GridItemSpan
// 导入懒加载网格
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
// 导入网格的 items 扩展函数
import androidx.compose.foundation.lazy.grid.items
// 导入懒加载列（列表）
import androidx.compose.foundation.lazy.LazyColumn
// 导入列表的 items 扩展函数
import androidx.compose.foundation.lazy.items
// 导入圆角形状
import androidx.compose.foundation.shape.RoundedCornerShape
// 导入 Material 图标
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.ui.draw.clip
// 导入边框（选中田块高亮用）
import androidx.compose.foundation.border
// 导入颜色类
import androidx.compose.ui.graphics.Color
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
import com.demo.kotlindemo.ui.screens.MineScreen
import com.demo.kotlindemo.viewmodel.UserViewModel
// 导入弹窗组件
import com.demo.kotlindemo.ui.components.BatchControlDialog
import com.demo.kotlindemo.ui.components.SwitchDialog
import com.demo.kotlindemo.ui.components.TimeRangeDialog
// 导入 ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
// 导入 TokenStore（任务红点状态，第二版）
import com.demo.kotlindemo.data.api.TokenStore
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
 * 底部导航栏的 Tab 枚举
 */
// 定义两个 tab 类型
private enum class MainTab {
    FIELDS,  // 区块 tab
    DEVICES, // 设备 tab
    MINE     // 我的 tab（第二版新增：身份/退出/使用者管理）
}

/**
 * App 主页面 — 底部有两个 Tab：区块 / 设备
 *
 * @param farmViewModel 农田 ViewModel
 * @param taskViewModel 任务 ViewModel
 * @param onLogout 退出登录回调
 * @param onFieldClick 点击田块回调，传入田块 ID
 * @param onTaskManageClick 点击任务管理回调
 * @param onDeviceHistoryClick 点击温湿度计设备查看历史数据回调
 */
// 标记使用实验性的 Material3 API
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    farmViewModel: FarmViewModel,   // 农田 ViewModel 参数
    taskViewModel: TaskViewModel,   // 任务 ViewModel 参数
    userViewModel: UserViewModel,   // 用户 ViewModel（第二版：「我的」页）
    onLogout: () -> Unit,           // 退出登录回调
    onFieldClick: (String) -> Unit, // 点击田块回调，参数是 fieldId
    onTaskManageClick: () -> Unit,   // 点击任务管理回调
    onDeviceHistoryClick: (String, String) -> Unit,  // 点击温湿度计查看历史
    onUserManageClick: () -> Unit    // 使用者管理入口（第二版）
) {
    // ── 当前选中的 tab ──
    // remember 记住当前选中的 tab，默认是区块
    var currentTab by remember { mutableStateOf(MainTab.FIELDS) }

    // ── 弹窗状态 ──
    // 开关弹窗：null=不显示，非null=要显示的设备
    var showSwitchDialog by remember { mutableStateOf<Device?>(null) }
    // 批量操作弹窗：true=显示
    var showBatchDialog  by remember { mutableStateOf(false) }
    // 定时任务弹窗：null=不显示，非null=要设置的设备
    var showTimeDialog   by remember { mutableStateOf<Device?>(null) }

    // ── 田块管理状态（第二版新增：新增/删除田块）──
    // 新增田块弹窗：true=显示
    var showAddFieldDialog by remember { mutableStateOf(false) }
    // 删除选择模式：true=田块卡片进入多选状态
    var selectionMode by remember { mutableStateOf(false) }
    // 选择模式下已勾选的田块 ID 集合
    var selectedFieldIds by remember { mutableStateOf(setOf<String>()) }
    // 删除确认弹窗：true=显示
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 操作结果提示（新增/删除完成后 Snackbar 或文本提示）
    var fieldOpMessage by remember { mutableStateOf<String?>(null) }

    // ── 设备管理状态（第二版新增：新增/挂载/删除设备）──
    // 新增设备弹窗：true=显示
    var showAddDeviceDialog by remember { mutableStateOf(false) }
    // 设备凭证弹窗：非 null=显示（携带新设备 accessToken）
    var newDeviceToken by remember { mutableStateOf<String?>(null) }
    // 挂载弹窗目标设备：非 null=显示（对自由设备选田块挂载）
    var mountDeviceTarget by remember { mutableStateOf<Device?>(null) }
    // 删除确认弹窗目标设备：非 null=显示
    var deleteDeviceTarget by remember { mutableStateOf<Device?>(null) }
    // 设备操作结果提示
    var devOpMessage by remember { mutableStateOf<String?>(null) }

    // Scaffold：Material3 页面骨架
    Scaffold(
        // ① 顶部标题栏
        topBar = {
            TopAppBar(
                // 标题根据当前 tab 动态切换
                title = {
                    Text(
                        if (currentTab == MainTab.FIELDS) "🌾 田块总览" else "📡 所有设备信息",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                // 右侧操作按钮
                actions = {
                    // 田块 tab 的田块管理按钮（第二版新增）
                    if (currentTab == MainTab.FIELDS) {                        if (selectionMode) {
                            // 选择模式：取消 + 删除选中
                            IconButton(onClick = { selectionMode = false; selectedFieldIds = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = "取消选择")
                            }
                            IconButton(
                                onClick = { if (selectedFieldIds.isNotEmpty()) showDeleteConfirm = true },
                                enabled = selectedFieldIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除选中田块")
                            }
                        } else {
                            // 普通模式：新增 + 删除（进入选择模式）
                            IconButton(onClick = { showAddFieldDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "新增田块")
                            }
                            IconButton(onClick = { selectionMode = true; selectedFieldIds = emptySet() }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除田块")
                            }
                        }
                    }
                    // 设备 tab：新增设备入口（第二版）
                    if (currentTab == MainTab.DEVICES) {
                        IconButton(onClick = { showAddDeviceDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新增设备")
                        }
                    }
                    // 任务管理入口按钮（第二版：有任务且未访问时显示红点，访问后消失）
                    IconButton(onClick = {
                        TokenStore.markTasksVisited()  // 标记已访问，红点消失
                        onTaskManageClick()
                    }) {
                        BadgedBox(
                            // 有任务且用户尚未访问过任务页 → 显示红点（需求文档：访问后消失）
                            badge = {
                                if (taskViewModel.tasks.isNotEmpty() && !TokenStore.hasVisitedTasks()) {
                                    Badge()
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Assignment, contentDescription = "任务管理")
                        }
                    }
                    // 退出登录按钮（第二版：退出时重置任务红点状态）
                    IconButton(onClick = {
                        TokenStore.resetTasksVisited()  // 重置红点，下次登录重新显示
                        onLogout()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出")
                    }
                },
                // 标题栏颜色：使用主题容器色
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },

        // ③ 底部导航栏
        bottomBar = {
            NavigationBar {
                // 区块 tab 按钮
                NavigationBarItem(
                    selected = currentTab == MainTab.FIELDS,   // 是否选中
                    onClick = { currentTab = MainTab.FIELDS },  // 点击切换
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },  // 图标
                    label = { Text("田块") }  // 标签文字
                )
                // 设备 tab 按钮
                NavigationBarItem(
                    selected = currentTab == MainTab.DEVICES,   // 是否选中
                    onClick = { currentTab = MainTab.DEVICES },  // 点击切换
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },   // 图标
                    label = { Text("设备") }  // 标签文字
                )
                // 我的 tab 按钮（第二版新增）
                NavigationBarItem(
                    selected = currentTab == MainTab.MINE,   // 是否选中
                    onClick = { currentTab = MainTab.MINE },  // 点击切换
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },  // 图标
                    label = { Text("我的") }  // 标签文字
                )
            }
        }
    ) { padding ->  // padding 是 Scaffold 自动计算的内边距
        // ── 数据加载与自动刷新（文档 3.7 设备状态实时更新）──
        // 进入主页立即加载田块与设备；之后每 10 秒调用 ThingsBoard REST API 轮询最新遥测。
        LaunchedEffect(Unit) {
            farmViewModel.loadFields()         // 田块总览
            farmViewModel.loadAllDevices()     // 设备列表
            taskViewModel.loadTasks()          // 任务列表（第二版：任务红点依据）
            while (true) {
                delay(10_000)                  // 每隔 10 秒
                farmViewModel.refreshFromApi() // 真实 API 轮询
                taskViewModel.loadTasks()      // 轮询任务（红点跟随任务变化）
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()           // 填满屏幕
                .padding(padding)         // 应用 Scaffold 内边距
        ) {
            // 根据当前 tab 显示不同内容
            when (currentTab) {
                // 区块 tab：显示田块网格（支持删除选择模式）
                MainTab.FIELDS -> FieldsGridContent(
                    fields = farmViewModel.fields,          // 传入田块列表
                    selectionMode = selectionMode,          // 是否选择模式
                    selectedIds = selectedFieldIds,         // 已勾选田块
                    onFieldClick = onFieldClick,            // 点击进入详情
                    onToggleSelect = { id ->                // 选择模式：切换勾选
                        selectedFieldIds = if (id in selectedFieldIds)
                            selectedFieldIds - id else selectedFieldIds + id
                    }
                )
                // 设备 tab：显示设备列表
                MainTab.DEVICES -> DevicesListContent(
                    devices = farmViewModel.devices,        // 传入设备列表
                    onToggle = { farmViewModel.toggleDevice(it.id) },  // 切换开关
                    onLongPress = { showSwitchDialog = it },  // 长按弹出开关弹窗
                    onTimingTask = { showTimeDialog = it },   // 弹出定时任务弹窗
                    onBatchClick = { showBatchDialog = true },  // 弹出批量操作弹窗
                    onHistoryClick = { id, name -> onDeviceHistoryClick(id, name) },  // 查看历史
                    onMount = { mountDeviceTarget = it },     // 挂载到田块（自由设备）
                    onDelete = { deleteDeviceTarget = it }    // 删除设备
                )
                // 我的 tab（第二版新增）：身份/使用者管理/退出
                MainTab.MINE -> MineScreen(
                    userViewModel = userViewModel,
                    onUserManageClick = onUserManageClick,  // 使用者管理入口
                    onLogout = {
                        TokenStore.resetTasksVisited()  // 重置任务红点（第二版）
                        onLogout()
                    }  // 退出登录
                )
            }
        }
    }

    // ── 弹窗 ──
    // 开关弹窗：showSwitchDialog 不为 null 时显示
    showSwitchDialog?.let { device ->
        SwitchDialog(
            device = device,                            // 要操作的设备
            onDismiss = { showSwitchDialog = null },     // 关闭弹窗
            onConfirm = {                                // 确认操作
                farmViewModel.toggleDevice(device.id)   // 切换设备开关
                showSwitchDialog = null                  // 关闭弹窗
            }
        )
    }
    // 定时任务弹窗
    showTimeDialog?.let { device ->
        TimeRangeDialog(
            device = device,                             // 要设置的设备
            onDismiss = { showTimeDialog = null },        // 关闭弹窗
            onConfirm = { start, end ->                   // 确认时间和日期
                taskViewModel.addTask(                    // 添加定时任务
                    deviceId = device.id,                 // 设备ID
                    deviceName = device.name,             // 设备名称
                    startTime = start,                    // 开始时间
                    endTime = end                         // 结束时间
                )
                showTimeDialog = null                      // 关闭弹窗
            }
        )
    }
    // 批量操作弹窗：勾选设备后对选中设备批量操作
    if (showBatchDialog) {
        BatchControlDialog(
            // 只列出可操作设备（电动阀等，不含温湿度传感器）
            devices = farmViewModel.devices.filter { it.type != DeviceType.SENSOR },
            onDismiss = { showBatchDialog = false },        // 关闭
            onTurnOn = { list ->                             // 一键开启（勾选设备）
                list.forEach { farmViewModel.toggleDevice(it.id, forceOn = true) }
                showBatchDialog = false
            },
            onTurnOff = { list ->                            // 一键关闭（勾选设备）
                list.forEach { farmViewModel.toggleDevice(it.id, forceOn = false) }
                showBatchDialog = false
            },
            onAddTiming = { list, start, end ->              // 批量添加定时任务（勾选设备）
                taskViewModel.addTasksBatch(
                    deviceIds = list.map { it.id to it.name },  // 勾选设备 ID+名称
                    startTime = start,
                    endTime = end
                )
                showBatchDialog = false
            }
        )
    }

    // ── 新增田块弹窗（第二版：租户管理员自助建田块）──
    if (showAddFieldDialog) {
        AddFieldDialog(
            onDismiss = { showAddFieldDialog = false },          // 取消
            onConfirm = { name ->                                // 确认新增
                farmViewModel.createField(name) { ok, msg ->     // 调用 VM 创建
                    fieldOpMessage = msg
                    if (ok) showAddFieldDialog = false           // 成功才关闭
                }
            }
        )
    }

    // ── 删除田块二次确认弹窗（第二版：选择模式删除）──
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除田块？") },
            text = {
                Text(
                    "将删除选中的 ${selectedFieldIds.size} 块田块，其下设备将变为自由设备" +
                        "（设备不会丢失，可重新挂载到其它田块）。是否继续？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        farmViewModel.deleteFields(selectedFieldIds.toList()) { ok, msg ->
                            fieldOpMessage = msg
                        }
                        // 退出选择模式
                        selectionMode = false
                        selectedFieldIds = emptySet()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // ── 田块操作结果提示（新增/删除成功或失败）──
    fieldOpMessage?.let { msg ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { fieldOpMessage = null }) { Text("知道了") }
            }
        ) { Text(msg) }
    }

    // ── 新增设备弹窗（第二版：名称 + 类型单选，创建后为自由设备）──
    if (showAddDeviceDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDeviceDialog = false },         // 取消
            onConfirm = { name, type ->                          // 确认创建
                farmViewModel.createDevice(name, type) { ok, msg, token ->
                    devOpMessage = msg
                    if (ok) {
                        showAddDeviceDialog = false
                        newDeviceToken = token                   // 弹出凭证展示
                    }
                }
            }
        )
    }

    // ── 设备凭证弹窗（第二版：展示 accessToken + 复制按钮）──
    newDeviceToken?.let { token ->
        TokenDialog(
            token = token,
            onDismiss = { newDeviceToken = null }
        )
    }

    // ── 挂载到田块弹窗（第二版：自由设备选田块）──
    mountDeviceTarget?.let { device ->
        MountFieldDialog(
            fields = farmViewModel.fields,                        // 田块列表
            onDismiss = { mountDeviceTarget = null },             // 取消
            onConfirm = { fieldId ->                              // 确认挂载
                farmViewModel.mountDevice(device.id, fieldId) { ok, msg -> devOpMessage = msg }
                mountDeviceTarget = null
            }
        )
    }

    // ── 删除设备二次确认弹窗（第二版：联动取消未完成任务）──
    deleteDeviceTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { deleteDeviceTarget = null },
            title = { Text("确认删除设备？") },
            text = {
                Text(
                    "将删除设备「${device.name}」，其未完成的定时任务将被取消，且不可恢复。是否继续？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDeviceTarget = null
                        farmViewModel.deleteDevice(device.id) { ok, msg -> devOpMessage = msg }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDeviceTarget = null }) { Text("取消") }
            }
        )
    }

    // ── 设备操作结果提示 ──
    devOpMessage?.let { msg ->
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { devOpMessage = null }) { Text("知道了") }
            }
        ) { Text(msg) }
    }
}


// ═══════════════════════════════════════════════════════════
// 区块网格（3列）
// ═══════════════════════════════════════════════════════════
@Composable
private fun FieldsGridContent(
    fields: List<Field>,            // 田块列表
    selectionMode: Boolean,         // 是否删除选择模式（第二版）
    selectedIds: Set<String>,       // 已勾选的田块 ID
    onFieldClick: (String) -> Unit, // 点击田块进入详情
    onToggleSelect: (String) -> Unit // 选择模式：切换勾选
) {
    // 懒加载网格，3列固定宽度
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),  // 固定3列
        contentPadding = PaddingValues(12.dp),  // 整体内边距
        verticalArrangement = Arrangement.spacedBy(12.dp),   // 行间距
        horizontalArrangement = Arrangement.spacedBy(12.dp)  // 列间距
    ) {
        // 顶部提示卡片（对齐原型图提示文案）
        item(span = { GridItemSpan(maxLineSpan) }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    "💡 点击某一田块可以查看操作该田块下的设备，",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }

        // 遍历田块列表，用 key 提高列表性能
        items(fields, key = { it.id }) { field ->
            // 选择模式：点击切换勾选；普通模式：点击进入详情
            FieldCard(
                field = field,
                selected = field.id in selectedIds,  // 是否已勾选
                selectionMode = selectionMode,
                onClick = { if (selectionMode) onToggleSelect(field.id) else onFieldClick(field.id) }
            )
        }
    }
}

/**
 * 新增田块弹窗（第二版：租户管理员自助建田块）
 * 输入名称 → 确认后调 FarmViewModel.createField
 */
@Composable
private fun AddFieldDialog(
    onDismiss: () -> Unit,     // 取消
    onConfirm: (String) -> Unit // 确认（携带田块名称）
) {
    // 输入框状态
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增田块") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("田块名称（必填，租户内唯一）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()  // 名称为空不可提交
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 单个田块卡片
 * 显示田块名称、作物、设备运行数量
 * 选择模式下选中卡片显示高亮边框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldCard(
    field: Field,
    selected: Boolean,       // 选择模式下是否已勾选
    selectionMode: Boolean,  // 是否选择模式
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,  // 点击事件
        modifier = Modifier
            .fillMaxWidth()   // 填满网格单元
            .height(120.dp)   // 固定高度120dp
            // 选择模式：选中卡片加主色边框高亮
            .then(
                if (selected)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            ),
        // 卡片颜色：有设备在运行则用primaryContainer，否则用surfaceVariant
        colors = CardDefaults.cardColors(
            containerColor = if (field.activeCount > 0)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()      // 填满卡片
                .padding(12.dp),    // 内边距12dp
            verticalArrangement = Arrangement.SpaceBetween  // 子元素两端分布
        ) {
            // 第一行：田块名称 + 在线小圆点
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    field.name,                                     // 田块名称
                    style = MaterialTheme.typography.titleMedium,   // 标题样式
                    fontWeight = FontWeight.Bold                    // 加粗
                )
                // 在线设备小圆点
                Box(
                    modifier = Modifier
                        .size(10.dp)  // 10dp 大小
                        .clip(RoundedCornerShape(50))  // 圆形裁剪
                        .background(
                            if (field.activeCount > 0) Color(0xFF4CAF50)  // 有设备运行=绿色
                            else MaterialTheme.colorScheme.outline         // 无设备=灰色
                        )
                )
            }

            // 中间：作物类型
            Text(
                field.cropType,       // 显示种植作物
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 底部：运行设备数/总设备数
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${field.activeCount}",      // 运行中数量
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    " / ${field.deviceCount}",   // 总设备数量
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════
// 设备列表
// ═══════════════════════════════════════════════════════════
@Composable
private fun DevicesListContent(
    devices: List<Device>,            // 设备列表
    onToggle: (Device) -> Unit,       // 切换开关回调
    onLongPress: (Device) -> Unit,    // 长按/更多回调
    onTimingTask: (Device) -> Unit,   // 添加定时任务回调
    onBatchClick: () -> Unit,          // 批量操作回调
    onHistoryClick: (String, String) -> Unit,  // 查看历史回调
    onMount: (Device) -> Unit,         // 挂载到田块（自由设备，第二版）
    onDelete: (Device) -> Unit         // 删除设备（第二版）
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
                onDelete = { onDelete(device) }     // 删除设备
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
private fun DeviceCard(
    device: Device,          // 设备对象
    onToggle: () -> Unit,    // 开关切换回调
    onMore: () -> Unit,      // 更多操作回调
    onTiming: () -> Unit,     // 定时任务回调
    onHistory: () -> Unit,    // 查看历史回调
    onMount: () -> Unit,      // 挂载到田块回调（自由设备）
    onDelete: () -> Unit      // 删除设备回调
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

            // ── 第三行：设备管理操作（第二版新增：挂载自由设备 / 删除设备）──
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
                }
                // 删除设备（管理员操作，所有设备可见）
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


// ═══════════════════════════════════════════════════════════
// 设备管理弹窗（第二版新增）
// ═══════════════════════════════════════════════════════════

/**
 * 新增设备弹窗（第二版）：名称 + 类型单选（电动阀/温湿度计）
 * 创建后为自由设备（不归属任何田块），接入凭证在下一步弹窗展示
 */
@Composable
private fun AddDeviceDialog(
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
                    listOf("VALVE" to "电动阀", "TEMPERATURE_HUMIDITY" to "温度湿度计").forEach { (v, label) ->
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
private fun TokenDialog(token: String, onDismiss: () -> Unit) {
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
private fun MountFieldDialog(
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
private fun deviceIcon(type: DeviceType) = when (type) {
    // VALVE（电动阀）→ WaterDrop（水滴图标）
    DeviceType.VALVE  -> Icons.Default.WaterDrop
    // SENSOR（传感器）→ Sensors（信号图标）
    DeviceType.SENSOR -> Icons.Default.Sensors
}

/**
 * 根据设备类型/状态生成副标题文字
 * 传感器显示温度湿度；其他设备显示在线/运行状态 + 电量
 */
private fun deviceSubtitle(device: Device): String = when (device.type) {
    // 传感器显示温度和湿度
    DeviceType.SENSOR -> "🌡 ${device.temperature}℃  💧 ${device.humidity}%RH"
    // 其他设备显示在线/运行状态 + 电量
    else -> when {
        !device.isOnline -> "❌ 离线"               // 离线
        device.isOn      -> "🟢 工作中  🔋${device.battery}%"  // 工作状态+电量
        else             -> "⚪ 未工作  🔋${device.battery}%"     // 待机+电量
    }
}

// 上报时间格式化器：HH:mm:ss（统一用 TimeFormats 单例）
private val reportTimeFormatter get() = TimeFormats.TIME_HHMMSS

// 把上报时间戳格式化为可读时间字符串
private fun formatReportTime(ts: Long) = reportTimeFormatter.format(Date(ts))
