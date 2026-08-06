# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 设备模拟器公共基类
=================================
抽取 sensor / valve 两个模拟器的公共逻辑：
- MQTT 连接（accessToken 认证）、断线检测与自动重连
- 遥测上报（publish + 日志）
- RPC 下行处理骨架（订阅/解析/回复），具体方法由子类实现

用法：
    class MyDevice(DeviceBase):
        SUPPORTS_RPC = True
        def report(self): ...
        def handle_rpc(self, method, params): ...
        def is_working(self): ...
"""
import json
import time

import paho.mqtt.client as mqtt

# ThingsBoard 标准 MQTT 主题（见设备端运行规则定义）
TELEMETRY_TOPIC = "v1/devices/me/telemetry"
RPC_REQUEST_TOPIC = "v1/devices/me/rpc/request/+"
RPC_RESPONSE_PREFIX = "v1/devices/me/rpc/response/"


class DeviceBase:
    """设备模拟器基类：封装 MQTT 连接、上报与 RPC 骨架"""

    # 是否支持 RPC 下行（温湿度计不可操作，不订阅；电动阀支持）
    SUPPORTS_RPC = False

    def __init__(self, token, host, port, keepalive=60):
        self.token = token
        self.host = host
        self.port = port
        self.keepalive = keepalive
        # 上报字段 deviceId：真实 UUID 由 ThingsBoard 管理，这里用 token 前缀标识
        self.device_id = token[:20]

        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        self.client.username_pw_set(token)      # MQTT 认证：accessToken 作为 username（密码留空）
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        if self.SUPPORTS_RPC:
            self.client.on_message = self._on_message

    # ---------- MQTT 回调 ----------

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        """连接成功回调：订阅 RPC 下行主题（仅可操作设备）"""
        if reason_code == 0:
            print("[MQTT] 连接成功 {}".format(self.host))
            if self.SUPPORTS_RPC:
                self.client.subscribe(RPC_REQUEST_TOPIC)
        else:
            print("[MQTT] 连接失败 rc={}".format(reason_code))

    def _on_disconnect(self, client, userdata, flags, reason_code, properties=None):
        """断开回调：提示并标记（主循环里会尝试重连）"""
        print("[MQTT] 连接断开 rc={}，等待自动重连...".format(reason_code))

    def _on_message(self, client, userdata, msg):
        """RPC 下行处理：解析请求 -> 调用子类 handle_rpc -> 回复到 response 主题"""
        try:
            request = json.loads(msg.payload.decode("utf-8"))
            method = request.get("method")
            params = request.get("params") or {}
            request_id = msg.topic.rsplit("/", 1)[-1]
            print("[RPC] method={} params={} id={}".format(method, params, request_id))
            response = self.handle_rpc(method, params)
            self.client.publish(RPC_RESPONSE_PREFIX + request_id, json.dumps(response), qos=1)
            print("[RPC] 回复: {}".format(response))
        except Exception as e:
            print("[RPC] 处理失败:", e)

    # ---------- 上报 ----------

    def publish(self, data):
        """发布遥测到标准主题（QoS1）并打印日志"""
        self.client.publish(TELEMETRY_TOPIC, json.dumps(data), qos=1)
        print("[上报] {}".format(json.dumps(data)))

    def report(self):
        """组装并上报遥测数据（子类实现各自的业务数据）"""
        raise NotImplementedError

    # ---------- RPC（可操作设备子类实现） ----------

    def handle_rpc(self, method, params):
        """处理 RPC 方法（子类实现）；基类默认拒绝未知方法"""
        return {"result": "FAIL", "error": "unknown method: {}".format(method)}

    # ---------- 状态 ----------

    def is_working(self):
        """是否处于工作状态（决定上报频率；传感器恒为 False）"""
        return False

    # ---------- 连接与主循环 ----------

    def _ensure_connected(self):
        """检测到断线时自动重连（paho 不会自动重连，需手动）"""
        if not self.client.is_connected():
            print("[MQTT] 尝试重连 {}:{} ...".format(self.host, self.port))
            try:
                self.client.reconnect()
            except Exception as e:
                print("[MQTT] 重连失败: {}".format(e))

    def run(self, idle_interval, working_interval=None):
        """
        主循环：连接 -> 立即上报一条 -> 按状态频率周期上报
        @param idle_interval    未工作状态上报间隔（秒）
        @param working_interval 工作状态上报间隔（秒）；None 表示不区分状态
        """
        print("[启动] 模拟器 -> {}:{}  token={}".format(self.host, self.port, self.token))
        self.client.connect(self.host, self.port, keepalive=self.keepalive)
        self.client.loop_start()
        self.report()  # 上电后立即上报第一条（文档：设备上电后立即上报）
        try:
            while True:
                interval = working_interval if (working_interval and self.is_working()) else idle_interval
                time.sleep(interval)
                self._ensure_connected()
                self.report()
        except KeyboardInterrupt:
            print("退出")
