"""
端到端冒烟测试

覆盖三件事：
1. 抢奖主链路（并发不超卖、重复拦截、跨轮次去重）
2. 安全（WS 握手鉴权、抢奖令牌、登出后 JWT 立即失效）
3. 可观测（Prometheus 指标端点有秒杀计数）

用法：python bench/smoke_test.py
"""

import base64
import json
import os
import socket
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "http://localhost:8080"
HOST, PORT = "localhost", 8080
PASS = 0
FAIL = 0


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token if token.startswith("Bearer ") else "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="ignore")
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"code": e.code, "message": raw[:120], "data": None}
    except Exception as e:
        return -1, {"code": -1, "message": str(e), "data": None}


def check(name, condition, detail=""):
    global PASS, FAIL
    if condition:
        PASS += 1
        print("  PASS  %s" % name)
    else:
        FAIL += 1
        print("  FAIL  %s  %s" % (name, detail))


def ws_handshake(token, participant_id, activity_id):
    """手工发一个 WebSocket Upgrade 请求，只看握手状态码，不建长连接"""
    key = base64.b64encode(os.urandom(16)).decode()
    query = "?activityId=%d" % activity_id
    if participant_id is not None:
        query += "&participantId=%d&token=%s" % (participant_id, token or "")
    request = (
        "GET /ws/lucky%s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n\r\n"
    ) % (query, HOST, PORT, key)

    s = socket.create_connection((HOST, PORT), timeout=5)
    s.sendall(request.encode())
    resp = s.recv(2048).decode(errors="ignore")
    s.close()
    return resp.split("\r\n")[0]


def main():
    global PASS, FAIL
    stamp = int(time.time()) % 100000

    print("=" * 64)
    print("端到端冒烟测试")
    print("=" * 64)

    # ---------- 1. 管理员登录 ----------
    _, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    check("管理员登录", login.get("code") == 200, login)
    jwt = login["data"]["token"]

    me = call("GET", "/api/auth/me", None, jwt)
    check("带 JWT 可访问 /me", me[1].get("code") == 200, me)

    # ---------- 2. 建活动 / 奖品 / 轮次 ----------
    _, act = call("POST", "/api/activity", {
        "name": "冒烟-%d" % stamp,
        "startTime": "2026-01-01T00:00:00",
        "endTime": "2030-01-01T00:00:00"
    }, jwt)
    activity_id = act["data"]["id"]
    call("PUT", "/api/activity/%d/status?status=1" % activity_id, None, jwt)

    _, prize = call("POST", "/api/prize", {
        "activityId": activity_id, "name": "冒烟奖品", "stock": 5, "totalStock": 5
    }, jwt)
    prize_id = prize["data"]["id"]

    _, rnd = call("POST", "/api/lottery/round", {
        "activityId": activity_id, "prizeId": prize_id,
        "roundName": "冒烟轮次", "winnerCount": 5
    }, jwt)
    round_id = rnd["data"]["id"]

    # ---------- 3. 造 20 个用户 ----------
    users = []
    for i in range(20):
        _, vo = call("POST", "/api/scan/login", {
            "activityId": activity_id,
            "studentId": "SMOKE%d%03d" % (stamp, i),
            "name": "冒烟%02d" % i
        })
        if vo.get("code") == 200:
            users.append(vo["data"])
    check("20 个用户扫码登录", len(users) == 20, len(users))

    # ---------- 4. 开轮 + 并发抢奖 ----------
    start = call("POST", "/api/lottery/round/%d/start" % round_id, None, jwt)
    check("开轮", start[1].get("code") == 200, start)

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(
            lambda u: call("POST", "/api/lottery/join/%d" % round_id, None, u["token"]),
            users
        ))

    codes = [(r[1].get("data") or {}).get("code") for r in results]
    check("20 人抢 5 个名额，成功数正好 5", codes.count(1) == 5, codes.count(1))
    check("名额不足的请求被拒绝", codes.count(0) + codes.count(-3) == 15, codes)

    time.sleep(2.5)

    winners = call("GET", "/api/lottery/winners?roundId=%d" % round_id, None, jwt)[1]
    check("中奖名单 5 条", len(winners.get("data") or []) == 5, len(winners.get("data") or []))

    _, prizes = call("GET", "/api/prize/list?activityId=%d" % activity_id, None, jwt)
    p = next((x for x in prizes.get("data", []) if x["id"] == prize_id), None)
    check("奖品库存扣减到 0", p and p["stock"] == 0, p.get("stock") if p else "缺失")

    # ---------- 5. 安全：抢奖鉴权 ----------
    _, no_token = call("POST", "/api/lottery/join/%d" % round_id, None, None)
    check("无令牌抢奖被拒绝", no_token.get("code") == 401, no_token)

    _, fake = call("POST", "/api/lottery/join/%d" % round_id, None, "fake-token-xyz")
    check("伪造令牌抢奖被拒绝", fake.get("code") == 401, fake)

    # ---------- 6. 安全：WS 握手 ----------
    sample = users[0]
    ok_line = ws_handshake(sample["token"], sample["participantId"], activity_id)
    check("WS 带正确令牌握手成功", "101" in ok_line, ok_line)

    bad_line = ws_handshake("bad-token", sample["participantId"], activity_id)
    check("WS 伪造令牌握手被拒绝", "101" not in bad_line, bad_line)

    mismatch = ws_handshake(users[0]["token"], users[1]["participantId"], activity_id)
    check("WS 令牌与身份不匹配被拒绝", "101" not in mismatch, mismatch)

    screen_line = ws_handshake(None, None, activity_id)
    check("大屏匿名连接允许", "101" in screen_line, screen_line)

    # ---------- 7. 领奖核销 ----------
    sample_winner = (winners.get("data") or [{}])[0]
    claim_code = sample_winner.get("claimCode")
    if claim_code:
        status, claimed = call("POST", "/api/lottery/claim?claimCode=%s" % claim_code, None, jwt)
        check("按领奖码核销成功", claimed.get("code") == 200 and claimed["data"]["status"] == 1,
              claimed)

        status, again = call("POST", "/api/lottery/claim?claimCode=%s" % claim_code, None, jwt)
        check("重复核销被拒绝", again.get("code") != 200, again)
    else:
        check("中奖记录带领奖码", False, "winners 里没有 claimCode")

    # ---------- 8. 售罄语义 ----------
    loser = next((u for u, r in zip(users, results)
                  if (r[1].get("data") or {}).get("code") in (0, -3)), None)
    if loser:
        _, after_sold_out = call("POST", "/api/lottery/join/%d" % round_id, None, loser["token"])
        code_after = (after_sold_out.get("data") or {}).get("code")
        check("名额抢完后返回「已抢完」而不是「已结束」", code_after == 0,
              after_sold_out.get("data"))

    # ---------- 9. 安全：登出后 JWT 失效 ----------
    call("POST", "/api/auth/logout", None, jwt)
    after = call("GET", "/api/auth/me", None, jwt)
    check("登出后旧 JWT 立即失效", after[0] == 403 or after[1].get("code") != 200, after)

    # ---------- 10. 可观测：Prometheus 指标 ----------
    status, body = call("GET", "/actuator/prometheus")
    text = json.dumps(body)[:0]  # 占位，指标是纯文本
    req = urllib.request.Request(BASE + "/actuator/prometheus")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            metrics = resp.read().decode()
    except Exception as e:
        metrics = ""

    check("Prometheus 端点可访问", "lucky_seckill_join" in metrics or "jvm_memory" in metrics,
          metrics[:80])
    check("秒杀结果指标已上报", "lucky_seckill_join_total" in metrics,
          "未找到 lucky_seckill_join_total")
    check("WebSocket 连接数指标已上报", "lucky_ws_local_connections" in metrics,
          "未找到 lucky_ws_local_connections")

    print()
    print("=" * 64)
    print("PASS = %d   FAIL = %d" % (PASS, FAIL))
    print("=" * 64)


if __name__ == "__main__":
    main()
