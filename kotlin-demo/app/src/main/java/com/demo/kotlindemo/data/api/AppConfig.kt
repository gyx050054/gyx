// 声明包名：网络层
package com.demo.kotlindemo.data.api

/**
 * 全局配置常量（集中管理，消除散落在各文件的硬编码）
 *
 * 修改指南：
 *  - 换服务器地址只需改 THINGSBOARD_BASE_URL / TASK_SERVICE_BASE_URL 两处；
 *  - 遥测字段、轮询间隔、分页大小等统一在此调整。
 */
object AppConfig {

    // ---------- 服务端地址 ----------
    // Android 模拟器访问宿主机用 10.0.2.2；真机（同一局域网）改为 <电脑局域网IP>
    const val THINGSBOARD_BASE_URL = "http://10.0.2.2:8080/"
    const val TASK_SERVICE_BASE_URL = "http://10.0.2.2:9300/"

    // ---------- 刷新与分页 ----------
    /** 页面自动刷新间隔（毫秒）：田块详情/总览每 10 秒轮询一次（需求文档 3.7） */
    const val POLL_INTERVAL_MS = 10_000L

    /** 分页大小：设备/田块列表一次拉取的上限 */
    const val PAGE_SIZE = 200

    // ---------- 遥测字段 ----------
    /**
     * 设备最新遥测一次性拉取的 key 列表（与 TB 数据模型一致，见设备端运行规则定义）：
     *  - 温湿度计：temperature / humidity
     *  - 电动阀：valveState / batteryLevel / instantFlow / totalWaterUsage / waterPressure / faultStatus
     */
    const val TELEMETRY_KEYS =
        "temperature,humidity,valveState,batteryLevel,instantFlow,totalWaterUsage,waterPressure,faultStatus"
}
