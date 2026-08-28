# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 土壤墒情检测器模拟器
==================================
模拟一台土壤墒情检测器接入 ThingsBoard：
- 通过 MQTT 使用设备 accessToken 认证（token 作为 username）
- 每 1 小时上报一次 {soilSalinity, soilPh, ts, deviceId}（第三代第一版 §3.1）
- 盐分做"缓慢升高"模拟（灌溉/施肥积累），pH 围绕中性小幅波动，更接近真实农田
- 不可操作设备：不订阅 RPC，不接收任何下行控制指令（同温湿度计）

用法：
    py soil_simulator.py <accessToken> [host] [port]
    默认 host/port 取 config.py
"""
import random
import time

from mqtt_base import DeviceBase
import config


class SoilSimulator(DeviceBase):
    """土壤墒情检测器：只上报盐分与 pH，不支持 RPC"""

    # 墒情检测器不可操作：不订阅 RPC（同温湿度计）
    SUPPORTS_RPC = False

    def __init__(self, token, host, port, keepalive=config.KEEPALIVE):
        super().__init__(token, host, port, keepalive)
        # 初始土壤墒情（盐分 ppm，pH 值）
        self.soil_salinity = 400.0
        self.soil_ph = 7.0

    def report(self):
        """组装土壤墒情遥测并上报：soilSalinity/soilPh/ts/deviceId

        模拟逻辑（贴近真实）：
        - 盐分：每周期小幅上升（灌溉/施肥积累），上界 2000ppm（重盐碱），
          小概率"淋洗"下降（透水/大雨）5%~10%
        - pH：围绕中性 7.0 小幅波动，范围 5.5~8.5
        """
        # 盐分：默认缓慢上升 2~20，15% 概率"淋洗"下降 5%~10%
        if random.random() < 0.15:
            delta = -self.soil_salinity * random.uniform(0.05, 0.10)
        else:
            delta = random.uniform(2.0, 20.0)
        self.soil_salinity = round(min(2000.0, max(50.0, self.soil_salinity + delta)), 1)
        # pH：平缓波动（限制在 5.5~8.5）
        self.soil_ph = round(min(8.5, max(5.5, self.soil_ph + random.uniform(-0.05, 0.05))), 2)

        payload = {
            "soilSalinity": self.soil_salinity,
            "soilPh": self.soil_ph,
            "ts": int(time.time() * 1000),
            "deviceId": self.device_id,
        }
        self.publish(payload)


def main():
    import sys
    if len(sys.argv) < 2:
        print("用法: py soil_simulator.py <accessToken> [host] [port]")
        return
    token = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else config.HOST
    port = int(sys.argv[3]) if len(sys.argv) > 3 else config.PORT
    # 墒情检测器：固定 1 小时上报间隔（同温湿度计调度的低频设备）
    SoilSimulator(token, host, port).run(config.SOIL_INTERVAL)


if __name__ == "__main__":
    main()

