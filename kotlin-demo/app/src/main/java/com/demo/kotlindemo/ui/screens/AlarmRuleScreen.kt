/**
 * ============================================================
 * 【文件职责】
 * 告警规则管理页（自研告警引擎，第 4 版，仅管理员）。
 *  - 规则列表：名称、指标、运算符+阈值、级别、启用开关、删除。
 *  - 「新增」按钮弹 CreateRuleDialog 收集：指标（预设 FilterChip 单排滚动）/ 高于·低于 /
 *    阈值 / 规则名（可选，留空自动生成）；提交时按预设映射 deviceType、metric、
 *    operator（gt/lt）、severity（HIGH/MEDIUM）与提示消息。同一条规则会自动推导级别：
 *    高于=HIGH、低于=MEDIUM。
 *
 * 【数据流】
 * 1) LaunchedEffect(Unit)：进入页面 loadRules() 拉取规则列表。
 * 2) 展示数据：alarmViewModel.rules / lastMessage。
 * 3) 用户交互 → AlarmViewModel：
 *    - 启用开关 toggleRule(id, !enabled) / 删除 deleteRule(id) /
 *      新增 createRule(name, deviceType, metric, op, threshold, severity, message)。
 * 4) 运算符显示 operatorText(op)：gt→>  eq→=  ne→≠  其他→<。
 * 5) 导航回调 onBack 由上层注入。
 */
// 声明包名：UI 页面层
package com.demo.kotlindemo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.demo.kotlindemo.data.dto.AlarmRuleDto
import com.demo.kotlindemo.viewmodel.AlarmViewModel

/**
 * 告警规则管理页（自研告警引擎，第四版，仅管理员）
 *
 * - 规则列表：名称、指标、运算符+阈值、级别、启用开关、删除
 * - 「新增规则」按钮弹表单：名称 / 设备类型 / 指标 / 运算符 / 阈值 / 级别 / 提示消息
 *
 * @param alarmViewModel 告警 ViewModel（共享实例）
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmRuleScreen(
    alarmViewModel: AlarmViewModel,
    onBack: () -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alarmViewModel.loadRules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("⚙️ 告警规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        showCreate = true
                    }) { Text("➕ 新增") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            alarmViewModel.lastMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            val rules = alarmViewModel.rules
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text("暂无规则，点右上角「新增」创建", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            onToggle = { alarmViewModel.toggleRule(rule.id, !rule.enabled) },
                            onDelete = { alarmViewModel.deleteRule(rule.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateRuleDialog(
            onDismiss = { showCreate = false },
            onSubmit = { name, deviceType, metric, op, threshold, severity, message ->
                alarmViewModel.createRule(name, deviceType, metric, op, threshold, severity, message) { ok, _ ->
                    if (ok) showCreate = false
                }
            }
        )
    }
}

/** 单条规则卡片 */
@Composable
private fun RuleCard(rule: AlarmRuleDto, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${rule.deviceType} · ${rule.metric} ${operatorText(rule.operator)} ${rule.threshold?.let { "%.1f".format(it) } ?: "-"} · ${rule.severity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                rule.message.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it.replace("{deviceName}", "设备").replace("{deviceId}", "ID"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDelete) { Text("🗑") }
            Spacer(Modifier.width(4.dp))
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        }
    }
}

/** 运算符中文显示 */
private fun operatorText(op: String?) = when (op) {
    "gt" -> ">"
    "eq" -> "="
    "ne" -> "≠"
    else -> "<"
}

/** 新增规则弹窗：表单收集字段后回调提交 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRuleDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String, Double, String, String) -> Unit
) {
    // 极简表单（第三/四版）：选指标 + 高于/低于 + 阈值，其余自动
    data class Preset(val label: String, val deviceType: String, val metric: String, val unit: String)
    val presetsObj = listOf(
        Preset("土壤盐分", "SOIL_MOISTURE", "soilSalinity", "ppm"),
        Preset("空气温度", "TEMPERATURE_HUMIDITY", "temperature", "℃"),
        Preset("空气湿度", "TEMPERATURE_HUMIDITY", "humidity", "%RH"),
        Preset("阀门电量", "VALVE", "batteryLevel", "%"),
        Preset("管道水压", "VALVE", "waterPressure", "MPa"),
        Preset("瞬时流量", "VALVE", "instantFlow", "L/min")
    )

    var presetIdx by remember { mutableStateOf(0) }
    var isAbove by remember { mutableStateOf(false) }   // true=高于, false=低于
    var threshold by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    val p = presetsObj[presetIdx]
    val operatorVal = if (isAbove) "gt" else "lt"
    val severityVal = if (isAbove) "HIGH" else "MEDIUM"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增告警规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 指标下拉（FilterChip 单排滚动选择）
                Text("监控指标", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    presetsObj.forEachIndexed { i, pr ->
                        FilterChip(selected = presetIdx == i, onClick = { presetIdx = i }, label = { Text(pr.label) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // 高于 / 低于
                Text("触发条件", style = MaterialTheme.typography.labelMedium)
                Row {
                    FilterChip(selected = !isAbove, onClick = { isAbove = false }, label = { Text("低于") }, modifier = Modifier.padding(end = 6.dp))
                    FilterChip(selected = isAbove, onClick = { isAbove = true }, label = { Text("高于") }, modifier = Modifier.padding(end = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                // 阈值
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    label = { Text("阈值（${p.unit}）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (customName.isBlank()) "规则名：${p.label} ${if (isAbove) "高于" else "低于"} ${threshold}${p.unit}"
                    else "规则名：$customName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("规则名（可选，留空自动生成）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = threshold.trim().toDoubleOrNull()
                if (t == null) return@TextButton
                val autoName = if (customName.isBlank())
                    "${p.label} ${if (isAbove) "高于" else "低于"} ${t}${p.unit}" else customName.trim()
                val msg = "${p.label} ${if (isAbove) "高于" else "低于"} ${t}${p.unit}"
                onSubmit(autoName, p.deviceType, p.metric, operatorVal, t, severityVal, msg)
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
