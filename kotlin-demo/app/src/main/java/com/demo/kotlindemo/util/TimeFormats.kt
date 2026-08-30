/**
 * 【文件职责】
 * 日期时间格式化工具：用线程安全的 java.time 实现统一封装格式化/解析能力，
 * 集中定义 APP 各页面复用的格式器（完整日期时间、纯日期、纯时间、时分秒、月日时分），
 * 消除各页面重复创建格式化器的问题，并替代旧 SimpleDateFormat。
 *
 * 【数据流】
 * 输入：毫秒时间戳 Long 或 Date（TsFormatter.format），或用户输入的字符串（TsFormatter.parse）。
 * 转换：DateTimeFormatter 按系统时区格式化/解析；parse 格式不符时返回 null（不抛异常）。
 * 输出：UI 层展示用的字符串（任务设置、历史筛选、设备最近上报时间、曲线横轴等）。
 */
// 声明包名：工具层
package com.demo.kotlindemo.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * 线程安全的格式化器包装（java.time 实现）
 *
 * 替代旧 SimpleDateFormat：DateTimeFormatter 线程安全、不依赖可变状态，
 * 可在协程/多线程环境放心复用（旧实现注释里"仅限 UI 单线程"的限制已解除）。
 *
 * 对外 API 与旧版 SimpleDateFormat 对齐（format(Long)/format(Date)/parse(String)?.time），
 * 调用方无需改动即可迁移。
 */
class TsFormatter(private val formatter: DateTimeFormatter) {

    /** 系统时区（固定一次，避免每次格式化重复查） */
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * 按本格式器格式化毫秒时间戳
     * @param ts 毫秒时间戳（UTC 基准，按系统时区显示）
     * @return 格式化字符串，如 "2026-08-05 14:30"
     */
    fun format(ts: Long): String = formatter.format(Instant.ofEpochMilli(ts).atZone(zone))

    /** 按本格式器格式化 Date（兼容旧调用方） */
    fun format(date: Date): String = format(date.time)

    /**
     * 解析符合本格式器的字符串为 Date；格式不符返回 null（不抛异常）
     * 用途：历史曲线自定义时间范围输入解析（旧实现 .parse(text).time 取毫秒）
     */
    fun parse(text: String): Date? = try {
        val temporal = formatter.parse(text)
        Date.from(LocalDateTime.from(temporal).atZone(zone).toInstant())
    } catch (_: Exception) {
        null
    }
}

/**
 * 时间格式化工具（java.time 实现，线程安全）
 *
 * 说明：所有格式器统一在此定义，消除各页面重复创建/重复定义的问题；
 * 格式化入口用 [TsFormatter.format]（传毫秒时间戳），解析用 [TsFormatter.parse]。
 */
object TimeFormats {

    /** 完整日期时间：2026-08-05 14:30（任务设置/历史筛选用） */
    val DATETIME = TsFormatter(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    /** 纯日期：2026-08-05（日期选择器用） */
    val DATE = TsFormatter(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** 纯时间：14:30（时间选择器用） */
    val TIME = TsFormatter(DateTimeFormatter.ofPattern("HH:mm"))

    /** 时分秒：14:30:05（设备最近上报时间用） */
    val TIME_HHMMSS = TsFormatter(DateTimeFormatter.ofPattern("HH:mm:ss"))

    /** 月日时分：08-05 14:30（历史曲线横轴用） */
    val MONTH_DAY_TIME = TsFormatter(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    /**
     * 按指定模式格式化时间戳（临时/一次性格式化用；高频场景请用上面预置格式器）
     * @param ts      毫秒时间戳
     * @param pattern DateTimeFormatter 模式串
     */
    fun format(ts: Long, pattern: String): String =
        DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
}
