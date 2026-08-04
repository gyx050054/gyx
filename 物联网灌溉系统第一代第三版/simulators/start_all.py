# -*- coding: utf-8 -*-
"""
统一启动所有设备模拟器
========================
从 device_inventory.json 读取全部设备，为每台设备启动一个模拟器进程。
用法：py start_all.py [--limit N]   # 默认启动全部；--limit 只启动前 N 台
"""
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
INV = os.path.join(HERE, "device_inventory.json")
HOST = "localhost"
PORT = 1883


def load_inventory(limit=None):
    with open(INV, encoding="utf-8") as f:
        inv = json.load(f)
    if limit:
        inv = inv[:limit]
    return inv


def main():
    limit = None
    if "--limit" in sys.argv:
        limit = int(sys.argv[sys.argv.index("--limit") + 1])
    inv = load_inventory(limit)
    procs = []
    logdir = os.path.join(HERE, "logs")
    os.makedirs(logdir, exist_ok=True)

    for it in inv:
        script = "valve_simulator.py" if it["type"] == "VALVE" else "sensor_simulator.py"
        logf = open(os.path.join(logdir, "{}.log".format(it["deviceName"])), "a", encoding="utf-8")
        p = subprocess.Popen(
            [sys.executable, "-u", os.path.join(HERE, script), it["accessToken"], HOST, str(PORT)],
            stdout=logf, stderr=subprocess.STDOUT
        )
        procs.append((it["deviceName"], p))
        print("[启动] {} ({}) pid={}".format(it["deviceName"], it["type"], p.pid))
        time.sleep(0.3)

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
