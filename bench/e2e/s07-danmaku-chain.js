/**
 * 弹幕链路专项验证
 *
 * 针对本轮修复的关键点做实测：
 *   1. 【功能失效级】被敏感词命中的弹幕绝不能上大屏
 *      —— 修复前用户看到"已被拒绝"，但内容照样打在 2000 人的大屏上
 *   2. 正常弹幕正常上大屏（不能误伤）
 *   3. 违规弹幕仍入库留档（审计需要）
 *   4. count-by-user 接口能一次给出所有人的弹幕数（替代前端 2000 次请求）
 */
const { chromium } = require('playwright');
const { Recorder } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const API = 'http://localhost:8080';
const RUN = String(Date.now()).slice(-6);

async function jfetch(path, options = {}, auth) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (auth) headers['Authorization'] = auth;
  const r = await fetch(API + path, { ...options, headers });
  const t = await r.text();
  try { return JSON.parse(t); } catch (e) { return { raw: t, status: r.status }; }
}

function sh(cmd) {
  const { execSync } = require('child_process');
  try {
    return execSync(cmd, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 15000 }).trim();
  } catch (e) {
    return '';
  }
}

(async () => {
  const rec = new Recorder('danmaku-chain');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    args: ['--window-position=60,40']
  });
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const page = await ctx.newPage();
  rec.attach(page, 'screen');

  try {
    // ============================================================
    rec.step('大屏先连上 WS');
    await page.goto('http://localhost/screen/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    rec.check('大屏 WS 已连接', rec.events.some(e => e.kind === 'ws-open' && e.role === 'screen'), null);

    rec.step('建场景：活动 + 敏感词 + 用户');
    const jwt = 'Bearer ' + (await jfetch('/api/auth/login', {
      method: 'POST', body: JSON.stringify({ username: 'admin', password: 'admin123' })
    })).data.token;

    const aid = (await jfetch('/api/activity', {
      method: 'POST', body: JSON.stringify({ name: `弹幕验证-${RUN}` })
    }, jwt)).data.id;
    await jfetch(`/api/activity/${aid}/status?status=1`, { method: 'PUT' }, jwt);

    const badWord = `敏感词${RUN}`;
    await jfetch(`/api/sensitive-word?word=${encodeURIComponent(badWord)}`, { method: 'POST' }, jwt);

    const vo = (await jfetch('/api/scan/login', {
      method: 'POST',
      body: JSON.stringify({ activityId: aid, studentId: `DM${RUN}`, name: '弹幕测试员' })
    })).data;
    const tok = vo.token, pid = vo.participantId;
    rec.check('场景就绪', !!aid && !!tok, { aid, pid });

    // ============================================================
    rec.step('发一条正常弹幕，应上大屏');
    const normalText = `正常弹幕${RUN}`;
    const r1 = await jfetch('/api/danmaku/send', {
      method: 'POST',
      body: JSON.stringify({ activityId: aid, participantId: pid, content: normalText })
    }, tok);
    rec.check('正常弹幕发送返回成功', r1.code === 200, r1.message);
    await page.waitForTimeout(3500);
    await rec.shot(page, 'normal-danmaku');

    const screenText1 = await page.locator('body').innerText();
    rec.check('正常弹幕出现在大屏', screenText1.includes(normalText),
      { shown: screenText1.includes(normalText) });

    // ============================================================
    rec.step('发一条含敏感词的弹幕，绝不能上大屏');
    const badText = `这段含${badWord}内容${RUN}`;
    const r2 = await jfetch('/api/danmaku/send', {
      method: 'POST',
      body: JSON.stringify({ activityId: aid, participantId: pid, content: badText })
    }, tok);
    rec.check('敏感词弹幕被接口拒绝', r2.code !== 200, { code: r2.code, message: r2.message });

    await page.waitForTimeout(3500);
    await rec.shot(page, 'sensitive-danmaku');

    const screenText2 = await page.locator('body').innerText();
    rec.check('【关键】敏感词内容没有出现在大屏', !screenText2.includes(badText),
      { leaked: screenText2.includes(badText) });

    // ============================================================
    rec.step('违规弹幕仍应入库留档');
    const rows = sh(`docker exec lucky-mysql mysql -uroot -p123456 lucky -N -e "SELECT status, COUNT(*) FROM danmaku WHERE activity_id=${aid} AND content LIKE '%${badWord}%' GROUP BY status"`);
    console.log('  违规弹幕入库情况(status/count): ' + (rows || '(无)'));
    rec.check('违规弹幕已入库（status=2）', rows.includes('2\t'), rows);
    const normalRows = sh(`docker exec lucky-mysql mysql -uroot -p123456 lucky -N -e "SELECT COUNT(*) FROM danmaku WHERE activity_id=${aid} AND content='${normalText}' AND status=1"`);
    rec.check('正常弹幕已入库（status=1）', normalRows.trim() === '1', normalRows);

    // ============================================================
    rec.step('count-by-user 接口（替代前端 2000 次请求）');
    const cnt = await jfetch(`/api/danmaku/count-by-user?activityId=${aid}`, {}, jwt);
    console.log('  统计结果: ' + JSON.stringify(cnt.data));
    rec.check('count-by-user 返回结果', cnt.code === 200 && !!cnt.data, cnt.data);
    const cntForPid = (cnt.data || {})[String(pid)];
    rec.check('该用户的弹幕数 = 1（只有那条正常的）', cntForPid === 1, cntForPid);

    // ============================================================
    rec.step('approved 接口按 participantId 过滤');
    const list = await jfetch(`/api/danmaku/approved?activityId=${aid}&participantId=${pid}`, {}, jwt);
    const listData = list.data || [];
    rec.check('按人查询只返回该用户的弹幕',
      listData.every(d => d.participantId === pid) && listData.length === 1,
      { count: listData.length, sample: listData.slice(0, 2) });

  } catch (e) {
    rec.check('脚本未中断', false, e.message);
    console.error(e);
    await rec.shot(page, 'error').catch(() => {});
  }

  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})();
