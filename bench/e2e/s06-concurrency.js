/**
 * 并发与可靠性专项验证
 *
 * 针对本轮修复的三件事做实测，不看代码看行为：
 *   1. WebSocket 心跳：大屏连接不应再被 30 秒清理任务误杀
 *      （验证方式：连接后观察 ws-close 次数 = 0，且 Redis 心跳值持续刷新）
 *   2. 领奖核销并发：同一个领奖码被并发核销，只能有一次成功
 *   3. 抢奖 RT：sendJoin 改成等 broker confirm 之后，延迟是否仍在可接受范围
 */
const { chromium } = require('playwright');
const { Recorder } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const API = 'http://localhost:8080';
const RUN = String(Date.now()).slice(-6);

async function jfetch(path, options = {}, token) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const r = await fetch(API + path, { ...options, headers });
  const text = await r.text();
  try { return JSON.parse(text); } catch (e) { return { raw: text, status: r.status }; }
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
  const rec = new Recorder('concurrency');
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
    rec.step('大屏连接，观察心跳是否被正确续期');
    await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(3000);

    const openEvents = () => rec.events.filter(e => e.kind === 'ws-open' && e.role === 'screen').length;
    const closeEvents = () => rec.events.filter(e => e.kind === 'ws-close' && e.role === 'screen').length;

    rec.check('首次连接建立', openEvents() >= 1, { opens: openEvents() });

    // 取本机 session 的心跳 key（每台实例的心跳 key 是 lucky:online:heartbeat:{sessionId}）
    const hbKey = sh('docker exec lucky-redis redis-cli --scan --pattern "lucky:online:heartbeat:*" | head -1');
    console.log('  心跳 key: ' + (hbKey || '(未找到)'));

    const readHb = () => hbKey ? sh(`docker exec lucky-redis redis-cli GET ${hbKey}`) : '';
    const first = readHb();
    console.log('  第 1 次读取心跳: ' + first);

    rec.check('连接建立后即写入心跳记录', !!first && first.length > 5, { first });

    // 等 30 秒（原来的清理周期），再读一次心跳值
    console.log('  等待 30 秒观察心跳续期...');
    await page.waitForTimeout(30000);
    const second = readHb();
    console.log('  第 2 次读取心跳: ' + second);

    rec.check('心跳值已刷新（前端 ping 生效）',
      !!first && !!second && first !== second,
      { first, second });
    rec.check('30 秒内未发生断连', closeEvents() === 0, { closes: closeEvents() });

    // 再等 35 秒，跨过原实现的 60 秒阈值
    console.log('  再等 35 秒，跨过旧的 60 秒误杀线...');
    await page.waitForTimeout(35000);
    const third = readHb();
    rec.check('65 秒后连接仍未被清理', closeEvents() === 0, { closes: closeEvents(), hb: third });

    // ============================================================
    rec.step('造一个中奖记录，用于并发核销测试');
    const login = await jfetch('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'admin', password: 'admin123' })
    });
    const jwt = login.data?.token;

    const act = await jfetch('/api/activity', { method: 'POST', body: JSON.stringify({ name: `并发验证-${RUN}` }) }, jwt);
    const activityId = act.data?.id;
    await jfetch(`/api/activity/${activityId}/status?status=1`, { method: 'PUT' }, jwt);

    const prize = await jfetch('/api/prize', {
      method: 'POST',
      body: JSON.stringify({ activityId, name: `并发奖品-${RUN}`, stock: 1, totalStock: 1 })
    }, jwt);
    const prizeId = prize.data?.id;

    const round = await jfetch('/api/lottery/round', {
      method: 'POST',
      body: JSON.stringify({ activityId, prizeId, roundName: `并发轮-${RUN}`, winnerCount: 1 })
    }, jwt);
    const roundId = round.data?.id;

    await jfetch(`/api/lottery/round/${roundId}/start`, { method: 'POST' }, jwt);

    const scan = await jfetch('/api/scan/login', {
      method: 'POST',
      body: JSON.stringify({ activityId, studentId: `RACE${RUN}`, name: '并发测试' })
    });
    const pToken = scan.data?.token;

    // 抢奖 RT 测量（这是加了 broker confirm 之后的真实延迟）
    const t0 = Date.now();
    const join = await jfetch(`/api/lottery/join/${roundId}`, { method: 'POST', headers: { Authorization: pToken } });
    const joinMs = Date.now() - t0;
    console.log(`  抢奖 RT: ${joinMs}ms, 结果: ${JSON.stringify(join).slice(0, 140)}`);
    rec.check('抢奖成功', join.data?.code === 1, join.data);
    rec.check('抢奖 RT 仍在可接受范围（< 100ms）', joinMs < 100, { joinMs });

    // 等 MQ 落库
    await page.waitForTimeout(2500);

    const claimCode = join.data?.claimCode
      || (await jfetch(`/api/lottery/result?roundId=${roundId}&participantId=${scan.data.participantId}`, {}, pToken)).data?.claimCode;
    console.log('  领奖码: ' + claimCode);
    rec.check('拿到领奖码', !!claimCode, { claimCode });

    // ============================================================
    rec.step('并发核销同一个领奖码（10 个请求同时打）');
    if (claimCode) {
      const results = await Promise.all(
        Array.from({ length: 10 }, () =>
          jfetch(`/api/lottery/claim?claimCode=${claimCode}`, { method: 'POST' }, jwt)
        )
      );
      const ok = results.filter(r => r.code === 200).length;
      const dup = results.filter(r => r.code !== 200 && String(r.message || '').includes('已领取')).length;
      console.log(`  成功 ${ok} 次，重复拦截 ${dup} 次，其他 ${10 - ok - dup} 次`);
      console.log('  返回样例: ' + JSON.stringify(results.slice(0, 3).map(r => ({ code: r.code, message: r.message }))));

      rec.check('并发核销只有 1 次成功（无 TOCTOU）', ok === 1, { ok, dup });
    }

    // ============================================================
    rec.step('大屏仍在线（未被心跳清理误杀）');
    await rec.shot(page, 'screen-alive');
    rec.check('全程只建立过 1 次连接', openEvents() === 1, { opens: openEvents(), closes: closeEvents() });

  } catch (e) {
    rec.check('脚本未中断', false, e.message);
    console.error(e);
  }

  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})();
