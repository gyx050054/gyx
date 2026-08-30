/**
 * ============================================================
 * 【文件职责】
 * 历史数据页（需求文档 3.4）。按设备类型展示对应遥测曲线：
 *  - SENSOR（温湿度计）：温度℃、湿度%RH；
 *  - SOIL_SENSOR（墒情检测器）：盐分 ppm、pH。
 *  - 两条曲线用 MPAndroidChart 折线图渲染，支持时间范围筛选：
 *    近 1 小时 / 近 24 小时 / 近 7 天 / 自定义（yyyy-MM-dd HH:mm）。
 *  - 自定义时间段按跨度自动选择聚合间隔：≤6h→1min、≤48h→5min、更长→1h。
 *
 * 【数据流】
 * 1) repository 是本页直接 new 的 ThingsBoardRepository（非共享 ViewModel），
 *    scope = rememberCoroutineScope() 用于发起协程。
 * 2) 时间范围：selectedRange 指示预设下标，ranges[idx] = (标签, 跨度ms, 聚合间隔ms)；
 *    selectedRange==ranges.size 时为用户自定义（customStart/customEnd 输入）。
 * 3) 依据设备类型确定两条遥测键：isSoil→soilSalinity/soilPh，否则 temperature/humidity。
 * 4) loadHistory() 在 scope.launch 内：先算 start/end/interval → repository.loadHistory(
 *    deviceId, key, start, end, interval) 调 ThingsBoard timeseries 聚合接口，
 *    按 key 取回 List<TelemetryItem> 存到 tempPoints/humPoints，异常写 error。
 * 5) LaunchedEffect(selectedRange)：预设下标切换时自动 loadHistory()；自定义由「查询」按钮触发。
 * 6) LineChartView 把 TelemetryItem 转成 Entry 渲染，横轴用时间格式化器显示月日时分。
 * 7) 导航回调 onBack 由上层注入。
 */
package com.demo.kotlindemo.ui.screens

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.demo.kotlindemo.data.api.ThingsBoardRepository
import com.demo.kotlindemo.data.dto.TelemetryItem
import com.demo.kotlindemo.util.TimeFormats
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史数据页面（需求文档 3.4）
 *
 * 按设备类型展示对应遥测曲线：
 *  - 温度湿度计（SENSOR）：温度℃、湿度%RH
 *  - 土壤墒情检测器（SOIL_SENSOR）：盐分 ppm、pH（第三代第一版 §3.1）
 *
 * - 折线图展示两条遥测键的变化趋势
 * - 支持时间范围筛选：近 1 小时 / 近 24 小时 / 近 7 天 / 自定义
 * - 可点击查看具体数值和时间（MPAndroidChart 自带十字线+高亮）
 *
 * @param deviceId 设备 ID（ThingsBoard deviceId）
 * @param deviceName 设备名称（标题显示）
 * @param deviceType 设备类型（DeviceType.name：SENSOR / SOIL_SENSOR）
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onBack: () -> Unit
) {
    val repository = remember { ThingsBoardRepository() }
    val scope = rememberCoroutineScope()

    // 时间范围选择：毫秒
    val ranges = listOf(
        Triple("近 1 小时", 3_600_000L, 60_000L),
        Triple("近 24 小时", 86_400_000L, 3_600_000L),
        Triple("近 7 天", 604_800_000L, 86_400_000L)
    )
    var selectedRange by remember { mutableStateOf(0) }

    // 自定义时间段输入（需求文档 3.4）
    var customStart by remember { mutableStateOf("") }
    var customEnd by remember { mutableStateOf("") }

    // 依据设备类型确定展示的两条遥测键：SENSOR=温度/湿度，SOIL_SENSOR=盐分/pH
    val isSoil = deviceType == "SOIL_SENSOR"
    val keyA = if (isSoil) "soilSalinity" else "temperature"
    val keyB = if (isSoil) "soilPh" else "humidity"

    // 图表数据（两条遥测曲线系列）
    var tempPoints by remember { mutableStateOf<List<TelemetryItem>>(emptyList()) }
    var humPoints by remember { mutableStateOf<List<TelemetryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 加载历史数据
/**
     * 按当前时间范围加载历史遥测（设备/起止/聚合间隔来自 UI 状态）
     */
    fun loadHistory() {
        scope.launch {
            loading = true
            error = null
            try {
                val (start, end, interval) = if (selectedRange < ranges.size) {
                    // 预设范围：近 1 小时 / 24 小时 / 7 天
                    val (_, span, interval) = ranges[selectedRange]
                    val end = System.currentTimeMillis()
                    Triple(end - span, end, interval)
                } else {
                    // 自定义时间段（需求 3.4）：解析用户输入
                    val s = parseCustomTime(customStart)
                    val e = parseCustomTime(customEnd)
                    if (s == null || e == null) {
                        error = "自定义时间格式错误，请使用 yyyy-MM-dd HH:mm"
                        loading = false
                        return@launch
                    }
                    if (e <= s) {
                        error = "结束时间需晚于开始时间"
                        loading = false
                        return@launch
                    }
                    val span = e - s
                    val interval = when {
                        span <= 6 * 3_600_000L -> 60_000L        // ≤6小时：1分钟聚合
                        span <= 48 * 3_600_000L -> 5 * 60_000L   // ≤48小时：5分钟聚合
                        else -> 3_600_000L                        // 更长时间：1小时聚合
                    }
                    Triple(s, e, interval)
                }
                val temp = repository.loadHistory(deviceId, keyA, start, end, interval)[keyA] ?: emptyList()
                val hum = repository.loadHistory(deviceId, keyB, start, end, interval)[keyB] ?: emptyList()
                tempPoints = temp
                humPoints = hum
            } catch (e: Exception) {
                error = "加载历史数据失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // 首次进入 + 切换时间范围时加载（自定义时间段由用户输入后点「查询」触发）
    LaunchedEffect(selectedRange) {
        if (selectedRange < ranges.size) loadHistory()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📈 $deviceName · 历史数据") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // 时间范围筛选（需求 3.4：预设 + 自定义）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ranges.forEachIndexed { index, (label, _, _) ->
                    FilterChip(
                        selected = selectedRange == index,
                        onClick = { selectedRange = index },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
                FilterChip(
                    selected = selectedRange == ranges.size,
                    onClick = { selectedRange = ranges.size },
                    label = { Text("自定义") },
                    modifier = Modifier.weight(1f)
                )
            }

            // 自定义时间段输入框（选中「自定义」时显示）
            if (selectedRange == ranges.size) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customStart,
                    onValueChange = { customStart = it },
                    label = { Text("开始时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customEnd,
                    onValueChange = { customEnd = it },
                    label = { Text("结束时间") },
                    placeholder = { Text("yyyy-MM-dd HH:mm") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { loadHistory() }) { Text("🔍 查询") }
            }

            Spacer(Modifier.height(12.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // 第一条曲线（温度℃ 或 土壤盐分 ppm）
                Text(
                    if (isSoil) "🌱 盐分（ppm）" else "🌡 温度（℃）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                LineChartView(tempPoints, lineColor = Color.rgb(244, 67, 54), Modifier.fillMaxWidth().height(220.dp))

                Spacer(Modifier.height(16.dp))

                // 第二条曲线（湿度%RH 或 pH）
                Text(
                    if (isSoil) "🧪 pH" else "💧 湿度（%RH）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                LineChartView(humPoints, lineColor = Color.rgb(33, 150, 243), Modifier.fillMaxWidth().height(220.dp))
            }
        }
    }
}

// ── 工具函数 ──

// 自定义时间解析格式：yyyy-MM-dd HH:mm（统一用 TimeFormats 单例）
private val customTimeFormatter get() = TimeFormats.DATETIME

/** 解析自定义时间字符串为毫秒时间戳；失败返回 null */
private fun parseCustomTime(text: String): Long? {
    if (text.isBlank()) return null
    return try {
        customTimeFormatter.parse(text.trim())?.time
    } catch (_: Exception) {
        null
    }
}

/**
 * 折线图组件（MPAndroidChart 封装）
 * @param points 数据点（ts/value）
 * @param lineColor 折线颜色
 */
@Composable
/**
     * 折线图组件（MPAndroidChart 封装）：渲染 ts/value 数据点，横轴用月日时分
     */
private fun LineChartView(
    points: List<TelemetryItem>,
    lineColor: Int,
    modifier: Modifier = Modifier
) {
    val timeFormatter = TimeFormats.MONTH_DAY_TIME

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LineChart(ctx).apply {
                // 基础配置
                description.isEnabled = false
                setTouchEnabled(true)          // 支持触摸查看数值
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(false)
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.setDrawGridLines(false)
                xAxis.granularity = 1f
                legend.isEnabled = true
                // 空数据提示
                if (points.isEmpty()) {
                    setNoDataText("暂无数据")
                }
            }
        },
        update = { chart ->
            val entries = points.mapIndexed { index, p ->
                Entry(index.toFloat(), p.value.toFloatOrNull() ?: 0f)
            }
            if (entries.isEmpty()) {
                chart.clear()
                chart.setNoDataText("暂无数据")
                return@AndroidView
            }
            val dataSet = LineDataSet(entries, "数值").apply {
                color = lineColor
                setCircleColor(lineColor)
                circleRadius = 2f
                setDrawValues(false)
                lineWidth = 2f
                setDrawCircleHole(false)
            }
            chart.data = LineData(dataSet)
            // x 轴标签显示时间
            chart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            /**
     * 折线图数值轴格式化回调：把 Float 值转成整数文本
     */
    override fun getFormattedValue(value: Float): String {
                    val index = value.toInt().coerceIn(0, points.size - 1)
                    return timeFormatter.format(Date(points[index].ts))
                }
            }
            chart.invalidate()
        }
    )
}
