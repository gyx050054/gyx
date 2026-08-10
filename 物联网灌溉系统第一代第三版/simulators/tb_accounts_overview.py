# -*- coding: utf-8 -*-
"""
tb_accounts_overview.py — 账号总览脚本（查看 ThingsBoard 所有账号）
=========================================================================
作用：用系统管理员登录 TB，列出所有租户、每个租户的管理员、每个客户下的员工账号。
用法：双击运行或命令行执行  py tb_accounts_overview.py
      （默认用演示系统管理员 guanliyuan 登录，也可加参数换账号）
      py tb_accounts_overview.py --email X --password Y

输出内容：
  1. 系统管理员（SYS_ADMIN）账号
  2. 所有租户（一家公司 = 一个租户）
  3. 每个租户的租户管理员（老板）账号
  4. 每个租户下的客户（公司）及其员工（CUSTOMER_USER）账号
"""
import argparse
import json
import urllib.request

# ---------------- 配置区（集中管理） ----------------
BASE = "http://localhost:8080"                       # ThingsBoard 地址（本机 Docker）
SYSADMIN_EMAIL = "guanliyuan@thingsboard.org"        # 系统管理员账号（内嵌微服务端的）
SYSADMIN_PASSWORD = "789456"


def login(email, password):
    """登录，返回 JWT token"""
    req = urllib.request.Request(
        BASE + "/api/auth/login",
        data=json.dumps({"username": email, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())["token"]


def api(token, path):
    """带 token 调 TB REST 接口，返回 JSON"""
    req = urllib.request.Request(BASE + path, headers={"X-Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode())


def main():
    # 支持命令行传参换系统管理员（一般不需要，默认即可）
    parser = argparse.ArgumentParser(description="查看 TB 所有账号")
    parser.add_argument("--email", default=SYSADMIN_EMAIL)
    parser.add_argument("--password", default=SYSADMIN_PASSWORD)
    # 租户管理员模式：想看某个租户的客户/员工时，用它登录查（SysAdmin 查客户会被 TB 拒 403）
    parser.add_argument("--tenant-admin", default="", help="租户管理员邮箱（可选）")
    parser.add_argument("--tenant-pass", default="", help="租户管理员密码")
    args = parser.parse_args()

    # ---- 模式二：租户管理员模式（列出该租户的客户 + 员工 + 分配）----
    if args.tenant_admin:
        token = login(args.tenant_admin, args.tenant_pass)
        print("已用租户管理员登录:", args.tenant_admin)
        print("=" * 60)
        customers = api(token, "/api/customers?pageSize=100&page=0").get("data", [])
        if not customers:
            print("该租户下没有客户（员工）。")
        for c in customers:
            cid = c["id"]["id"]
            print(f"\n■ 客户: {c.get('title','')} ({cid[:12]})")
            cusers = api(token, f"/api/customer/{cid}/users?pageSize=100&page=0").get("data", [])
            for cu in cusers:
                print(f"    员工账号: {cu.get('email')}")
            assets = api(token, f"/api/customer/{cid}/assetInfos?pageSize=100&page=0").get("data", [])
            print("    已分配田块:", ", ".join(a.get('name','') for a in assets) if assets else "无")
            devs = api(token, f"/api/customer/{cid}/deviceInfos?pageSize=100&page=0").get("data", [])
            print("    已分配设备:", ", ".join(d.get('name','') for d in devs) if devs else "无")
        return

    # ---- 模式一：系统管理员模式（列出所有租户 + 各租户管理员）----
    token = login(args.email, args.password)
    print("已用系统管理员登录:", args.email)
    print("=" * 60)

    # 1. 系统管理员（他们都在 TB 内建的系统租户下）
    print("\n【1】系统管理员（SYS_ADMIN）：")
    SYSTEM_TENANT = "13814000-1dd2-11b2-8080-808080808080"   # TB 内建系统租户 id（固定值）
    try:
        users = api(token, f"/api/tenant/{SYSTEM_TENANT}/users?pageSize=100&page=0").get("data", [])
        if users:
            for u in users:
                print("   -", u.get("email"))
        else:
            print("   （该接口在本 TB 不返回系统管理员，已知账号: guanliyuan@thingsboard.org）")
    except Exception as e:
        print("   查询失败:", e)

    # 2. 所有租户
    tenants = api(token, "/api/tenants?pageSize=100&page=0").get("data", [])
    print(f"\n【2】租户（共 {len(tenants)} 个，一家公司 = 一个租户）：")
    for t in tenants:
        tid = t["id"]["id"]
        print(f"\n  ■ 租户: {t.get('title','')} ({tid[:8]})")
        # 3. 租户管理员
        admins = api(token, f"/api/tenant/{tid}/users?pageSize=100&page=0").get("data", [])
        for u in admins:
            print(f"      租户管理员: {u.get('email')}")
        # 4. 客户（SysAdmin 接口被 TB 拒 403，提示改用租户管理员模式看）
        try:
            api(token, f"/api/customers?pageSize=1&page=0&tenantId={tid}")
            print("      （本 TB 限制：SysAdmin 查客户返回 403，请用 --tenant-admin 模式查看）")
        except Exception:
            print("      （客户详情请用 --tenant-admin <邮箱> --tenant-pass <密码> 查看）")

    print("\n" + "=" * 60)
    print("用法示例：")
    print("  全部租户/管理员:      py tb_accounts_overview.py")
    print("  某租户的客户/员工:    py tb_accounts_overview.py --tenant-admin 15079983758@163.com --tenant-pass 147258")


if __name__ == "__main__":
    main()
