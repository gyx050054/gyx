// =============================================================================
// 📄 MainActivity.kt
// 作用：App 的"大门" — 系统启动这个 Activity 时，开始加载我们的 UI
//       所有的 Compose 内容从这里"生长"出去
// =============================================================================

// ── 包声明 ──
// 必须和 AndroidManifest 里的 package / namespace 一致
package com.demo.kotlindemo

// ── Android 系统导入 ──
import android.os.Bundle                              // Activity 传参用的 Bundle
import androidx.activity.ComponentActivity            // Compose 专用的 Activity 基类
import androidx.activity.compose.setContent            // 扩展函数，让 Activity 加载 Compose UI
import androidx.activity.enableEdgeToEdge              // 让内容延伸到状态栏/导航栏下方

// ── 项目内部导入 ──
import androidx.navigation.compose.rememberNavController // 创建导航控制器
import com.demo.kotlindemo.ui.navigation.AppNavGraph     // 项目导航图
import com.demo.kotlindemo.ui.theme.KotlinDemoTheme      // 项目主题

// ═══════════════════════════════════════════════════════
// MainActivity — App 的唯一 Activity（单 Activity 架构）
// ═══════════════════════════════════════════════════════
// ComponentActivity 是 AndroidX 提供的轻量基类，
// 比传统 AppCompatActivity 更适合 Compose 项目
class MainActivity : ComponentActivity() {

    // onCreate：Activity 被创建时调用，整个 App 的起点
    override fun onCreate(savedInstanceState: Bundle?) {
        // 先调父类，完成 Activity 基础初始化
        super.onCreate(savedInstanceState)

        // ── 边到边显示 ──
        // enableEdgeToEdge() 让内容画到屏幕最边缘，
        // 包括状态栏（显示时间/电量那栏）和导航栏（虚拟按键那栏）
        // 配合 Compose 的 WindowInsets 自动处理边距
        enableEdgeToEdge()

        // ── 加载 Compose UI ──
        // setContent { } 是 ComponentActivity 的扩展函数
        // 它创建了一个 ComposeView 作为 Activity 的根布局
        setContent {
            // 应用主题：包裹所有 UI，提供颜色、字体等全局样式
            KotlinDemoTheme {
                // 创建导航控制器 — 管理页面之间的跳转状态
                val navController = rememberNavController()
                // 加载导航图 — 定义所有页面和它们之间的跳转关系
                AppNavGraph(navController = navController)
            }
        }
    }
}
// 💡 单 Activity 架构：
//    传统 Android 是一个页面一个 Activity，
//    Compose 推荐的方式是——整个 App 只有这一个 Activity，
//    页面切换用 Navigation Compose 来完成
