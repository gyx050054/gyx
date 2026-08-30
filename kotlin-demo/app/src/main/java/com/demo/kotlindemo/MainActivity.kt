/**
 * 【文件职责】MainActivity —— App 唯一入口 Activity（单 Activity + Jetpack Compose 架构）。
 *   - 启用 edge-to-edge：内容延伸到状态栏/导航栏，边距由 Compose WindowInsets 处理；
 *   - 初始化 [TokenStore]：JWT 持久化，必须在任何网络请求前执行（AuthInterceptor 冷启动恢复 JWT 依赖它）；
 *   - 挂载主题与导航图（[AppNavGraph] 定义全部页面路由）。
 *
 * 【数据流】onCreate 中先 enableEdgeToEdge() 扩展内容到系统栏；再 TokenStore.init(this) 从本地读回 JWT（杀进程重启后保持登录）；
 *   随后 setContent 挂载 KotlinDemoTheme -> AppNavGraph(rememberNavController())，由导航图根据当前路由决定真正显示的页面。
 */
package com.demo.kotlindemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.demo.kotlindemo.data.api.TokenStore
import com.demo.kotlindemo.ui.navigation.AppNavGraph
import com.demo.kotlindemo.ui.theme.KotlinDemoTheme

/**
 * App 唯一入口 Activity（单 Activity + Compose 架构）
 *
 * 职责：
 *  - 启用 edge-to-edge（内容延伸到状态栏/导航栏，边距由 Compose WindowInsets 处理）；
 *  - 初始化 [TokenStore]（必须在任何网络请求前：AuthInterceptor 冷启动恢复 JWT 依赖它）；
 *  - 挂载主题与导航图（[AppNavGraph] 定义全部页面路由）。
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity 创建入口：初始化 edge-to-edge 与 JWT 持久化，挂载主题与导航图
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // JWT 持久化初始化（第二版：杀进程重启后保持登录；须先于网络层任何调用）
        TokenStore.init(this)
        setContent {
            KotlinDemoTheme {
                // 挂载导航图：默认以 LOGIN 为起始页，路由常量与页面跳转关系见 NavGraph.kt
                AppNavGraph(navController = rememberNavController())
            }
        }
    }
}
