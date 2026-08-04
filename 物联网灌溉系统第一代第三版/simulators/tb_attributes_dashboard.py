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
   - 田块总览表（Entities table：fieldName/deviceCount）
   - 温湿度实时表（Entities table：temperature/humidity/ts）
   - 电动阀状态表（Entities table：valveState/batteryLevel/instantFlow/totalWaterUsage/waterPressure/faultStatus）
   - 温湿度历史曲线（Timeseries Line Chart：temperature/humidity）
   - 阀门流量曲线（Timeseries Line Chart：instantFlow/totalWaterUsage/waterPressure）

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


def build_dashboard(devices, field_assets):
    """构造「智能灌溉总览」dashboard configuration（TB 4.x JSON）"""
    alias_ids = {}

    def alias_id(kind):
        uid = str(uuid.uuid4())
        alias_ids[kind] = uid
        return uid

    # ---- 实体别名 ----
    entity_aliases = {
        alias_id("fields"): {"alias": "全部田块",
                             "filter": {"type": "entityType", "entityType": "ASSET", "assetType": "FIELD"}},
        alias_id("sensors"): {"alias": "全部温湿度计",
                              "filter": {"type": "entityType", "entityType": "DEVICE",
                                         "deviceType": "TEMPERATURE_HUMIDITY"}},
        alias_id("valves"): {"alias": "全部电动阀",
                             "filter": {"type": "entityType", "entityType": "DEVICE",
                                        "deviceType": "VALVE"}},
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
        return [{"type": "entity", "name": alias_ids[alias_kind], "entityAliasId": alias_ids[alias_kind],
                 "dataKeys": keys}]

    def widget(wid, wtype_ref, title, ds, size_x, size_y, row, col, timewindow=None):
        cfg = {"datasources": ds, "settings": {}, "title": title, "showTitle": True,
               "showTitleIcon": False, "showTitleButtons": True,
               "timewindow": timewindow or {"realtime": {"timewindowMs": 60000}}}
        return {"id": wid, "type": wtype_ref["widgetType"], "title": title,
                "sizeX": size_x, "sizeY": size_y, "row": row, "col": col,
                "config": cfg, "widgetType": wtype_ref}

    widgets = {}

    def add(w):
        widgets[w["id"]] = w

    # w1 田块总览表
    add(widget(str(uuid.uuid4()),
               {"name": "Entities table", "fqn": "cards.entities_table", "widgetType": "latest"},
               "田块总览（设备数量）",
               datasource("fields", [key("fieldName", "田块", "#2196f3", "attribute"),
                                     key("deviceCount", "设备数", "#4caf50", "attribute")]),
               12, 7, 0, 0))
    # w2 温湿度实时表
    add(widget(str(uuid.uuid4()),
               {"name": "Entities table", "fqn": "cards.entities_table", "widgetType": "latest"},
               "温湿度计实时数据",
               datasource("sensors", [key("temperature", "温度(℃)", "#f44336"),
                                      key("humidity", "湿度(%RH)", "#2196f3"),
                                      key("ts", "时间", "#9e9e9e")]),
               12, 7, 0, 12))
    # w3 电动阀状态表
    add(widget(str(uuid.uuid4()),
               {"name": "Entities table", "fqn": "cards.entities_table", "widgetType": "latest"},
               "电动阀状态",
               datasource("valves", [key("valveState", "状态", "#ff9800"),
                                     key("instantFlow", "瞬时流量(L/min)", "#4caf50"),
                                     key("totalWaterUsage", "累计用水(m³)", "#2196f3"),
                                     key("waterPressure", "水压(MPa)", "#9c27b0"),
                                     key("batteryLevel", "电量(%)", "#ff5722"),
                                     key("faultStatus", "故障", "#f44336")]),
               12, 7, 7, 0))
    # w4 温湿度历史曲线
    if s1:
        add(widget(str(uuid.uuid4()),
                   {"name": "Timeseries Line Chart", "fqn": "charts.basic_timeseries",
                    "widgetType": "timeseries"},
                   "温湿度历史曲线（田地1-温湿度计）",
                   datasource("sensor1", [key("temperature", "温度", "#f44336"),
                                          key("humidity", "湿度", "#2196f3")]),
                   12, 7, 7, 12,
                   {"realtime": {"timewindowMs": 3600000}}))
    # w5 阀门流量曲线
    if v1:
        add(widget(str(uuid.uuid4()),
                   {"name": "Timeseries Line Chart", "fqn": "charts.basic_timeseries",
                    "widgetType": "timeseries"},
                   "阀门流量曲线（田地1-灌溉阀门A）",
                   datasource("valve1", [key("instantFlow", "瞬时流量", "#4caf50"),
                                         key("waterPressure", "水压", "#9c27b0")]),
                   12, 7, 14, 0,
                   {"realtime": {"timewindowMs": 3600000}}))

    configuration = {
        "entityAliases": entity_aliases,
        "widgets": widgets,
        "states": {
            "default": {
                "name": "Default", "root": True,
                "layouts": {
                    "main": {
                        "widgets": list(widgets.keys()),
                        "grid": {"columns": 24, "margin": 10, "bgColor": "#fafafa",
                                 "outerMargin": 10},
                        "resolved": False
                    }
                }
            }
        },
        "filters": {},
        "settings": {"stateControllerId": "0", "showTitle": False,
                     "showDashboardsSelection": True, "showEntitiesSelection": True,
                     "showFilters": True, "showTimewindow": True, "showWidgetsSelection": True}
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
    print("  设备 {} 台，田块资产 {} 个（FIELD {} 个）".format(
        len(devices), len(assets), len(field_assets)))

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
        body["id"] = existing["id"]["id"]
        r = requests.post(BASE + "/api/dashboard", headers=H, json=body, timeout=30)
        r.raise_for_status()
        print("  已更新仪表板: {} ({})".format(r.json()["title"], r.json()["id"]["id"][:8]))
    else:
        r = requests.post(BASE + "/api/dashboard", headers=H, json=body, timeout=30)
        r.raise_for_status()
        print("  已创建仪表板: {} ({})".format(r.json()["title"], r.json()["id"]["id"][:8]))

    print("\n=== 完成 ===")
    print("浏览器访问 ThingsBoard UI (http://localhost:8080) -> 仪表板 -> 智能灌溉总览")


if __name__ == "__main__":
    main()
