# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 温湿度计模拟器
================================
模拟一台温湿度计设备接入 ThingsBoard：
- 通过 MQTT 使用设备 accessToken 认证（token 作为 username）
- 每 10 分钟上报一次 {temperature, humidity, ts, deviceId}
- 使用 ThingsBoard 标准主题 v1/devices/me/telemetry
- 不可操作设备：不订阅 RPC，不接收任何下行控制指令（文档 2.1）

用法：
    py sensor_simulator.py <accessToken> [host] [port]
    默认 host/port 取 config.py（当前为本机局域网 IP:1883）
"""
import random
import time

from mqtt_base import DeviceBase
import config


class SensorSimulator(DeviceBase):
    """温湿度计模拟器：只上报温湿度，不支持 RPC"""

    # 温湿度计不可操作：不订阅 RPC（文档 2.1）
    SUPPORTS_RPC = False

    def __init__(self, token, host, port, keepalive=config.KEEPALIVE):
        super().__init__(token, host, port, keepalive)
        # 初始温湿度（后续每次上报做小幅随机波动）
        self.temperature = 26.0
        self.humidity = 60.0

    def report(self):
        """组装温湿度遥测并上报（文档 2.2：temperature/humidity/ts/deviceId）"""
        self.temperature = round(self.temperature + random.uniform(-0.3, 0.3), 1)
        self.humidity = round(min(99.0, max(10.0, self.humidity + random.uniform(-1.0, 1.0))), 1)
        payload = {
            "temperature": self.temperature,
            "humidity": self.humidity,
            "ts": int(time.time() * 1000),
            "deviceId": self.device_id,
        }
        self.publish(payload)


def main():
    import sys
    if len(sys.argv) < 2:
        print("用法: py sensor_simulator.py <accessToken> [host] [port]")
        return
    token = sys.argv[1]
    host = sys.argv[2] if len(sys.argv) > 2 else config.HOST
    port = int(sys.argv[3]) if len(sys.argv) > 3 else config.PORT
    # 温湿度计：固定 10 分钟上报间隔（文档 2.3），无工作/空闲区分
    SensorSimulator(token, host, port).run(config.SENSOR_INTERVAL)


if __name__ == "__main__":
    main()
