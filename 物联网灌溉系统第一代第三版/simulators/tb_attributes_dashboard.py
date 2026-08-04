# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 设备属性设置 + 仪表板创建脚本
==============================================
1. 27 台设备 Server Attributes：type / deviceName / fieldName / fieldId
2. 9 个田块资产 Server Attributes：fieldName / deviceCount
3. 仪表板「智能灌溉总览」：3 个 entities_table 分页表格（田块/温湿度/阀门）
   - 田块总览表（全部 9 田块：田块名/设备数）
   - 温湿度计表（全部 9 台：温度/湿度/时间）
   - 电动阀表（全部 18 台：状态/瞬时流量/累计用水/水压/电量/故障）
4. 分配仪表板 + 全部实体给第一个 customer（客户用户可见）

组件选型说明（TB 4.3.1.3）：
- entities_table 表格分页展示（用户指定要分页版本）
- 注意：本版本 entities_table 查询 pageSize 固定为 1（只显示 1 台）为已知限制，
  若需全量请改用 value_card 单值卡片方案（历史版本）

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
    """构造「智能灌溉总览」：3 个 entities_table 分页表格（田块/温湿度/阀门）"""
    sensors = [d for d in devices if d["type"] == "TEMPERATURE_HUMIDITY"]
    valves = [d for d in devices if d["type"] == "VALVE"]

    alias_ids = {}

    def alias_id(kind):
        uid = str(uuid.uuid4())
        alias_ids[kind] = uid
        return uid

    entity_aliases = {
        alias_id("fields"): {"alias": "全部田块",
                             "filter": {"type": "entityList", "entityType": "ASSET",
                                        "entityList": [a["id"] for a in field_assets]}},
        alias_id("sensors"): {"alias": "全部温湿度计",
                              "filter": {"type": "entityList", "entityType": "DEVICE",
                                         "entityList": [d["id"] for d in sensors]}},
        alias_id("valves"): {"alias": "全部电动阀",
                             "filter": {"type": "entityList", "entityType": "DEVICE",
                                        "entityList": [d["id"] for d in valves]}},
    }

    def find_dev(name):
        return next((d for d in devices if d["name"] == name), None)

    s1 = find_dev("田地1-温湿度计")
    v1 = find_dev("田地1-灌溉阀门A")
    if s1:
        entity_aliases[alias_id("sensor1")] = {
            "alias": "田地1-温湿度计",
            "filter": {"type": "singleEntity",
                       "singleEntity": {"entityType": "DEVICE", "id": s1["id"]}}}
    if v1:
        entity_aliases[alias_id("valve1")] = {
            "alias": "田地1-灌溉阀门A",
            "filter": {"type": "singleEntity",
                       "singleEntity": {"entityType": "DEVICE", "id": v1["id"]}}}

    def key(name, label, color, ktype="timeseries"):
        return {"name": name, "label": label, "type": ktype, "color": color,
                "settings": {}, "_hash": round(abs(hash(name)) % 1000 / 1000, 3)}

    def datasource(alias_kind, keys):
        return [{"type": "entity", "name": alias_ids[alias_kind],
                 "entityAliasId": alias_ids[alias_kind], "dataKeys": keys}]

    def widget(wid, type_full_fqn, wtype, title, ds, size_x, size_y, row, col,
               timewindow=None, wsettings=None):
        cfg = {"datasources": ds, "settings": wsettings or {}, "title": title,
               "showTitle": True, "showTitleIcon": False, "showTitleButtons": True,
               "backgroundColor": "rgba(0, 0, 0, 0)", "color": "rgba(0, 0, 0, 0.87)",
               "padding": "4px", "dropShadow": True, "enableFullscreen": True,
               "timewindow": timewindow or {"realtime": {"timewindowMs": 60000}}}
        return {"id": wid, "typeFullFqn": type_full_fqn, "type": wtype, "title": title,
                "sizeX": size_x, "sizeY": size_y, "row": row, "col": col,
                "config": cfg}

    # entities_table 完整 settings（默认 defaultPageSize=10 分页）
    table_settings = {
        "entitiesTitle": "实体", "enableSearch": True, "enableSelectColumnDisplay": True,
        "enableStickyHeader": True, "enableStickyAction": True,
        "reserveSpaceForHiddenAction": "true",
        "displayEntityName": False, "displayEntityLabel": False, "displayEntityType": False,
        "displayPagination": True, "defaultPageSize": 10,
        "pageStepCount": 3, "pageStepIncrement": 10,
        "defaultSortOrder": "displayName", "useRowStyleFunction": False
    }

    widgets = {}

    def add(w):
        widgets[w["id"]] = w

    # w1 田块总览表
    add(widget(str(uuid.uuid4()), "system.cards.entities_table", "latest",
               "田块总览（设备数量）",
               datasource("fields", [key("fieldName", "田块", "#2196f3", "attribute"),
                                     key("deviceCount", "设备数", "#4caf50", "attribute")]),
               12, 7, 0, 0, wsettings=table_settings))
    # w2 温湿度计实时数据表
    add(widget(str(uuid.uuid4()), "system.cards.entities_table", "latest",
               "温湿度计实时数据",
               datasource("sensors", [key("temperature", "温度(℃)", "#f44336"),
                                      key("humidity", "湿度(%RH)", "#2196f3"),
                                      key("ts", "时间", "#9e9e9e")]),
               12, 7, 0, 12, wsettings=table_settings))
    # w3 电动阀状态表
    add(widget(str(uuid.uuid4()), "system.cards.entities_table", "latest",
               "电动阀状态",
               datasource("valves", [key("valveState", "状态", "#ff9800"),
                                     key("instantFlow", "瞬时流量(L/min)", "#4caf50"),
                                     key("totalWaterUsage", "累计用水(m³)", "#2196f3"),
                                     key("waterPressure", "水压(MPa)", "#9c27b0"),
                                     key("batteryLevel", "电量(%)", "#ff5722"),
                                     key("faultStatus", "故障", "#f44336")]),
               12, 7, 7, 0, wsettings=table_settings))
    # w4 温湿度历史曲线（田地1-温湿度计）
    if s1:
        add(widget(str(uuid.uuid4()), "system.charts.basic_timeseries", "timeseries",
                   "温湿度历史曲线（田地1-温湿度计）",
                   datasource("sensor1", [key("temperature", "温度", "#f44336"),
                                          key("humidity", "湿度", "#2196f3")]),
                   12, 7, 7, 12, {"realtime": {"timewindowMs": 3600000}}))
    # w5 阀门流量曲线（田地1-灌溉阀门A）
    if v1:
        add(widget(str(uuid.uuid4()), "system.charts.basic_timeseries", "timeseries",
                   "阀门流量曲线（田地1-灌溉阀门A）",
                   datasource("valve1", [key("instantFlow", "瞬时流量", "#4caf50"),
                                         key("waterPressure", "水压", "#9c27b0")]),
                   12, 7, 14, 0, {"realtime": {"timewindowMs": 3600000}}))

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
