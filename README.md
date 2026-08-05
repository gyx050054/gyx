# 智能灌溉物联网系统（第一版）

面向农田灌溉管理的物联网平台：设备（MQTT）→ ThingsBoard（Docker）→ Android APP + 定时任务微服务端。

## 目录结构

```
物联网灌溉系统/
├── 物联网灌溉系统第一代第三版/    设备端模拟器 + 需求文档 + 流程图
│   └── simulators/              温湿度计/电动阀模拟器、ThingsBoard 建模脚本、设备清单
├── kotlin-demo/                 Android APP（Kotlin + Jetpack Compose + Retrofit）
│   └── app/
└── task-service/                微服务端（Spring Boot 定时任务调度）
```

## 三部分来源（原路径）

| 部分 | 来源目录 |
|------|----------|
| 设备端模拟器+文档 | `C:\Users\15079\Desktop\q\物联网灌溉系统第一代第三版` |
| Android APP | `C:\Users\15079\Desktop\q\kotlin-demo` |
| 微服务端 | `C:\Users\15079\Desktop\java\webtest\task-service` |

> 本仓库为第一版快照（从上述路径复制），此后如继续开发请以本仓库为准或保持同步。

## 一键启动

- 双击 **`一键启动.bat`**（或运行 `start-all.ps1`）按依赖顺序启动整套系统：
  1. Docker Desktop → Docker MySQL（容器 `mysql57`，宿主 `:3307`，库 `task_service`）
  2. ThingsBoard（`docker compose up -d`，UI `http://localhost:8080`，MQTT `:1883`）
  3. 微服务端 `task-service`（`:9300`，`mvn spring-boot:run`）
  4. 设备模拟器 27 台（`simulators/start_all.py`）
  5. Android 模拟器 Pixel_7 + 安装并启动 APP
- 日志输出在 `logs/` 目录；重复执行会跳过已在运行的服务（幂等）。
- 停止：关闭 Android 模拟器窗口；`taskkill /F /IM python.exe` 停止设备模拟器；`docker compose -f C:SERSH79THINGSBOARDDOCKER-COMPOSE.YML DOWN` 停止 THINGSBOARD。

## 运行说明（摘要）

- **ThingsBoard**：Docker 部署，REST/UI `:8080`，MQTT `:1883`，Edge `:17070`（宿主机 7070 被 Windows 保留端口占用）。
- **模拟器**：`simulators/start_all.py` 统一启动 27 台设备（9 田块 × 1 温湿度计 + 2 电动阀）；先运行 `tb_setup.py` 建模并生成 `device_inventory.json`。
- **微服务端**：`task-service`，Spring Boot，端口 `9300`，数据库 MySQL `:3307/task_service`；每 10 秒扫描任务表，冲突检测 `s1<e2 && e1>s2`。
- **APP**：模拟器 baseUrl `10.0.2.2:8080`（真机改局域网 IP，见 `ApiClient.kt`）。

## 版本记录

- **v1.0（第一版提交）**：设备端模拟器、Android APP、微服务端三部分初始快照。
