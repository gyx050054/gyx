# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 模拟器全局配置（集中管理）
=========================================
所有模拟器脚本共用的配置项统一放这里，消除散落各文件的硬编码与漂移。

修改指南：
- 换服务器：改 HOST / PORT（当前为修复 Windows mosquitto 抢占 1883 后的本机局域网 IP）
- 换上报频率：改各 *_INTERVAL
- TB 管理凭据：TB_USERNAME / TB_PASSWORD（用于 tb_setup 等建模脚本）
"""
import os

# ---------- MQTT 连接 ----------
# 说明：localhost 被 Windows mosquitto 服务抢占 127.0.0.1:1883，
#       故改连本机局域网 IP 直达 Docker 端口 -> ThingsBoard（详见 git 提交 fabb336）
HOST = "localhost"  # TB 在本地 Docker，MQTT 1883 直连（原 192.168.50.138 为本机旧 IP，DHCP 变更后失效）
PORT = 1883
KEEPALIVE = 60

# ---------- 上报频率（秒） ----------
SENSOR_INTERVAL = 600        # 温湿度计：每 10 分钟（文档 2.3；原值，未加速）
VALVE_IDLE_INTERVAL = 60     # 电动阀未工作：每 60 秒（文档 30 分钟，演示加速）
VALVE_WORKING_INTERVAL = 10  # 电动阀工作：每 10 秒（文档 3.4.2）

# ---------- 路径 ----------
HERE = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(HERE, "logs")            # start_all 的子进程日志目录
INVENTORY_FILE = os.path.join(HERE, "device_inventory.json")  # 设备清单（tb_setup 生成）

# ---------- ThingsBoard REST（建模/测试脚本用） ----------
TB_BASE_URL = "http://localhost:8080"
TB_USERNAME = "15079983758@163.com"
TB_PASSWORD = "147258"
