/**
 * 【文件职责】
 * 颜色常量表：集中定义 APP 的全部颜色值（Material3 紫色主题的调色板）。
 * 含暗色模式色板（Purple80/PurpleGrey80/Pink80）、亮色模式色板
 * （Purple40/PurpleGrey40/Pink40）及自定义扩展色（Surface/Primary 的明暗两套）。
 *
 * 【数据流】
 * 定义：在此以 Color(0xFF...) 一次性声明，颜色值唯一来源。
 * 消费：ui.theme.Theme 中 darkColorScheme()/lightColorScheme() 引用这些常量，
 *       构造 MaterialTheme 的 colorScheme，进而被所有 Composable 页面经
 *       MaterialTheme.colorScheme.** 取用；因此改颜色只需改此文件。
 */
// =============================================================================
// 📄 Color.kt
// 作用：集中管理 App 的所有颜色常量
//       好处是改颜色只改这一个文件，不用到处翻代码
// =============================================================================
package com.demo.kotlindemo.ui.theme

// 导入 Compose 的颜色类（Color 是 Compose 里的颜色表示方式）
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════
// 调色板
// ═══════════════════════════════════════════════════════

// ── 暗色模式颜色（后缀 80 = 80% 透明度，偏亮，在暗色背景上可见）──
val Purple80 = Color(0xFFD0BCFF)          // 柔和紫色 - 主色
val PurpleGrey80 = Color(0xFFCCC2DC)      // 紫灰色 - 辅助色
val Pink80 = Color(0xFFEFB8C8)            // 粉色 - 强调色

// ── 亮色模式颜色（后缀 40 = 更纯更深的颜色，在浅色背景上可见）──
val Purple40 = Color(0xFF6650A4)          // 深紫色 - 主色
val PurpleGrey40 = Color(0xFF625B71)      // 深紫灰色 - 辅助色
val Pink40 = Color(0xFF7D5260)            // 深粉色 - 强调色

// ── 自定义扩展色 ──
val SurfaceLight = Color(0xFFFFFBFE)      // 浅色模式表面颜色（偏白带一丝粉）
val SurfaceDark = Color(0xFF1C1B1F)       // 暗色模式表面颜色（深灰）
val PrimaryLight = Color(0xFF6750A4)      // 亮色主色（与 Purple40 接近）
val PrimaryDark = Color(0xFFD0BCFF)       // 暗色主色（与 Purple80 接近）

// 💡 为什么用 Color(0xFF...) 这种写法？
//    0xFF = 完全不透明（FF 是十六进制的 255）
//    后面 6 位是 RGB 值
//    所以 0xFFD0BCFF = Alpha=FF, R=D0, G=BC, B=FF → 浅紫色
