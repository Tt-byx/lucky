"""
抢奖接口压测脚本

设计要点：
1. 不经过扫码登录造用户（单 IP 200 次/60 秒的限制会把准备阶段拖到几十分钟），
   直接通过 docker exec 往 MySQL 插参与者、往 Redis 写令牌，秒级完成。
2. 每个线程先打一个「无效轮次」的预热请求把 TCP 连接建好，
   正式请求复用连接，避免把三次握手算进接口 RT。
3. 两轮场景：
   - 顺利路径：名额 = 用户数，全部成功，测的是纯转发 + Redis + MQ 的吞吐
   - 竞争路径：名额 = 用户数 / 5，80% 请求走「名额已满」分支，测快速失败路径

用法：
    python bench/bench_join.py                 # 默认 500 用户，名额 500
    python bench/bench_join.py -n 1000 -q 200  # 1000 用户抢 200 个名额
"""

import argparse
import http.client
import json
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

HOST = "localhost"
PORT = 8080
MYSQL_CONTAINER = "lucky-mysql"
REDIS_CONTAINER = "lucky-redis"


def http_call(conn, method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body) if body is not None else None
    conn.request(method, path, payload, headers)
    resp = conn.getresponse()
    raw = resp.read().decode("utf-8", errors="ignore")
    try:
        return resp.status, json.loads(raw)
    except json.JSONDecodeError:
        return resp.status, {"code": resp.status, "message": raw[:120], "data": None}


def admin_login():
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    _, data = http_call(conn, "POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
    conn.close()
    if data.get("code") != 200:
        raise RuntimeError("管理员登录失败: %s" % data)
    return data["data"]["token"]


def mysql_exec(sql):
    return subprocess.run(
        ["docker", "exec", MYSQL_CONTAINER, "mysql", "-uroot", "-p123456", "lucky", "-N", "-e", sql],
        capture_output=True, text=True, encoding="utf-8"
    )


def prepare_participants(activity_id, count):
    """批量插入参与者，返回 [(participantId, token)]"""
    sql = (
        "INSERT INTO participant (activity_id, name, student_id, lottery_status, is_muted, is_banned) "
        "WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM seq WHERE n < %d) "
        "SELECT %d, CONCAT('压测用户', n), CONCAT('BENCH%d_', LPAD(n,5,'0')), 1, 0, 0 FROM seq;"
    ) % (count, activity_id, activity_id)
    r = mysql_exec(sql)
    if r.returncode != 0:
        raise RuntimeError("插入参与者失败: %s" % r.stderr)

    r = mysql_exec(
        "SELECT id FROM participant WHERE activity_id=%d AND student_id LIKE 'BENCH%d_%%' ORDER BY id;"
        % (activity_id, activity_id)
    )
    ids = [int(line.strip()) for line in r.stdout.strip().splitlines() if line.strip()]
    if len(ids) != count:
        raise RuntimeError("参与者数量不符: 期望 %d 实际 %d" % (count, len(ids)))

    # 令牌值格式必须和 Jackson 序列化结果一致（带引号的字符串），否则 Java 端反序列化失败
    lines = []
    tokens = []
    for idx, pid in enumerate(ids):
        token = "bench%d%05d" % (activity_id, idx)
        tokens.append(token)
        lines.append('SET lucky:scan:token:%s "\\"%d:%d\\""' % (token, pid, activity_id))

    r = subprocess.run(
        ["docker", "exec", "-i", REDIS_CONTAINER, "redis-cli", "--pipe"],
        input=("\n".join(lines) + "\n").encode("utf-8"),
        capture_output=True
    )
    if r.returncode != 0:
        raise RuntimeError("写入令牌失败: %s" % r.stderr.decode("utf-8", errors="ignore"))

    return tokens


def build_scene(jwt, user_count, quota, prize_name):
    """建活动 → 奖品 → 轮次 → 开轮"""
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    stamp = int(time.time())

    _, act = http_call(conn, "POST", "/api/activity", {
        "name": "压测-%d" % stamp,
        "startTime": "2026-01-01T00:00:00",
        "endTime": "2030-01-01T00:00:00"
    }, jwt)
    activity_id = act["data"]["id"]
    http_call(conn, "PUT", "/api/activity/%d/status?status=1" % activity_id, None, jwt)

    _, prize = http_call(conn, "POST", "/api/prize", {
        "activityId": activity_id,
        "name": prize_name,
        "stock": quota,
        "totalStock": quota
    }, jwt)
    prize_id = prize["data"]["id"]

    _, rnd = http_call(conn, "POST", "/api/lottery/round", {
        "activityId": activity_id,
        "prizeId": prize_id,
        "roundName": "压测轮次",
        "winnerCount": quota
    }, jwt)
    round_id = rnd["data"]["id"]

    _, start = http_call(conn, "POST", "/api/lottery/round/%d/start" % round_id, None, jwt)
    if start.get("code") != 200:
        raise RuntimeError("开轮失败: %s" % start)

    conn.close()
    return activity_id, round_id


def worker(args):
    """一个线程处理一批 token，连接复用，避免把线程创建和 TCP 握手算进 RT"""
    tokens, round_id = args
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)

    # 预热：用不存在的轮次建连，不产生业务副作用
    http_call(conn, "POST", "/api/lottery/join/99999999", None, tokens[0])

    results = []
    for token in tokens:
        start = time.perf_counter()
        status, body = http_call(conn, "POST", "/api/lottery/join/%d" % round_id, None, token)
        cost_ms = (time.perf_counter() - start) * 1000

        result = body.get("data") or {}
        results.append({
            "cost": cost_ms,
            "code": result.get("code"),
            "http": status,
            "message": result.get("message") or body.get("message") or ""
        })

    conn.close()
    return results


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    idx = min(int(len(sorted_values) * p), len(sorted_values) - 1)
    return sorted_values[idx]


def run_bench(tokens, round_id, concurrency, label):
    print("\n" + "=" * 68)
    print("%s  |  用户 %d  并发 %d" % (label, len(tokens), concurrency))
    print("=" * 68)

    # 把 token 切成 concurrency 份，每个线程顺序处理自己那份，连接全程复用
    workers = min(concurrency, len(tokens))
    batches = [tokens[i::workers] for i in range(workers)]
    tasks = [(batch, round_id) for batch in batches]

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        nested = list(pool.map(worker, tasks))
    elapsed = time.perf_counter() - started

    results = [r for sub in nested for r in sub]

    costs = sorted(r["cost"] for r in results)
    success = sum(1 for r in results if r["code"] == 1)
    sold_out = sum(1 for r in results if r["code"] == 0)
    dup = sum(1 for r in results if r["code"] == -1)
    crossed = sum(1 for r in results if r["code"] == -2)
    closed = sum(1 for r in results if r["code"] == -3)
    # -3 有两种含义：本轮未开始 / 名额抢完后轮次已自动结束，都属于正常业务分支
    failed = [r for r in results if r["code"] not in (1, 0, -1, -2, -3)]

    print("  总耗时      : %.2f s" % elapsed)
    print("  吞吐        : %.0f req/s" % (len(results) / elapsed))
    print("  RT  min/avg : %.1f / %.1f ms" % (costs[0], sum(costs) / len(costs)))
    print("  RT  p50/p90 : %.1f / %.1f ms" % (percentile(costs, 0.5), percentile(costs, 0.9)))
    print("  RT  p99/max : %.1f / %.1f ms" % (percentile(costs, 0.99), costs[-1]))
    print("  结果分布    : 成功 %d, 名额已满 %d, 轮次已结束 %d, 重复 %d, 跨轮次 %d, 异常 %d"
          % (success, sold_out, closed, dup, crossed, len(failed)))

    if failed:
        print("  异常样例    : %s" % failed[0])

    return {
        "elapsed": elapsed,
        "qps": len(results) / elapsed,
        "success": success,
        "sold_out": sold_out,
        "dup": dup,
        "costs": costs,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("-n", "--users", type=int, default=500, help="并发用户数")
    parser.add_argument("-c", "--concurrency", type=int, default=500, help="线程数")
    parser.add_argument("-q", "--quota", type=int, default=None, help="本轮名额，默认等于用户数")
    args = parser.parse_args()

    quota = args.quota if args.quota else args.users

    print("准备场景 ...")
    jwt = admin_login()
    activity_id, round_id = build_scene(jwt, args.users, quota, "压测奖品")
    print("  活动 %d / 轮次 %d / 名额 %d" % (activity_id, round_id, quota))

    print("准备 %d 个压测用户 ..." % args.users)
    tokens = prepare_participants(activity_id, args.users)
    print("  完成，令牌已写入 Redis")

    # 名额小于用户数时，多余请求会走「名额已满」分支，一次压测就能同时看到两种路径
    concurrency = min(args.concurrency, args.users)
    run_bench(tokens, round_id, concurrency, "抢奖压测")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("压测失败: %s" % e, file=sys.stderr)
        sys.exit(1)
