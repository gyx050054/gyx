/**
 * 【文件职责】
 * 田块（区块）数据模型：定义 Field 数据类，承载每个农田区块的展示信息
 * （名称、作物、设备数、面积）与地图定位信息（田块中心 lat/lon）。
 *
 * 【数据流】
 * 上游：田块列表数据（含设备统计）→ 映射为 Field。
 * 消费：APP 首页网格逐格展示每个田块；lat/lon 被地图层读取，作为该田块的设备点位
 *       的基准中心（配合 util.FieldCoords 计算）。
 */
// 声明包名，这个文件属于 data.model 层
package com.demo.kotlindemo.data.model

/**
 * 田块（区块）数据模型
 * 每个田块对应一个农田区块，APP 首页网格展示
 *
 * @property id          田块唯一 ID，如 "f1""f2"
 * @property name        田块名称，如 "1号田"
 * @property cropType    种植作物类型，如 "番茄"
 * @property deviceCount 关联设备总数
 * @property activeCount 在线/开启的设备数量
 * @property areaSqm     面积，单位平方米
 */
// data class 存储田块数据，用在大厅网格
data class Field(
    val id: String,             // 田块ID，字符串
    val name: String,           // 田块名称
    val cropType: String = "",  // 作物类型，默认空
    val deviceCount: Int = 0,   // 关联设备数，默认 0
    val activeCount: Int = 0,   // 激活设备数，默认 0
    val areaSqm: Double = 0.0,  // 面积，平方米，默认 0
    val lat: Double = 0.0,      // 田块中心纬度（地图用，第三代 §6）
    val lon: Double = 0.0       // 田块中心经度（地图用）
)
