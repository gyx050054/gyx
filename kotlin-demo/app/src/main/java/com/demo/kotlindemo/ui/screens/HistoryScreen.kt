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
import com.demo.kotlindemo.data.api.FarmRepository
import com.demo.kotlindemo.data.dto.TelemetryItem
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
 * 温度湿度计 — 历史数据页面（需求文档 3.4）
 *
 * - 折线图展示历史温度、湿度变化趋势
 * - 支持时间范围筛选：近 1 小时 / 近 24 小时 / 近 7 天
 * - 鼠标（点击）可查看具体数值和时间（MPAndroidChart 自带十字线+高亮）
 *
 * @param deviceId 设备 ID（ThingsBoard deviceId）
 * @param deviceName 设备名称（标题显示）
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    deviceId: String,
    deviceName: String,
    onBack: () -> Unit
) {
    val repository = remember { FarmRepository() }
    val scope = rememberCoroutineScope()

    // 时间范围选择：毫秒
    val ranges = listOf(
        Triple("近 1 小时", 3_600_000L, 60_000L),
        Triple("近 24 小时", 86_400_000L, 3_600_000L),
        Triple("近 7 天", 604_800_000L, 86_400_000L)
    )
    var selectedRange by remember { mutableStateOf(0) }

    // 图表数据（温度/湿度两个系列）
    var tempPoints by remember { mutableStateOf<List<TelemetryItem>>(emptyList()) }
    var humPoints by remember { mutableStateOf<List<TelemetryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 加载历史数据
    fun loadHistory() {
        scope.launch {
            loading = true
            error = null
            try {
                val (_, span, interval) = ranges[selectedRange]
                val end = System.currentTimeMillis()
                val start = end - span
                val temp = repository.loadHistory(deviceId, "temperature", start, end, interval)["temperature"] ?: emptyList()
                val hum = repository.loadHistory(deviceId, "humidity", start, end, interval)["humidity"] ?: emptyList()
                tempPoints = temp
                humPoints = hum
            } catch (e: Exception) {
                error = "加载历史数据失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    // 首次进入 + 切换时间范围时加载
    LaunchedEffect(selectedRange) {
        loadHistory()
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
            // 时间范围筛选
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
                // 温度曲线
                Text("🌡 温度（℃）", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                LineChartView(tempPoints, lineColor = Color.rgb(244, 67, 54), Modifier.fillMaxWidth().height(220.dp))

                Spacer(Modifier.height(16.dp))

                // 湿度曲线
                Text("💧 湿度（%RH）", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                LineChartView(humPoints, lineColor = Color.rgb(33, 150, 243), Modifier.fillMaxWidth().height(220.dp))
            }
        }
    }
}

/**
 * 折线图组件（MPAndroidChart 封装）
 * @param points 数据点（ts/value）
 * @param lineColor 折线颜色
 */
@Composable
private fun LineChartView(
    points: List<TelemetryItem>,
    lineColor: Int,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

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
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt().coerceIn(0, points.size - 1)
                    return timeFormatter.format(Date(points[index].ts))
                }
            }
            chart.invalidate()
        }
    )
}
