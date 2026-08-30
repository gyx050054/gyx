/**
 * 【文件职责】
 * 字体排版系统：用 Material3 的 Typography 定义 APP 的 5 级文本样式
 * （display/headline/title/body/label）中各常用档位的字号、字重、行高与字间距，
 * 未覆盖的样式走 Material3 默认值。
 *
 * 【数据流】
 * 定义：在此统一声明 Typography，作为全局排版风格唯一来源。
 * 消费：ui.theme.Theme 中 MaterialTheme(typography = Typography) 把排版向下传递，
 *       页面中的 Text 经 MaterialTheme.typography.** 或 MaterialTheme.typography 间接取用。
 */
// =============================================================================
// 📄 Type.kt
// 作用：定义 App 的字体排版系统 — 字号、字重、行高等
//       Material3 的 Typography 提供了 5 级文本样式：
//       display / headline / title / body / label
//       每级又有 small / medium / large 三种尺寸
// =============================================================================
package com.demo.kotlindemo.ui.theme

import androidx.compose.material3.Typography           // Material3 的排版类
import androidx.compose.ui.text.TextStyle               // 单个文本样式
import androidx.compose.ui.text.font.FontFamily          // 字体家族
import androidx.compose.ui.text.font.FontWeight          // 字重（粗细）
import androidx.compose.ui.unit.sp                       // sp = 缩放像素（字号单位）

// ═══════════════════════════════════════════════════════
// Typography — 全局排版风格
// ═══════════════════════════════════════════════════════
// 我们只覆盖了部分常用的样式，没写的会走 Material3 默认值
val Typography = Typography(

    // displayLarge：最大的标题，用于首页大数字/品牌标语
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,    // 默认字体（系统字体）
        fontWeight = FontWeight.Bold,       // 加粗
        fontSize = 36.sp,                   // 36sp — 非常大的字
        lineHeight = 44.sp,                 // 行高 44sp（略大于字号，保证可读性）
        letterSpacing = (-0.25).sp          // 字间距 -0.25（大标题通常收紧一点）
    ),

    // headlineMedium：二级大标题，用于页面标题
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,   // 半粗体
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),

    // titleLarge：三级标题，用于卡片/区域标题
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,     // 中等
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),

    // bodyLarge：正文最大的尺寸，用于卡片内容、列表项
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,     // 正常
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp             // 正文字间距稍大一点更易读
    ),

    // labelLarge：按钮/标签/小号说明文字
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
)
// 💡 如果你需要更多样式（如 bodyMedium、labelSmall），
//    只要在 Typography() 里加上对应的属性就行
