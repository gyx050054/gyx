// =============================================================================
// 📄 Theme.kt
// 作用：Compose 主题的"总开关" — 定义亮色/暗色/动态取色的配色方案
//       所有 Composable 页面用 MaterialTheme.colorScheme.** 取色
// =============================================================================
package com.demo.kotlindemo.ui.theme

// ── Android 系统导入 ──
import android.app.Activity
import android.os.Build

// ── Compose 导入 ──
import androidx.compose.foundation.isSystemInDarkTheme   // 判断系统是否处于暗色模式
import androidx.compose.material3.MaterialTheme          // Material3 主题容器
import androidx.compose.material3.darkColorScheme         // 暗色配色方案构建函数
import androidx.compose.material3.dynamicDarkColorScheme  // 动态暗色配色（Android 12+）
import androidx.compose.material3.dynamicLightColorScheme // 动态亮色配色（Android 12+）
import androidx.compose.material3.lightColorScheme        // 亮色配色方案构建函数
import androidx.compose.runtime.Composable                // 可组合函数注解
import androidx.compose.runtime.SideEffect                // 副作用：在组合时执行一段代码
import androidx.compose.ui.graphics.toArgb                // 把 Color 转成 ARGB 整数
import androidx.compose.ui.platform.LocalContext          // 获取当前 Context
import androidx.compose.ui.platform.LocalView             // 获取当前 View
import androidx.core.view.WindowCompat                    // Window 兼容工具类

// ═══════════════════════════════════════════════════════
// 配色方案（静态版 — 不使用动态取色时的备选方案）
// ═══════════════════════════════════════════════════════
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,         // 主色
    secondary = PurpleGrey80,   // 辅助色
    tertiary = Pink80           // 第三色
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

// ═══════════════════════════════════════════════════════
// KotlinDemoTheme — App 主题组件
// ═══════════════════════════════════════════════════════
//
// 用法：在 setContent 里包裹所有 UI
//   KotlinDemoTheme {
//       // 你的 UI 代码
//   }
//
@Composable
fun KotlinDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),     // 默认自动跟随系统
    dynamicColor: Boolean = true,                    // Android 12+ 自动适配壁纸颜色
    content: @Composable () -> Unit                  // 子 UI 内容
) {
    // ── 选择配色方案 ──
    // 优先级：动态取色 > 暗色方案 > 亮色方案
    val colorScheme = when {
        // 如果支持动态取色（Android 12+，即 SDK 31+）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)   // 从壁纸提取暗色调
            else dynamicLightColorScheme(context)            // 从壁纸提取亮色调
        }
        // 不支持动态取色 → 使用我们预设的静态配色
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // ── 把状态栏颜色同步到主题色 ──
    val view = LocalView.current
    // isInEditMode 检查：如果是 Android Studio 预览模式，不执行 SideEffect
    // 否则在预览时会报错（预览模式下没有真正的 Window）
    if (!view.isInEditMode) {
        SideEffect {
            // 获取当前 Activity 的 Window
            val window = (view.context as Activity).window
            // 把状态栏颜色设为主题的主色
            window.statusBarColor = colorScheme.primary.toArgb()
            // 根据亮/暗模式决定状态栏文字颜色：
            // 亮色 → 深色文字（isAppearanceLightStatusBars = true）
            // 暗色 → 浅色文字（false）
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // ── 应用主题 ──
    // MaterialTheme 会向下传递 colorScheme 和 typography
    // 所有子 Composable 都可以通过 MaterialTheme.colorScheme / .typography 获取
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
// 💡 动态取色（Monet / Material You）：
//    Android 12+ 自动从用户的壁纸提取颜色主题
//    所以不同用户的 App 颜色可能不同，很酷吧？
