# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 温湿度计模拟器
================================
模拟一台温湿度计设备接入 ThingsBoard：
- 通过 MQTT 使用设备 accessToken 认证
- 每 10 分钟上报一次 {temperature, humidity, ts, deviceId}
- 使用 ThingsBoard 标准主题 v1/devices/me/telemetry
- 不可操作设备：不订阅 RPC，不接收任何下行控制指令（文档 2.1）

用法：
    py sensor_simulator.py <accessToken> [host] [port]
    默认 host=localhost, port=1883
"""
import json
import random
import sys
import time

import paho.mqtt.client as mqtt

TELEMETRY_TOPIC = "v1/devices/me/telemetry"

# 温湿度计不可操作：仅上报数据，无任何下行控制指令（文档 2.1）
current_temp = 26.0
current_hum = 60.0


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        print("[MQTT] 连接成功 (host={})".format(userdata))
    else:
        print("[MQTT] 连接失败, rc={}".format(reason_code))


def main():
    if len(sys.argv) < 2:
        print("用法: py sensor_simulator.py <accessToken> [host] [port]")
        return
    token = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "localhost"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 1883

    global current_temp, current_hum
    device_id = token  # 实际 deviceId 由 ThingsBoard 管理，这里用 token 前缀标识
    print("[启动] 温湿度计模拟器 -> {}:{}  token={}".format(host, port, token))

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, userdata=(host, port))
    client.username_pw_set(token)
    client.on_connect = on_connect
    client.connect(host, port, keepalive=60)
    client.loop_start()

    # 上电后立即上报第一条
    def report():
        global current_temp, current_hum
        current_temp = round(current_temp + random.uniform(-0.3, 0.3), 1)
        current_hum = round(min(99.0, max(10.0, current_hum + random.uniform(-1.0, 1.0))), 1)
        payload = {
            "temperature": current_temp,
            "humidity": current_hum,
            "ts": int(time.time() * 1000),
            "deviceId": device_id[:20],
        }
        client.publish(TELEMETRY_TOPIC, json.dumps(payload), qos=1)
        print("[上报] {}".format(json.dumps(payload)))

    report()  # 立即上报第一条
    while True:
        time.sleep(600)  # 每 10 分钟上报一次（文档 2.3）
        report()


if __name__ == "__main__":
    main()
