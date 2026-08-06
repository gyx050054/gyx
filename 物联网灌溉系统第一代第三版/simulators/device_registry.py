# -*- coding: utf-8 -*-
"""
智能灌溉系统 - 设备清单（inventory）读取工具
============================================
统一从 device_inventory.json 读取设备清单，供 start_all / test_irrigation 使用，
消除重复的读取逻辑。
"""
import json

import config


def load_inventory(limit=None):
    """
    读取全部设备清单
    @param limit 只取前 N 台（start_all --limit 用）；None=全部
    @return 设备 dict 列表：[{"deviceId","deviceName","type","accessToken",...}]
    """
    with open(config.INVENTORY_FILE, encoding="utf-8") as f:
        inv = json.load(f)
    return inv[:limit] if limit else inv


def find_device(device_name):
    """按设备名称查找清单项；未找到返回 None"""
    for it in load_inventory():
        if it.get("deviceName") == device_name:
            return it
    return None


def find_by_type(device_type):
    """按类型过滤（VALVE / TEMPERATURE_HUMIDITY）"""
    return [it for it in load_inventory() if it.get("type") == device_type]
