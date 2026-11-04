/**
 * 阶段 2：大屏 + 手机端探测
 *
 * 重点看三件事：
 *  1. 大屏/手机端的 API 请求路径是否正确（已知 /api/api 重复问题）
 *  2. WebSocket 是否连得上（.env.production 写死了公网 IP）
 *  3. 手机端在「没有活动」时的表现
 */
const { chromium } = require('playwright');
const { Recorder } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

(async () => {
  const rec = new Recorder('probe-screen-mobile');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    slowMo: 200,
    args: ['--start-maximized', '--window-position=60,40']
  });

  // ---------- 大屏 ----------
  const screenCtx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
  const screenPage = await screenCtx.newPage();
  rec.attach(screenPage, 'screen');

  rec.step('打开大屏 5173');
  await screenPage.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' });
  await screenPage.waitForTimeout(4000);
  await rec.shot(screenPage, 'screen-home');

  const screenText = (await screenPage.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400);
  console.log('  大屏可见文本: ' + screenText);

  // 大屏上的关键状态节点
  const idleVisible = await screenPage.locator('.lottery-idle').count();
  const activeVisible = await screenPage.locator('.lottery-active').count();
  rec.check('大屏渲染出抢奖区域（等待或进行中）', idleVisible + activeVisible > 0, {
    idle: idleVisible, active: activeVisible
  });

  // 二维码 canvas 有没有真的画出东西
  const qrHasContent = await screenPage.evaluate(() => {
    const c = document.querySelector('.qrcode-canvas');
    if (!c) return { found: false };
    try {
      const ctx = c.getContext('2d');
      const data = ctx.getImageData(0, 0, c.width, c.height).data;
      let nonBlank = 0;
      for (let i = 3; i < data.length; i += 4) if (data[i] !== 0) nonBlank++;
      return { found: true, w: c.width, h: c.height, nonBlankPixels: nonBlank };
    } catch (e) {
      return { found: true, error: e.message };
    }
  });
  rec.check('大屏二维码已绘制', qrHasContent.found && qrHasContent.nonBlankPixels > 100, qrHasContent);

  // ---------- 手机端 ----------
  const mCtx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'
  });
  const mPage = await mCtx.newPage();
  rec.attach(mPage, 'mobile');

  rec.step('打开手机端 5174');
  await mPage.goto('http://localhost:5174/', { waitUntil: 'domcontentloaded' });
  await mPage.waitForTimeout(4000);
  await rec.shot(mPage, 'mobile-home');

  const mobileText = (await mPage.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400);
  console.log('  手机端可见文本: ' + mobileText);

  const hasRegForm = await mPage.locator('input[placeholder="输入学号快速进入"]').count();
  const hasEmptyPage = await mPage.locator('.empty-page').count();
  console.log(`  注册表单=${hasRegForm}  无活动页=${hasEmptyPage}`);

  rec.step('检查前端构建产物里的接口地址');
  const envProbe = await mPage.evaluate(async () => {
    // 直接读主 JS，看里面残留的公网 IP 和 /api/api 拼接
    const scripts = Array.from(document.querySelectorAll('script[src]')).map(s => s.src);
    return { scripts };
  });
  console.log('  加载的脚本: ' + JSON.stringify(envProbe.scripts));

  await screenPage.waitForTimeout(300);
  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})().catch((e) => {
  console.error('脚本异常:', e);
  process.exit(1);
});
