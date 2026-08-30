/**
 * ═══════════════════════════════════════════════════════════════
 * 【文件职责】
 * 田块域网格/弹窗展示组件（从 MainScreen.kt 拆分，田块域高内聚）：
 *  - FieldsGridContent ：田块 3 列懒加载网格（顶部提示条 + 田块卡片）
 *  - FieldCard         ：单块田块卡片（名称/作物/在线圆点/运行设备数）
 *  - AddFieldDialog    ：新增田块弹窗（输入名称，确认后调 createField）
 *
 * 【数据流】
 * FieldsGridContent 接收田块列表与选择模式，把用户操作上抛：
 *  - 普通模式：onFieldClick(id) 点击进入田块详情
 *  - 选择模式：onToggleSelect(id) 切换勾选（用于删除）
 *  selectionMode/selectedIds 由父层传入，卡片根据 selected 决定是否高亮边框。
 * FieldCard 为纯展示，onClick 由父层根据 selectionMode 绑定不同逻辑；
 * 有运行设备(activeCount>0)时卡片用 primaryContainer 色、在线圆点绿色，否则用表面色。
 * AddFieldDialog 仅维护 name 的输入状态，确认时把「田块名」经 onConfirm 上抛，
 * 由父层调用 FarmViewModel.createField 完成创建；name 为空时禁止提交。
 * ═══════════════════════════════════════════════════════════════
 */
// 包声明：田块列表组件（第三版重构：从 MainScreen.kt 拆出，田块域高内聚）
package com.demo.kotlindemo.ui.components

// ═══════════════════════════════════════════════════════════
// import 区（与原 MainScreen.kt 一致，含本文件全部组件所需）
// ═══════════════════════════════════════════════════════════
// 导入背景绘制
import androidx.compose.foundation.background
// 导入点击
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
// 导入列表的 items 扩展函数
import androidx.compose.foundation.lazy.items
// 导入圆角形状
import androidx.compose.foundation.shape.RoundedCornerShape
// 导入 Material 图标
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
// 导入字重
import androidx.compose.ui.text.font.FontWeight
// 导入文字溢出处理
// 导入 dp
import androidx.compose.ui.unit.dp
// 导入数据模型
import com.demo.kotlindemo.data.model.Field
// 导入「我的」页与用户 ViewModel（第二版）
// 导入弹窗组件
// 导入 ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
// 导入 TokenStore（任务红点状态，第二版）
import com.demo.kotlindemo.data.api.TokenStore
// 导入协程
// 导入日期格式化（显示最近上报时间）

@Composable
/**
     * 田块网格内容：田块卡片列表 + 新增田块入口（管理员可见）
     */
internal fun FieldsGridContent(
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
/**
     * 新增田块弹窗：名称输入 + 提交回调
     */
internal fun AddFieldDialog(
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
/**
     * 单块田块卡片：名称 + 设备数 + 土壤墒情角标（可选，管理员删除模式）
     */
internal fun FieldCard(
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
                    Modifier.border(
                        3.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(12.dp)
                    )
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

