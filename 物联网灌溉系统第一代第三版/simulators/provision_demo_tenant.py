# -*- coding: utf-8 -*-
"""
provision_demo_tenant.py — 演示租户初始化脚本（第二代专用，幂等可重跑）
=========================================================================
目标：确保系统中存在「演示租户管理员 15079983758@163.com + 9 田块 27 台设备」，
     用于演示/验收（App 登录即可看到全部数据）。

流程：
  1. SysAdmin 登录（凭证支持命令行覆盖，默认演示 SysAdmin 账号）
  2. 探测演示租户管理员 15079983758@163.com 是否已可登录：
       - 可登录 → 直接复用其现有租户，进入建模
       - 不可登录 → 检查该邮箱是否已存在于其它租户（存在则只警告不修改），
                    否则用 SysAdmin 创建演示租户 + 租户管理员并设置初始密码
  3. 复用 tb_setup 的建模逻辑创建 9 田块 + 27 台设备 + Contains 关系（全部幂等）
  4. 输出演示租户设备清单 device_inventory_demo.json

幂等与安全设计（重要）：
  - 所有「创建」均为 get_or_create：已存在实体直接复用，绝不重复建；
  - 若演示管理员账号存在但登录失败（密码不符），只警告并退出，绝不重置密码/改角色；
  - 演示设备清单单独输出（device_inventory_demo.json），不影响现有模拟器运行。

用法：
  py provision_demo_tenant.py
  py provision_demo_tenant.py --sysadmin-email X --sysadmin-pass Y
"""
import argparse
import json
import sys
import time

import requests

import tb_setup  # 复用其幂等建模函数（设备/资产配置、田块、设备、关系、凭证）

# ---------------- 配置区（集中管理，勿散落硬编码） ----------------
BASE = "http://localhost:8080"                # ThingsBoard REST 地址（本机 Docker）
DEMO_TENANT_TITLE = "演示租户"                 # 干净环境中新建租户的显示名
DEMO_ADMIN_EMAIL = "15079983758@163.com"      # 演示租户管理员（与 App 演示登录一致）
DEMO_ADMIN_PASSWORD = "147258"                # 演示租户管理员密码（首次登录强制改密）
FIELD_NAMES = tb_setup.FIELD_NAMES            # 田地1~田地9（与 tb_setup 保持一致）
VALVES_PER_FIELD = tb_setup.VALVES_PER_FIELD  # 每田块 2 台电动阀
OUTPUT_INVENTORY = "device_inventory_demo.json"  # 演示租户设备清单输出文件


def login(email, password):
    """通用登录：成功返回 JWT；失败抛出异常"""
    r = requests.post(BASE + "/api/auth/login",
                      json={"username": email, "password": password}, timeout=15)
    r.raise_for_status()
    return r.json()["token"]


def api(token):
    """构造 TB 请求头（Bearer token）"""
    return {"X-Authorization": "Bearer {}".format(token), "Content-Type": "application/json"}


def get_or_create_tenant(token, title):
    """按标题查找租户，不存在则创建（幂等）。返回 (tenantId, 是否新建)"""
    H = api(token)
    r = requests.get(BASE + "/api/tenants?pageSize=100&page=0&textSearch={}".format(title),
                     headers=H, timeout=15)
    for t in r.json().get("data", []):
        if t.get("title") == title:
            print("  [已有] 租户: {} ({})".format(title, t["id"]["id"][:8]))
            return t["id"]["id"], False
    body = {"title": title}
    r = requests.post(BASE + "/api/tenant", headers=H, json=body, timeout=15)
    r.raise_for_status()
    tid = r.json()["id"]["id"]
    print("  [新建] 租户: {} ({})".format(title, tid[:8]))
    return tid, True


def find_user_global(token, email):
    """跨所有租户按邮箱查找用户（SysAdmin 视角）。返回 (用户对象, 租户id) 或 (None, None)"""
    H = api(token)
    r = requests.get(BASE + "/api/tenants?pageSize=100&page=0", headers=H, timeout=15)
    for t in r.json().get("data", []):
        tid = t["id"]["id"]
        r2 = requests.get(BASE + "/api/tenant/{}/users?pageSize=100&page=0&textSearch={}".format(
            tid, email), headers=H, timeout=15)
        for u in r2.json().get("data", []):
            if u.get("email") == email:
                return u, tid
    return None, None


def activate_user_with_password(token, user_id, password):
    """获取激活 token 并设置初始密码（对应 TB noauth/activate 流程）"""
    H = api(token)
    r = requests.get(BASE + "/api/user/{}/activationLinkInfo".format(user_id),
                     headers=H, timeout=15)
    r.raise_for_status()
    activate_token = r.json()["activateToken"]
    r2 = requests.post(BASE + "/api/noauth/activate",
                       json={"activateToken": activate_token, "password": password}, timeout=15)
    r2.raise_for_status()
    return r2.json()


def ensure_demo_admin(sys_token):
    """确保演示租户管理员可用，返回其 JWT。

    策略（幂等 + 不破坏现状）：
      - 已能登录 → 直接复用；
      - 不存在 → SysAdmin 建演示租户 + 用户（TENANT_ADMIN）+ 设初始密码；
      - 存在但登录失败 → 只警告不修改，返回 None（由调用方终止）。
    """
    print("\n=== 2. 演示租户管理员（{}）===".format(DEMO_ADMIN_EMAIL))
    try:
        token = login(DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD)
        print("  [已有] 演示租户管理员已可登录，直接复用其租户")
        return token
    except Exception:
        pass  # 登录失败，继续下面的存在性检查

    user, tid = find_user_global(sys_token, DEMO_ADMIN_EMAIL)
    if user is not None:
        # 账号存在但密码不符：绝不重置密码/改角色，避免破坏现状
        print("  [警告] {} 已存在于租户 {} 但当前密码登录失败（密码可能已被修改），"
              "为不破坏现状跳过创建，请人工确认".format(DEMO_ADMIN_EMAIL, tid[:8]))
        return None

    # 账号不存在 → 走完整创建流程（干净环境）
    tenant_id, created = get_or_create_tenant(sys_token, DEMO_TENANT_TITLE)
    if created:
        time.sleep(2)  # 新租户异步初始化，稍候再建用户，避免偶发 404

    body = {"email": DEMO_ADMIN_EMAIL, "authority": "TENANT_ADMIN",
            "tenantId": {"entityType": "TENANT", "id": tenant_id}}
    r = requests.post(BASE + "/api/user?sendActivationMail=false",
                      headers=api(sys_token), json=body, timeout=15)
    if r.status_code not in (200, 201):
        print("  [警告] 创建用户失败：{}".format(r.text[:150]))
        return None
    user_id = r.json()["id"]["id"]
    activate_user_with_password(sys_token, user_id, DEMO_ADMIN_PASSWORD)
    print("  [新建] 演示租户管理员 {}（初始密码已设置，首登强制改密）".format(DEMO_ADMIN_EMAIL))
    return login(DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD)


def build_demo_data(admin_token):
    """复用 tb_setup 的建模逻辑：9 田块 + 27 设备 + 关系（全部幂等）"""
    print("\n=== 3. 演示数据建模（9 田块 × 1 温湿度计 + 2 电动阀） ===")
    # 设备/资产配置（get_or_create；Profile 为租户级，跨租户各自独立）
    valve_profile, _ = tb_setup.get_or_create_device_profile(admin_token, "VALVE")
    temp_profile, _ = tb_setup.get_or_create_device_profile(admin_token, "TEMPERATURE_HUMIDITY")
    field_profile = tb_setup.get_or_create_asset_profile(admin_token, "FIELD")

    inventory = []
    for field_name in FIELD_NAMES:
        field_id = tb_setup.find_by_name("asset", field_name, admin_token)
        if field_id is None:
            field_id = tb_setup.create_asset(admin_token, field_name, field_profile)
            print("  [新建] 田块: {}".format(field_name))
        tb_setup.ensure_field_type(admin_token, field_id, field_profile)

        # 温湿度计（每田块 1 台）
        sensor_name = "{}-温湿度计".format(field_name)
        sensor_id = tb_setup.find_by_name("device", sensor_name, admin_token)
        if sensor_id is None:
            sensor_id = tb_setup.create_device(admin_token, sensor_name, temp_profile[0])
            print("    [新建] {}".format(sensor_name))
        tb_setup.ensure_relation(admin_token, field_id, sensor_id)
        inventory.append({
            "field": field_name, "deviceName": sensor_name, "type": "TEMPERATURE_HUMIDITY",
            "deviceId": sensor_id,
            "accessToken": tb_setup.get_access_token(admin_token, sensor_id)
        })

        # 电动阀（每田块 2 台：灌溉阀门A / 灌溉阀门B）
        for vi in range(1, VALVES_PER_FIELD + 1):
            valve_name = "{}-灌溉阀门{}".format(field_name, "AB"[vi - 1])
            valve_id = tb_setup.find_by_name("device", valve_name, admin_token)
            if valve_id is None:
                valve_id = tb_setup.create_device(admin_token, valve_name, valve_profile[0])
                print("    [新建] {}".format(valve_name))
            tb_setup.ensure_relation(admin_token, field_id, valve_id)
            inventory.append({
                "field": field_name, "deviceName": valve_name, "type": "VALVE",
                "deviceId": valve_id,
                "accessToken": tb_setup.get_access_token(admin_token, valve_id)
            })

    # 输出演示租户设备清单（不覆盖现有 device_inventory.json，模拟器按需切换）
    with open(OUTPUT_INVENTORY, "w", encoding="utf-8") as f:
        json.dump(inventory, f, ensure_ascii=False, indent=2)
    print("\n演示租户设备清单已保存: {}（共 {} 台）".format(OUTPUT_INVENTORY, len(inventory)))
    return inventory


def main():
    parser = argparse.ArgumentParser(description="初始化演示租户（幂等）")
    parser.add_argument("--sysadmin-email", default="guanliyuan@thingsboard.org",
                        help="SysAdmin 邮箱（默认演示 SysAdmin 账号）")
    parser.add_argument("--sysadmin-pass", default="789456", help="SysAdmin 密码")
    args = parser.parse_args()

    print("=== 1. SysAdmin 登录 ===")
    sys_token = login(args.sysadmin_email, args.sysadmin_pass)
    print("登录成功（{}）".format(args.sysadmin_email))

    # 演示租户管理员（探测已有 → 复用；不存在 → 创建）
    admin_token = ensure_demo_admin(sys_token)
    if admin_token is None:
        print("\n[结论] 演示租户管理员不可用（详见上方警告），未执行数据建模。")
        sys.exit(1)

    # 演示数据建模（复用 tb_setup，幂等）
    build_demo_data(admin_token)
    print("\n[结论] 演示环境就绪：登录 {} / {} 即可查看 9 田块 + 27 台设备数据".format(
        DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD))


if __name__ == "__main__":
    main()
