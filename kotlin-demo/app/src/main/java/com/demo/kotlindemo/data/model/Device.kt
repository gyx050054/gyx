// 声明这个文件属于 com.demo.kotlindemo.data.model 包
package com.demo.kotlindemo.data.model

import com.demo.kotlindemo.data.dto.DeviceInfoDto
import com.demo.kotlindemo.data.dto.TelemetryItem

/**
 * 设备类型枚举
 *  - VALVE    电动阀（控制灌溉阀门开关）
 *  - SENSOR   温湿度传感器（采集土壤数据）
 *  - PUMP     施肥泵（控制施肥）
 *  - FAN      通风扇（控制通风）
 */
// 定义一个枚举类，表示所有可能的设备类型
enum class DeviceType {
    VALVE,     // 电动阀（可操作）
    SENSOR     // 温湿度传感器（不可操作）
}

/**
 * 设备数据模型
 * 每个设备在 APP 里用这个数据类表示
 *
 * @property id          设备唯一 ID，比如 "valve_001"
 * @property name        设备名称，用户界面上看到的文字
 * @property type        设备类型，来自 DeviceType 枚举
 * @property isOnline    是否在线，false 表示离线
 * @property isOn        是否开启，只对阀/泵/扇有意义
 * @property battery     电量百分比 0-100，部分设备才有
 * @property temperature 当前温度值，仅传感器有效
 * @property humidity    当前湿度值，仅传感器有效
 * @property fieldId     所属田块的 ID，关联到 Field
 * @property lastReportAt 最近一次数据上报时间（毫秒时间戳），用于展示记录时间
 * @property valveState  电动阀工作状态：WORKING=工作中 / IDLE=未工作（文档 3.2）
 * @property instantFlow 瞬时流量 L/min（工作状态）
 * @property totalWaterUsage 累计用水量 m³
 * @property waterPressure 管道水压 MPa
 * @property faultStatus 是否故障
 */
// 用 data class 自动生成 equals/hashCode/toString 方法
data class Device(
    val id: String,           // 设备ID，字符串类型
    val name: String,         // 设备名称，用户可读
    val type: DeviceType,     // 设备类型，从枚举取值
    val isOnline: Boolean = false,  // 是否在线，默认离线
    val isOn: Boolean = false,      // 是否开启，默认关闭
    val battery: Int = 100,         // 电量百分比，默认满电
    val temperature: Double = 0.0,   // 温度值，默认 0
    val humidity: Double = 0.0,      // 湿度值，默认 0
    val fieldId: String = "",        // 关联田块 ID，默认空串
    val lastReportAt: Long = 0L,     // 最近上报时间戳，默认 0（未上报）
    val valveState: String = "",     // WORKING / IDLE
    val instantFlow: Double = 0.0,   // 瞬时流量 L/min
    val totalWaterUsage: Double = 0.0, // 累计用水量 m³
    val waterPressure: Double = 0.0, // 管道水压 MPa
    val faultStatus: Boolean = false // 是否故障
)

// ================= DTO → 模型 转换（集中在本文件，与模型高内聚） =================

/**
 * ThingsBoard 设备信息 DTO → APP 设备模型
 *
 * 设备类型映射：TB 的 type 字段（来自 DeviceProfile 名）→ APP 的 DeviceType。
 * 注意：未知类型目前保守映射为 VALVE（可操作），第二版应改为显式拒绝并提示。
 */
fun DeviceInfoDto.toDevice(fieldId: String?): Device {
    val type = when (this.type) {
        "TEMPERATURE_HUMIDITY" -> DeviceType.SENSOR
        "VALVE" -> DeviceType.VALVE
        else -> DeviceType.VALVE
    }
    return Device(
        id = id.id,
        name = name,
        type = type,
        isOnline = active,
        isOn = false,   // 由遥测 valveState 覆盖
        fieldId = fieldId ?: "",
        battery = 100
    )
}

/**
 * 最新遥测数据填充到设备模型（只覆盖有值的字段，无值保留原值）
 * 对应设备端运行规则定义中的遥测键：temperature/humidity/valveState/batteryLevel/
 * instantFlow/totalWaterUsage/waterPressure/faultStatus
 */
fun Device.applyTelemetry(telemetry: Map<String, List<TelemetryItem>>): Device {
    fun latest(key: String): String? = telemetry[key]?.firstOrNull()?.value

    val valveState = latest("valveState")
    val battery = latest("batteryLevel")?.toIntOrNull() ?: battery
    val temp = latest("temperature")?.toDoubleOrNull()
    val hum = latest("humidity")?.toDoubleOrNull()
    val flow = latest("instantFlow")?.toDoubleOrNull()
    val usage = latest("totalWaterUsage")?.toDoubleOrNull()
    val pressure = latest("waterPressure")?.toDoubleOrNull()
    val fault = latest("faultStatus")?.toBooleanStrictOrNull() ?: false
    val ts = telemetry.values.firstOrNull()?.firstOrNull()?.ts ?: lastReportAt

    return copy(
        isOn = valveState == "WORKING",
        valveState = valveState ?: "",
        battery = battery,
        temperature = temp ?: temperature,
        humidity = hum ?: humidity,
        instantFlow = flow ?: instantFlow,
        totalWaterUsage = usage ?: totalWaterUsage,
        waterPressure = pressure ?: waterPressure,
        faultStatus = fault,
        lastReportAt = ts
    )
}
