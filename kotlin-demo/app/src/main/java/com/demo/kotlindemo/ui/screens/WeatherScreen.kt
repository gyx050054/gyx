/**
 * 【文件职责】WeatherContent —— 天气板块（第三代第一版 §4.3，底部第 4 个 Tab）。
 *   数据来自微服务端天气网关（Open-Meteo）：天气描述、气温（℃）、降水量（mm）、未来 1 小时降雨概率（%）；
 *   天气不可用降级显示「天气暂不可用」；按田块定位 —— 顶部可选田块，用所选田块坐标查各自天气（第三代 §6.3 坐标）。
 *
 * 【数据流】LaunchedEffect(Unit) 先经 ThingsBoardRepository().loadFields() 取田块列表（含坐标）：有田块默认加载第一块，无田块退化默认坐标(28.19,112.93)。
 *   loadAt(lat,lon) 调 TaskRepository().getWeather(lat,lon) 写入 weather，loading 控制加载态；切换田块 / 点刷新走 loadSelected()。
 *   渲染按 weather.success 分支：失败显示「天气暂不可用」卡；成功显示主天气卡（城市/描述/气温）+ 数据卡（降水量 / 1h 降雨概率）+ 高概率(≥80%)自动浇水任务跳过提示卡。
 *
 * 本文件同时提供两个私有 Composable：[InfoRow]（「标签-值」行）与 [horizontalDivider]（分隔线），供天气卡复用。
 */
// 声明包名：UI 页面层
package com.demo.kotlindemo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.demo.kotlindemo.data.api.TaskRepository
import com.demo.kotlindemo.data.api.ThingsBoardRepository
import com.demo.kotlindemo.data.dto.WeatherDto
import com.demo.kotlindemo.data.model.Field
import kotlinx.coroutines.launch

/**
 * 天气板块（第三代第一版 §4.3，底部第4个 tab）
 *
 * 数据来自微服务端天气网关（Open-Meteo）：
 *  - 天气描述、气温（℃）、降水量（mm）、未来 1 小时降雨概率（%）
 *  - 天气不可用降级显示"天气暂不可用"
 *  - **按田块定位**：顶部可选田块，用所选田块坐标查各自天气（第三代 §6.3 坐标）
 */
@Composable
fun WeatherContent() {
    var weather by remember { mutableStateOf<WeatherDto?>(null) }        // 当前查询到的天气数据（null 表示尚未到手）
    var loading by remember { mutableStateOf(false) }                   // 是否正在请求天气
    var fields by remember { mutableStateOf<List<Field>>(emptyList()) } // 田块列表（含坐标，用于按田块查天气）
    var selectedIdx by remember { mutableStateOf(0) }                   // 当前选中的田块下标（联动顶部 FilterChip）
    val scope = rememberCoroutineScope()                                // 协程作用域：发起天气/田块网络请求

    // 按经纬度加载天气：置 loading，经微服务端天气网关查询，异常时降级为「天气暂不可用」
    fun loadAt(lat: Double, lon: Double) {
        loading = true
        scope.launch {
            try {
                weather = TaskRepository().getWeather(lat.toString(), lon.toString())
            } catch (_: Exception) {
                weather = WeatherDto(success = false, message = "天气暂不可用")
            }
            loading = false
        }
    }

    // 加载当前选中田块的天气；无田块（越界）时回退默认坐标（长沙岳麓区农田带）
    fun loadSelected() {
        val f = fields.getOrNull(selectedIdx)
        loadAt(f?.lat ?: 28.19, f?.lon ?: 112.93)
    }

    // 进入先取田块列表（含坐标），并默认查第一块田的天气
    LaunchedEffect(Unit) {
        fields = try {
            ThingsBoardRepository().loadFields()
        } catch (_: Exception) {
            emptyList()
        }
        if (fields.isEmpty()) {
            // 无田块时退化为默认坐标（长沙岳麓区农田带）
            loadAt(28.19, 112.93)
        } else {
            loadSelected()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 标题行 + 刷新
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌤 今日天气", style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { loadSelected() }) { Text("🔄 刷新") }
        }
        // 田块选择（按田块定位天气，第三代 §6.3）
        if (fields.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                fields.forEachIndexed { i, f ->
                    FilterChip(
                        selected = selectedIdx == i,
                        onClick = { selectedIdx = i; loadSelected() },
                        label = { Text(f.name) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 首次加载中：尚未拿到任何天气，居中显示加载圈
        if (loading && weather == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val w = weather
            // 天气不可用（失败或未拿到）：显示降级提示卡
            if (w == null || !w.success) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "天气暂不可用，请稍后重试",
                        Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // 主天气卡：城市 + 描述 + 气温（需求4：显示田块所在城市）
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.city, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(w.weatherDesc, style = MaterialTheme.typography.displaySmall)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            if (w.temperature.isNaN()) "——" else "%.1f℃".format(w.temperature),
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 数据卡：降水量 + 1小时降雨概率
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        InfoRow("当前降水量", "%.1f mm".format(w.precipitation))
                        horizontalDivider()
                        InfoRow("未来 1 小时降雨概率",
                            if (w.precipProb1h != null) "${w.precipProb1h}%" else "——")
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 天气联动提示（自动任务遇雨跳过的场景）
                val prob = w.precipProb1h ?: 0
                if (prob >= 80) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "⚠️ 预报降雨概率 ${prob}%，自动浇水任务已当日跳过",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

// 通用「标签-值」行：标题左对齐占据剩余宽度，数值右对齐加粗，上下留 6dp，供数据卡内的「降水量 / 降雨概率」等条目复用
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    }
}

// 自定义分隔线：全宽、0.5dp 细线，上下留 4dp，用于在数据卡内分隔不同数据条目
@Composable
private fun horizontalDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        thickness = 0.5.dp
    )
}
