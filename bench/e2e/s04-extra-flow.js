/**
 * 补充场景 E2E
 *
 * 主流程之外的边界与配套功能：
 *   1. 放大屏背景图（走本地存储降级 + nginx /uploads 代理）
 *   2. 敏感词：后台加词 → 手机端发含该词的弹幕应被拦截
 *   3. 名额只有 1，两个人抢：一个中、一个「未抢到」
 *   4. 领奖核销：调用后端接口验证状态流转（前端暂无入口，记入缺陷）
 */
const { chromium } = require('playwright');
const { Recorder, grepBackend } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const URLS = {
  admin: 'http://localhost:5175/',
  mobile: 'http://localhost:5174/',
  screen: 'http://localhost:5173/'
};
const RUN = String(Date.now()).slice(-6);

// 1x1 PNG，够用来验证「文件能存下来、能被 nginx 代理出去」
const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

(async () => {
  const rec = new Recorder('extra-flow');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    slowMo: 150,
    args: ['--start-maximized', '--window-position=40,20']
  });

  const screenCtx = await browser.newContext({ viewport: { width: 1280, height: 720 } });
  const adminCtx = await browser.newContext({ viewport: { width: 1280, height: 820 } });
  const m1Ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  const m2Ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  const screenPage = await screenCtx.newPage();
  const adminPage = await adminCtx.newPage();
  const m1 = await m1Ctx.newPage();
  const m2 = await m2Ctx.newPage();

  rec.attach(screenPage, 'screen');
  rec.attach(adminPage, 'admin');
  rec.attach(m1, 'mobile1');
  rec.attach(m2, 'mobile2');

  const dialogs = [];
  for (const [page, role] of [[adminPage, 'admin'], [m1, 'mobile1'], [m2, 'mobile2']]) {
    page.on('dialog', async (d) => {
      dialogs.push({ role, type: d.type(), text: d.message() });
      console.log(`  [${role}] 弹窗(${d.type()}): ${d.message()}`);
      await d.accept();
    });
  }
  const lastDialog = () => dialogs.length ? dialogs[dialogs.length - 1].text : '';

  try {
    // ============================================================
    rec.step('大屏与管理后台就位');
    await screenPage.goto(URLS.screen, { waitUntil: 'domcontentloaded' });
    await adminPage.goto(URLS.admin, { waitUntil: 'domcontentloaded' });
    await adminPage.waitForTimeout(1200);

    // 登录
    await adminPage.locator('.login-form input[type="text"]').fill('admin');
    await adminPage.locator('.login-form input[type="password"]').fill('admin123');
    await adminPage.locator('.login-form button[type="submit"]').click();
    await adminPage.waitForTimeout(2500);
    rec.check('登录成功', (await adminPage.locator('.admin-app').count()) === 1, null);

    // ============================================================
    rec.step('上传大屏背景图');
    await adminPage.locator('.sidebar-nav a:has-text("屏幕设置")').click();
    await adminPage.waitForTimeout(1200);
    await rec.shot(adminPage, 'screen-settings');

    const fileInput = adminPage.locator('input[type="file"]').first();
    await fileInput.setInputFiles({
      name: `bg-${RUN}.png`,
      mimeType: 'image/png',
      buffer: Buffer.from(PNG_B64, 'base64')
    });
    // onFileSelected 只把文件暂存到 selectedFile，必须再点「上传背景」才真正发起请求
    await adminPage.locator('button:has-text("上传背景")').click();
    await adminPage.waitForTimeout(4000);
    await rec.shot(adminPage, 'screen-bg-uploaded');

    const uploadMsg = lastDialog();
    rec.check('背景上传成功', uploadMsg.includes('成功'), uploadMsg);

    // 直接问后端要当前配置，再验证这个 URL 在浏览器里能取到（浏览器走 nginx）
    const cfg = await adminPage.evaluate(async () => {
      const r = await fetch('/api/screen/config');
      return r.json();
    });
    const bgUrl = cfg?.data?.backgroundUrl || '';
    console.log('  当前背景 URL: ' + bgUrl);
    rec.check('配置已指向新上传的文件', bgUrl.startsWith('/uploads/'), bgUrl);

    if (bgUrl) {
      const imgResp = await adminPage.request.get(`http://localhost:5175${bgUrl}`);
      rec.check('背景图可通过 nginx 访问', imgResp.status() === 200, { status: imgResp.status() });
    }

    // 大屏的背景配置是 30 秒轮询一次（bgPollTimer），必须等过一个完整周期
    console.log('  等待大屏背景轮询（30 秒周期）...');
    await screenPage.waitForTimeout(32000);
    await rec.shot(screenPage, 'screen-with-bg');
    const bgApplied = await screenPage.evaluate(() => {
      const el = document.querySelector('.screen');
      if (!el) return null;
      const s = getComputedStyle(el);
      return { bgImage: s.backgroundImage, bgColor: s.backgroundColor };
    });
    console.log('  大屏背景样式: ' + JSON.stringify(bgApplied));
    rec.check('大屏已应用自定义背景',
      !!(bgApplied && bgApplied.bgImage && bgApplied.bgImage.includes('/uploads/')),
      bgApplied);

    // ============================================================
    rec.step('添加敏感词');
    await adminPage.locator('.sidebar-nav a:has-text("敏感词")').click();
    await adminPage.waitForTimeout(1000);
    const badWord = `违规${RUN}`;
    await adminPage.locator('input[placeholder="输入敏感词"]').fill(badWord);
    await adminPage.locator('button:has-text("添加")').first().click();
    await adminPage.waitForTimeout(2000);
    await rec.shot(adminPage, 'sensitive-added');

    const wordList = await adminPage.locator('.sensitive-word-item').allInnerTexts();
    rec.check('敏感词已加入列表', wordList.some(t => t.includes(badWord)), wordList.slice(0, 5));

    // ============================================================
    rec.step('建一个只有 1 个名额的轮次');
    await adminPage.locator('.sidebar-nav a:has-text("活动管理")').click();
    await adminPage.waitForTimeout(1000);

    // 当前活动可能已结束，先确保进行中
    const startActBtn = adminPage.locator('button:has-text("开始活动")');
    if (await startActBtn.count() > 0) {
      await startActBtn.click();
      await adminPage.waitForTimeout(1500);
    }

    const prizeName = `限量奖品-${RUN}`;
    await adminPage.locator('input[placeholder="奖品名称（如：无线耳机）"]').fill(prizeName);
    await adminPage.locator('input[placeholder="数量"]').fill('1');
    await adminPage.locator('button:has-text("添加奖品")').click();
    await adminPage.waitForTimeout(2000);

    const sel = adminPage.locator('select');
    const optVal = await sel.locator('option', { hasText: prizeName }).getAttribute('value').catch(() => null);
    if (optVal) await sel.selectOption(optVal);
    const roundName = `秒光轮-${RUN}`;
    await adminPage.locator('input[placeholder="奖项名称（如：一等奖）"]').fill(roundName);
    await adminPage.locator('input[placeholder="名额"]').fill('1');
    await adminPage.locator('button:has-text("创建轮次")').click();
    await adminPage.waitForTimeout(2000);
    await rec.shot(adminPage, 'limited-round-created');
    rec.check('限 1 名额的轮次已创建',
      (await adminPage.locator('.round-item', { hasText: roundName }).count()) === 1, null);

    // ============================================================
    rec.step('两个手机端注册');
    async function register(page, name, sid) {
      await page.goto(URLS.mobile, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2500);
      if (await page.locator('.live-room').count() > 0) {
        await page.evaluate(() => localStorage.clear());
        await page.reload({ waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(2500);
      }
      const quick = page.locator('input[placeholder="输入学号快速进入"]');
      if (await quick.count() > 0) {
        await quick.fill(sid);
        await page.locator('.btn-quick').click();
        await page.waitForTimeout(1500);
      }
      if (await page.locator('input[placeholder="请输入姓名"]').count() > 0) {
        await page.locator('input[placeholder="请输入姓名"]').fill(name);
        await page.locator('input[placeholder="请输入学号"]').fill(sid);
        await page.locator('.btn-register').click();
        await page.waitForTimeout(3000);
      }
      return (await page.locator('.live-room').count()) === 1;
    }
    const ok1 = await register(m1, '李四', `2025${RUN}`);
    const ok2 = await register(m2, '王五', `2026${RUN}`);
    rec.check('两位用户都进入直播间', ok1 && ok2, { ok1, ok2 });
    await rec.shot(m1, 'mobile1-live');
    await rec.shot(m2, 'mobile2-live');

    // ============================================================
    rec.step('敏感词拦截');
    const badInput = m1.locator('input[placeholder="说点什么..."]');
    await badInput.fill(`这句话含${badWord}内容`);
    await m1.locator('.input-row button').click();
    await m1.waitForTimeout(2500);
    await rec.shot(m1, 'sensitive-blocked');

    const blockMsg = lastDialog();
    const inputStillHasText = await badInput.inputValue();
    rec.check('含敏感词的弹幕被拦截',
      blockMsg.includes('敏感') || blockMsg.includes('违规') || inputStillHasText.length > 0,
      { blockMsg, inputStillHasText });

    // ============================================================
    rec.step('开始抢奖（1 个名额 / 2 个人抢）');
    await adminPage.locator('.round-item', { hasText: roundName })
      .locator('button:has-text("开始抢奖")').click();
    await adminPage.waitForTimeout(2500);
    await rec.shot(adminPage, 'limited-round-started');

    await m1.waitForTimeout(1500);
    await m2.waitForTimeout(1500);
    const btn1 = m1.locator('.seckill-btn');
    const btn2 = m2.locator('.seckill-btn');
    rec.check('两个手机端都出现抢奖按钮',
      (await btn1.count()) === 1 && (await btn2.count()) === 1, null);

    // 同时点击，制造真实争抢
    await Promise.all([btn1.click(), btn2.click()]);
    await m1.waitForTimeout(3500);
    await m2.waitForTimeout(1500);
    await rec.shot(m1, 'mobile1-result');
    await rec.shot(m2, 'mobile2-result');

    // 名额抢完后轮次会结束，.seckill-bar 整个消失（v-if="activeRound"），
    // 所以这里要容错：拿不到按钮就从页面文本里找结果
    async function readResult(page, btn) {
      if (await btn.count() > 0) {
        return (await btn.innerText().catch(() => '')).replace(/\s+/g, ' ');
      }
      const body = await page.locator('body').innerText().catch(() => '');
      return '(按钮已消失) ' + body.replace(/\s+/g, ' ').slice(0, 160);
    }
    const t1 = await readResult(m1, btn1);
    const t2 = await readResult(m2, btn2);
    console.log(`  手机1: ${t1}`);
    console.log(`  手机2: ${t2}`);

    // 中奖态有两个文案：轮次进行中显示「已抢到」，轮次结束后常驻卡片显示「恭喜中奖」
    const isWin = (t) => /已抢到|恭喜中奖/.test(t)
    const isLose = (t) => /没抢到|未抢到|手慢|名额已抢完/.test(t)
    const winners = [t1, t2].filter(isWin).length
    rec.check('恰好一人抢到（不超卖）', winners === 1, { winners, t1: t1.slice(0, 60), t2: t2.slice(0, 60) });

    const lostText = isWin(t1) ? t2 : t1;
    rec.check('另一人被明确告知未抢到', isLose(lostText), lostText.slice(0, 80));

    // ============================================================
    rec.step('后端库存核对');
    // 管理后台每 6 秒回源一次轮次与奖品，等够一个周期再断言
    await adminPage.waitForTimeout(8000);
    // 直接从界面读剩余库存，比再拼一次接口参数可靠
    const prizeChips = await adminPage.locator('.prize-chip').allInnerTexts();
    const limitedChip = prizeChips.find(t => t.includes(prizeName)) || '';
    console.log('  奖品库存显示: ' + limitedChip);
    rec.check('奖品库存已扣到 0', /剩余 0/.test(limitedChip), limitedChip);

    // ============================================================
    rec.step('领奖核销接口');
    // 取中奖者的领奖码
    const claimCode = await (async () => {
      const winnerText = isWin(t1) ? t1 : t2;
      const m = winnerText.match(/([0-9A-F]{8})/);
      return m ? m[1] : null;
    })();
    console.log('  领奖码: ' + claimCode);

    if (claimCode) {
      const token = await adminPage.evaluate(() => localStorage.getItem('lucky_admin_token'));
      const first = await adminPage.evaluate(async ({ code, token }) => {
        const r = await fetch('/api/lottery/claim?claimCode=' + code, {
          method: 'POST',
          headers: { 'Authorization': 'Bearer ' + token }
        });
        return { status: r.status, body: await r.json().catch(() => null) };
      }, { code: claimCode, token });
      console.log('  首次核销: ' + JSON.stringify(first));
      rec.check('首次核销成功', first.body?.code === 200, first);

      const second = await adminPage.evaluate(async ({ code, token }) => {
        const r = await fetch('/api/lottery/claim?claimCode=' + code, {
          method: 'POST',
          headers: { 'Authorization': 'Bearer ' + token }
        });
        return { status: r.status, body: await r.json().catch(() => null) };
      }, { code: claimCode, token });
      console.log('  重复核销: ' + JSON.stringify(second));
      rec.check('重复核销被拒绝', second.body?.code !== 200, second);
    } else {
      rec.check('拿到领奖码', false, '按钮文案里没解析到 8 位领奖码');
    }

    // ============================================================
    rec.step('敏感词是否误伤历史弹幕');
    const backendSensitive = grepBackend('敏感', 3);
    console.log('  后端敏感词日志: ' + (backendSensitive.length ? backendSensitive.map(l => l.slice(0, 140)).join(' | ') : '（无）'));

  } catch (err) {
    rec.check('脚本执行未中断', false, err.message);
    console.error('  执行异常:', err);
    await rec.shot(adminPage, 'error-admin').catch(() => {});
  }

  const result = rec.summary();
  await browser.close();
  process.exit(result.failed > 0 ? 2 : 0);
})();
