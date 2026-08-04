# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 设备属性设置 + 仪表板创建脚本
==============================================
1. 27 台设备 Server Attributes：type / deviceName / fieldName / fieldId
2. 9 个田块资产 Server Attributes：fieldName / deviceCount
3. 仪表板「智能灌溉总览」：每台设备每个属性一张 value_card 单值卡片（唯一被实证可显示的组件）
   - 田块：9 张（设备数）
   - 温湿度计：9 台 × 2（温度、湿度）= 18 张
   - 电动阀：18 台 × 6（状态/瞬时流量/累计用水/水压/电量/故障）= 108 张
4. 分配仪表板 + 实体给第一个 customer

组件选型说明（TB 4.3.1.3 实测）：
- entities_table / timeseries_table：pageSize=1，永远只显示 1 台设备（坑）
- html_card：Angular 模板不生效（原样显示）
- value_card（layout=centered）：单值卡片，device/asset 直绑可稳定显示 → 采用

用法：py tb_attributes_dashboard.py（幂等，可重跑）
"""
import json
import uuid

import requests

BASE = "http://localhost:8080"
USERNAME = "15079983758@163.com"
PASSWORD = "147258"


def login():
    r = requests.post(BASE + "/api/auth/login",
                      json={"username": USERNAME, "password": PASSWORD}, timeout=15)
    r.raise_for_status()
    return r.json()["token"]


def api(token):
    return {"X-Authorization": "Bearer {}".format(token), "Content-Type": "application/json"}


def get_all_entities(token, entity):
    H = api(token)
    out, page = [], 0
    while True:
        r = requests.get(BASE + "/api/tenant/{}Infos?pageSize=100&page={}".format(entity, page),
                         headers=H, timeout=15)
        d = r.json()
        out.extend(d.get("data", []))
        if len(out) >= d.get("totalElements", 0) or not d.get("data"):
            break
        page += 1
    return [{"id": x["id"]["id"], "name": x["name"], "type": x.get("type")} for x in out]


def get_customers(token):
    H = api(token)
    r = requests.get(BASE + "/api/customers?pageSize=50&page=0", headers=H, timeout=15)
    return [{"id": x["id"]["id"], "title": x["title"]} for x in r.json().get("data", [])]


def set_server_attributes(token, entity_type, entity_id, attrs):
    H = api(token)
    r = requests.post(BASE + "/api/plugins/telemetry/{}/{}/SERVER_SCOPE".format(entity_type, entity_id),
                      headers=H, json=attrs, timeout=15)
    return r.status_code in (200, 201)


def find_dashboard(token, title):
    H = api(token)
    r = requests.get(BASE + "/api/tenant/dashboards?pageSize=50&page=0&textSearch={}".format(title),
                     headers=H, timeout=15)
    for d in r.json().get("data", []):
        if d["title"] == title:
            return d
    return None


def get_relation_devices(token, asset_id):
    H = api(token)
    r = requests.get(BASE + "/api/relations?fromId={}&fromType=ASSET&relationType=Contains".format(asset_id),
                     headers=H, timeout=15)
    return [x["to"]["id"] for x in r.json()]


def assign_to_customer(token, customer_id, entity_type, entity_id):
    H = api(token)
    r = requests.post(BASE + "/api/customer/{}/{}/{}".format(customer_id, entity_type, entity_id),
                      headers=H, timeout=15)
    return r.status_code in (200, 201)


def build_dashboard(devices, field_assets):
    """构造「智能灌溉总览」：每设备每属性一张 value_card"""
    sensors = [d for d in devices if d["type"] == "TEMPERATURE_HUMIDITY"]
    valves = [d for d in devices if d["type"] == "VALVE"]

    # value_card 的 dataKey（单值卡片，settings.type=SERVER 用于 attribute）
    def dkey(name, label, color, ktype="timeseries", scope=None):
        s = {"type": scope} if scope else {}
        return {"name": name, "label": label, "type": ktype, "color": color,
                "settings": s, "_hash": round(abs(hash(name)) % 1000 / 1000, 3)}

    def value_card_widget(dtype, entity_id, title, dk, size_x, size_y, row, col):
        ds = [{"type": dtype, "name": "", "deviceId": entity_id if dtype == "device" else None,
               "assetId": entity_id if dtype == "asset" else None,
               "dataKeys": [dk], "alarmFilterConfig": {"statusList": ["ACTIVE"]}}]
        ds[0].pop("deviceId", None) if dtype != "device" else None
        ds[0].pop("assetId", None) if dtype != "asset" else None
        cfg = {"datasources": ds,
               "settings": {
                   "labelPosition": "top", "layout": "centered", "showLabel": True,
                   "labelFont": {"family": "Roboto", "size": 12, "sizeUnit": "px",
                                 "style": "normal", "weight": "500"},
                   "labelColor": {"type": "constant", "color": "rgba(0, 0, 0, 0.87)"},
                   "showUnits": True, "showDate": False,
                   "unitsColor": {"type": "constant", "color": "rgba(0, 0, 0, 0.54)"},
                   "valueFont": {"family": "Roboto", "size": 20, "sizeUnit": "px",
                                 "style": "normal", "weight": "500"}
               },
               "title": title, "showTitle": True, "showTitleIcon": False,
               "showTitleButtons": False, "backgroundColor": "rgba(0, 0, 0, 0)",
               "color": "rgba(0, 0, 0, 0.87)", "padding": "4px", "dropShadow": True,
               "enableFullscreen": True,
               "timewindow": {"realtime": {"timewindowMs": 60000}}}
        return {"id": str(uuid.uuid4()), "typeFullFqn": "system.cards.value_card",
                "type": "latest", "title": title,
                "sizeX": size_x, "sizeY": size_y, "row": row, "col": col, "config": cfg}

    widgets = {}

    def add(w):
        widgets[w["id"]] = w

    row = 0
    # ① 田块卡片 x9（设备数，asset 数据源 + attribute SERVER）
    for i, a in enumerate(field_assets):
        add(value_card_widget("asset", a["id"], a["name"] + " · 设备数",
                              dkey("deviceCount", "设备数", "#4caf50", "attribute", "SERVER"),
                              8, 3, row + i // 3 * 3, i % 3 * 8))
    row += 9
    # ② 温湿度卡片 x18（9 台 × 温度/湿度）
    for i, d in enumerate(sensors):
        short = d["name"].replace("-温湿度计", "")
        add(value_card_widget("device", d["id"], short + " · 温度(℃)",
                              dkey("temperature", "温度", "#f44336"),
                              12, 3, row + i * 3, 0))
        add(value_card_widget("device", d["id"], short + " · 湿度(%RH)",
                              dkey("humidity", "湿度", "#2196f3"),
                              12, 3, row + i * 3, 12))
    row += 9 * 3
    # ③ 电动阀卡片 x108（18 台 × 6 属性）
    valve_keys = [
        ("valveState", "状态", "#ff9800"),
        ("instantFlow", "瞬时流量(L/min)", "#4caf50"),
        ("totalWaterUsage", "累计用水(m³)", "#2196f3"),
        ("waterPressure", "水压(MPa)", "#9c27b0"),
        ("batteryLevel", "电量(%)", "#ff5722"),
        ("faultStatus", "故障", "#f44336"),
    ]
    for i, d in enumerate(valves):
        short = d["name"].replace("田地", "").replace("-灌溉阀门", "阀门")
        for j, (k, label, color) in enumerate(valve_keys):
            add(value_card_widget("device", d["id"], short + " · " + label,
                                  dkey(k, label, color),
                                  4, 3, row + i * 3, j * 4))
    row += 18 * 3

    configuration = {
        "description": "智能灌溉总览",
        "entityAliases": {},
        "widgets": widgets,
        "states": {
            "default": {
                "name": "Default", "root": True,
                "layouts": {
                    "main": {
                        "widgets": {wid: {"sizeX": w["sizeX"], "sizeY": w["sizeY"],
                                          "row": w["row"], "col": w["col"]}
                                    for wid, w in widgets.items()},
                        "gridSettings": {"layoutType": "default", "backgroundColor": "#fafafa",
                                         "columns": 24, "margin": 10, "outerMargin": 10},
                        "resolved": False
                    }
                }
            }
        },
        "filters": {},
        "timewindow": {"realtime": {"timewindowMs": 60000}},
        "settings": {"stateControllerId": "0", "showTitle": False,
                     "showDashboardsSelection": False, "showEntitiesSelection": False,
                     "showFilters": False, "showTimewindow": True, "showWidgetsSelection": False}
    }
    return configuration


def main():
    print("=== 登录 ===")
    token = login()
    H = api(token)
    print("登录成功")

    print("\n=== 1. 获取设备/资产列表 ===")
    devices = get_all_entities(token, "device")
    assets = get_all_entities(token, "asset")
    field_assets = [a for a in assets if a["type"] == "FIELD"]
    print("  设备 {} 台，田块资产 {} 个".format(len(devices), len(field_assets)))

    print("\n=== 2. 设置设备 Server Attributes ===")
    ok_dev = 0
    for d in devices:
        field_name = d["name"].rsplit("-", 1)[0] if "-" in d["name"] else ""
        field_id = next((a["id"] for a in field_assets if a["name"] == field_name), None)
        attrs = {"type": d["type"], "deviceName": d["name"],
                 "fieldName": field_name, "fieldId": field_id or ""}
        if set_server_attributes(token, "DEVICE", d["id"], attrs):
            ok_dev += 1
    print("  已设置设备属性 {}/{}".format(ok_dev, len(devices)))

    print("\n=== 3. 设置田块资产 Server Attributes ===")
    ok_asset = 0
    for a in field_assets:
        dev_ids = get_relation_devices(token, a["id"])
        attrs = {"fieldName": a["name"], "deviceCount": len(dev_ids)}
        if set_server_attributes(token, "ASSET", a["id"], attrs):
            ok_asset += 1
        print("    {}: deviceCount={}".format(a["name"], len(dev_ids)))
    print("  已设置田块属性 {}/{}".format(ok_asset, len(field_assets)))

    print("\n=== 4. 创建/更新仪表板「智能灌溉总览」 ===")
    configuration = build_dashboard(devices, field_assets)
    existing = find_dashboard(token, "智能灌溉总览")
    body = {"title": "智能灌溉总览", "configuration": configuration}
    if existing:
        body["id"] = {"entityType": "DASHBOARD", "id": existing["id"]["id"]}
        r = requests.post(BASE + "/api/dashboard", headers=H, json=body, timeout=30)
        r.raise_for_status()
        dashboard_id = r.json()["id"]["id"]
        print("  已更新仪表板: {} ({})".format(r.json()["title"], dashboard_id[:8]))
    else:
        r = requests.post(BASE + "/api/dashboard", headers=H, json=body, timeout=30)
        r.raise_for_status()
        dashboard_id = r.json()["id"]["id"]
        print("  已创建仪表板: {} ({})".format(r.json()["title"], dashboard_id[:8]))

    print("\n=== 5. 分配给客户（customer 用户可见） ===")
    customers = get_customers(token)
    if not customers:
        print("  [跳过] 无客户")
    else:
        c = customers[0]
        print("  客户: {}".format(c["title"]))
        assign_to_customer(token, c["id"], "dashboard", dashboard_id)
        print("  仪表板已分配")
        n_dev = sum(1 for d in devices if assign_to_customer(token, c["id"], "device", d["id"]))
        print("  设备已分配 {}/{}".format(n_dev, len(devices)))
        n_ast = sum(1 for a in field_assets if assign_to_customer(token, c["id"], "asset", a["id"]))
        print("  田块资产已分配 {}/{}".format(n_ast, len(field_assets)))

    print("\n=== 完成 ===")
    print("浏览器访问 ThingsBoard UI (http://localhost:8080) -> 仪表板 -> 智能灌溉总览")
    print("注：customer 用户（客户端）登录同样可见；实体/仪表板均已分配")


if __name__ == "__main__":
    main()
