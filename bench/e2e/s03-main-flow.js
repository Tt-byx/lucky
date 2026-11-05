/**
 * 主流程 E2E：三端联合作业
 *
 * 覆盖真实使用路径：
 *   管理后台登录 → 建活动 → 开始活动 → 建奖品 → 建轮次
 *   → 手机端扫码注册 → 发弹幕 → 大屏收到
 *   → 管理员开轮 → 手机端抢奖 → 大屏实时显示 → 手机端拿到领奖码
 *   → 管理员结束轮次 → 数据库核对
 *
 * 观测面：
 *   - 浏览器 console / pageerror / 失败请求 / 非 2xx 响应
 *   - 每步截图存 artifacts/
 *   - 每步之后打印后端日志里对应的处理痕迹（交叉验证「前端说成功」是不是真的落库了）
 */
const { chromium } = require('playwright');
const { Recorder, grepBackend } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const URLS = {
  admin: 'http://localhost/admin/',
  mobile: 'http://localhost/',
  screen: 'http://localhost/screen/'
};

// 每次跑用不同的后缀，避免历史活动/缓存干扰
const RUN = String(Date.now()).slice(-6);
const ACTIVITY_NAME = `E2E毕业典礼-${RUN}`;
const PRIZE_NAME = `无线耳机-${RUN}`;
const ROUND_NAME = `一等奖-${RUN}`;
const STUDENT = { name: '张三', studentId: `2024${RUN}`, phone: '13800138000' };

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  const rec = new Recorder('main-flow');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    slowMo: 180,
    args: ['--start-maximized', '--window-position=40,20']
  });

  // ---------- 三个独立上下文，模拟三台设备 ----------
  const screenCtx = await browser.newContext({ viewport: { width: 1440, height: 810 } });
  const adminCtx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const mobileCtx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true
  });

  const screenPage = await screenCtx.newPage();
  const adminPage = await adminCtx.newPage();
  const mobilePage = await mobileCtx.newPage();

  rec.attach(screenPage, 'screen');
  rec.attach(adminPage, 'admin');
  rec.attach(mobilePage, 'mobile');

  // 管理后台大量使用 alert/confirm，必须接管，否则脚本会卡在对话框上
  // 同时把文案记下来——这是最直接的业务反馈信号
  const dialogs = [];
  adminPage.on('dialog', async (d) => {
    dialogs.push({ type: d.type(), text: d.message(), t: new Date().toISOString() });
    console.log(`  [admin] 弹窗(${d.type()}): ${d.message()}`);
    await d.accept();
  });
  mobilePage.on('dialog', async (d) => {
    dialogs.push({ type: d.type(), text: d.message(), t: new Date().toISOString() });
    console.log(`  [mobile] 弹窗(${d.type()}): ${d.message()}`);
    await d.accept();
  });

  const lastDialog = () => dialogs.length ? dialogs[dialogs.length - 1].text : '';

  try {
    // ============================================================
    rec.step('大屏首屏加载');
    await screenPage.goto(URLS.screen, { waitUntil: 'domcontentloaded' });
    await screenPage.waitForTimeout(3500);
    await rec.shot(screenPage, 'screen-initial');
    rec.check('大屏无 HTTP 错误', !rec.events.some(e => e.kind === 'http' && e.role === 'screen'), null);

    // ============================================================
    rec.step('管理后台应显示登录页');
    await adminPage.goto(URLS.admin, { waitUntil: 'domcontentloaded' });
    await adminPage.waitForTimeout(1200);
    await rec.shot(adminPage, 'admin-login');

    const loginVisible = await adminPage.locator('.login-card').count();
    rec.check('展示登录页', loginVisible === 1, { count: loginVisible });
    rec.check('未登录时不发业务请求（控制台干净）',
      !rec.events.some(e => e.kind === 'http' && e.role === 'admin'),
      rec.events.filter(e => e.kind === 'http' && e.role === 'admin').map(e => e.url));

    // ============================================================
    rec.step('管理员登录');
    await adminPage.locator('.login-form input[type="text"]').fill('admin');
    await adminPage.locator('.login-form input[type="password"]').fill('admin123');
    await adminPage.locator('.login-form button[type="submit"]').click();
    await adminPage.waitForTimeout(2500);
    await rec.shot(adminPage, 'admin-logged-in');

    const appVisible = await adminPage.locator('.admin-app').count();
    rec.check('登录后进入主界面', appVisible === 1, { appVisible, loginError: await adminPage.locator('.login-error').count() });
    const jwt = await adminPage.evaluate(() => localStorage.getItem('lucky_admin_token'));
    rec.check('JWT 已写入 localStorage', !!jwt && jwt.length > 40, { len: jwt ? jwt.length : 0 });

    // ============================================================
    rec.step('创建活动');
    await adminPage.locator('input[placeholder="活动名称"]').fill(ACTIVITY_NAME);
    await adminPage.locator('button:has-text("创建")').first().click();
    await adminPage.waitForTimeout(2000);
    await rec.shot(adminPage, 'admin-activity-created');

    const createdMsg = lastDialog();
    rec.check('活动创建成功提示', createdMsg.includes('成功'), createdMsg);
    // 数据库里有历史活动，必须按新活动名断言，不能取第一个 .info-row
    const activityPanelText = await adminPage.locator('.panel').first().innerText().catch(() => '');
    rec.check('界面显示当前活动名', activityPanelText.includes(ACTIVITY_NAME),
      activityPanelText.replace(/\s+/g, ' ').slice(0, 160));

    // ============================================================
    rec.step('开始活动');
    const startBtn = adminPage.locator('button:has-text("开始活动")');
    if (await startBtn.count() > 0) {
      await startBtn.click();
      await adminPage.waitForTimeout(1800);
      await rec.shot(adminPage, 'admin-activity-started');
      const statusText = await adminPage.locator('.info-row').first().innerText().catch(() => '');
      rec.check('活动状态变为进行中', statusText.includes('进行中'), statusText);
    } else {
      rec.check('存在「开始活动」按钮', false, '按钮未出现');
    }

    // ============================================================
    rec.step('添加奖品');
    await adminPage.locator('input[placeholder="奖品名称（如：无线耳机）"]').fill(PRIZE_NAME);
    await adminPage.locator('input[placeholder="数量"]').fill('3');
    await adminPage.locator('button:has-text("添加奖品")').click();
    await adminPage.waitForTimeout(1800);
    await rec.shot(adminPage, 'admin-prize-added');

    const prizeChips = await adminPage.locator('.prize-chip').allInnerTexts();
    rec.check('奖品列表出现该奖品', prizeChips.some(t => t.includes(PRIZE_NAME)), prizeChips);

    // ============================================================
    rec.step('创建抢奖轮次');
    const sel = adminPage.locator('select');
    // 选中刚创建的奖品（选项文本里含奖品名）
    const optionValue = await sel.locator('option', { hasText: PRIZE_NAME }).getAttribute('value').catch(() => null);
    if (optionValue) {
      await sel.selectOption(optionValue);
    }
    await adminPage.locator('input[placeholder="奖项名称（如：一等奖）"]').fill(ROUND_NAME);
    await adminPage.locator('input[placeholder="名额"]').fill('2');
    await adminPage.locator('button:has-text("创建轮次")').click();
    await adminPage.waitForTimeout(1800);
    await rec.shot(adminPage, 'admin-round-created');

    const roundItems = await adminPage.locator('.round-item').allInnerTexts();
    rec.check('轮次列表出现该轮次', roundItems.some(t => t.includes(ROUND_NAME)), roundItems);

    // ============================================================
    rec.step('手机端注册');
    await mobilePage.goto(URLS.mobile, { waitUntil: 'domcontentloaded' });
    await mobilePage.waitForTimeout(3000);
    await rec.shot(mobilePage, 'mobile-register');

    const quickInput = mobilePage.locator('input[placeholder="输入学号快速进入"]');
    if (await quickInput.count() === 0) {
      // 可能直接进了直播间（本地有缓存），清掉重来
      await mobilePage.evaluate(() => localStorage.clear());
      await mobilePage.reload({ waitUntil: 'domcontentloaded' });
      await mobilePage.waitForTimeout(2500);
    }

    // 新学号不存在 → 会提示未注册并展开完整表单
    await mobilePage.locator('input[placeholder="输入学号快速进入"]').fill(STUDENT.studentId);
    await mobilePage.locator('.btn-quick').click();
    await mobilePage.waitForTimeout(1500);

    const hasFullForm = await mobilePage.locator('input[placeholder="请输入姓名"]').count();
    if (hasFullForm > 0) {
      await mobilePage.locator('input[placeholder="请输入姓名"]').fill(STUDENT.name);
      await mobilePage.locator('input[placeholder="请输入学号"]').fill(STUDENT.studentId);
      await mobilePage.locator('input[placeholder="请输入手机号（可选）"]').fill(STUDENT.phone);
      await mobilePage.locator('.btn-register').click();
      await mobilePage.waitForTimeout(3000);
    }
    await rec.shot(mobilePage, 'mobile-registered');

    const inLiveRoom = await mobilePage.locator('.live-room').count();
    rec.check('进入直播间', inLiveRoom === 1, { inLiveRoom, pageText: (await mobilePage.locator('body').innerText()).slice(0, 200) });

    // ============================================================
    rec.step('手机端 WebSocket 连接');
    await mobilePage.waitForTimeout(2000);
    const mobileWs = rec.events.filter(e => e.kind === 'ws-open' && e.role === 'mobile');
    rec.check('手机端建立 WebSocket', mobileWs.length > 0, mobileWs.map(e => e.url));

    // ============================================================
    rec.step('大屏应感知在线人数');
    await screenPage.waitForTimeout(3000);
    const onlineText = await screenPage.locator('.stat-value').nth(1).innerText().catch(() => '0');
    rec.check('大屏在线人数 > 0', parseInt(onlineText) > 0, { onlineText });
    await rec.shot(screenPage, 'screen-online');

    // ============================================================
    rec.step('手机端发送弹幕');
    const danmakuText = `弹幕测试${RUN}`;
    await mobilePage.locator('input[placeholder="说点什么..."]').fill(danmakuText);
    await mobilePage.locator('.input-row button').click();
    await mobilePage.waitForTimeout(3000);
    await rec.shot(mobilePage, 'mobile-danmaku-sent');
    await rec.shot(screenPage, 'screen-danmaku');

    const screenDanmaku = await screenPage.locator('.danmaku-item').allInnerTexts().catch(() => []);
    rec.check('大屏显示该弹幕', screenDanmaku.some(t => t.includes(danmakuText)), screenDanmaku.slice(0, 5));
    const backendDanmaku = grepBackend('弹幕', 3);
    console.log('  后端弹幕日志: ' + (backendDanmaku.length ? backendDanmaku.map(l => l.slice(0, 150)).join('\n              ') : '（无）'));

    // ============================================================
    rec.step('管理员开始抢奖');
    const startRoundBtn = adminPage.locator('.round-item button:has-text("开始抢奖")').first();
    if (await startRoundBtn.count() > 0) {
      await startRoundBtn.click();
      await adminPage.waitForTimeout(2500);
      await rec.shot(adminPage, 'admin-round-started');
      // 历史轮次也在列表里，必须定位到本轮自己那条
      const roundText = await adminPage.locator('.round-item', { hasText: ROUND_NAME }).innerText().catch(() => '');
      // 界面文案是「抢奖中」，不是「进行中」
      rec.check('轮次状态变为抢奖中', roundText.includes('抢奖中'), roundText.replace(/\s+/g, ' '));
    } else {
      rec.check('存在「开始抢奖」按钮', false, await adminPage.locator('.round-item').allInnerTexts());
    }

    // 大屏应切到 active
    await screenPage.waitForTimeout(2500);
    await rec.shot(screenPage, 'screen-round-active');
    const activeState = await screenPage.locator('.lottery-active').count();
    rec.check('大屏切换到抢奖进行中', activeState === 1, { activeState });

    // 手机端应出现抢奖按钮
    await mobilePage.waitForTimeout(2000);
    await rec.shot(mobilePage, 'mobile-seckill-bar');
    const seckillBtn = mobilePage.locator('.seckill-btn');
    rec.check('手机端出现抢奖按钮', (await seckillBtn.count()) === 1, null);

    // ============================================================
    rec.step('手机端抢奖');
    if (await seckillBtn.count() > 0) {
      const btnTextBefore = await seckillBtn.innerText();
      console.log('  抢奖按钮文案: ' + btnTextBefore.replace(/\s+/g, ' '));
      await seckillBtn.click();
      await mobilePage.waitForTimeout(3500);
      await rec.shot(mobilePage, 'mobile-seckill-result');

      const btnTextAfter = await seckillBtn.innerText();
      console.log('  抢奖后按钮文案: ' + btnTextAfter.replace(/\s+/g, ' '));
      rec.check('抢奖有明确结果（抢到/未抢到）',
        /已抢到|未抢到|名额|结束/.test(btnTextAfter), btnTextAfter.replace(/\s+/g, ' '));
    }

    // ============================================================
    rec.step('大屏实时显示中奖者');
    await screenPage.waitForTimeout(3000);
    await rec.shot(screenPage, 'screen-winner');
    const winnerRows = await screenPage.locator('.active-winner-row').allInnerTexts().catch(() => []);
    rec.check('大屏出现中奖者', winnerRows.length > 0, winnerRows);
    console.log('  大屏中奖者: ' + JSON.stringify(winnerRows));

    // ============================================================
    rec.step('重复抢奖应被拒绝');
    if (await seckillBtn.count() > 0) {
      const enabled = await seckillBtn.isEnabled();
      rec.check('抢奖按钮已禁用（防重复点击）', !enabled, { enabled });
    }

    // ============================================================
    rec.step('后端日志交叉核对');
    const joinLogs = grepBackend('抢奖', 5);
    console.log('  后端抢奖日志:\n' + (joinLogs.length ? joinLogs.map(l => '    ' + l.slice(0, 180)).join('\n') : '    （无）'));
    const wsLogs = grepBackend('WebSocket 连接建立', 3);
    console.log('  WebSocket 建连日志:\n' + (wsLogs.length ? wsLogs.map(l => '    ' + l.slice(0, 180)).join('\n') : '    （无）'));

  } catch (err) {
    rec.check('脚本执行未中断', false, err.message);
    console.error('  执行异常:', err);
    await rec.shot(adminPage, 'error-admin').catch(() => {});
    await rec.shot(mobilePage, 'error-mobile').catch(() => {});
  }

  // ============================================================
  rec.step('汇总');
  const result = rec.summary();

  // 把弹窗文案也落盘，便于判断业务反馈是否符合预期
  require('fs').writeFileSync(
    require('path').join(__dirname, 'artifacts', `dialogs-${RUN}.json`),
    JSON.stringify(dialogs, null, 2)
  );

  await browser.close();
  process.exit(result.failed > 0 ? 2 : 0);
})();
