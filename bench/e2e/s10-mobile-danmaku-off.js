/**
 * 弹幕推送改造专项验证（2026-11-05）
 *
 * 决策：手机端不再接收弹幕推送（服务端只推大屏），省掉「条数 × 在线人数」的
 * 公网出带宽放大；手机端保留发送入口，发送成功后由前端本地回显。
 *
 * 本脚本断言三件事：
 *   1. 正常弹幕仍然上大屏（不能因为改造把大屏搞坏）
 *   2. 【关键】弹幕不再推送到手机端（改造目标本身）
 *   3. 手机端 UI 发送弹幕成功后，本地消息流出现自己的发言（发送反馈闭环）
 *
 * 前置：docker compose up -d（frontend 在 80，backend 在 127.0.0.1:8080）
 */
const { chromium } = require('playwright');
const { Recorder } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const API = 'http://localhost:8080';
const SCREEN_URL = 'http://localhost/screen/';
const MOBILE_URL = 'http://localhost/';
const RUN = String(Date.now()).slice(-6);

async function jfetch(path, options = {}, auth) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (auth) headers['Authorization'] = auth;
  const r = await fetch(API + path, { ...options, headers });
  const t = await r.text();
  try { return JSON.parse(t); } catch (e) { return { raw: t, status: r.status }; }
}

(async () => {
  const rec = new Recorder('mobile-danmaku-off');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    args: ['--window-position=60,40']
  });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const screenPage = await ctx.newPage();
  const mobilePage = await ctx.newPage();
  rec.attach(screenPage, 'screen');
  rec.attach(mobilePage, 'mobile');

  try {
    // ============================================================
    rec.step('建场景：活动 + 参与者');
    const jwt = 'Bearer ' + (await jfetch('/api/auth/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin', password: 'admin123' })
    })).data.token;

    const aid = (await jfetch('/api/activity', {
      method: 'POST', body: JSON.stringify({ name: `弹幕只推大屏-${RUN}` })
    }, jwt)).data.id;
    await jfetch(`/api/activity/${aid}/status?status=1`, { method: 'PUT' }, jwt);

    const vo = (await jfetch('/api/scan/login', {
      method: 'POST',
      body: JSON.stringify({ activityId: aid, studentId: `MO${RUN}`, name: '回显测试员' })
    })).data;
    rec.check('场景就绪（活动+令牌）', !!aid && !!vo.token, { aid, pid: vo.participantId });

    // ============================================================
    rec.step('大屏打开 /screen/ 并连上 WS');
    await screenPage.goto(SCREEN_URL, { waitUntil: 'domcontentloaded' });
    await screenPage.waitForTimeout(2500);
    rec.check('大屏 WS 已连接', rec.events.some(e => e.kind === 'ws-open' && e.role === 'screen'), null);

    // ============================================================
    rec.step('手机端注入登录态后打开 / 并连上 WS');
    // 直接 API 注册拿令牌，再通过 localStorage 恢复机制进直播间（与真实「扫码后注册」同一套状态）
    await mobilePage.goto(MOBILE_URL, { waitUntil: 'domcontentloaded' });
    await mobilePage.evaluate((data) => {
      localStorage.setItem('lucky_participant', JSON.stringify(data));
    }, {
      id: vo.participantId,
      name: vo.name,
      studentId: vo.studentId,
      activityId: aid,
      token: vo.token
    });
    await mobilePage.reload({ waitUntil: 'domcontentloaded' });
    await mobilePage.waitForTimeout(2500);
    rec.check('手机端 WS 已连接', rec.events.some(e => e.kind === 'ws-open' && e.role === 'mobile'), null);
    const mobileRoomReady = await mobilePage.locator('.message-area').count();
    rec.check('手机端已进入直播间（有消息区）', mobileRoomReady === 1, { messageArea: mobileRoomReady });

    // ============================================================
    rec.step('API 发一条正常弹幕：应上大屏、不应进手机端');
    const text = `只上大屏的弹幕${RUN}`;
    const r1 = await jfetch('/api/danmaku/send', {
      method: 'POST',
      body: JSON.stringify({ activityId: aid, participantId: vo.participantId, content: text })
    }, vo.token);
    rec.check('弹幕发送接口返回成功', r1.code === 200, r1.message);

    await screenPage.waitForTimeout(4000);
    await rec.shot(screenPage, 'screen-received');
    await rec.shot(mobilePage, 'mobile-not-received');

    const screenText = await screenPage.locator('body').innerText();
    rec.check('大屏收到了弹幕', screenText.includes(text), { shown: screenText.includes(text) });

    const mobileText = await mobilePage.locator('body').innerText();
    rec.check('【关键】手机端没有收到推送的弹幕', !mobileText.includes(text),
      { leaked: mobileText.includes(text) });

    // ============================================================
    rec.step('手机端 UI 发送弹幕：发送成功后本地回显');
    const myText = `我自己发的${RUN}`;
    await mobilePage.fill('input[placeholder="说点什么..."]', myText);
    await mobilePage.click('button:has-text("发送")');
    await mobilePage.waitForTimeout(1500);
    await rec.shot(mobilePage, 'mobile-local-echo');

    const mobileText2 = await mobilePage.locator('body').innerText();
    rec.check('手机端消息流出现自己发的弹幕（本地回显）', mobileText2.includes(myText),
      { echoed: mobileText2.includes(myText) });
    const sender = await mobilePage.locator('.live-msg .msg-sender').first().innerText().catch(() => '');
    rec.check('回显身份显示为「我」', sender === '我', { sender });

    // 不清理测试活动：F-71 之后活动删除会因存在关联参与者/弹幕被拒，
    // 本地测试库里残留一条「弹幕只推大屏-xxx」活动无影响（s07 同样不清理）。

  } catch (e) {
    rec.check('脚本未中断', false, e.message);
    console.error(e);
  }

  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})();
