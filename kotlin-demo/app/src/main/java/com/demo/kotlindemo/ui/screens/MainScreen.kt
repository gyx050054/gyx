/**
 * ============================================================
 * 【文件职责】
 * App 主界面（底部五 Tab：田块 / 设备 / 天气 / 消息 / 我的）。
 *  - 顶部 TopAppBar 标题随 Tab 切换；右侧操作区包含：
 *      田块管理（新增/删除多选，仅租户管理员 + 仅田块 Tab 显示）、
 *      设备新增（仅设备 Tab + 管理员）、告警铃铛（未确认红点数字）、
 *      任务管理入口（有任务且未访问时红点）、退出登录。
 *  - 内容区按 currentTab 分派：田块网格 / 设备列表 / 天气 / 消息（告警）/ 我的。
 *  - 维护本页全部弹窗状态：开关、批量、定时、新增田块、删除确认、
 *      新增设备、设备凭证、挂载/取下/改挂、删除设备、冲突清理。
 *  - 数据来自 FarmViewModel / TaskViewModel / AlarmViewModel / UserViewModel，
 *      导航动作通过回调上抛给上层 NavHost。
 *
 * 【数据流】
 * 1) 进入页面 LaunchedEffect(Unit)：立刻 loadFields() / loadAllDevices() / loadTasks()，
 *    然后 while(true) 每 10s 轮询 refreshFromApi()（真实 API 拉遥测）+ loadTasks()（同步任务红点）。
 * 2) 顶栏告警红点 LaunchedEffect(alarmViewModel)：每 15s refreshUnread() 刷新未确认告警数。
 * 3) 用户交互 → ViewModel：
 *    - 设备：toggleDevice / 长按 SwitchDialog / 定时 TimeRangeDialog→addTask /
 *      批量 BatchControlDialog→toggleDevice(forceOn)+addTasksBatch；
 *    - 田块：createField / 多选 deleteFields；
 *    - 设备管理：createDevice(成功后 TokenDialog 展示 accessToken) / mountDevice /
 *      unmountDevice / remountDevice / deleteDevice；
 *    - 任务冲突：addTask/addTasksBatch 返回冲突时 setConflict()，UI 弹 AlertDialog，
 *      确认后预勾选该设备任务并跳转 TaskManagementScreen（onTaskManageClick）。
 * 4) 角色可见性由 farmViewModel.isAdmin 控制（田块/设备管理入口、消息 Tab 规则入口）。
 * 5) 导航回调（onFieldClick / onTaskManageClick / onDeviceHistoryClick /
 *      onUserManageClick / onAlarmClick / onManageRules / onLogout）由上层注入，
 *      本页只负责「触发 ViewModel + 更新状态」，不自行执行导航。
 */
// 声明包名，这个文件属于页面层
package com.demo.kotlindemo.ui.screens

// 导入背景绘制
// 导入点击
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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.ExitToApp
// 导入 Material3 组件
import androidx.compose.material3.*
// 导入运行时核心
import androidx.compose.runtime.*
// 导入对齐方式
// 导入修饰符
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
// 导入绘制裁剪
// 导入边框（选中田块高亮用）
// 导入颜色类
// 导入剪贴板（复制设备凭证用）
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入文字溢出处理
// 导入 dp
import androidx.compose.ui.unit.dp
// 导入数据模型
import com.demo.kotlindemo.data.model.Device
import com.demo.kotlindemo.data.model.DeviceType
import com.demo.kotlindemo.data.model.isSensor
// 需求3：消息Tab展示告警，需导入告警 DTO
import com.demo.kotlindemo.data.dto.AlarmRecordDto
import com.demo.kotlindemo.util.TimeFormats
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
import com.demo.kotlindemo.viewmodel.AlarmViewModel
// 第三版重构：田块/设备列表组件与弹窗拆到 ui.components
import com.demo.kotlindemo.ui.components.FieldsGridContent
import com.demo.kotlindemo.ui.components.AddFieldDialog
import com.demo.kotlindemo.ui.components.DevicesListContent
import com.demo.kotlindemo.ui.components.AddDeviceDialog
import com.demo.kotlindemo.ui.components.TokenDialog
import com.demo.kotlindemo.ui.components.MountFieldDialog

// 导入协程
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
// 导入日期格式化（显示最近上报时间）

/**
 * 底部导航栏的 Tab 枚举
 */
// 定义 tab 类型
private enum class MainTab {
    FIELDS,   // 区块 tab
    DEVICES,  // 设备 tab
    WEATHER,  // 天气 tab（第三代第一版 §4.3）
    MESSAGES, // 消息 tab（需求3：告警放这里）
    MINE      // 我的 tab（第二版新增：身份/退出/使用者管理）
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
    onDeviceHistoryClick: (String, String, String) -> Unit,  // 点击传感器查看历史（deviceId, name, type）
    onUserManageClick: () -> Unit,    // 使用者管理入口（第二版）
    alarmViewModel: AlarmViewModel,   // 告警 ViewModel（第四版：顶栏红点）
    onAlarmClick: () -> Unit,         // 点击告警铃铛回调
    onManageRules: () -> Unit         // 进入告警规则管理（需求3：消息Tab内）
) {
    // ── 当前选中的 tab ──
    // remember 记住当前选中的 tab，默认是区块
    var currentTab by remember { mutableStateOf(MainTab.FIELDS) }

    // 告警铃铛红点：周期刷新未确认计数（第四版）
    LaunchedEffect(alarmViewModel) {
        while (true) {
            alarmViewModel.refreshUnread()
            kotlinx.coroutines.delay(15000)
        }
    }

    // ── 弹窗状态 ──
    // 开关弹窗：null=不显示，非null=要显示的设备
    var showSwitchDialog by remember { mutableStateOf<Device?>(null) }
    // 批量操作弹窗：true=显示
    var showBatchDialog by remember { mutableStateOf(false) }
    // 定时任务弹窗：null=不显示，非null=要设置的设备
    var showTimeDialog by remember { mutableStateOf<Device?>(null) }

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
    // 取下目标（已挂载设备，第三版）
    var unmountTarget by remember { mutableStateOf<Device?>(null) }
    // 改挂目标（已挂载设备，第三版）
    var remountTarget by remember { mutableStateOf<Device?>(null) }
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
                        when (currentTab) {
                            MainTab.FIELDS -> "🌾 田块总览"
                            MainTab.WEATHER -> "🌤 天气"
                            MainTab.DEVICES -> "📡 所有设备信息"
                            MainTab.MESSAGES -> "🔔 消息"
                            MainTab.MINE -> "我的"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                // 右侧操作按钮
                actions = {
                    // 田块 tab 的田块管理按钮（第二版：仅租户管理员可见）
                    if (currentTab == MainTab.FIELDS && farmViewModel.isAdmin) {
                        if (selectionMode) {
                            // 选择模式：取消 + 删除选中
                            IconButton(onClick = {
                                selectionMode = false; selectedFieldIds = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "取消选择")
                            }
                            IconButton(
                                onClick = {
                                    if (selectedFieldIds.isNotEmpty()) showDeleteConfirm = true
                                },
                                enabled = selectedFieldIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除选中田块")
                            }
                        } else {
                            // 普通模式：新增 + 删除（进入选择模式）
                            IconButton(onClick = { showAddFieldDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "新增田块")
                            }
                            IconButton(onClick = {
                                selectionMode = true; selectedFieldIds = emptySet()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除田块")
                            }
                        }
                    }
                    // 设备 tab：新增设备入口（第二版，仅租户管理员可见）
                    if (currentTab == MainTab.DEVICES && farmViewModel.isAdmin) {
                        IconButton(onClick = { showAddDeviceDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新增设备")
                        }
                    }
                    // 告警铃铛入口（第四版：unreadCount>0 显示红点数字，点击进告警页）
                    IconButton(onClick = onAlarmClick) {
                        BadgedBox(
                            badge = {
                                if (alarmViewModel.unreadCount > 0) {
                                    Badge { Text(if (alarmViewModel.unreadCount > 99) "99+" else alarmViewModel.unreadCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "告警")
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
                            Icon(
                                Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = "任务管理"
                            )
                        }
                    }
                    // 退出登录按钮（第三版：清 token + 清空三个 ViewModel，防切换账号串号/残留）
                    IconButton(onClick = {
                        farmViewModel.logout()       // 清 JWT + 田块/设备缓存
                        taskViewModel.clear()        // 清任务列表 + 租户缓存
                        userViewModel.clear()        // 清成员/家庭/身份
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
                // 天气 tab 按钮（第三代第一版 §4.3）
                NavigationBarItem(
                    selected = currentTab == MainTab.WEATHER,
                    onClick = { currentTab = MainTab.WEATHER },
                    icon = { Icon(Icons.Default.WbSunny, contentDescription = null) },
                    label = { Text("天气") }
                )
                // 消息 tab 按钮（需求3：告警消息放这里，带红点）
                NavigationBarItem(
                    selected = currentTab == MainTab.MESSAGES,
                    onClick = { currentTab = MainTab.MESSAGES },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (alarmViewModel.unreadCount > 0) {
                                    Badge { Text(if (alarmViewModel.unreadCount > 99) "99+" else alarmViewModel.unreadCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null)
                        }
                    },
                    label = { Text("消息") }
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
                    onHistoryClick = { id, name, type -> onDeviceHistoryClick(id, name, type) },  // 查看历史
                    onMount = { mountDeviceTarget = it },     // 挂载到田块（自由设备）
                    onUnmount = { unmountTarget = it },       // 取下设备（已挂载→自由，第三版）
                    onRemount = { remountTarget = it },       // 改挂到别的田块（第三版）
                    onDelete = { deleteDeviceTarget = it },    // 删除设备
                    isAdmin = farmViewModel.isAdmin             // 是否管理员（员工隐藏挂载/删除）
                )
                // 天气 tab（第三代第一版 §4.3）
                MainTab.WEATHER -> WeatherContent()
                // 消息 tab（需求3：告警放这里，替代单独告警页）
                MainTab.MESSAGES -> MessageTabContent(
                    alarmViewModel = alarmViewModel,
                    onManageRules = onManageRules
                )
                // 我的 tab（第二版新增）：身份/使用者管理/退出
                MainTab.MINE -> MineScreen(
                    userViewModel = userViewModel,
                    onUserManageClick = onUserManageClick,  // 使用者管理入口
                    onLogout = {
                        farmViewModel.logout()       // 清 JWT + 田块/设备缓存
                        taskViewModel.clear()        // 清任务列表 + 租户缓存
                        userViewModel.clear()        // 清成员/家庭/身份
                        TokenStore.resetTasksVisited()  // 重置任务红点（第三版）
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
    // 批量操作弹窗：勾选设备后对选中设备批量操作
    if (showBatchDialog) {
        BatchControlDialog(
            // 只列出可操作设备（电动阀等，不含温湿度传感器）
            devices = farmViewModel.devices.filter { !it.type.isSensor },
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
                ) { ok, msg ->                             // 第三版：批量冲突 → 记录冲突（无法定位单设备）
                    if (!ok && msg.contains("冲突")) {
                        taskViewModel.setConflict("所选设备", "")
                    }
                }
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

    // ── 取下设备确认弹窗（第三版：已挂载→自由设备）──
    unmountTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { unmountTarget = null },
            title = { Text("确认取下设备？") },
            text = { Text("将设备「${device.name}」从田块取下，变为自由设备（可重新挂载）。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    val fid = device.fieldId
                    unmountTarget = null
                    if (!fid.isNullOrEmpty()) {
                        farmViewModel.unmountDevice(device.id, fid) { ok, msg -> devOpMessage = msg }
                    }
                }) { Text("取下", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { unmountTarget = null }) { Text("取消") } }
        )
    }

    // ── 改挂到别的田块弹窗（第三版：先取下再挂新田块）──
    remountTarget?.let { device ->
        MountFieldDialog(
            fields = farmViewModel.fields.filter { it.id != device.fieldId },  // 排除当前田块
            onDismiss = { remountTarget = null },
            onConfirm = { fieldId ->
                val oldFid = device.fieldId
                if (!oldFid.isNullOrEmpty()) {
                    farmViewModel.remountDevice(device.id, oldFid, fieldId) { ok, msg -> devOpMessage = msg }
                }
                remountTarget = null
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
// 消息 Tab（需求3：告警消息集中展示，替代单独告警页）
// ═══════════════════════════════════════════════════════════
@Composable
fun MessageTabContent(
    alarmViewModel: AlarmViewModel,
    onManageRules: () -> Unit
) {
    // 进入 Tab 时加载告警（含未确认红点）
    LaunchedEffect(Unit) {
        alarmViewModel.loadAlarms()
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部工具条：刷新 + 规则管理（管理员可见）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "未确认 ${alarmViewModel.unreadCount} 条",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { alarmViewModel.loadAlarms() }) { Text("🔄 刷新") }
            if (alarmViewModel.isAdmin) {
                TextButton(onClick = onManageRules) { Text("⚙️ 规则") }
            }
        }

        val alarms = alarmViewModel.alarms
        if (alarms.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("暂无告警消息", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alarms, key = { it.id }) { alarm ->
                    MessageAlarmCard(alarm, onAck = { alarmViewModel.ack(alarm.id) })
                }
            }
        }
    }
}

/** 消息Tab内的告警卡片：级别色标 + 消息 + 设备 + 时间 + 确认 */
@Composable
private fun MessageAlarmCard(alarm: AlarmRecordDto, onAck: () -> Unit) {
    val color = when (alarm.severity.uppercase()) {
        "HIGH" -> Color(0xFFD32F2F)
        "MEDIUM" -> Color(0xFFFF9800)
        else -> Color(0xFFFFEB3B)
    }
    val statusText = when (alarm.status) {
        "RESOLVED" -> "已恢复"
        "ACKNOWLEDGED" -> "已确认"
        else -> "未确认"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color))
                Spacer(Modifier.width(8.dp))
                Text(
                    "【${alarm.severity}】 $statusText",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(alarm.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${alarm.deviceName} · ${TimeFormats.DATETIME.format(java.util.Date(alarm.firstAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (alarm.status == "ACTIVE") {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAck, modifier = Modifier.fillMaxWidth()) {
                    Text("确认")
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════════
// 区块网格（3列）
// ═══════════════════════════════════════════════════════════

