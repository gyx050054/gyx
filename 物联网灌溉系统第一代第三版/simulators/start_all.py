# -*- coding: utf-8 -*-
"""
统一启动所有设备模拟器
========================
从 device_inventory.json 读取全部设备，为每台设备启动一个模拟器子进程，
日志写入 logs/ 目录。主进程每 10 秒检测子进程存活情况。

用法：py start_all.py [--limit N]   # 默认启动全部；--limit 只启动前 N 台
"""
import os
import subprocess
import sys
import time

import config
import device_registry


def main():
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])

    inv = device_registry.load_inventory(limit)
    procs = []
    os.makedirs(config.LOG_DIR, exist_ok=True)

    for it in inv:
        # 按设备类型选择对应模拟器脚本（类型枚举：VALVE / TEMPERATURE_HUMIDITY）
        script = "valve_simulator.py" if it["type"] == "VALVE" else "sensor_simulator.py"
        logf = open(os.path.join(config.LOG_DIR, "{}.log".format(it["deviceName"])), "a", encoding="utf-8")
        p = subprocess.Popen(
            [sys.executable, "-u", os.path.join(config.HERE, script), it["accessToken"], config.HOST, str(config.PORT)],
            stdout=logf, stderr=subprocess.STDOUT
        )
        procs.append((it["deviceName"], p))
        print("[启动] {} ({}) pid={}".format(it["deviceName"], it["type"], p.pid))
        time.sleep(0.3)   # 错峰启动，避免瞬时 MQTT 连接风暴

    print("\n共启动 {} 台模拟器，Ctrl+C 退出".format(len(procs)))
    try:
        while True:
            time.sleep(10)
            alive = sum(1 for _, p in procs if p.poll() is None)
            if alive < len(procs):
                dead = [n for n, p in procs if p.poll() is not None]
                print("[警告] 有进程退出: {}".format(dead))
    except KeyboardInterrupt:
        for n, p in procs:
            p.terminate()
        print("已停止")


if __name__ == "__main__":
    main()
