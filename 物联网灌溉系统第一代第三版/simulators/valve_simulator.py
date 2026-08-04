# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 电动阀模拟器
================================
模拟一台电动阀（内嵌流量计）接入 ThingsBoard：
- 通过 MQTT 使用设备 accessToken 认证
- 未工作(IDLE)：每 60 秒上报 {valveState, batteryLevel, faultStatus}（文档：每 30 分钟，演示加速）
- 工作(WORKING)：每 10 秒上报 {valveState, instantFlow, totalWaterUsage, waterPressure, batteryLevel}
- 支持 RPC：setValveState(开/关)、getValveStatus、pauseValve

用法：py valve_simulator.py <accessToken> [host] [port]
"""
import json
import random
import sys
import time

import paho.mqtt.client as mqtt

TELEMETRY_TOPIC = "v1/devices/me/telemetry"
RPC_REQUEST_TOPIC = "v1/devices/me/rpc/request/+"


class ValveSimulator:
    def __init__(self, token, host, port):
        self.token = token
        self.host = host
        self.port = port
        self.is_on = False            # 阀门是否开启
        self.battery = random.randint(70, 100)
        self.fault = False            # 是否故障
        self.instant_flow = 0.0       # 瞬时流量 L/min
        self.total_usage = random.uniform(1.0, 20.0)   # 累计用水量 m³
        self.water_pressure = 0.0     # 管道水压 MPa

        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self.client.username_pw_set(token)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message

    def on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code == 0:
            print("[MQTT] 连接成功 {}".format(self.host))
            self.client.subscribe(RPC_REQUEST_TOPIC)
        else:
            print("[MQTT] 连接失败 rc={}".format(reason_code))

    def on_message(self, client, userdata, msg):
        try:
            request = json.loads(msg.payload.decode("utf-8"))
            method = request.get("method")
            params = request.get("params") or {}
            request_id = msg.topic.rsplit("/", 1)[-1]
            print("[RPC] method={} params={} id={}".format(method, params, request_id))
            response = self.handle_rpc(method, params)
            self.client.publish("v1/devices/me/rpc/response/{}".format(request_id),
                                json.dumps(response), qos=1)
            print("[RPC] 回复: {}".format(response))
        except Exception as e:
            print("[RPC] 处理失败:", e)

    def handle_rpc(self, method, params):
        if method == "setValveState":
            state = params.get("state", False)
            self.is_on = bool(state)
            # 开启时给个初始流量
            if self.is_on:
                self.instant_flow = round(random.uniform(8.0, 15.0), 1)
                self.water_pressure = round(random.uniform(0.2, 0.4), 2)
            else:
                self.instant_flow = 0.0
                self.water_pressure = 0.0
            self.report()   # 状态变更立即上报，避免等下一个上报周期
            return {"result": "SUCCESS", "valveState": "WORKING" if self.is_on else "IDLE"}
        elif method == "getValveStatus":
            return {"result": "SUCCESS",
                    "valveState": "WORKING" if self.is_on else "IDLE",
                    "batteryLevel": self.battery, "faultStatus": self.fault}
        elif method == "pauseValve":
            self.is_on = False
            self.instant_flow = 0.0
            self.report()   # 暂停后立即上报 IDLE
            return {"result": "SUCCESS", "valveState": "IDLE", "paused": True}
        return {"result": "FAIL", "error": "unknown method: {}".format(method)}

    def report(self):
        """按当前状态组装遥测并上报"""
        data = {
            "valveState": "WORKING" if self.is_on else "IDLE",
            "batteryLevel": self.battery,
        }
        if self.is_on:
            # 工作状态：流量波动
            self.instant_flow = round(max(0.5, self.instant_flow + random.uniform(-0.8, 0.8)), 1)
            self.total_usage = round(self.total_usage + self.instant_flow * 10 / 60000, 3)
            self.water_pressure = round(max(0.1, self.water_pressure + random.uniform(-0.02, 0.02)), 2)
            data.update({
                "instantFlow": self.instant_flow,
                "totalWaterUsage": self.total_usage,
                "waterPressure": self.water_pressure,
            })
        else:
            data["faultStatus"] = self.fault
        # 电量缓慢下降
        if random.random() < 0.05 and self.battery > 10:
            self.battery -= 1
        self.client.publish(TELEMETRY_TOPIC, json.dumps(data), qos=1)
        print("[上报] {}".format(json.dumps(data)))

    def run(self):
        print("[启动] 电动阀模拟器 -> {}:{}  token={}".format(self.host, self.port, self.token))
        self.client.connect(self.host, self.port, keepalive=60)
        self.client.loop_start()
        self.report()   # 立即上报一条
        try:
            while True:
                if self.is_on:
                    time.sleep(10)        # 工作状态：每 10 秒上报（文档 valveopen）
                else:
                    time.sleep(60)        # 未工作：每 60 秒上报（文档 30 分钟，演示加速）
                self.report()
        except KeyboardInterrupt:
            print("退出")


def main():
    if len(sys.argv) < 2:
        print("用法: py valve_simulator.py <accessToken> [host] [port]")
        return
    token = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else "localhost"
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 1883
    ValveSimulator(token, host, port).run()


if __name__ == "__main__":
    main()
