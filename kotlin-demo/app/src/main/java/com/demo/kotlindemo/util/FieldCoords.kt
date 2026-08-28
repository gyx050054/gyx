// 声明包名：工具层
package com.demo.kotlindemo.util

/**
 * 田块默认经纬度表（第三代第一版 §6.3）
 *
 * 演示环境：TB 的田块（asset）没有经纬度，App 从这里按田块名取默认坐标，
 * 让「按田块天气」与「田块地图设备点位」在无真实坐标时也能展示真实分布。
 * 后续若 TB asset 提供 real lat/lon（属性优先），可覆盖本表。
 *
 * 坐标取南昌西南—西北一条农业带上的 9 个点（每家田块一个中心）。
 * 设备点位 = 田块中心 + 该设备在田块内的编号偏移。
 */
object FieldCoords {

    /** 9 块田的默认中心坐标（按田块名匹配，含"田地N"等命名）——长沙岳麓区周边农田带（用户指定） */
    private val DEFAULTS: Map<String, Pair<Double, Double>> = mapOf(
        "田地1" to (28.1860 to 112.9330),
        "田地2" to (28.1900 to 112.9390),
        "田地3" to (28.1940 to 112.9320),
        "田地4" to (28.1980 to 112.9450),
        "田地5" to (28.2020 to 112.9360),
        "田地6" to (28.2060 to 112.9490),
        "田地7" to (28.2100 to 112.9400),
        "田地8" to (28.2140 to 112.9520),
        "田地9" to (28.2180 to 112.9440)
    )

    /** 兜底中心（长沙岳麓区） */
    val FALLBACK: Pair<Double, Double> = 28.19 to 112.93

    /** 按田块名取中心坐标；匹配不到用兜底。 */
    fun centerFor(name: String): Pair<Double, Double> {
        for ((k, v) in DEFAULTS) {
            if (name.contains(k)) return v
        }
        return FALLBACK
    }

    /** 田块内第 index 台设备的点位（示意分布，避免全部叠在同一点）。 */
    fun devicePoint(center: Pair<Double, Double>, index: Int): Pair<Double, Double> {
        val (lat, lon) = center
        val row = index / 3
        val col = index % 3
        return (lat + (row - 1) * 0.0012 + (col - 1) * 0.0009) to
                (lon + (row - 1) * 0.0009 + (col - 1) * 0.0012)
    }
}
