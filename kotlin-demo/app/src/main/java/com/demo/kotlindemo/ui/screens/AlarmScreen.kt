// 声明包名：UI 页面层
package com.demo.kotlindemo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.demo.kotlindemo.data.dto.AlarmRecordDto
import com.demo.kotlindemo.util.TimeFormats
import com.demo.kotlindemo.viewmodel.AlarmViewModel

/**
 * 告警列表页（自研告警引擎，第四版）
 *
 * - 展示本租户全部告警记录：级别色标（HIGH=红/MEDIUM=橙/LOW=黄）、设备、消息、触发时间
 * - 每条可「确认」（红点计数减少），条件恢复后自动标已恢复（RESOLVED）
 * - 管理员可进入「规则管理」页
 *
 * @param alarmViewModel 告警 ViewModel（共享实例）
 * @param onBack 返回回调
 * @param onManageRules 进入规则管理页回调（仅管理员有此入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    alarmViewModel: AlarmViewModel,
    onBack: () -> Unit,
    onManageRules: () -> Unit
) {
    // 首次进入 / 刷新时加载
    LaunchedEffect(Unit) {
        alarmViewModel.loadAlarms()
        alarmViewModel.loadRules()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🚨 告警") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { alarmViewModel.loadAlarms() }) { Text("🔄 刷新") }
                    if (alarmViewModel.isAdmin) {
                        TextButton(onClick = onManageRules) { Text("⚙️ 规则") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            alarmViewModel.lastMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            val alarms = alarmViewModel.alarms
            if (alarms.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("暂无告警", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmCard(alarm = alarm, onAck = { alarmViewModel.ack(alarm.id) })
                    }
                }
            }
        }
    }
}

/** 单条告警卡片：级别色标 + 消息 + 设备 + 时间 + 确认按钮 */
@Composable
private fun AlarmCard(alarm: AlarmRecordDto, onAck: () -> Unit) {
    val color = when (alarm.severity.uppercase()) {
        "HIGH" -> MaterialTheme.colorScheme.error
        "MEDIUM" -> Color(0xFFFF9800)
        else -> Color(0xFFFFEB3B)
    }
    val statusText = when (alarm.status) {
        "RESOLVED" -> "已恢复"
        "ACKNOWLEDGED" -> "已确认"
        else -> "未确认"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                // 级别色点
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
