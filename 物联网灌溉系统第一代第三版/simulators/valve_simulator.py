# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 电动阀模拟器
================================
模拟一台电动阀（内嵌流量计）接入 ThingsBoard：
- 通过 MQTT 使用设备 accessToken 认证（token 作为 username）
- 未工作(IDLE)：每 60 秒上报 {valveState, deviceId, faultStatus, batteryLevel, ts}（文档：每 30 分钟，演示加速）
- 工作(WORKING)：每 10 秒上报 {valveState, deviceId, instantFlow, totalWaterUsage, waterPressure, batteryLevel, ts}
- 支持 RPC：setValveState(开/关)、getValveStatus、pauseValve（文档 3.5.4）

用法：py valve_simulator.py <accessToken> [host] [port]
"""
import random
import time

from mqtt_base import DeviceBase
import config


class ValveSimulator(DeviceBase):
    """电动阀模拟器：可操作设备，支持 RPC 控制与流量/电量模拟"""

    # 电动阀可操作：订阅 RPC 下行主题（文档 3.5.3）
    SUPPORTS_RPC = True

    # 固定电量白名单：命中的设备电量固定为指定值，不再随机初始化和衰减。
    # key —— device accessToken；value —— 固定的 batteryLevel(%)。
    # （用途：演示/联调时把特定阀门电量钉在某个值，如田地1的两台阀固定 30%。）
    FIXED_BATTERY = {
        "g2rg3Ii6ZiZloVuVHCzD": 30,   # 田地1-灌溉阀门A
        "l8WKqmKSRqsXA57UoH2B": 30,   # 田地1-灌溉阀门B
    }

    def __init__(self, token, host, port, keepalive=config.KEEPALIVE):
        super().__init__(token, host, port, keepalive)
        self.is_on = False          # 阀门是否开启
        # 电量：命中固定电量白名单则钉死该值；否则随机初始化（70-100）
        self.battery = self.FIXED_BATTERY.get(token, random.randint(70, 100))
        self.fixed_battery = token in self.FIXED_BATTERY  # 是否固定电量（fixed 时不衰减）
        self.fault = False          # 是否故障
        self.instant_flow = 0.0     # 瞬时流量 L/min
        self.total_usage = random.uniform(1.0, 20.0)  # 累计用水量 m³
        self.water_pressure = 0.0   # 管道水压 MPa

    # ---------- 状态 ----------

    def is_working(self):
        """工作状态判定：阀门开启即高频上报（文档 3.4.2：每 10 秒）"""
        return self.is_on

    # ---------- RPC 处理（文档 3.5.4） ----------

    def handle_rpc(self, method, params):
        if method == "setValveState":
            state = params.get("state", False)
            self.is_on = bool(state)
            # 开启时给个初始流量/水压
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
        return super().handle_rpc(method, params)

    # ---------- 上报 ----------

    def report(self):
        """按当前状态组装遥测并上报（文档 3.3.1 未工作 / 3.4.1 工作状态）"""
        data = {
            "valveState": "WORKING" if self.is_on else "IDLE",
            "deviceId": self.device_id,
            "batteryLevel": self.battery,
            "ts": int(time.time() * 1000),
        }
        if self.is_on:
            # 工作状态：流量小幅波动 + 累计用水量递增 + 水压波动
            self.instant_flow = round(max(0.5, self.instant_flow + random.uniform(-0.8, 0.8)), 1)
            self.total_usage = round(self.total_usage + self.instant_flow * 10 / 60000, 3)
            self.water_pressure = round(max(0.1, self.water_pressure + random.uniform(-0.02, 0.02)), 2)
            data.update({
                "instantFlow": self.instant_flow,
                "totalWaterUsage": self.total_usage,
                "waterPressure": self.water_pressure,
            })
        else:
            # 未工作状态：只报故障标记（文档 3.3.1）
            data["faultStatus"] = self.fault
        # 电量缓慢下降（5% 概率 -1，模拟长时间运行电量耗尽）
        # 固定电量白名单内的设备电量钉死，不参与衰减
        if self.fixed_battery:
            self.battery = self.FIXED_BATTERY[self.token]
        elif random.random() < 0.05 and self.battery > 10:
            self.battery -= 1
        self.publish(data)


def main():
    import sys
    if len(sys.argv) < 2:
        print("用法: py valve_simulator.py <accessToken> [host] [port]")
        return
    token = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else config.HOST
    port = int(sys.argv[3]) if len(sys.argv) > 3 else config.PORT
    # 电动阀：工作 10 秒 / 未工作 60 秒（文档 3.4.2 / 演示加速）
    ValveSimulator(token, host, port).run(config.VALVE_IDLE_INTERVAL, config.VALVE_WORKING_INTERVAL)


if __name__ == "__main__":
    main()
