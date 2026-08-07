// 声明包名，这个文件属于导航层
package com.demo.kotlindemo.ui.navigation

// 导入 Composable 注解
import androidx.compose.runtime.Composable
// 导入导航控制器
import androidx.navigation.NavHostController
// 导入 NavHost 可组合函数
import androidx.navigation.compose.NavHost
// 导入 composable 路由注册函数
import androidx.navigation.compose.composable
// 导入所有页面的 Composable
import com.demo.kotlindemo.ui.screens.FieldDetailScreen
import com.demo.kotlindemo.ui.screens.HistoryScreen
import com.demo.kotlindemo.ui.screens.LoginScreen
import com.demo.kotlindemo.ui.screens.MainScreen
import com.demo.kotlindemo.ui.screens.TaskManagementScreen
import com.demo.kotlindemo.ui.screens.RegisterScreen
import com.demo.kotlindemo.ui.screens.ChangePasswordScreen
import com.demo.kotlindemo.ui.screens.FaqScreen
// 导入 URL 编码（设备名可能含中文）
import android.net.Uri
// 导入 ViewModel 创建函数
import androidx.lifecycle.viewmodel.compose.viewModel
// 导入自定义 ViewModel
import com.demo.kotlindemo.viewmodel.FarmViewModel
import com.demo.kotlindemo.viewmodel.TaskViewModel

/**
 * 路由常量对象
 *
 * 集中管理所有路由名，避免拼写错误
 */
object Routes {
    // 登录页路由名
    const val LOGIN = "login"
    // 注册页路由名（第二版新增）
    const val REGISTER = "register"
    // 修改密码页路由名（第二版新增，带邮箱参数）
    const val CHANGE_PASSWORD = "change_password/{email}"
    // 常见问题页路由名（第二版新增）
    const val FAQ = "faq"
    // 主页面路由名
    const val MAIN  = "main"
    // 任务管理页路由名
    const val TASKS = "tasks"
    // 田块详情页路由名（带参数 fieldId）
    const val FIELD = "field/{fieldId}"
    // 历史数据页路由名（带参数 deviceId / deviceName）
    const val HISTORY = "history/{deviceId}/{deviceName}"

    /**
     * 生成田块详情页的完整路由
     * @param fieldId 田块ID
     * @return 包含 fieldId 的实际路由字符串
     */
    fun fieldDetail(fieldId: String) = "field/$fieldId"

    /**
     * 生成修改密码页的完整路由（邮箱 URL 编码，避免中文等特殊字符）
     * @param email 当前登录邮箱
     * @return 包含 email 的实际路由字符串
     */
    fun changePassword(email: String) = "change_password/${Uri.encode(email)}"

    /**
     * 生成历史数据页的完整路由
     * @param deviceId 设备ID
     * @param deviceName 设备名称（URL 编码）
     */
    fun history(deviceId: String, deviceName: String) =
        "history/$deviceId/${Uri.encode(deviceName)}"
}

/**
 * App 导航图
 *
 * 定义所有页面和页面间的跳转关系
 *
 * @param navController 导航控制器，负责页面跳转
 */
@Composable
fun AppNavGraph(navController: NavHostController) {
    // 创建共享的 FarmViewModel，多个页面共用一个实例
    val farmViewModel: FarmViewModel = viewModel()
    // 创建共享的 TaskViewModel，多个页面共用一个实例
    val taskViewModel: TaskViewModel = viewModel()

    // NavHost 是导航容器，startDestination 是起始页
    NavHost(
        navController = navController,  // 传入导航控制器
        startDestination = Routes.LOGIN  // 启动时显示登录页
    ) {
        // 注册登录页路由
        composable(Routes.LOGIN) {
            LoginScreen(
                // 登录成功（无需改密）后的回调：进入主页，清空返回栈
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                // 登录成功但需强制改密：跳转改密页
                onNeedChangePassword = { email ->
                    navController.navigate(Routes.changePassword(email))
                },
                // 注册入口：跳注册页
                onRegisterClick = { navController.navigate(Routes.REGISTER) },
                // 常见问题入口：跳 FAQ 页
                onFaqClick = { navController.navigate(Routes.FAQ) }
            )
        }

        // 注册页（第二版新增）：注册+自动登录成功 → 跳改密页
        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = { email ->
                    navController.navigate(Routes.changePassword(email)) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // 修改密码页（第二版新增）：改密完成 → 进主界面
        composable(Routes.CHANGE_PASSWORD) { entry ->
            val email = entry.arguments?.getString("email") ?: ""
            ChangePasswordScreen(
                email = email,
                onSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // 常见问题页（第二版新增）
        composable(Routes.FAQ) {
            FaqScreen(onBack = { navController.popBackStack() })
        }

        // 注册主页面路由
        composable(Routes.MAIN) {
            MainScreen(
                farmViewModel = farmViewModel,   // 传入农田 ViewModel
                taskViewModel = taskViewModel,   // 传入任务 ViewModel
                onLogout = {                     // 退出登录回调
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                onFieldClick = { fieldId ->      // 点击田块回调
                    navController.navigate(Routes.fieldDetail(fieldId))
                },
                onTaskManageClick = {            // 点击任务管理回调
                    navController.navigate(Routes.TASKS)
                },
                onDeviceHistoryClick = { deviceId, deviceName ->   // 点击温湿度计查看历史
                    navController.navigate(Routes.history(deviceId, deviceName))
                }
            )
        }

        // 注册任务管理页路由
        composable(Routes.TASKS) {
            TaskManagementScreen(
                taskViewModel = taskViewModel,  // 传入任务 ViewModel
                onBack = { navController.popBackStack() }  // 返回上一页
            )
        }

        // 注册田块详情页路由，使用 {fieldId} 占位符接收参数
        composable(Routes.FIELD) { entry ->
            // 从路由参数中取出 fieldId，取不到就用空串
            val fieldId = entry.arguments?.getString("fieldId") ?: ""
            FieldDetailScreen(
                fieldId = fieldId,              // 田块ID
                farmViewModel = farmViewModel,  // 传入农田 ViewModel
                taskViewModel = taskViewModel,  // 传入任务 ViewModel
                onTaskManageClick = {           // 田块详情页内点击任务管理
                    navController.navigate(Routes.TASKS)
                },
                onDeviceHistoryClick = { deviceId, deviceName ->   // 点击温湿度计查看历史
                    navController.navigate(Routes.history(deviceId, deviceName))
                },
                onBack = { navController.popBackStack() }  // 返回上一页
            )
        }
        // 注册历史数据页路由，使用 {deviceId}/{deviceName} 占位符接收参数
        composable(Routes.HISTORY) { entry ->
            val deviceId = entry.arguments?.getString("deviceId") ?: ""
            val deviceName = entry.arguments?.getString("deviceName") ?: "历史数据"
            HistoryScreen(
                deviceId = deviceId,
                deviceName = deviceName,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
