/**
 * 大屏「抢奖进行中」布局验证
 *
 * 这条路径之前没有任何 CSS（16 个类名全是裸的），
 * 表现为内容挤在左上角、文字逐字换行、名单压住二维码。
 *
 * 做法：先让大屏连上 WS，再用接口造活动→奖品→轮次→开轮，
 * 大屏收到 lottery_start 后切到 active，这时测量真实布局。
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
  return r.json();
}

(async () => {
  const rec = new Recorder('screen-active-layout');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    args: ['--window-position=60,40']
  });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 810 } });
  const page = await ctx.newPage();
  rec.attach(page, 'screen');

  try {
    rec.step('大屏先连上 WebSocket');
    await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);
    const wsOpen = rec.events.some(e => e.kind === 'ws-open' && e.role === 'screen');
    rec.check('大屏 WS 已连接', wsOpen, null);

    rec.step('用接口造场景：活动 → 奖品 → 轮次 → 开轮');
    const login = await jfetch('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'admin', password: 'admin123' })
    });
    const jwt = login.data?.token;
    rec.check('管理员登录拿到 JWT', !!jwt, { code: login.code });

    const act = await jfetch('/api/activity', {
      method: 'POST',
      body: JSON.stringify({ name: `大屏布局验证-${RUN}` })
    }, jwt);
    const activityId = act.data?.id;
    rec.check('活动已创建', !!activityId, { activityId, msg: act.message });

    await jfetch(`/api/activity/${activityId}/status?status=1`, { method: 'PUT' }, jwt);

    const prize = await jfetch('/api/prize', {
      method: 'POST',
      body: JSON.stringify({ activityId, name: `布局奖品-${RUN}`, stock: 5, totalStock: 5 })
    }, jwt);
    const prizeId = prize.data?.id;
    rec.check('奖品已创建', !!prizeId, { prizeId, msg: prize.message });

    const round = await jfetch('/api/lottery/round', {
      method: 'POST',
      body: JSON.stringify({ activityId, prizeId, roundName: `布局轮次-${RUN}`, winnerCount: 5 })
    }, jwt);
    const roundId = round.data?.id;
    rec.check('轮次已创建', !!roundId, { roundId, msg: round.message });

    rec.step('开轮，大屏应切到抢奖进行中');
    await jfetch(`/api/lottery/round/${roundId}/start`, { method: 'POST' }, jwt);
    await page.waitForTimeout(3000);

    const hasActive = await page.locator('.lottery-active').count();
    rec.check('大屏进入 active 状态', hasActive === 1, { hasActive });

    rec.step('测量 active 区域的真实布局');
    const layout = await page.evaluate(() => {
      const rect = (sel) => {
        const el = document.querySelector(sel);
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const s = getComputedStyle(el);
        return {
          x: Math.round(r.x), y: Math.round(r.y),
          w: Math.round(r.width), h: Math.round(r.height),
          display: s.display, fontSize: s.fontSize
        };
      };
      const active = document.querySelector('.lottery-active');
      return {
        active: rect('.lottery-active'),
        rightPanel: rect('.right-panel'),
        header: rect('.active-header'),
        roundTitle: rect('.active-round'),
        stats: rect('.active-stats'),
        eachStat: rect('.active-stat'),
        winners: rect('.active-winners'),
        hint: rect('.active-hint'),
        // 标题是否被压成竖排：宽度 / 高度 的比值能看出来
        roundTitleText: active?.querySelector('.active-round')?.textContent?.trim() || '',
        // 内容纵向跨度是否撑开
        contentHeight: active ? Math.round(active.getBoundingClientRect().height) : 0
      };
    });
    console.log('  布局测量: ' + JSON.stringify(layout, null, 2));

    const rp = layout.rightPanel;
    const at = layout.roundTitle;
    // 右侧面板 1120 宽，标题若被压到 200 以下说明没撑开
    rec.check('抢奖区宽度撑满右侧面板',
      !!layout.active && layout.active.w >= rp.w * 0.9,
      { activeW: layout.active?.w, rightW: rp.w });
    rec.check('轮次标题横向铺开（未被压成竖排）',
      !!at && at.w > 200, at);
    rec.check('三个统计卡片横排', !!layout.stats && layout.stats.w > 300 && layout.stats.h < 200, layout.stats);
    rec.check('中奖名单区域有独立高度', !!layout.winners && layout.winners.w > 400, layout.winners);
    rec.check('布局纵向有层次（内容高度撑开）', layout.contentHeight > 300, layout.contentHeight);

    await rec.shot(page, 'screen-active-fixed');

    rec.step('造一个中奖者，确认名单行样式');
    // 直接调抢奖接口需要扫码令牌，这里走扫码登录拿一个
    const scan = await jfetch('/api/scan/login', {
      method: 'POST',
      body: JSON.stringify({ activityId, studentId: `LAYOUT${RUN}`, name: '布局测试同学' })
    });
    const token = scan.data?.token;
    const pid = scan.data?.participantId;
    rec.check('扫码登录拿到令牌', !!token && !!pid, { hasToken: !!token, pid });

    const join = await jfetch(`/api/lottery/join/${roundId}`, { method: 'POST', headers: { Authorization: token } });
    console.log('  抢奖返回: ' + JSON.stringify(join).slice(0, 200));
    await page.waitForTimeout(2500);

    const rowCount = await page.locator('.active-winner-row').count();
    rec.check('大屏出现中奖名单行', rowCount > 0, { rowCount });

    if (rowCount > 0) {
      const rowLayout = await page.evaluate(() => {
        const row = document.querySelector('.active-winner-row');
        const r = row.getBoundingClientRect();
        const s = getComputedStyle(row);
        return {
          x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height),
          display: s.display, borderLeft: s.borderLeftWidth,
          nameW: Math.round(row.querySelector('.row-name')?.getBoundingClientRect().width || 0)
        };
      });
      console.log('  名单行测量: ' + JSON.stringify(rowLayout));
      rec.check('名单行横向展开', rowLayout.display === 'flex' && rowLayout.w > 300, rowLayout);
      rec.check('姓名列有实际宽度', rowLayout.nameW > 40, rowLayout);
    }

    await rec.shot(page, 'screen-active-with-winner');

    rec.step('收尾：结束本轮');
    await jfetch(`/api/lottery/round/${roundId}/end`, { method: 'POST' }, jwt);
    await page.waitForTimeout(1500);
    await rec.shot(page, 'screen-round-end');

  } catch (e) {
    rec.check('脚本未中断', false, e.message);
    console.error(e);
  }

  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})();
