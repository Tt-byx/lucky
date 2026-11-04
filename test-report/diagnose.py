# -*- coding: utf-8 -*-
"""针对性诊断：敏感词过滤 + 抽奖轮次状态"""
import json, urllib.request, urllib.error
from urllib.parse import urlencode

BASE = "http://localhost:8080"


def call(method, path, body=None, token=None, params=None):
    url = BASE + path + ("?" + urlencode(params) if params else "")
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    h = {}
    if data:
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        resp = urllib.request.urlopen(r, timeout=20)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


_, t = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
TOKEN = json.loads(t)["data"]["token"]

_, t = call("POST", "/api/activity", {"name": "诊断活动"}, TOKEN)
ACT = json.loads(t)["data"]["id"]
print("activityId =", ACT)

pids = []
for i, sid in enumerate(["D001", "D002", "D003"], 1):
    _, t = call("POST", "/api/participant/register",
                {"activityId": ACT, "name": "诊断用户%d" % i, "studentId": sid})
    pids.append(json.loads(t)["data"]["id"])
print("participantIds =", pids)

print()
print("### 诊断 1：敏感词过滤 ###")
_, t = call("GET", "/api/sensitive-word/list", token=TOKEN)
print("初始敏感词:", [w["word"] for w in json.loads(t)["data"]])

print("新增敏感词 '测试违禁词' ->", call("POST", "/api/sensitive-word",
                                     params={"word": "测试违禁词"}, token=TOKEN)[1][:80])
_, t = call("GET", "/api/sensitive-word/list", token=TOKEN)
words = [w["word"] for w in json.loads(t)["data"]]
print("新增后敏感词:", words)
print("'测试违禁词' 是否在列表:", "测试违禁词" in words)

for pid, content in [(pids[0], "傻逼"), (pids[1], "测试违禁词"), (pids[2], "测试违禁词在这里")]:
    code, body = call("POST", "/api/danmaku/send",
                      {"activityId": ACT, "participantId": pid, "content": content})
    try:
        b = json.loads(body)
        print("  发弹幕 %-14s -> HTTP %s biz=%s msg=%s" % (content, code, b.get("code"), str(b.get("message"))[:30]))
    except Exception:
        print("  发弹幕 %-14s -> HTTP %s %s" % (content, code, body[:80]))

print()
print("### 诊断 2：抽奖轮次状态（同步落库验证）###")
_, t = call("POST", "/api/lottery/round",
            {"activityId": ACT, "roundName": "诊断一等奖", "winnerCount": 2}, TOKEN)
ROUND = json.loads(t)["data"]["id"]
print("roundId =", ROUND)
code, body = call("POST", "/api/lottery/draw/%s" % ROUND, token=TOKEN)
b = json.loads(body)
print("第 1 次抽奖 -> biz=%s 中奖人数=%s msg=%s" % (b.get("code"), len(b.get("data") or []), b.get("message")))
code, body = call("POST", "/api/lottery/draw/%s" % ROUND, token=TOKEN)
b = json.loads(body)
print("第 2 次抽奖 -> biz=%s msg=%s" % (b.get("code"), b.get("message")))
code, body = call("GET", "/api/lottery/winners", token=TOKEN, params={"roundId": ROUND})
print("中奖记录条数 =", len(json.loads(body)["data"]), "（轮次配置 2 人）")

print()
print("清理:", call("DELETE", "/api/activity/%s" % ACT, token=TOKEN)[1][:60])
