# -*- coding: utf-8 -*-
"""
ThingsBoard 智能灌溉系统 - 建模脚本
=====================================
按需求文档建立：
- 设备配置(DeviceProfile)：VALVE（电动阀）、TEMPERATURE_HUMIDITY（温湿度计）
- 资产配置(AssetProfile)：FIELD（田块）
- 9 个田块资产（田地1~田地9）
- 每田块 1 台温湿度计 + 2 台电动阀，共 27 台设备
- 田块 Contains 设备 关系
- 输出全部设备 accessToken 清单

用法：py tb_setup.py
幂等：可重复运行，已存在的实体跳过。
"""
import json
import sys
import time

import requests

BASE = "http://localhost:8080"
USERNAME = "15079983758@163.com"
PASSWORD = "147258"

# 命名方案
FIELD_NAMES = ["田地{}".format(i) for i in range(1, 10)]   # 田地1~田地9
VALVES_PER_FIELD = 2                                       # 每田块电动阀数


def login():
    r = requests.post(BASE + "/api/auth/login",
                      json={"username": USERNAME, "password": PASSWORD}, timeout=15)
    r.raise_for_status()
    return r.json()["token"]


def api(token):
    return {"X-Authorization": "Bearer {}".format(token), "Content-Type": "application/json"}


def get_or_create_device_profile(token, name):
    """按名称查找设备配置，不存在则创建（type=DEFAULT, transport=DEFAULT）"""
    H = api(token)
    r = requests.get(BASE + "/api/deviceProfileInfos?pageSize=100&page=0&textSearch={}".format(name),
                     headers=H, timeout=15)
    for p in r.json().get("data", []):
        if p["name"] == name:
            return p["id"]["id"], p
    body = {
        "name": name,
        "description": name,
        "type": "DEFAULT",
        "transportType": "DEFAULT",
        "profileData": {
            "configuration": {"type": "DEFAULT"},
            "transportConfiguration": {"type": "DEFAULT"},
            "provisionConfiguration": {"type": "DISABLED"}
        },
        "default": False
    }
    r = requests.post(BASE + "/api/deviceProfile", headers=H, json=body, timeout=15)
    r.raise_for_status()
    p = r.json()
    print("  [新建] 设备配置 {}".format(name))
    return p["id"]["id"], p


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
    """在 tenant/assetInfos 或 tenant/deviceInfos 中按名称查找，返回 id 或 None"""
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
    body = {
        "from": {"entityType": "ASSET", "id": asset_id},
        "type": "Contains",
        "to": {"entityType": "DEVICE", "id": device_id}
    }
    # 注意：创建关系端点是 POST /api/relation（/api/relations 是查询路径）
    r = requests.post(BASE + "/api/relation", headers=H, json=body, timeout=15)
    # 已存在时返回 400 也视为成功
    return r.status_code in (200, 201)


def ensure_field_type(token, asset_id, field_profile_id):
    """把已有资产也更新到 FIELD 资产配置（统一 type）"""
    H = api(token)
    r = requests.get(BASE + "/api/asset/{}".format(asset_id), headers=H, timeout=15)
    if r.status_code != 200:
        return
    asset = r.json()
    if asset.get("type") != "FIELD":
        asset["type"] = "FIELD"
        asset["assetProfileId"] = {"entityType": "ASSET_PROFILE", "id": field_profile_id}
        r2 = requests.post(BASE + "/api/asset", headers=H, json=asset, timeout=15)
        if r2.status_code in (200, 201):
            print("    [更新] {} 资产类型 -> FIELD".format(asset["name"]))
        else:
            print("    [警告] 更新资产类型失败: {}".format(r2.text[:100]))


def get_access_token(token, device_id):
    H = api(token)
    r = requests.get(BASE + "/api/device/{}/credentials".format(device_id), headers=H, timeout=15)
    r.raise_for_status()
    return r.json()["credentialsId"]


def main():
    print("=== 登录 ===")
    token = login()
    print("登录成功")

    print("\n=== 1. 设备配置 ===")
    valve_profile = get_or_create_device_profile(token, "VALVE")
    temp_profile = get_or_create_device_profile(token, "TEMPERATURE_HUMIDITY")
    print("  VALVE profile:", valve_profile[0])
    print("  TEMPERATURE_HUMIDITY profile:", temp_profile[0])

    print("\n=== 2. 资产配置 FIELD ===")
    field_profile = get_or_create_asset_profile(token, "FIELD")
    print("  FIELD profile:", field_profile)

    print("\n=== 3. 田块资产 + 设备 + 关系 ===")
    inventory = []  # [{field, deviceName, type, deviceId, accessToken}]
    for field_name in FIELD_NAMES:
        field_id = find_by_name("asset", field_name, token)
        if field_id is None:
            field_id = create_asset(token, field_name, field_profile)
            print("  [新建] 田块: {} ({})".format(field_name, field_id[:8]))
        else:
            print("  [已有] 田块: {} ({})".format(field_name, field_id[:8]))
        ensure_field_type(token, field_id, field_profile)

        # 温湿度计
        sensor_name = "{}-温湿度计".format(field_name)
        sensor_id = find_by_name("device", sensor_name, token)
        if sensor_id is None:
            sensor_id = create_device(token, sensor_name, temp_profile[0])
            print("    [新建] {}".format(sensor_name))
        ensure_relation(token, field_id, sensor_id)
        inventory.append({
            "field": field_name, "deviceName": sensor_name, "type": "TEMPERATURE_HUMIDITY",
            "deviceId": sensor_id, "accessToken": get_access_token(token, sensor_id)
        })

        # 电动阀
        for vi in range(1, VALVES_PER_FIELD + 1):
            valve_name = "{}-灌溉阀门{}".format(field_name, "AB"[vi - 1])
            valve_id = find_by_name("device", valve_name, token)
            if valve_id is None:
                valve_id = create_device(token, valve_name, valve_profile[0])
                print("    [新建] {}".format(valve_name))
            ensure_relation(token, field_id, valve_id)
            inventory.append({
                "field": field_name, "deviceName": valve_name, "type": "VALVE",
                "deviceId": valve_id, "accessToken": get_access_token(token, valve_id)
            })

    print("\n=== 4. 设备清单（共 {} 台） ===".format(len(inventory)))
    for it in inventory:
        print("  {:<8} {:<14} {:<20} {}".format(
            it["field"], it["type"], it["deviceName"], it["accessToken"]))

    # 保存清单供后续使用
    with open("device_inventory.json", "w", encoding="utf-8") as f:
        json.dump(inventory, f, ensure_ascii=False, indent=2)
    print("\n清单已保存: device_inventory.json")


if __name__ == "__main__":
    main()
