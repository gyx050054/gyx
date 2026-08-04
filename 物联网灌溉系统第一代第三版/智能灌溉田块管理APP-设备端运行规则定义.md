# 智能灌溉田块管理 — 设备端运行规则定义

> 文档版本：v1.2
> 更新日期：2026-08-04
> 适用范围：温度湿度传感器（不可操作设备）、电动阀（内嵌流量计，可操作设备）

---

## 目录

1. [文档概述](#1-文档概述)
2. [温湿度计（不可操作设备）](#2-温湿度计不可操作设备)
3. [电动阀（内嵌流量计，可操作设备）](#3-电动阀内嵌流量计可操作设备)
4. [ThingsBoard MQTT 主题总览](#4-thingsboard-mqtt-主题总览)
5. [客户端数据读取规范](#5-客户端数据读取规范)
6. [附录：数据模型定义](#6-附录数据模型定义)

---

## 1. 文档概述

### 1.1 系统角色定义

| 角色 | 说明 |
|------|------|
| **设备端** | 部署在农田现场的 IoT 硬件设备（传感器、执行器），通过 MQTT 协议与 ThingsBoard 通信 |
| **ThingsBoard 服务端** | 物联网平台，负责接收设备上行数据、存储时序数据、转发下行指令 |
| **微服务端** | 自定义定时任务调度服务，负责任务冲突检测、@Scheduled 定时扫描、RPC 触发 |
| **客户端（APP）** | 用户交互界面，通过 REST API 与 ThingsBoard 和微服务端通信 |

### 1.2 设备分类

| 设备类型 | 可操作性 | 说明 |
|----------|----------|------|
| **温湿度计** | ❌ 不可操作 | 仅上报数据，无任何下行控制指令 |
| **电动阀（内嵌流量计）** | ✅ 可操作 | 支持远程开启/关闭、定时任务、流量监测 |

### 1.3 通信架构



```
                          ┌──────────────────┐
                          │                  │
                     ┌───▶│  ThingsBoard     │
                     │    │  服务端           │
                     │    │  (v4.0.1)        │
  ┌──────────┐       │    │                  │
  │ 温湿度计  │───────┘    └──────┬───────────┘
  │ (不可操)  │  MQTT上行         │
  └──────────┘                   │ REST API / RPC
                                 │
  ┌──────────┐       ┌──────────▼───────────┐
  │ 电动阀   │───────┤                      │
  │ (可操作) │ MQTT  │    APP 客户端         │
  └──────────┘       │    + 微服务端         │
                     │                      │
                     └──────────────────────┘
```

---

## 2. 温湿度计（不可操作设备）

### 2.1 设备概述

温湿度计属于**不可操作设备**，仅负责环境数据的采集与上报，设备端**不接收**任何来自服务端的下行控制指令。

### 2.2 数据上报格式

设备以 JSON 格式上报数据，每个字段的定义如下：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `temperature` | float | ✓ | 当前环境温度（摄氏度） | `28.5` |
| `humidity` | float | ✓ | 当前环境湿度（百分比） | `65.2` |
| `ts` | long | ✓ | 数据采集时间戳（毫秒） | `1721800000000` |
| `deviceId` | string | ✓ | 设备唯一标识（UUID） | `"a1b2c3d4-..."` |

**示例 JSON：**
```json
{
  "temperature": 28.5,
  "humidity": 65.2,
  "ts": 1721800000000,
  "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### 2.3 上报频率

| 参数 | 值 |
|------|-----|
| 上报间隔 | **每 10 分钟**主动上报一次 |
| 上报时机 | 设备上电后立即上报第1条，之后每隔10分钟上报 |

### 2.4 MQTT 上行主题

| 项目 | 内容 |
|------|------|
| **主题** | `v1/devices/me/telemetry` |
| **方向** | 设备 → ThingsBoard（数据上行） |
| **数据去向** | ThingsBoard 服务端接收后，**存入系统时序数据库**（Timeseries Database） |
| **QoS** | 建议 QoS 1（至少一次） |

### 2.5 数据读取规则（客户端）

| 项目 | 内容 |
|------|------|
| 读取方式 | 客户端通过**定时调用 ThingsBoard REST API** 访问时序数据库 |
| 对应接口 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` |
| 查询参数 | `keys=temperature,humidity` |
| 读取频率 | 建议客户端每 2 分钟轮询一次获取最新数据 |
| 历史曲线 | 可通过 `startTs` / `endTs` 参数查询历史数据，展示**历史温度变化曲线** |

## 3. 电动阀（内嵌流量计，可操作设备）

### 3.1 设备概述

电动阀属于**可操作设备**，内嵌**流量计**，支持以下能力：
- 远程开启/关闭控制
- 即时流量监测（瞬时流量、累计用水量）
- 管道水压监测
- 电量监测
- 自检设备是否故障
- 定时任务控制

### 3.2 登录时自动检查电动阀状态

**触发时机：** 用户登录系统时

**校验逻辑：**
1. 客户端成功登录后（获取 JWT Token）
2. 自动调用 API 查询 ThingsBoard 中**所有电动阀的当前工作状态**
3. 在用户登录态下，返回全部电动阀的"**是/否工作**"状态标识

**对应 API：**

```
GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries?keys=valveState
```

**返回示例：**

```json
{
  "valveState": [{ "ts": 1721800000000, "value": "WORKING" }]
}
```

| 工作状态值 | 含义 |
|-----------|------|
| `WORKING` | 工作中（阀门开启） |
| `IDLE` | 未工作（阀门关闭） |

### 3.3 非工作状态上报规则

**生效条件：** 设备处于**未工作状态**（`valveState = IDLE`）

#### 3.3.1 上报数据格式

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `valveState` | string | ✓ | 开关状态 | `"IDLE"` |
| `deviceId` | string | ✓ | 设备唯一标识 | `"uuid-string"` |
| `faultStatus` | boolean | ✓ | 是否故障（true=故障, false=正常） | `false` |
| `batteryLevel` | int | ✓ | 设备电量（百分比 0~100） | `85` |
| `ts` | long | ✓ | 上报时间戳 | `1721800000000` |

#### 3.3.2 上报频率

| 参数 | 值 |
|------|-----|
| 上报间隔 | 非工作状态下，每 **30 分钟**上报一次（降低功耗） |
| 状态变更 | 如果状态从 IDLE 变为 WORKING，立即切换上报模式 |

#### 3.3.3 MQTT 主题

| 项目 | 内容 |
|------|------|
| 主题 | `v1/devices/me/telemetry` |

### 3.4 工作状态上报规则

**生效条件：** 设备处于**工作状态**（`valveState = WORKING`，阀门开启中）

#### 3.4.1 上报数据格式

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `deviceId` | string | ✓ | 设备唯一标识 | `"uuid-string"` |
| `instantFlow` | float | ✓ | 瞬时流量（单位：L/min） | `12.5` |
| `totalWaterUsage` | float | ✓ | 累计用水量（单位：m³） | `3.24` |
| `waterPressure` | float | ✓ | 管道水压（单位：MPa） | `0.35` |
| `batteryLevel` | int | ✓ | 设备电量（百分比 0~100） | `85` |
| `ts` | long | ✓ | 上报时间戳 | `1721800000000` |

**示例 JSON（工作状态）：**
```json
{
  "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "instantFlow": 12.5,
  "totalWaterUsage": 3.24,
  "waterPressure": 0.35,
  "batteryLevel": 85,
  "ts": 1721800000000
}
```

#### 3.4.2 上报频率

| 参数 | 值 |
|------|-----|
| 上报间隔 | 工作状态下，每 **10 秒**上报一次数据（高频率监测） |

#### 3.4.3 MQTT 上行主题

| 项目 | 内容 |
|------|------|
| **主题** | `v1/devices/me/telemetry` |
| **方向** | 设备 → ThingsBoard（数据上行） |
| **数据去向** | ThingsBoard 服务端接收后，存入系统时序数据库 |
| **QoS** | 建议 QoS 1（至少一次） |

### 3.5 下行控制规则

#### 3.5.1 控制链路

```
┌──────────┐     REST API      ┌──────────────┐    MQTT下行     ┌──────────┐
│          │  ────────────▶   │              │  ────────────▶ │          │
│ APP客户端 │                  │ ThingsBoard   │                │  电动阀   │
│          │  ◀────────────   │  服务端       │  ◀──────────── │          │
│          │    REST API       │              │   MQTT响应     │          │
└──────────┘                  └──────────────┘                └──────────┘
```

#### 3.5.2 控制流程

**步骤一：客户端下发指令**

1. 用户在 APP 上点击设备的"开启"或"关闭"按钮
2. APP 调用 ThingsBoard RPC API 推送指令

**API 调用：**
```
POST /api/rpc/oneway/{deviceId}
```

**请求体：**
```json
{
  "method": "setValveState",
  "params": {
    "state": true
  }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `method` | string | 方法名 `setValveState` |
| `params.state` | boolean | `true`=开启阀门, `false`=关闭阀门 |

**步骤二：ThingsBoard 转发至设备**
1. ThingsBoard 收到 REST 请求后
2. 通过 MQTT 下发指令到对应设备

**MQTT 下行主题：**
```
v1/devices/me/rpc/request/<requestId>
```

**下行消息体：**
```json
{
  "method": "setValveState",
  "params": {
    "state": true
  }
}
```

**步骤三：设备执行并回复**
1. 设备收到 MQTT 下行消息
2. 解析 `method` 和 `params`
3. 执行对应操作（开启/关闭阀门电机）
4. 通过 MQTT 回复执行结果

**MQTT 回复主题：**
```
v1/devices/me/rpc/response/<requestId>
```

**回复消息体：**
```json
{
  "result": "SUCCESS",
  "valveState": "WORKING"
}
```

#### 3.5.3 下行控制主题一览

| 方向 | MQTT 主题 | 说明 |
|------|-----------|------|
| 下行 | `v1/devices/me/rpc/request/+` | 服务端→设备，发送 RPC 指令 |
| 上行(回复) | `v1/devices/me/rpc/response/<requestId>` | 设备→服务端，回复 RPC 执行结果 |

#### 3.5.4 支持的 RPC 方法

| 方法名 | 参数 | 说明 |
|--------|------|------|
| `setValveState` | `{ "state": true/false }` | 开启/关闭阀门 |
| `getValveStatus` | `{}` | 查询阀门当前状态（用于登录校验） |
| `pauseValve` | `{}` | 暂停任务执行（用于删除运行中任务） |

---

## 4. ThingsBoard MQTT 主题总览

### 4.1 上行主题（设备 → ThingsBoard）

| 设备类型 | MQTT 主题 | 上报频率 | 说明 |
|----------|-----------|----------|------|
| 温湿度计 | `v1/devices/me/telemetry` | 每 10 分钟 | 温度、湿度 |
| 电动阀（未工作） | `v1/devices/me/telemetry` | 每 30 分钟 | 电量、故障、状态 |
| 电动阀（工作中） | `v1/devices/me/telemetry` | 每 10 秒 | 流量、水压、电量 |
| 电动阀（RPC 响应） | `v1/devices/me/rpc/response/<requestId>` | 按需 | RPC 回复 |

### 4.2 下行主题（ThingsBoard → 设备）

| MQTT 主题 | 说明 |
|-----------|------|
| `v1/devices/me/rpc/request/+` | RPC 远程控制指令 |

### 4.3 通信协议汇总

```
温湿度计：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每 10 分钟 ── 温度/湿度/时间/设备 ID
  └─ 下行 ── 无（不可操作设备）

电动阀（非工作状态）：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每 30 分钟 ── 开关状态/设备 ID/故障状态/电量
  └─ 下行 ── topic: v1/devices/me/rpc/request/+ ── 远程控制

电动阀（工作状态）：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每 10 秒 ── 设备 ID/瞬时流量/累计用水量/水压/电量
  └─ 下行 ── topic: v1/devices/me/rpc/request/+ ── 远程控制
```

---

## 5. 客户端数据读取规范

### 5.1 温湿度计数据读取

| 用途 | API | 参数 | 说明 |
|------|-----|------|------|
| 获取最新温湿度 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=temperature,humidity` | limit 默认为 1，返回最新值 |
| 获取历史温度曲线 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=temperature&startTs={start}&endTs={end}&limit=1000` | 按时间范围查询历史数据，用于绘制曲线图 |
| 获取历史湿度曲线 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=humidity&startTs={start}&endTs={end}&limit=1000` | 同上 |

### 5.2 电动阀数据读取

| 用途 | API | 参数 | 说明 |
|------|-----|------|------|
| 获取阀门状态 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=valveState` | 返回 WORKING/IDLE |
| 获取瞬时流量 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=instantFlow` | 阀门工作状态下有效 |
| 获取累计用水量 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=totalWaterUsage` | 总用水量 |
| 获取管道水压 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=waterPressure` | 管道压力值 |
| 获取电量 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=batteryLevel` | 电池剩余百分比 |
| 获取设备故障状态 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=faultStatus` | true/false |

### 5.3 登录态设备状态校验

| 用途 | API | 说明 |
|------|-----|------|
| 登录后自动查询所有阀门状态 | `GET /api/tenant/deviceInfos?type=VALVE` 获取设备列表 → 逐个查询 `valveState` | 返回全部电动阀的是/否工作标识 |

---

## 6. 附录：数据模型定义

### 6.1 遥测键名（Timeseries Keys）完整定义

| 设备类型 | 键名 | 类型 | 含义 | 单位 | 上报场景 |
|----------|------|------|------|------|----------|
| 温湿度计 | `temperature` | float | 温度 | ℃ | 每次上报 |
| 温湿度计 | `humidity` | float | 湿度 | %RH | 每次上报 |
| 电动阀 | `valveState` | string | 开关状态 | — | 每次上报 |
| 电动阀 | `faultStatus` | boolean | 是否故障 | — | 非工作状态 |
| 电动阀 | `batteryLevel` | int | 设备电量 | % | 每次上报 |
| 电动阀 | `instantFlow` | float | 瞬时流量 | L/min | 工作状态每 10 秒 |
| 电动阀 | `totalWaterUsage` | float | 累计用水量 | m³ | 工作状态每 10 秒 |
| 电动阀 | `waterPressure` | float | 管道水压 | MPa | 工作状态每 10 秒 |

### 6.2 MQTT 数据模型汇总

#### 温湿度计上报（主题：`v1/devices/me/telemetry`）

```json
{
  "temperature": 28.5,
  "humidity": 65.2,
  "ts": 1721800000000,
  "deviceId": "a1b2c3d4-..."
}
```

#### 电动阀非工作状态上报（主题：`v1/devices/me/telemetry`）

```json
{
  "valveState": "IDLE",
  "deviceId": "a1b2c3d4-...",
  "faultStatus": false,
  "batteryLevel": 85,
  "ts": 1721800000000
}
```

#### 电动阀工作状态上报（主题：`v1/devices/me/telemetry`）

```json
{
  "deviceId": "a1b2c3d4-...",
  "instantFlow": 12.5,
  "totalWaterUsage": 3.24,
  "waterPressure": 0.35,
  "batteryLevel": 85,
  "ts": 1721800000000
}
```

#### RPC 下行指令（主题：`v1/devices/me/rpc/request/+`）

```json
{
  "method": "setValveState",
  "params": {
    "state": true
  }
}
```

#### RPC 上行回复（主题：`v1/devices/me/rpc/response/<requestId>`）

```json
{
  "result": "SUCCESS",
  "valveState": "WORKING"
}
```

---

## 微服务端定时任务模块

### 任务表数据结构

客户端向微服务端发送定时任务时，请求体包含以下四个字段：

```json
{
  "deviceId": "uuid-string",
  "startTime": 1721800000000,
  "endTime": 1721803600000,
  "status": "PENDING"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `deviceId` | string | 设备 ID |
| `startTime` | long | 任务开始时间（毫秒时间戳），默认当前时间 |
| `endTime` | long | 任务结束时间（毫秒时间戳），间隔 ≥ 1 分钟 |
| `status` | enum | 任务状态，新增时固定为 `PENDING` |

**四种状态定义：**

| 状态值 | 含义 | 说明 |
|--------|------|------|
| `PENDING` | 等待执行 | 新创建任务的初始状态 |
| `RUNNING` | 执行中 | 已到达开始时间正在执行 |
| `COMPLETED` | 已完成 | 已到达结束时间执行结束，记录保留供任务管理展示 |
| `CANCELLED` | 已取消 | 用户手动取消（软删除，不物理删除） |

### 微服务端定时调度机制（Spring Boot @Scheduled）

微服务端开启 Spring Boot 原生定时任务能力，使用 `@Scheduled` 注解驱动，**每 10 秒扫描一次数据库**。

```
@Scheduled(fixedRate = 10000)  // 每 10 秒执行一次
```

**说明：**
- 10 秒间隔完全可以满足灌溉任务的时间精度要求
- 不会给数据库带来性能压力

### 自动状态流转逻辑

每 10 秒扫描时，微服务端自动识别状态流转：

#### 流转一：PENDING → RUNNING

**条件：** `status = PENDING` 且当前系统时间 `>= startTime`

**动作：**
1. 将任务状态更新为 `RUNNING`
2. 触发对应的灌溉执行逻辑：调用 ThingsBoard RPC 开启/关闭设备

```
UPDATE task_schedule SET status = 'RUNNING' WHERE status = 'PENDING' AND startTime <= NOW();
→ POST /api/rpc/oneway/{deviceId} 发送控制指令
```

#### 流转二：RUNNING → COMPLETED

**条件：** `status = RUNNING` 且当前系统时间 `>= endTime`

**动作：**
1. 将任务状态更新为 `COMPLETED`
2. 触发关阀等收尾逻辑：调用 ThingsBoard RPC 发送反向指令
3. **保留任务记录**（状态 `COMPLETED`），供任务管理页展示；不物理删除

```
UPDATE task_schedule SET status = 'COMPLETED' WHERE status = 'RUNNING' AND endTime <= NOW();
→ POST /api/rpc/oneway/{deviceId} 发送反向指令
→ 任务记录保留（COMPLETED），不做物理删除
```

#### 流转三：跳过已完成/已取消

**条件：** `status = COMPLETED` 或 `status = CANCELLED`

**动作：** 跳过所有已经是 `COMPLETED` / `CANCELLED` 的任务，避免重复执行。

---

### 微服务端定时任务完整执行流程

```
┌─────────────────────────────────────────────────────────────┐
│                     定时任务全生命周期                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ① 用户创建任务                                              │
│     APP → POST /api/tasks → 微服务端                         │
│     参数: {deviceId, startTime, endTime, status:"PENDING"}   │
│                                                             │
│  ② 冲突检测                                                  │
│     微服务端查询任务表（设备ID，开始时间，结束时间）            │
│     单选直接查，多选拆集合按 ID 查                            │
│                                                             │
│  ③ 写入数据库                                                │
│     无冲突 → INSERT（status 默认 PENDING）                     │
│     有冲突 → 拒绝加入，返回失败                                │
│                                                             │
│  ④ 定时扫描（Spring Boot @Scheduled，每 10 秒）               │
│     SELECT * FROM task_schedule                               │
│     WHERE status IN ('PENDING', 'RUNNING')                    │
│                                                             │
│  ⑤ 自动状态流转                                              │
│     PENDING + 当前时间 >= startTime → RUNNING → 执行 RPC      │
│     RUNNING + 当前时间 >= endTime  → COMPLETED → 关阀收尾    │
│     COMPLETED / CANCELLED → 跳过，避免重复执行                 │
│                                                             │
│  ⑥ 用户手动取消任务（软删除）                                  │
│     status = PENDING  → 置 CANCELLED（不物理删除）             │
│     status = RUNNING  → 先发暂停 RPC → 置 CANCELLED           │
│     status = COMPLETED / CANCELLED → 保持原状态，不可再取消    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```
