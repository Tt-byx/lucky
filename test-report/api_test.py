# -*- coding: utf-8 -*-
"""
毕业典礼抽奖系统 lucky-server 全接口自动化测试（最终版 v3）
基于「期望值」判定：HTTP 状态码 + Result.code 双重校验
每个断言使用独立参与者，避免 1 秒限流窗口串扰；数据使用时间戳后缀保证可重复执行
"""
import json
import time
import uuid
import socket
import base64
import urllib.request
import urllib.error
from urllib.parse import urlencode

BASE = "http://localhost:8080"
RUN = str(int(time.time()))[-6:]
RESULTS = []


def _verdict(code, txt, expect_http, expect_biz):
    if code != expect_http:
        return "FAIL"
    if expect_biz is None:
        return "PASS"
    try:
        d = json.loads(txt)
    except Exception:
        return "FAIL"
    return "PASS" if d.get("code") == expect_biz else "FAIL"


def _record(label, method, path, params, reqbody, code, ms, resp, expect_http, expect_biz, verdict):
    RESULTS.append(dict(label=label, method=method, path=path, params=params, reqbody=reqbody,
                        code=code, ms=ms, resp=resp, expect_http=expect_http,
                        expect_biz=expect_biz, verdict=verdict))
    print("%-4s | %-6s %-46s | HTTP %-4s | %5dms" % (verdict, method, path[:46], code, ms))


def req(method, path, body=None, token=None, params=None, label=None,
        expect_http=200, expect_biz=200, absolute=False, is_text=False):
    url = (path if absolute else BASE + path) + ("?" + urlencode(params) if params else "")
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    t0 = time.time()
    try:
        resp = urllib.request.urlopen(r, timeout=25)
        code, txt = resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code, txt = e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        code, txt = -1, "EXCEPTION: %r" % e
    ms = int((time.time() - t0) * 1000)
    v = _verdict(code, txt, expect_http, None if is_text else expect_biz)
    _record(label or (method + " " + path), method, path, params, body, code, ms, txt,
            expect_http, expect_biz, v)
    return code, txt


def req_multipart(path, filename, filedata, ctype_file, token=None, params=None,
                  label=None, expect_http=200, expect_biz=200):
    boundary = "----wbboundary" + uuid.uuid4().hex
    data = b"".join([
        ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
         "Content-Type: %s\r\n\r\n" % (boundary, filename, ctype_file)).encode("utf-8"),
        filedata,
        ("\r\n--%s--\r\n" % boundary).encode("utf-8")])
    url = BASE + path + ("?" + urlencode(params) if params else "")
    headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, data=data, headers=headers, method="POST")
    t0 = time.time()
    try:
        resp = urllib.request.urlopen(r, timeout=30)
        code, txt = resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        code, txt = e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        code, txt = -1, "EXCEPTION: %r" % e
    ms = int((time.time() - t0) * 1000)
    v = _verdict(code, txt, expect_http, expect_biz)
    _record(label or ("POST " + path), "POST", path, params, "{multipart}", code, ms, txt,
            expect_http, expect_biz, v)
    return code, txt


def jget(txt, *keys, default=None):
    try:
        d = json.loads(txt)
    except Exception:
        return default
    for k in keys:
        if isinstance(d, dict) and k in d:
            d = d[k]
        else:
            return default
    return d


def extra(label, passed, detail, method="CHECK", path="-", expect="-"):
    RESULTS.append(dict(label=label, method=method, path=path, params=None, reqbody=None,
                        code=200 if passed else -1, ms=0, resp=detail, expect_http=expect,
                        expect_biz=None, verdict="PASS" if passed else "FAIL"))
    print("%-4s | %-6s %-46s | %s" % ("PASS" if passed else "FAIL", method, label[:46], detail[:90]))


WORD_NEW = "违禁词" + RUN
WORD_TMP = "临时敏感词" + RUN
SID = "S%s" % RUN

print("=" * 112)
print("0. 基础连通性 / 健康检查 / API 文档   [运行批次 %s]" % RUN)
print("=" * 112)
req("GET", "/actuator/health", label="[基建] Actuator 健康检查", expect_biz=None, is_text=True)
req("GET", "/v3/api-docs", label="[基建] OpenAPI 文档 JSON", expect_biz=None, is_text=True)
req("GET", "/swagger-ui/index.html", label="[基建] Swagger UI 页面", expect_biz=None, is_text=True)

print()
print("=" * 112)
print("1. 认证模块 /api/auth")
print("=" * 112)
code, txt = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"},
                label="[认证] 管理员登录(正确凭证)", expect_biz=200)
TOKEN = jget(txt, "data", "token")
req("POST", "/api/auth/login", body={"username": "admin", "password": "wrong"},
    label="[认证] 管理员登录(错误密码)", expect_biz=500)
req("POST", "/api/auth/login", body={"username": "", "password": ""},
    label="[认证] 登录参数为空(@Valid)", expect_http=400, expect_biz=400)
req("GET", "/api/auth/me", token=TOKEN, label="[认证] 当前用户(带 token)", expect_biz=200)
req("GET", "/api/auth/me", label="[认证] 当前用户(无 token)", expect_http=403, expect_biz=None)
req("GET", "/api/auth/me", token="invalid.token.here", label="[认证] 伪造 token",
    expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("2. 活动模块 /api/activity")
print("=" * 112)
req("GET", "/api/activity/current", label="[活动] 当前活动(初始应为空)", expect_biz=500)
code, txt = req("POST", "/api/activity", body={"name": "自动化测试活动" + RUN}, token=TOKEN,
                label="[活动] 创建活动 A", expect_biz=200)
ACT_ID = jget(txt, "data", "id")
req("POST", "/api/activity", body={"name": ""}, token=TOKEN,
    label="[活动] 创建活动-名称为空(@Valid)", expect_http=400, expect_biz=400)
req("POST", "/api/activity", body={"name": "无 token 创建"}, label="[活动] 创建活动(无 token)",
    expect_http=403, expect_biz=None)
req("GET", "/api/activity/current", label="[活动] 当前活动(已创建 A)", expect_biz=200)
req("PUT", "/api/activity/%s/status" % ACT_ID, params={"status": 1}, token=TOKEN,
    label="[活动] 更新状态->进行中(1)", expect_biz=200)
req("PUT", "/api/activity/999999/status", params={"status": 1}, token=TOKEN,
    label="[活动] 更新不存在活动状态", expect_biz=500)
req("GET", "/api/activity/history", label="[活动] 历史活动(公开)", expect_biz=200)

# ---- 缓存失效专项（本次修复点）----
code, txt = req("POST", "/api/activity", body={"name": "缓存校验活动 B" + RUN}, token=TOKEN,
                label="[缓存] 创建活动 B", expect_biz=200)
ACT_B = jget(txt, "data", "id")
req("GET", "/api/activity/current", label="[缓存] 当前活动(应命中 B)", expect_biz=200)
req("DELETE", "/api/activity/%s" % ACT_B, token=TOKEN, label="[缓存] 删除活动 B", expect_biz=200)
code, txt = req("GET", "/api/activity/current", label="[缓存] 删除后当前活动应回退到 A",
                expect_biz=200)
cur = jget(txt, "data", "id")
extra("[缓存] 删除活动后 current 不再返回已删除活动", cur == ACT_ID,
      "current.id=%s (期望 %s，修复前会返回 %s)" % (cur, ACT_ID, ACT_B))

print()
print("=" * 112)
print("3. 参与者模块 /api/participant（注册 8 人，供后续用例隔离使用）")
print("=" * 112)
PIDS = []
for i in range(1, 9):
    code, txt = req("POST", "/api/participant/register",
                    body={"activityId": ACT_ID, "name": "测试用户%02d" % i,
                          "phone": "1380000%04d" % i, "studentId": "%s-%02d" % (SID, i)},
                    label="[参与者] 注册 测试用户%02d" % i, expect_biz=200)
    PIDS.append(jget(txt, "data", "id"))
P1, P2, P3, P4, P5, P6, P7, P8 = PIDS
req("POST", "/api/participant/register",
    body={"activityId": ACT_ID, "name": "测试用户01", "studentId": "%s-01" % SID},
    label="[参与者] 重复注册(幂等窗口内)", expect_biz=500)
req("POST", "/api/participant/register", body={"name": "缺活动ID", "studentId": "S9"},
    label="[参与者] 注册-缺 activityId(@Valid)", expect_http=400, expect_biz=400)
req("GET", "/api/participant/check", params={"activityId": ACT_ID, "studentId": "%s-01" % SID},
    label="[参与者] 查询已注册", expect_biz=200)
req("GET", "/api/participant/check", params={"activityId": ACT_ID, "studentId": "NOTEXIST"},
    label="[参与者] 查询未注册", expect_biz=500)
req("GET", "/api/participant/list", params={"activityId": ACT_ID}, label="[参与者] 参与者列表",
    expect_biz=200)
code, txt = req("GET", "/api/participant/count", params={"activityId": ACT_ID},
                label="[参与者] 参与者计数", expect_biz=200)
extra("[参与者] 计数应为 8", jget(txt, "data") == 8, "count=%s" % jget(txt, "data"))

print()
print("=" * 112)
print("4. 奖品模块 /api/prize")
print("=" * 112)
code, txt = req("POST", "/api/prize",
                body={"activityId": ACT_ID, "name": "一等奖 iPhone", "description": "顶配",
                      "stock": 1, "image": "https://example.com/p.jpg"}, token=TOKEN,
                label="[奖品] 新增奖品", expect_biz=200)
PRIZE_ID = jget(txt, "data", "id")
req("GET", "/api/prize/list", params={"activityId": ACT_ID}, token=TOKEN,
    label="[奖品] 奖品列表", expect_biz=200)
req("PUT", "/api/prize/%s" % PRIZE_ID, body={"activityId": ACT_ID, "name": "一等奖 iPad", "stock": 2},
    token=TOKEN, label="[奖品] 更新奖品", expect_biz=200)
req("PUT", "/api/prize/%s" % PRIZE_ID, body={"name": "x"}, label="[奖品] 更新奖品(无 token)",
    expect_http=403, expect_biz=None)
req("GET", "/api/prize/list", params={"activityId": ACT_ID}, label="[奖品] 奖品列表(无 token)",
    expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("5. 弹幕模块 /api/danmaku")
print("=" * 112)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P1, "content": "恭喜恭喜！"},
    label="[弹幕] 正常发送(P1)", expect_biz=200)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P1, "content": "同用户立即再发"},
    label="[弹幕] P1 立即再发(限流 1/s)", expect_biz=429)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P2, "content": "666"},
    label="[弹幕] 正常发送(P2)", expect_biz=200)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P3, "content": "傻逼测试"},
    label="[弹幕] 内置敏感词拦截(P3)", expect_biz=403)
req("POST", "/api/sensitive-word", params={"word": WORD_NEW}, token=TOKEN,
    label="[弹幕] 新增敏感词 '%s'" % WORD_NEW, expect_biz=200)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P4, "content": "这段包含%s的内容" % WORD_NEW},
    label="[弹幕] 新增敏感词即时生效(P4)", expect_biz=403)
req("POST", "/api/danmaku/send", body={"activityId": ACT_ID, "participantId": P5, "content": ""},
    label="[弹幕] 内容为空(@Valid, P5)", expect_http=400, expect_biz=400)
req("POST", "/api/danmaku/send",
    body={"activityId": ACT_ID, "participantId": P6, "content": "缺活动ID的弹幕", },
    label="[弹幕] 正常发送(P6)", expect_biz=200)
req("GET", "/api/danmaku/approved", params={"activityId": ACT_ID}, token=TOKEN,
    label="[弹幕] 已通过列表", expect_biz=200)
req("GET", "/api/danmaku/pending", params={"activityId": ACT_ID}, token=TOKEN,
    label="[弹幕] 待审核列表", expect_biz=200)
time.sleep(1.2)  # 等待弹幕批量入库刷新
code, txt = req("GET", "/api/danmaku/approved", params={"activityId": ACT_ID}, token=TOKEN,
                label="[弹幕] 已通过列表(取审核目标 id)", expect_biz=200)
DM_ID = (jget(txt, "data", default=[]) or [{}])[0].get("id")
extra("[弹幕] 异步批量入库生效(可取到弹幕记录)", DM_ID is not None, "danmakuId=%s" % DM_ID)
req("PUT", "/api/danmaku/%s/review" % DM_ID, params={"status": 0}, token=TOKEN,
    label="[弹幕] 审核->待审核(status=0)", expect_biz=200)
req("PUT", "/api/danmaku/%s/review" % DM_ID, params={"status": 1}, token=TOKEN,
    label="[弹幕] 审核通过并定向广播(status=1)", expect_biz=200)
req("PUT", "/api/danmaku/999999/review", params={"status": 1}, token=TOKEN,
    label="[弹幕] 审核不存在的弹幕", expect_biz=200)
req("PUT", "/api/danmaku/%s/review" % DM_ID, params={"status": 1},
    label="[弹幕] 审核(无 token)", expect_http=403, expect_biz=None)
req("GET", "/api/danmaku/approved", params={"activityId": ACT_ID},
    label="[弹幕] 已通过列表(无 token)", expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("6. 抽奖模块 /api/lottery")
print("=" * 112)
code, txt = req("POST", "/api/lottery/round",
                body={"activityId": ACT_ID, "roundName": "一等奖", "winnerCount": 2}, token=TOKEN,
                label="[抽奖] 创建轮次(count=2)", expect_biz=200)
ROUND_ID = jget(txt, "data", "id")
req("POST", "/api/lottery/round", body={"activityId": ACT_ID, "roundName": "", "winnerCount": 0},
    token=TOKEN, label="[抽奖] 创建轮次-参数非法(@Valid)", expect_http=400, expect_biz=400)
req("GET", "/api/lottery/rounds", params={"activityId": ACT_ID}, token=TOKEN,
    label="[抽奖] 轮次列表", expect_biz=200)
code, txt = req("POST", "/api/lottery/draw/%s" % ROUND_ID, token=TOKEN,
                label="[抽奖] 执行抽奖", expect_biz=200)
W = jget(txt, "data", default=[]) or []
extra("[抽奖] 首次抽奖中奖人数=2", len(W) == 2, "返回 %d 人: %s" % (
    len(W), [w.get("name") for w in W]))
req("POST", "/api/lottery/draw/%s" % ROUND_ID, token=TOKEN,
    label="[抽奖] 立即重复抽奖(修复后应拒绝)", expect_biz=500)
req("POST", "/api/lottery/draw/999999", token=TOKEN, label="[抽奖] 抽不存在的轮次", expect_biz=500)
req("POST", "/api/lottery/draw/%s" % ROUND_ID, label="[抽奖] 抽奖(无 token)",
    expect_http=403, expect_biz=None)
time.sleep(1.0)
code, txt = req("GET", "/api/lottery/winners", params={"roundId": ROUND_ID}, token=TOKEN,
                label="[抽奖] 中奖名单", expect_biz=200)
extra("[抽奖] 中奖记录数=2(与轮次配置一致)", len(jget(txt, "data", default=[]) or []) == 2,
      "winner 行数=%d" % len(jget(txt, "data", default=[]) or []))

print()
print("=" * 112)
print("7. 屏幕配置模块 /api/screen")
print("=" * 112)
req("GET", "/api/screen/config", label="[屏幕] 获取配置(公开)", expect_biz=200)
req("GET", "/api/screen/danmaku-settings", label="[屏幕] 弹幕设置(公开)", expect_biz=200)
req("POST", "/api/screen/danmaku-settings",
    body={"area": "top", "opacity": 60, "fontSize": 32, "speed": 8}, token=TOKEN,
    label="[屏幕] 保存弹幕设置", expect_biz=200)
code, txt = req("GET", "/api/screen/danmaku-settings", label="[屏幕] 弹幕设置回读校验",
                expect_biz=200)
extra("[屏幕] 弹幕设置已持久化", jget(txt, "data", "area") == "top" and
      jget(txt, "data", "opacity") == 60, "回读=%s" % json.dumps(jget(txt, "data"), ensure_ascii=False))
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
req_multipart("/api/screen/background", "t.png", PNG, "image/png", token=TOKEN,
              params={"type": "image"}, label="[屏幕] 上传背景图(依赖 OSS 配置)", expect_biz=500)
req_multipart("/api/screen/background", "t.txt", b"hello", "text/plain", token=TOKEN,
              params={"type": "image"}, label="[屏幕] 上传背景-非法格式", expect_biz=500)
req_multipart("/api/screen/background", "t.png", PNG, "image/png", token=TOKEN,
              params={"type": "bad"}, label="[屏幕] 上传背景-非法 type", expect_biz=500)
req("DELETE", "/api/screen/background", token=TOKEN, label="[屏幕] 清除背景", expect_biz=200)
req("DELETE", "/api/screen/mobile-background", token=TOKEN, label="[屏幕] 清除手机端背景",
    expect_biz=200)
req("DELETE", "/api/screen/background", label="[屏幕] 清除背景(无 token)",
    expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("8. 扫码登录模块 /api/scan  ★修复重点（原被误要求 JWT）")
print("=" * 112)
code, txt = req("POST", "/api/scan/login",
                body={"activityId": ACT_ID, "studentId": "%s-M1" % SID, "name": "扫码用户一",
                      "phone": "13900000001"}, label="[扫码] 扫码登录(无 token，应公开)",
                expect_biz=200)
SCAN = jget(txt, "data", "token")
extra("[扫码] 返回场次专属临时令牌", bool(SCAN), "token=%s" % (SCAN or "")[:12])
req("GET", "/api/scan/validate", token=SCAN, label="[扫码] 校验令牌(无管理员 token)",
    expect_biz=200)
req("GET", "/api/scan/validate", token="notexisttoken", label="[扫码] 校验无效令牌",
    expect_biz=401)
req("POST", "/api/scan/login",
    body={"activityId": ACT_ID, "studentId": "%s-M2" % SID, "name": "扫码用户二"},
    label="[扫码] 扫码登录-手机号可空", expect_biz=200)
req("POST", "/api/scan/login", body={"studentId": "M9", "name": "缺活动ID"},
    label="[扫码] 扫码登录-缺 activityId(@Valid)", expect_http=400, expect_biz=400)
req("POST", "/api/scan/logout", token=SCAN, label="[扫码] 登出(无管理员 token)", expect_biz=200)
req("GET", "/api/scan/validate", token=SCAN, label="[扫码] 登出后令牌应失效", expect_biz=401)

print()
print("=" * 112)
print("9. 管理端用户模块 /api/admin/user")
print("=" * 112)
req("GET", "/api/admin/user/list", params={"activityId": ACT_ID}, token=TOKEN,
    label="[管理] 参与者列表", expect_biz=200)
req("GET", "/api/admin/user/online", params={"activityId": ACT_ID}, token=TOKEN,
    label="[管理] 在线用户列表(无 WS 连接)", expect_biz=200)
req("PUT", "/api/admin/user/%s/mute" % P2, params={"muted": "true"}, token=TOKEN,
    label="[管理] 禁言 测试用户02", expect_biz=200)
req("PUT", "/api/admin/user/%s/mute" % P2, params={"muted": "false"}, token=TOKEN,
    label="[管理] 取消禁言 测试用户02", expect_biz=200)
req("PUT", "/api/admin/user/%s/lottery" % P3, params={"removed": "true"}, token=TOKEN,
    label="[管理] 移除 测试用户03 抽奖资格", expect_biz=200)
req("PUT", "/api/admin/user/%s/lottery" % P3, params={"removed": "false"}, token=TOKEN,
    label="[管理] 恢复 测试用户03 抽奖资格", expect_biz=200)
req("PUT", "/api/admin/user/999999/mute", params={"muted": "true"}, token=TOKEN,
    label="[管理] 禁言不存在的用户", expect_biz=500)
req("PUT", "/api/admin/user/%s/ban" % P3, params={"banned": "true"}, token=TOKEN,
    label="[管理] 移出直播间 测试用户03", expect_biz=200)
req("PUT", "/api/admin/user/%s/ban" % P3, params={"banned": "false"}, token=TOKEN,
    label="[管理] 恢复直播间 测试用户03", expect_biz=200)
req("GET", "/api/admin/user/list", params={"activityId": ACT_ID},
    label="[管理] 参与者列表(无 token)", expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("10. 敏感词模块 /api/sensitive-word")
print("=" * 112)
req("GET", "/api/sensitive-word/list", token=TOKEN, label="[敏感词] 列表", expect_biz=200)
req("POST", "/api/sensitive-word", params={"word": WORD_TMP}, token=TOKEN,
    label="[敏感词] 新增 '%s'" % WORD_TMP, expect_biz=200)
code, txt = req("GET", "/api/sensitive-word/list", token=TOKEN, label="[敏感词] 列表(中文编码校验)",
                expect_biz=200)
words = [w["word"] for w in (jget(txt, "data", default=[]) or [])]
extra("[敏感词] 中文正确存储(无乱码)", ("傻逼" in words) and (WORD_TMP in words),
      "含'傻逼'=%s 含'%s'=%s" % ("傻逼" in words, WORD_TMP, WORD_TMP in words))
TMP_ID = next((w["id"] for w in jget(txt, "data", default=[]) if w["word"] == WORD_TMP), None)
req("POST", "/api/sensitive-word", params={"word": "违禁词批%s" % RUN}, token=TOKEN,
    label="[敏感词] 新增(用于批量/删除校验)", expect_biz=200)
code, txt = req("GET", "/api/sensitive-word/list", token=TOKEN, label="[敏感词] 列表(取删除目标 id)",
                expect_biz=200)
DEL_ID = next((w["id"] for w in jget(txt, "data", default=[]) if w["word"] == "违禁词批%s" % RUN), None)
req("DELETE", "/api/sensitive-word/%s" % DEL_ID, token=TOKEN,
    label="[敏感词] 删除(id=%s)" % DEL_ID, expect_biz=200)
req("POST", "/api/sensitive-word/batch", body=["批量词%sA" % RUN, "批量词%sB" % RUN], token=TOKEN,
    label="[敏感词] 批量新增", expect_biz=200)
req("GET", "/api/sensitive-word/list", label="[敏感词] 列表(无 token)",
    expect_http=403, expect_biz=None)

print()
print("=" * 112)
print("11. WebSocket 辅助接口 /api/ws  ★修复重点")
print("=" * 112)
req("GET", "/api/ws/online", label="[WS] 在线人数(无 token，应公开)", expect_biz=200)
req("GET", "/api/ws/online", token=TOKEN, label="[WS] 在线人数(带 token)", expect_biz=200)

print()
print("=" * 112)
print("12. 前端 nginx 容器 (5173 大屏 / 5174 手机端 / 5175 管理后台)")
print("=" * 112)
for port, name in [(5173, "大屏 lucky-screen"), (5174, "手机端 lucky-mobile"),
                   (5175, "管理后台 lucky-admin")]:
    code, txt = req("GET", "http://localhost:%d/" % port,
                    label="[前端] %s 首页(:%d)" % (name, port), absolute=True,
                    expect_biz=None, is_text=True)
    extra("[前端] %s 返回有效 index.html" % name, "<div id=\"app\"" in txt or "<script" in txt,
          "长度=%d" % len(txt))
req("GET", "http://localhost:5175/api/screen/config", label="[前端] nginx /api 反代到后端(:5175)",
    absolute=True, expect_biz=200)

print()
print("=" * 112)
print("13. WebSocket /ws/lucky 握手与心跳")
print("=" * 112)


def ws_test():
    key = base64.b64encode(uuid.uuid4().bytes).decode()
    path = "/ws/lucky?activityId=%s&participantId=%s" % (ACT_ID, P1)
    s = socket.create_connection(("localhost", 8080), timeout=10)
    s.sendall(("GET %s HTTP/1.1\r\nHost: localhost:8080\r\nUpgrade: websocket\r\n"
               "Connection: Upgrade\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
               % (path, key)).encode())
    head = s.recv(4096).split(b"\r\n\r\n")[0].decode("utf-8", "replace")
    status = head.split("\r\n")[0]
    payload, mask = b"ping", b"\x01\x02\x03\x04"
    masked = bytes(payload[i] ^ mask[i % 4] for i in range(len(payload)))
    s.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + masked)
    time.sleep(0.6)
    s.settimeout(5)
    try:
        resp = s.recv(4096)
    except Exception:
        resp = b""
    s.close()
    return status, resp


try:
    status, resp = ws_test()
    extra("[WS] /ws/lucky 握手 101 且收到 pong", ("101" in status) and (b"pong" in resp),
          "%s | recv=%s" % (status, repr(resp[:90])), method="WS",
          path="/ws/lucky?activityId&participantId")
except Exception as e:
    extra("[WS] /ws/lucky 握手 101 且收到 pong", False, repr(e), method="WS", path="/ws/lucky")
req("GET", "/api/ws/online", label="[WS] 在线人数(WS 断开后回落)", expect_biz=200)

print()
print("=" * 112)
print("14. 数据清理")
print("=" * 112)
req("DELETE", "/api/prize/%s" % PRIZE_ID, token=TOKEN, label="[清理] 删除奖品", expect_biz=200)
req("DELETE", "/api/activity/%s" % ACT_ID, token=TOKEN, label="[清理] 删除活动", expect_biz=200)
code, txt = req("GET", "/api/sensitive-word/list", token=TOKEN, label="[清理] 取敏感词列表",
                expect_biz=200)
for w in (jget(txt, "data", default=[]) or []):
    if RUN in (w.get("word") or ""):
        req("DELETE", "/api/sensitive-word/%s" % w["id"], token=TOKEN,
            label="[清理] 删除动态敏感词 %s" % w["word"], expect_biz=200)

# ============================================================
P = sum(1 for r in RESULTS if r["verdict"] == "PASS")
F = sum(1 for r in RESULTS if r["verdict"] == "FAIL")
with open("api_test_results.json", "w", encoding="utf-8") as f:
    json.dump(RESULTS, f, ensure_ascii=False, indent=2)
print()
print("=" * 112)
print("合计 %d 个用例：PASS=%d，FAIL=%d" % (len(RESULTS), P, F))
print("=" * 112)
if F:
    print("失败用例清单：")
    for r in RESULTS:
        if r["verdict"] == "FAIL":
            print("  - %s | %s %s | HTTP %s (期望 %s) | biz=%s (期望 %s)"
                  % (r["label"], r["method"], r["path"], r["code"], r["expect_http"],
                     jget(r["resp"], "code"), r["expect_biz"]))
            print("    body: %s" % r["resp"][:200])
else:
    print("全部用例通过。")
print("详细结果已写入 api_test_results.json")
