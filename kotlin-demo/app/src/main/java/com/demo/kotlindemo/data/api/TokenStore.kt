/**
 * 【文件职责】JWT 持久化存储：用 SharedPreferences 保存登录 token 与任务页访问标记，解决「杀进程后掉线」问题。
 * 【数据流】登录成功 → TokenStore.save() 写 token；AuthInterceptor 请求前 TokenStore.load() 读 token；退出登录 → TokenStore.clear()/resetTasksVisited() 清理；App 启动早期 init(context) 注入 context。
 */
// 包声明：网络层
package com.demo.kotlindemo.data.api

// Android 上下文（Application context 用）
import android.content.Context
// SharedPreferences 读写
import android.content.SharedPreferences

/**
 * JWT 持久化存储（第二版新增，解决第一版"重启即掉线"）
 *
 * 背景：第一版 token 只存在 AuthInterceptor 的内存静态变量里，杀进程后丢失；
 * 本类把 token 落到 SharedPreferences，App 重启后由 AuthInterceptor 自动恢复。
 *
 * 使用说明：
 *  - 必须在 App 启动早期调用 [init]（MainActivity.onCreate 里）注入 context；
 *  - 登录成功时调用 [save]，退出登录调用 [clear]。
 */
object TokenStore {

    /** SharedPreferences 文件名（与其它配置隔离） */
    private const val PREFS_NAME = "auth_prefs"

    /** token 存储键名 */
    private const val KEY_TOKEN = "jwt_token"

    /** 任务页访问标记键名（第二版：任务红点） */
    private const val KEY_TASKS_VISITED = "tasks_visited"

    // lateinit：init 前访问会抛异常，保证必须先初始化
    private lateinit var prefs: SharedPreferences

    /**
     * 初始化（幂等）：用 applicationContext 避免内存泄漏
     * @param context 任意 context（内部会取 applicationContext）
     */
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 保存 token（登录成功后调用） */
    fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    /** 读取 token；无记录时返回 null（未登录/已退出） */
    fun load(): String? = prefs.getString(KEY_TOKEN, null)

    /** 清除 token（退出登录时调用） */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    // ── 任务红点状态（第二版：访问任务页后消失）──

    /** 标记已访问任务管理页（红点消失） */
    fun markTasksVisited() {
        prefs.edit().putBoolean(KEY_TASKS_VISITED, true).apply()
    }

    /** 是否访问过任务管理页（false=显示红点） */
    fun hasVisitedTasks(): Boolean = prefs.getBoolean(KEY_TASKS_VISITED, false)

    /** 重置任务访问状态（退出登录时调用，下次登录重新显示红点） */
    fun resetTasksVisited() {
        prefs.edit().remove(KEY_TASKS_VISITED).apply()
    }
}
