# -*- coding: utf-8 -*-
"""
add_devices.py — 给指定租户添加模拟设备（需求1：新注册租户也能建田块+设备）
==========================================================================
用法：
  python add_devices.py --email <租户管理员邮箱> --password <密码> \
      --field-name 我的田 --valves 2 --sensors 1 --tenant-id <可选,指定租户>

说明：
  - 用【该租户自己的账号】登录 ThingsBoard，为它创建田块（资产）+ 电动阀/温湿度计设备，
    并建立「田块 Contains 设备」关系；输出设备清单（device_inventory.json 格式）供模拟器连接。
  - 幂等：已存在的田块/设备自动复用，不重复创建。
  - 与 tb_setup.py 共用建模函数，保持数据模型一致（VALVE / TEMPERATURE_HUMIDITY / SOIL_MOISTURE）。

依赖：requests（pip install requests）
"""
import argparse
import json
import os
import sys

import requests

# 默认连接本地 ThingsBoard（实际部署时改成云端 IP:8080）
BASE = "http://localhost:8080"


def login(email, password):
    r = requests.post(BASE + "/api/auth/login",
                      json={"username": email, "password": password}, timeout=15)
    r.raise_for_status()
    return r.json()["token"]


def api(token):
    return {"X-Authorization": "Bearer {}".format(token), "Content-Type": "application/json"}


def get_or_create_device_profile(token, name):
    H = api(token)
    r = requests.get(BASE + "/api/deviceProfileInfos?pageSize=100&page=0&textSearch={}".format(name),
                     headers=H, timeout=15)
    for p in r.json().get("data", []):
        if p["name"] == name:
            return p["id"]["id"]
    body = {
        "name": name, "description": name, "type": "DEFAULT", "transportType": "DEFAULT",
        "profileData": {
            "configuration": {"type": "DEFAULT"},
            "transportConfiguration": {"type": "DEFAULT"},
            "provisionConfiguration": {"type": "DISABLED"}
        },
        "default": False
    }
    r = requests.post(BASE + "/api/deviceProfile", headers=H, json=body, timeout=15)
    r.raise_for_status()
    print("  [新建] 设备配置 {}".format(name))
    return r.json()["id"]["id"]


def get_or_create_asset_profile(token, name):
    H = api(token)
    r = requests.get(BASE + "/api/assetProfileInfos?pageSize=100&page=0&textSearch={}".format(name),
                     headers=H, timeout=15)
    for p in r.json().get("data", []):
        if p["name"] == name:
            return p["id"]["id"]
    body = {"name": name, "description": name, "type": "ASSET"}
    r = requests.post(BASE + "/api/assetProfile", headers=H, json=body, timeout=15)
    r.raise_for_status()
    print("  [新建] 资产配置 {}".format(name))
    return r.json()["id"]["id"]


def find_by_name(entity, name, token):
    H = api(token)
    url = BASE + "/api/tenant/{}Infos?pageSize=200&page=0&textSearch={}".format(entity, name)
    r = requests.get(url, headers=H, timeout=15)
    for it in r.json().get("data", []):
        if it["name"] == name:
            return it["id"]["id"]
    return None


def create_asset(token, name, asset_profile_id):
    H = api(token)
    body = {"name": name, "type": "FIELD",
            "assetProfileId": {"entityType": "ASSET_PROFILE", "id": asset_profile_id}}
    r = requests.post(BASE + "/api/asset", headers=H, json=body, timeout=15)
    r.raise_for_status()
    return r.json()["id"]["id"]


def create_device(token, name, profile_id):
    H = api(token)
    body = {"name": name,
            "deviceProfileId": {"entityType": "DEVICE_PROFILE", "id": profile_id}}
    r = requests.post(BASE + "/api/device", headers=H, json=body, timeout=15)
    r.raise_for_status()
    return r.json()["id"]["id"]


def ensure_relation(token, asset_id, device_id):
    H = api(token)
    body = {"from": {"entityType": "ASSET", "id": asset_id},
            "type": "Contains",
            "to": {"entityType": "DEVICE", "id": device_id}}
    r = requests.post(BASE + "/api/relation", headers=H, json=body, timeout=15)
    return r.status_code in (200, 201)


def get_access_token(token, device_id):
    H = api(token)
    r = requests.get(BASE + "/api/device/{}/credentials".format(device_id), headers=H, timeout=15)
    r.raise_for_status()
    return r.json()["credentialsId"]


def main():
    parser = argparse.ArgumentParser(description="给租户添加田块+模拟设备")
    parser.add_argument("--email", required=True, help="租户管理员邮箱（TB 登录账号）")
    parser.add_argument("--password", required=True, help="租户管理员密码")
    parser.add_argument("--base", default=BASE, help="ThingsBoard 地址，默认 http://localhost:8080")
    parser.add_argument("--field-name", default="我的田", help="田块名称")
    parser.add_argument("--valves", type=int, default=2, help="电动阀数量")
    parser.add_argument("--sensors", type=int, default=1, help="温湿度计数量")
    parser.add_argument("--soil", type=int, default=0, help="墒情检测器数量")
    parser.add_argument("--out", default="device_inventory_add.json", help="输出设备清单文件")
    args = parser.parse_args()

    global BASE
    BASE = args.base

    print("=== 登录租户 {} ===".format(args.email))
    token = login(args.email, args.password)
    print("登录成功")

    print("\n=== 设备/资产配置（幂等） ===")
    valve_profile = get_or_create_device_profile(token, "VALVE")
    temp_profile = get_or_create_device_profile(token, "TEMPERATURE_HUMIDITY")
    soil_profile = get_or_create_device_profile(token, "SOIL_MOISTURE")
    field_profile = get_or_create_asset_profile(token, "FIELD")
    print("  配置就绪")

    print("\n=== 田块: {} ===".format(args.field_name))
    field_id = find_by_name("asset", args.field_name, token)
    if field_id is None:
        field_id = create_asset(token, args.field_name, field_profile)
        print("  [新建] 田块 {}".format(args.field_name))
    else:
        print("  [复用] 田块 {}".format(args.field_name))

    inventory = []
    # 电动阀
    for i in range(1, args.valves + 1):
        name = "{}-灌溉阀门{}".format(args.field_name, "AB"[i - 1] if i <= 2 else i)
        did = find_by_name("device", name, token)
        if did is None:
            did = create_device(token, name, valve_profile)
            print("  [新建] 设备 {}".format(name))
        else:
            print("  [复用] 设备 {}".format(name))
        ensure_relation(token, field_id, did)
        inventory.append({"field": args.field_name, "deviceName": name, "type": "VALVE",
                          "deviceId": did, "accessToken": get_access_token(token, did)})
    # 温湿度计
    for i in range(1, args.sensors + 1):
        name = "{}-温湿度计".format(args.field_name) if args.sensors == 1 else "{}-温湿度计{}".format(args.field_name, i)
        did = find_by_name("device", name, token)
        if did is None:
            did = create_device(token, name, temp_profile)
            print("  [新建] 设备 {}".format(name))
        else:
            print("  [复用] 设备 {}".format(name))
        ensure_relation(token, field_id, did)
        inventory.append({"field": args.field_name, "deviceName": name, "type": "TEMPERATURE_HUMIDITY",
                          "deviceId": did, "accessToken": get_access_token(token, did)})
    # 墒情检测器
    for i in range(1, args.soil + 1):
        name = "{}-墒情检测器".format(args.field_name) if args.soil == 1 else "{}-墒情检测器{}".format(args.field_name, i)
        did = find_by_name("device", name, token)
        if did is None:
            did = create_device(token, name, soil_profile)
            print("  [新建] 设备 {}".format(name))
        else:
            print("  [复用] 设备 {}".format(name))
        ensure_relation(token, field_id, did)
        inventory.append({"field": args.field_name, "deviceName": name, "type": "SOIL_MOISTURE",
                          "deviceId": did, "accessToken": get_access_token(token, did)})

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(inventory, f, ensure_ascii=False, indent=2)
    print("\n设备清单已保存: {}（共 {} 台）".format(args.out, len(inventory)))
    print("提示：把 {} 复制为 device_inventory.json 后，用 start_all.py 启动模拟器连接这些设备。".format(args.out))


if __name__ == "__main__":
    main()
