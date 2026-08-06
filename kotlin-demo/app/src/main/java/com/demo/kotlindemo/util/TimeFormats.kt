// 声明包名：工具层
package com.demo.kotlindemo.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间格式化工具（集中管理，消除各页面重复定义 SimpleDateFormat 的问题）
 *
 * 使用注意：SimpleDateFormat 非线程安全，但 UI 层均为单线程调用，可安全复用单例；
 * 若未来在协程 IO 线程使用，请改用 java.time（LocalDateTime）或 ThreadLocal。
 */
object TimeFormats {

    /** 完整日期时间：2026-08-05 14:30（任务设置/历史筛选用） */
    val DATETIME = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /** 纯日期：2026-08-05（日期选择器用） */
    val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /** 纯时间：14:30（时间选择器用） */
    val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** 时分秒：14:30:05（设备最近上报时间用） */
    val TIME_HHMMSS = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 月日时分：08-05 14:30（历史曲线横轴用） */
    val MONTH_DAY_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /**
     * 按指定格式格式化时间戳
     * @param ts 毫秒时间戳
     * @param pattern SimpleDateFormat 模式
     */
    fun format(ts: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}
