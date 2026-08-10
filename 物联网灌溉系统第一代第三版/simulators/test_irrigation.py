# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 全链路一键测试程序
==================================
验证三大部件 + 完整业务链路是否可联调：

测试计划步骤：
  A. ThingsBoard REST 连通
     A1. 登录拿 token
     A2. 查询田块资产（应为 9 个，type=FIELD）
     A3. 查询田地1 设备（应为 3 台：1 温湿度计 + 2 电动阀）
  B. 遥测数据
     B1. 读田地1-温湿度计最新遥测（temperature/humidity 有值）
     B2. 读田地1-灌溉阀门A 最新遥测（valveState/batteryLevel 有值）
  C. RPC 控制链路
     C1. twoway 开启阀门 → 回执 WORKING → 遥测更新
     C2. twoway 关闭阀门 → 回执 IDLE
  D. 微服务端（定时任务）
     D1. 创建任务（now+3s 开始，now+20s 结束）→ 成功
     D2. 等待执行 → 任务状态 RUNNING / 阀门自动开启
     D3. 创建冲突任务（时间段重叠）→ 应被拒绝
     D4. 删除任务 → 阀门关闭（发暂停）

用法：py test_irrigation.py
"""
import json
import sys
import time

import requests

TB = "http://localhost:8080"
SVC = "http://localhost:9300"   # 微服务端端口（第二版起为 9300）
USERNAME = "15079983758@163.com"
PASSWORD = "147258"

INVENTORY = None
TOKEN = None

# 测试结果收集
results = []


def check(name, ok, detail=""):
    tag = "PASS" if ok else "FAIL"
    results.append((name, ok))
    print("  [{:4s}] {:<36s} {}".format(tag, name, detail))


def login():
    global TOKEN
    r = requests.post(TB + "/api/auth/login",
                      json={"username": USERNAME, "password": PASSWORD}, timeout=15)
    r.raise_for_status()
    TOKEN = r.json()["token"]
    return TOKEN


def h():
    return {"X-Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}


def load_inventory():
    global INVENTORY
    with open("device_inventory.json", encoding="utf-8") as f:
        INVENTORY = json.load(f)


def find_device(name):
    return next((it for it in INVENTORY if it["deviceName"] == name), None)


def find_asset_id(name):
    r = requests.get(TB + "/api/tenant/assetInfos?pageSize=200&page=0", headers=h(), timeout=15)
    for it in r.json().get("data", []):
        if it["name"] == name:
            return it["id"]["id"]
    return None


def main():
    print("=" * 60)
    print("智能灌溉系统全链路测试开始")
    print("=" * 60)
    load_inventory()

    # ---------- A. ThingsBoard REST ----------
    print("\n[A] ThingsBoard REST 连通性")
    try:
        login()
        check("A1 登录拿 token", len(TOKEN) > 50, "token 长度 {}".format(len(TOKEN)))
    except Exception as e:
        check("A1 登录拿 token", False, str(e))
        print("\n登录失败，终止测试")
        return

    # A2 田块资产
    try:
        r = requests.get(TB + "/api/tenant/assetInfos?pageSize=200&page=0", headers=h(), timeout=15)
        assets = r.json().get("data", [])
        fields = [a for a in assets if a.get("type") == "FIELD"]
        check("A2 田块资产(应9)", len(fields) == 9, "实际 {} 个".format(len(fields)))
    except Exception as e:
        check("A2 田块资产", False, str(e))

    # A3 田地1 设备（通过关系）
    try:
        field1 = find_asset_id("田地1")
        r = requests.get(TB + "/api/relations/from/ASSET/{}".format(field1), headers=h(), timeout=15)
        devs = [rel for rel in r.json() if rel["to"]["entityType"] == "DEVICE"]
        check("A3 田地1设备(应3)", len(devs) == 3, "实际 {} 台".format(len(devs)))
    except Exception as e:
        check("A3 田地1设备", False, str(e))

    # ---------- B. 遥测 ----------
    print("\n[B] 遥测数据")
    sensor = find_device("田地1-温湿度计")
    valve = find_device("田地1-灌溉阀门A")

    def latest(device_id, keys):
        r = requests.get(
            TB + "/api/plugins/telemetry/DEVICE/{}/values/timeseries".format(device_id),
            params={"keys": keys, "limit": 1, "orderBy": "DESC"}, headers=h(), timeout=15)
        d = r.json()
        return {k: (v[0]["value"] if v else None) for k, v in d.items()}

    try:
        t = latest(sensor["deviceId"], "temperature,humidity")
        ok = t.get("temperature") is not None and t.get("humidity") is not None
        check("B1 温湿度计遥测", ok, "温度={} 湿度={}".format(t.get("temperature"), t.get("humidity")))
    except Exception as e:
        check("B1 温湿度计遥测", False, str(e))

    try:
        v = latest(valve["deviceId"], "valveState,batteryLevel")
        ok = v.get("valveState") is not None
        check("B2 电动阀遥测", ok, "状态={} 电量={}".format(v.get("valveState"), v.get("batteryLevel")))
    except Exception as e:
        check("B2 电动阀遥测", False, str(e))

    # ---------- C. RPC 控制 ----------
    print("\n[C] RPC 控制链路")
    try:
        # 开
        r = requests.post(TB + "/api/rpc/twoway/{}".format(valve["deviceId"]), headers=h(),
                          json={"method": "setValveState", "params": {"state": True}}, timeout=20)
        resp = r.json()
        check("C1 开启阀门(回执WORKING)", resp.get("valveState") == "WORKING", str(resp))
        time.sleep(3)
        v = latest(valve["deviceId"], "valveState")
        check("C1b 遥测已更新WORKING", v.get("valveState") == "WORKING", "遥测={}".format(v.get("valveState")))
        # 关
        r = requests.post(TB + "/api/rpc/twoway/{}".format(valve["deviceId"]), headers=h(),
                          json={"method": "setValveState", "params": {"state": False}}, timeout=20)
        resp = r.json()
        check("C2 关闭阀门(回执IDLE)", resp.get("valveState") == "IDLE", str(resp))
        time.sleep(2)
        v = latest(valve["deviceId"], "valveState")
        check("C2b 遥测已更新IDLE", v.get("valveState") == "IDLE", "遥测={}".format(v.get("valveState")))
    except Exception as e:
        check("C RPC 控制", False, str(e))

    # ---------- D. 微服务端任务 ----------
    print("\n[D] 微服务端（定时任务）")
    task_id = None
    try:
        now = int(time.time() * 1000)
        r = requests.post(SVC + "/api/tasks", json={
            "deviceId": valve["deviceId"], "deviceName": "田地1-灌溉阀门A",
            "startTime": now + 3000, "endTime": now + 20000, "action": "on"}, timeout=10)
        resp = r.json()
        task_id = resp.get("taskId")
        check("D1 创建任务", resp.get("success") is True and task_id is not None, str(resp))
    except Exception as e:
        check("D1 创建任务", False, str(e))

    # 等待执行：微服务端每 10 秒扫描一次，最多等 18 秒，并轮询阀门状态
    status = "PENDING"
    valve_on = False
    for _ in range(9):
        time.sleep(2)
        try:
            r = requests.get(SVC + "/api/tasks", timeout=10)
            mine = [t for t in r.json() if t["id"] == task_id]
            if mine:
                status = mine[0]["status"]
            v = latest(valve["deviceId"], "valveState")
            valve_on = v.get("valveState") == "WORKING"
            if status == "RUNNING" and valve_on:
                break
        except Exception:
            pass
    check("D2 任务自动执行", status == "RUNNING", "任务状态={}".format(status))
    check("D2b 阀门已被任务开启", valve_on, "遥测WORKING={}".format(valve_on))

    # 冲突检测
    try:
        now = int(time.time() * 1000)
        r = requests.post(SVC + "/api/tasks", json={
            "deviceId": valve["deviceId"], "deviceName": "冲突任务",
            "startTime": now + 5000, "endTime": now + 30000, "action": "on"}, timeout=10)
        resp = r.json()
        check("D3 冲突任务被拒", resp.get("success") is False, str(resp))
    except Exception as e:
        check("D3 冲突任务被拒", False, str(e))

    # 删除任务（运行中 → 发暂停）
    try:
        r = requests.delete(SVC + "/api/tasks/{}".format(task_id), timeout=10)
        resp = r.json()
        check("D4 删除任务", resp.get("success") is True, str(resp))
        time.sleep(3)
        v = latest(valve["deviceId"], "valveState")
        check("D4b 删除后阀门关闭(暂停)", v.get("valveState") == "IDLE", "遥测={}".format(v.get("valveState")))
    except Exception as e:
        check("D4 删除任务", False, str(e))

    # ---------- 汇总 ----------
    print("\n" + "=" * 60)
    passed = sum(1 for _, ok in results if ok)
    total = len(results)
    print("测试汇总: {}/{} 通过".format(passed, total))
    for name, ok in results:
        if not ok:
            print("  [FAIL] " + name)
    print("=" * 60)
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
