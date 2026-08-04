// 声明包名，这个文件是数据层模型
package com.demo.kotlindemo.data.model

/**
 * 定时任务状态枚举
 *  - PENDING    等待执行，还没到开始时间
 *  - RUNNING    正在执行，当前时间在起止之间
 *  - COMPLETED  已完成，任务自动执行结束
 *  - CANCELLED  已取消，用户手动取消
 */
// 定义定时任务的四种状态
enum class TaskStatus {
    PENDING,     // 等待执行
    RUNNING,     // 执行中
    COMPLETED,   // 已完成
    CANCELLED    // 已取消
}

/**
 * 定时任务数据模型
 * 用户为设备设置的定时开关任务
 *
 * @property id         任务唯一 ID，UUID 字符串
 * @property deviceId   目标设备 ID，关联到 Device
 * @property deviceName 目标设备名称，列表展示用
 * @property startTime  任务开始时间，毫秒时间戳
 * @property endTime    任务结束时间，毫秒时间戳
 * @property action     任务动作，默认 "on"
 * @property status     任务当前状态，默认 PENDING
 */
// data class 存定时任务，用在任务管理页
data class TimingTask(
    val id: String,                     // 任务ID，UUID
    val deviceId: String,               // 关联设备ID
    val deviceName: String,             // 设备名称，冗余存储
    val startTime: Long,                // 开始时间戳
    val endTime: Long,                  // 结束时间戳
    val action: String = "on",          // 动作，默认开启
    val status: TaskStatus = TaskStatus.PENDING  // 状态，默认待执行
)
