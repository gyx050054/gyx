# 智能灌溉田块管理 — 内部需求文档

> 文档版本：v1.3  
> 更新日期：2026-08-25  
> 适用范围：温度湿度传感器（不可操作设备）、电动阀（内嵌流量计，可操作设备）、微服务端定时任务、客户端交互流程

---

## 目录

1. [文档概述](#1-文档概述)
2. [温湿度计（不可操作设备）](#2-温湿度计不可操作设备)
3. [电动阀（内嵌流量计，可操作设备）](#3-电动阀内嵌流量计可操作设备)
4. [ThingsBoard MQTT 主题总览](#4-thingsboard-mqtt-主题总览)
5. [客户端数据读取规范](#5-客户端数据读取规范)
6. [附录：数据模型定义](#6-附录数据模型定义)
7. [账号与用户管理体系](#7-账号与用户管理体系)

---

## 1. 文档概述

### 1.1 系统角色定义

| 角色 | 说明 |
|------|------|
| **设备端** | 部署在农田现场的 IoT 硬件设备（传感器、执行器），通过 MQTT 协议与 ThingsBoard 通信 |
| **ThingsBoard 服务端** | 物联网平台，负责接收设备上行数据、存储时序数据、转发下行指令 |
| **微服务端** | 自定义定时任务调度服务，负责任务冲突检测、定时扫描、RPC 触发；另承担注册（代建租户/账号）与强制改密标记 |
| **客户端（APP）** | 用户交互界面，通过 REST API 与 ThingsBoard 和微服务端通信 |
| **系统管理员（SysAdmin）** | ThingsBoard 内置最高权限账号。仅在注册链路被微服务端后台调用：代创建租户、租户管理员并激活设置默认密码；凭证只存在于微服务端配置，**绝不下发 App** |
| **租户管理员（TENANT_ADMIN）** | 即"管理员/农户"。注册生成的第一账号；可建田块、加设备、管理本租户全部内容与成员，是**成员管理等管理操作的唯一操作者** |
| **客户（Customer，家庭）** | 一个"家庭/使用者集合"容器。用于把田块/设备可见范围分配给一组使用者；由租户管理员在成员管理中创建 |
| **客户用户（CUSTOMER_USER，使用者）** | 家庭成员，登录后仅能查看/操作被分配到其所在家庭的田块与设备 |

### 1.2 设备分类

| 设备类型 | 可操作性 | 说明 |
|----------|----------|------|
| **温湿度计** | ❌ 不可操作 | 仅上报数据，无任何下行控制指令 |
| **电动阀（内嵌流量计）** | ✅ 可操作 | 支持远程开启/关闭、定时任务、流量监测 |

### 1.3 通信架构![](物联网灌溉系统第一代第三版/流程图.png)



```
┌──────────────────┐     MQTT上行      ┌──────────────────┐
│                   │ ──────────────▶  │                  │
│   温湿度计         │   topic: v1/devices/me/telemetry│                  │
│   (不可操作)       │                  │                  │
│                   │                  │                  │
├──────────────────┤                  │   ThingsBoard    │
│                   │     MQTT上行      │   服务端          │
│   电动阀           │ ──────────────▶  │   (v4.0.1)      │
│   (内嵌流量计)     │   topic: v1/devices/me/telemetry│                  │
│                   │ /valveclose      │                  │
│                   │ ◀────────────── │                  │
│                   │   MQTT下行      / │                  │
│                   │   topic控制指令/   │                  │
└──────────────────┘              /    └────────┬─────────┘
                                /                │
                               /      REST API   │  RPC
                              /                  │
                             /           ┌───────▼─────────┐
          ┌───────▼─────────┐  	        │                  │
          |    微服务端 	  |          │   APP 客户端      │
          |          	     | ◀──────  │                  │
          └──────────────────┘          │                  │
                                        │  				  │
                                        │                  │
                                        └──────────────────┘
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
| 上报间隔 | **每 10分钟**主动上报一次 |
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
| 读取频率 | 建议客户端每 2分钟轮询一次获取最新数据 |
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
| 上报间隔 | 非工作状态下，每 **30分钟**上报一次（降低功耗） |
| 状态变更 | 如果状态从 IDLE 变为 WORKING，立即切换上报模式 |

> **演示/联调说明**：30 分钟间隔过长不利于联调观察，模拟器在演示环境下可加速（如每 60 秒），正式设备按 30 分钟执行。温湿度计同理（正式 10 分钟，演示可加速）。

#### 3.3.3 MQTT 主题

| 项目 | 内容 |
|------|------|
| 主题 | `v1/devices/me/telemetry` |

### 3.4 工作状态上报规则

**生效条件：** 设备处于**工作状态**（`valveState = WORKING`，阀门开启中）

#### 3.4.1 上报数据格式

| 字段 | 类型 | 必填 | 说明 | 示例 |
|------|------|------|------|------|
| `valveState` | string | ✓ | 开关状态 | `"WORKING"` |
| `deviceId` | string | ✓ | 设备唯一标识 | `"uuid-string"` |
| `instantFlow` | float | ✓ | 瞬时流量（单位：L/min） | `12.5` |
| `totalWaterUsage` | float | ✓ | 累计用水量（单位：m³） | `3.24` |
| `waterPressure` | float | ✓ | 管道水压（单位：MPa） | `0.35` |
| `batteryLevel` | int | ✓ | 设备电量（百分比 0~100） | `85` |
| `ts` | long | ✓ | 上报时间戳 | `1721800000000` |

> **注意**：工作状态上报必须携带 `valveState = "WORKING"`，客户端登录/刷新时查询 `keys=valveState` 才能拿到最新工作状态（见 3.2 与 6.1：valveState 每次上报）。

**示例 JSON（工作状态）：**
```json
{
  "deviceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "valveState": "WORKING",
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
┌──────────┐    REST API     ┌──────────────┐    MQTT下行      ┌──────────┐
│           │  ────────────▶  │              │  ───────────▶ │           │
│ APP客户端  │                │ ThingsBoard   │                │  电动阀   │
│           │  ◀──────────── │  服务端       │  ◀──────────── │           │
│           │   REST API      │              │   MQTT响应      │           │
└──────────┘                └──────────────┘                 └──────────┘
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
1. ThingsBoard 收到 REST请求后
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
| 下行 | `v1/devices/me/rpc/request/+` | 服务端→设备，发送RPC指令 |
| 上行(回复) | `v1/devices/me/rpc/response/<requestId>` | 设备→服务端，回复RPC执行结果 |

#### 3.5.4 支持的 RPC 方法

| 方法名 | 参数 | 说明 |
|--------|------|------|
| `setValveState` | `{ "state": true/false }` | 开启/关闭阀门 |
| `getValveStatus` | `{}` | 查询阀门当前状态（用于登录校验） |
| `pauseValve` | `{}` | 暂停任务执行（用于删除运行中任务）。**语义：等效关闭阀门**——设备收到后立即关阀，并上报 `valveState=IDLE`（用于取消运行中任务时停止浇水） |

---

## 4. ThingsBoard MQTT 主题总览

### 4.1 上行主题（设备 → ThingsBoard）

| 设备类型 | MQTT 主题 | 上报频率 | 说明 |
|----------|-----------|----------|------|
| 温湿度计 | `v1/devices/me/telemetry` | 每 10分钟 | 温度、湿度 |
| 电动阀（未工作） | `v1/devices/me/telemetry` | 每 30分钟 | 电量、故障、状态 |
| 电动阀（工作中） | `v1/devices/me/telemetry` | 每 10 秒 | 流量、水压、电量 |
| 电动阀（RPC响应） | `v1/devices/me/rpc/response/<requestId>` | 按需 | RPC回复 |

### 4.2 下行主题（ThingsBoard → 设备）

| MQTT 主题 | 说明 |
|-----------|------|
| `v1/devices/me/rpc/request/+` | RPC 远程控制指令 |

### 4.3 通信协议汇总

```
温湿度计：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每10分钟 ── 温度/湿度/时间/设备ID
  └─ 下行 ── 无（不可操作设备）

电动阀（非工作状态）：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每30分钟 ── 开关状态/设备ID/故障状态/电量
  └─ 下行 ── topic: v1/devices/me/rpc/request/+ ── 远程控制

电动阀（工作状态）：
  └─ 上行 ── topic: v1/devices/me/telemetry ── 每10秒 ── 设备ID/瞬时流量/累计用水量/水压/电量
  └─ 下行 ── topic: v1/devices/me/rpc/request/+ ── 远程控制
```

---

## 5. 客户端数据读取规范

### 5.1 温湿度计数据读取

| 用途 | API | 参数 | 说明 |
|------|-----|------|------|
| 获取最新温湿度 | `GET /api/plugins/telemetry/DEVICE/{deviceId}/values/timeseries` | `keys=temperature,humidity` | limit默认为1，返回最新值 |
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

> **注意事项**：
> 1. `GET /api/tenant/deviceInfos` 为分页接口，**必须携带分页参数**（如 `pageSize=100&page=0`，同时用 `sortProperty`/`sortOrder` 固定排序），否则按默认分页返回。
> 2. `type` 参数匹配的是**设备 Profile 类型**（Device Profile 的 type），需保证电动阀所属 Profile 的类型名为 `VALVE`，或改用 `deviceProfileId` 精确过滤。
> 3. `keys=valveState` 查到的最新一条数据即设备当前工作状态；若某设备从无上报，则查不到该 key（视为未知状态，不做工作判定）。

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
| 电动阀 | `instantFlow` | float | 瞬时流量 | L/min | 工作状态每10秒 |
| 电动阀 | `totalWaterUsage` | float | 累计用水量 | m³ | 工作状态每10秒 |
| 电动阀 | `waterPressure` | float | 管道水压 | MPa | 工作状态每10秒 |

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
  "valveState": "WORKING",
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



## 微服务端定时任务完整执行流程

```
┌─────────────────────────────────────────────────────────────┐
│                     定时任务全生命周期                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ① 用户创建任务                                              │
│     APP → POST /api/tasks → 微服务端                         │
│                                                             │
│  ② 冲突检测                                                  │
│     微服务端查询 任务表 （设备id，开始时间，结束时间）			  |
|	如果是单选直接查，多选就拆集合，按id查						  |
│															  |
│  ③(无冲突) 写入数据库   有冲突拒绝加入任务             		 │
│                                                             │
│  ④ 定时扫描（每 10 秒）                                      │
│                                                             │
│  ⑤ 执行任务                                                  │
│                                                             │
│  ⑥ 到达结束时间，任务自动完成（COMPLETED，记录保留）             │
│  ⑦ 用户手动取消任务（软删除，置 CANCELLED）                     │
│    未开始(PENDING) → 直接取消                                   │
│    进行中(RUNNING) → 先发暂停 RPC → 再取消                      │
└─────────────────────────────────────────────────────────────┘
```

登录界面

\1. 用户未登录时只能访问登录界面，登录直接发 api 给 ThingsBoard 服务端验证登录

农田界面

\2. 农田界面可以查看所有农田信息，发 api 给 ThingsBoard 查询信息（农田的设备数量、农田名称、农田 ID）。点击某个农田会返回该农田下所有的设备列表及设备信息（温度湿度计返回：设备 id、温度、湿度、设备安装的农田、记录的时间；电动阀返回：当前状态、电量）。

\3. 农田界面单击某一个可操作设备可以选择对其操作开启或者关闭，发 api 给 ThingsBoard 操作{设备id, 操作开/关(布尔类型)}；可以添加任务发给微服务端{设备id, 开始时间（默认立刻），结束时间（间隔时间以 1min 起步）}。微服务端检验设备 id 是否有冲突：无冲突则返回添加成功，有冲突不让添加，返回失败。如果添加成功了，微服务端在任务表数据库里加上一条新数据（id, 开始时间，结束时间）；数据库不为空时，每隔 10 秒取出一条数据来建立任务，到了时间自动去执行开启/关闭；执行完微服务端将任务置为已完成（COMPLETED），记录保留供任务管理展示。

\4. 如果多选设备，发 api 给 ThingsBoard 操作{设备id, 操作开/关(布尔类型)}；添加定时任务可以发{list[设备ID], 开始时间（默认立刻），结束时间}。微服务端接收到后拆解 id 列表，按顺序逐条（id, 开始时间，结束时间）写入数据库，每写一条都检查该设备 id 是否有冲突：任一设备有冲突则所有任务都不允许添加，返回失败；全部无冲突则全部添加成功。批量任务用 id 拆分成一条条数据，任务管理时也可方便对具体设备单独删除。

\5. 任务管理，返回所有任务表里的数据（含已完成/已取消）；取消未开始的任务可直接取消（置 CANCELLED）；取消已经开始的任务先给设备发暂停（pauseValve，等效关阀），再置 CANCELLED（软删除，不物理删除）

设备界面

直接返回所有的设备列表，操作界面与农田界面里具体一块田里的设备界面相同，

---

## 7. 账号与用户管理体系

> 章节对应已实现功能（客户端 App + 微服务端），覆盖：注册、首次登录强制改密、手动改密、成员管理、常见问题（FAQ）。

### 7.1 认证链路总览

登录验证始终由客户端直连 **ThingsBoard**（`POST /api/auth/login`）完成，服务端不接管登录。注册与改密标记由 **微服务端** 承担（`/api/auth/**`）。

| 后端 | 承担 | 说明 |
|------|------|------|
| ThingsBoard（TB） | 登录、改密执行 | `POST /api/auth/login`、`POST /api/auth/changePassword` |
| 微服务端 | 注册建号、强制改密标记、成员登记 | `POST /api/auth/register`、`GET/POST /api/auth/must-change-password|mark-must-change|pwd-changed` |

### 7.2 注册

**入口：** 登录页「注册」，输入邮箱。

**流程** `POST /api/auth/register {"email":"xxx"}`（微服务端，以 SysAdmin 后台代建）：
1. 校验邮箱格式（服务端二次校验，非法返回 400 "邮箱格式不正确"）。
2. 创建租户（title=注册邮箱）。
3. 创建该租户的**租户管理员**（TENANT_ADMIN）。
4. 激活并设置**默认密码 `123456`**。
5. 在微服务端登记**强制改密标记**（user_pwd_flag），首次登录强制改密。

**结果：** 邮箱已注册返回 409 "该邮箱已注册"；TB 不可达等返回 500 通用错误；成功返回 200 "注册成功，请登录"。

**App 侧注册成功后：** 用「邮箱 + 默认密码 123456」**自动登录 TB**（用户无感），随后**跳转改密页**进入首次强制改密流程。

### 7.3 首次登录强制改密

1. 新账号（注册生成 / 成员管理中创建）均默认密码 `123456`，已登记强制改密。
2. App 登录前先查 `GET /api/auth/must-change-password?email=...`（微服务端）判断是否需强制改密（返回值 `mustChange`）。
3. 需改密时：App 调 TB `POST /api/auth/changePassword`，**代填当前密码 `123456`**（体验上"不需要旧密码"），用户输入新密码。
4. 改密成功：App 用新密码**重新登录 TB 拿新 JWT**（TB 改密会使旧 token 失效），再调 `POST /api/auth/pwd-changed {"email":...}` **清除强制改密标记**，进入系统。

### 7.4 手动修改密码

「我的」页可随时改密：App 调 TB `POST /api/auth/changePassword {currentPassword, newPassword}`（校验当前密码），成功后重新登录拿新 token。

### 7.5 成员管理（仅租户管理员可见可操作）

「成员管理」页（租户管理员专属），实体关系：**租户（公司）→ 客户（家庭）→ 客户用户（使用者）**，另有**同租户下的多个租户管理员**。

| 操作 | 说明 |
|------|------|
| 新增管理员 | 加入当前公司（同租户再建一个 TENANT_ADMIN）；创建后登记强制改密 |
| 新增使用者 | 加入**已有家庭**或**新建家庭**（CUSTOMER_USER）；创建后登记强制改密 |
| 删除成员账号 | 只删账号（`DELETE /api/user/{id}`），家庭/设备/任务保留 |
| 删除家庭 | `DELETE /api/customer/{id}`，其下成员账号级联删除，田块/设备/任务保留 |
| 分配可见范围 | 把田块/设备分配（assign）给某家庭：`POST /api/customer/{id}/asset/{aid}`、`POST /api/customer/{id}/device/{deviceId}`；被分配使用者仅见被赋权内容 |
| 删除管理员 | 不能删除自己（App 侧控制） |

**权限差异：**
- 租户管理员：查租户全局内容（`/api/tenant/assetInfos`、`/api/tenant/deviceInfos`），并拥有全部管理按钮。
- 客户用户（使用者）：仅查被分配的田块/设备（`/api/customer/{id}/assetInfos`、`/api/customer/{id}/deviceInfos`），无管理按钮；越权访问返回 403。

### 7.6 常见问题（FAQ）

登录页入口，静态页面，三个问题的通俗化说明：如何注册、为何不能随意建号（账号由系统/管理员维护）、为何首登要改密码。

---
