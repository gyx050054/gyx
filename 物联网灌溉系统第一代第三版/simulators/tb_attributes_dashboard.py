# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 设备属性设置 + 仪表板创建脚本
==============================================
1. 给 27 台设备设置 Server Attributes（文档 1.2 设备分类 / 需求 3.2,3.3）：
   - type        : VALVE / TEMPERATURE_HUMIDITY
   - fieldName   : 所属田块名
   - fieldId     : 所属田块资产 id
   - deviceName  : 设备名称
2. 给 9 个田块资产设置 Server Attributes：
   - fieldName   : 田块名
   - deviceCount : 田块内设备数量（1 温湿度计 + 2 电动阀 = 3）
3. 创建/更新仪表板「智能灌溉总览」：
   - 田块卡片 x9（value_card：fieldName/deviceCount）
   - 温湿度卡片 x9（value_card：temperature/humidity）
   - 电动阀卡片 x18（value_card：valveState/instantFlow/totalWaterUsage/waterPressure/batteryLevel/faultStatus）
   - 温湿度历史曲线（全部温湿度计）
   - 阀门流量曲线（全部电动阀）
4. 分配仪表板 + 全部实体给第一个 customer（客户用户可见）

注：不用 entities_table（TB 4.3.1.3 该组件查询 pageSize=1 只显示 1 个实体），改用
单设备 value_card 卡片（与「温度湿度」仪表板同款，已实证可正常显示）。

用法：py tb_attributes_dashboard.py
幂等：属性重复设置覆盖；仪表板已存在则更新。
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
    """entity: 'device' 或 'asset'，返回 [{id,name,type}]"""
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
    """POST /api/plugins/telemetry/{ENTITY_TYPE}/{id}/SERVER_SCOPE 设置服务端属性"""
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
    """查田块资产 Contains 的所有设备 id"""
    H = api(token)
    r = requests.get(BASE + "/api/relations?fromId={}&fromType=ASSET&relationType=Contains".format(asset_id),
                     headers=H, timeout=15)
    return [x["to"]["id"] for x in r.json()]


def assign_to_customer(token, customer_id, entity_type, entity_id):
    """把实体分配给客户（设备/资产/仪表板），幂等"""
    H = api(token)
    r = requests.post(BASE + "/api/customer/{}/{}/{}".format(customer_id, entity_type, entity_id),
                      headers=H, timeout=15)
    return r.status_code in (200, 201)


def build_dashboard(devices, field_assets):
    """构造「智能灌溉总览」dashboard（value_card 卡片 + 曲线）"""
    alias_ids = {}

    def alias_id(kind):
        uid = str(uuid.uuid4())
        alias_ids[kind] = uid
        return uid

    sensors = [d for d in devices if d["type"] == "TEMPERATURE_HUMIDITY"]
    valves = [d for d in devices if d["type"] == "VALVE"]

    # 曲线用 entityList alias（走 WS，可正常显示全部设备）
    entity_aliases = {
        alias_id("sensors"): {"alias": "全部温湿度计",
                              "filter": {"type": "entityList", "entityType": "DEVICE",
                                         "entityList": [d["id"] for d in sensors]}},
        alias_id("valves"): {"alias": "全部电动阀",
                             "filter": {"type": "entityList", "entityType": "DEVICE",
                                        "entityList": [d["id"] for d in valves]}},
    }
    # 每台设备/资产一个 singleEntity alias（卡片数据源用 type=entity + alias，兼容设备与资产）
    for i, a in enumerate(field_assets):
        entity_aliases[alias_id("f{}".format(i))] = {
            "alias": a["name"],
            "filter": {"type": "singleEntity",
                       "singleEntity": {"entityType": "ASSET", "id": a["id"]}}}
    for i, d in enumerate(sensors):
        entity_aliases[alias_id("s{}".format(i))] = {
            "alias": d["name"],
            "filter": {"type": "singleEntity",
                       "singleEntity": {"entityType": "DEVICE", "id": d["id"]}}}
    for i, d in enumerate(valves):
        entity_aliases[alias_id("v{}".format(i))] = {
            "alias": d["name"],
            "filter": {"type": "singleEntity",
                       "singleEntity": {"entityType": "DEVICE", "id": d["id"]}}}

    def key(name, label, color, ktype="timeseries"):
        return {"name": name, "label": label, "type": ktype, "color": color,
                "settings": {}, "_hash": round(abs(hash(name)) % 1000 / 1000, 3)}

    def card_widget(wid, alias_kind, title, keys, size_x, size_y, row, col):
        ds = [{"type": "entity", "name": alias_ids[alias_kind],
               "entityAliasId": alias_ids[alias_kind],
               "dataKeys": keys}]
        cfg = {"datasources": ds, "settings": {"layout": "column", "showLabel": True,
                                               "labelPosition": "top", "showUnits": True},
               "title": title, "showTitle": True, "showTitleIcon": False,
               "showTitleButtons": False, "backgroundColor": "rgba(0, 0, 0, 0)",
               "color": "rgba(0, 0, 0, 0.87)", "padding": "8px", "dropShadow": True,
               "enableFullscreen": True,
               "timewindow": {"realtime": {"timewindowMs": 60000}}}
        return {"id": wid, "typeFullFqn": "system.cards.value_card", "type": "latest",
                "title": title, "sizeX": size_x, "sizeY": size_y, "row": row, "col": col,
                "config": cfg}

    def curve_widget(wid, alias_kind, title, keys, row, col):
        ds = [{"type": "entity", "name": alias_ids[alias_kind], "entityAliasId": alias_ids[alias_kind],
               "dataKeys": keys}]
        cfg = {"datasources": ds, "settings": {}, "title": title, "showTitle": True,
               "showTitleIcon": False, "showTitleButtons": True,
               "backgroundColor": "rgba(0, 0, 0, 0)", "color": "rgba(0, 0, 0, 0.87)",
               "padding": "8px", "timewindow": {"realtime": {"timewindowMs": 3600000}}}
        return {"id": wid, "typeFullFqn": "system.charts.basic_timeseries", "type": "timeseries",
                "title": title, "sizeX": 12, "sizeY": 7, "row": row, "col": col,
                "config": cfg}

    widgets = {}

    def add(w):
        widgets[w["id"]] = w

    row = 0
    # ① 田块卡片 x9（3 列 x 3 行）
    for i, a in enumerate(field_assets):
        add(card_widget(str(uuid.uuid4()), "f{}".format(i), a["name"],
                        [key("fieldName", "田块", "#2196f3", "attribute"),
                         key("deviceCount", "设备数", "#4caf50", "attribute")],
                        8, 2, row + i // 3 * 2, i % 3 * 8))
    row += 6
    # ② 温湿度卡片 x9（3 列 x 3 行）
    for i, d in enumerate(sensors):
        add(card_widget(str(uuid.uuid4()), "s{}".format(i), d["name"].replace("-温湿度计", ""),
                        [key("temperature", "温度(℃)", "#f44336"),
                         key("humidity", "湿度(%RH)", "#2196f3")],
                        8, 3, row + i // 3 * 3, i % 3 * 8))
    row += 9
    # ③ 电动阀卡片 x18（6 列 x 3 行）
    for i, d in enumerate(valves):
        add(card_widget(str(uuid.uuid4()), "v{}".format(i), d["name"].replace("-灌溉阀门", "阀门"),
                        [key("valveState", "状态", "#ff9800"),
                         key("instantFlow", "流量(L/min)", "#4caf50"),
                         key("batteryLevel", "电量(%)", "#ff5722"),
                         key("faultStatus", "故障", "#f44336")],
                        4, 3, row + i // 6 * 3, i % 6 * 4))
    row += 9
    # ④ 温湿度曲线（全部温湿度计）
    add(curve_widget(str(uuid.uuid4()), "sensors", "温湿度历史曲线（全部温湿度计）",
                     [key("temperature", "温度", "#f44336"), key("humidity", "湿度", "#2196f3")],
                     row, 0))
    # ⑤ 阀门流量曲线（全部电动阀）
    add(curve_widget(str(uuid.uuid4()), "valves", "阀门流量曲线（全部电动阀）",
                     [key("instantFlow", "瞬时流量", "#4caf50"), key("waterPressure", "水压", "#9c27b0")],
                     row, 12))

    configuration = {
        "description": "智能灌溉总览",
        "entityAliases": entity_aliases,
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
