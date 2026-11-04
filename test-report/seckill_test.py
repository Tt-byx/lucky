"""
抢奖（秒杀）链路验证脚本

覆盖的核心断言：
1. 20 人并发抢 5 个名额，只能有 5 个成功（Redis 预扣不超卖）
2. 数据库 winner 记录数 == 名额数，奖品库存 == 0（DB 兜底不超卖）
3. 同一人重复点击不重复中奖（Lua 内 SISMEMBER 去重）
4. 跨轮次同一人不能再中（活动级去重）
5. 名额抢完后轮次自动结束
6. 无令牌 / 伪造令牌无法抢奖

用法：
    python test-report/seckill_test.py
"""

import json
import random
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0


def call(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token if token.startswith("Bearer ") else "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="ignore")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"code": e.code, "message": raw[:200], "data": None}
    except Exception as e:
        return {"code": -1, "message": str(e), "data": None}


def check(name, condition, detail=""):
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def main():
    # 时间戳 + 随机数，避免两次运行间隔太短导致学号重复（单学号有 60s 频次限制）
    suffix = f"{int(time.time()) % 100000:05d}{random.randint(10, 99)}"
    print("=" * 64)
    print("抢奖链路验证")
    print("=" * 64)

    # ---------- 准备：管理员登录 ----------
    login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    if login.get("code") != 200:
        print("管理员登录失败，无法继续:", login)
        return
    jwt = login["data"]["token"]
    print("\n[1] 管理员登录成功")

    # ---------- 创建活动 ----------
    activity = call("POST", "/api/activity", {
        "name": f"抢奖验证-{suffix}",
        "startTime": "2026-01-01T00:00:00",
        "endTime": "2030-01-01T00:00:00"
    }, jwt)["data"]
    call("PUT", f"/api/activity/{activity['id']}/status?status=1", None, jwt)
    print(f"[2] 活动创建并开始: id={activity['id']}")

    # ---------- 创建奖品（库存 5） ----------
    prize = call("POST", "/api/prize", {
        "activityId": activity["id"],
        "name": f"测试奖品-{suffix}",
        "stock": 5,
        "totalStock": 5
    }, jwt)["data"]
    print(f"[3] 奖品创建: id={prize['id']}, 库存=5")

    # ---------- 名额超库存应被拒绝 ----------
    over = call("POST", "/api/lottery/round", {
        "activityId": activity["id"],
        "prizeId": prize["id"],
        "roundName": "超量轮次",
        "winnerCount": 99
    }, jwt)
    check("名额超过奖品库存时创建失败", over.get("code") != 200, over.get("message"))

    # ---------- 创建轮次（名额 5） ----------
    rnd = call("POST", "/api/lottery/round", {
        "activityId": activity["id"],
        "prizeId": prize["id"],
        "roundName": f"一等奖-{suffix}",
        "winnerCount": 5
    }, jwt)["data"]
    print(f"[4] 轮次创建: id={rnd['id']}, 名额=5")

    # ---------- 20 个用户扫码登录 ----------
    users = []
    for i in range(20):
        vo = call("POST", "/api/scan/login", {
            "activityId": activity["id"],
            "studentId": f"S{suffix}{i:02d}",
            "name": f"用户{i:02d}"
        })
        if vo.get("code") == 200:
            users.append(vo["data"])
    check("20 个用户扫码登录成功", len(users) == 20, f"实际 {len(users)}")

    # ---------- 未开轮时不能抢 ----------
    before = call("POST", f"/api/lottery/join/{rnd['id']}", None, users[0]["token"])
    if not isinstance(before.get("data"), dict):
        print("      未开轮抢奖原始返回:", before)
    check("未开轮时抢奖被拒绝", (before.get("data") or {}).get("code") == -3,
          before.get("data"))

    # ---------- 开轮 ----------
    start = call("POST", f"/api/lottery/round/{rnd['id']}/start", None, jwt)
    check("开轮成功", start.get("code") == 200, start.get("message"))

    remaining = call("GET", f"/api/lottery/remaining?roundId={rnd['id']}")
    check("开轮后剩余名额 = 5", remaining.get("data") == 5, remaining.get("data"))

    # ---------- 20 人并发抢奖 ----------
    print("\n[5] 20 人并发抢 5 个名额 ...")
    started = time.time()
    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(
            lambda u: call("POST", f"/api/lottery/join/{rnd['id']}", None, u["token"]),
            users
        ))
    cost = (time.time() - started) * 1000

    codes = [r.get("data", {}).get("code") for r in results]
    success = [r for r in results if r.get("data", {}).get("code") == 1]
    sold_out = [r for r in results if r.get("data", {}).get("code") == 0]

    print(f"      并发耗时 {cost:.0f}ms，成功 {len(success)}，名额已满 {len(sold_out)}")
    check("成功抢到的人数正好等于名额 5", len(success) == 5, f"实际 {len(success)}")
    check("其余 15 人返回名额已满", len(sold_out) == 15, f"实际 {len(sold_out)}")

    # ---------- 等待 MQ 异步落库 ----------
    time.sleep(3)

    # ---------- 中奖名单 ----------
    winners = call("GET", f"/api/lottery/winners?roundId={rnd['id']}", None, jwt)
    winner_list = winners.get("data") or []
    check("中奖名单 5 条", len(winner_list) == 5, f"实际 {len(winner_list)}")

    winner_ids = {w["participantId"] for w in winner_list}
    check("中奖者无重复", len(winner_ids) == len(winner_list),
          f"去重后 {len(winner_ids)} / 原始 {len(winner_list)}")

    expected_ids = {r["data"]["participantId"] for r in success}
    check("中奖名单与抢奖成功者完全一致", winner_ids == expected_ids,
          f"期望 {expected_ids} 实际 {winner_ids}")

    check("每个中奖者都有领奖码",
          all(w.get("claimCode") for w in winner_list))

    # ---------- 库存 ----------
    prizes = call("GET", f"/api/prize/list?activityId={activity['id']}", None, jwt)
    prize_now = next((p for p in prizes.get("data", []) if p["id"] == prize["id"]), None)
    check("奖品库存扣减到 0", prize_now and prize_now["stock"] == 0,
          prize_now.get("stock") if prize_now else "奖品不存在")

    # ---------- 重复点击 ----------
    first_winner = success[0]["data"]["participantId"]
    first_user = next(u for u in users if u["participantId"] == first_winner)
    dup = call("POST", f"/api/lottery/join/{rnd['id']}", None, first_user["token"])
    dup_code = dup.get("data", {}).get("code")
    check("重复抢奖被拒绝", dup_code in (-1, -2, -3, 0), dup.get("data"))

    # ---------- 名额耗尽后轮次自动结束 ----------
    rounds = call("GET", f"/api/lottery/rounds?activityId={activity['id']}", None, jwt)
    this_round = next((r for r in rounds.get("data", []) if r["id"] == rnd["id"]), None)
    check("名额耗尽后轮次自动结束", this_round and this_round["status"] == 2,
          this_round.get("status") if this_round else "轮次不存在")

    # ---------- 跨轮次去重 ----------
    prize2 = call("POST", "/api/prize", {
        "activityId": activity["id"],
        "name": f"二等奖-{suffix}",
        "stock": 3,
        "totalStock": 3
    }, jwt)["data"]
    rnd2 = call("POST", "/api/lottery/round", {
        "activityId": activity["id"],
        "prizeId": prize2["id"],
        "roundName": "二等奖",
        "winnerCount": 3
    }, jwt)["data"]
    call("POST", f"/api/lottery/round/{rnd2['id']}/start", None, jwt)

    # 已中奖的人再抢第二轮
    winner_user = next(u for u in users if u["participantId"] in winner_ids)
    cross = call("POST", f"/api/lottery/join/{rnd2['id']}", None, winner_user["token"])
    check("已中奖的人不能参与下一轮", cross.get("data", {}).get("code") == -2,
          cross.get("data"))

    # ---------- 鉴权 ----------
    no_token = call("POST", f"/api/lottery/join/{rnd2['id']}", None, None)
    check("无令牌抢奖被拒绝", no_token.get("code") == 401, no_token)

    fake = call("POST", f"/api/lottery/join/{rnd2['id']}", None, "fake-token-123456")
    check("伪造令牌抢奖被拒绝", fake.get("code") == 401, fake)

    # ---------- 汇总 ----------
    print("\n" + "=" * 64)
    print(f"PASS = {PASS}   FAIL = {FAIL}")
    print("=" * 64)


if __name__ == "__main__":
    main()
