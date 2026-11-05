/**
 * 阶段 1：管理后台探测（只观察，不断言）
 *
 * 目的：确认管理后台在浏览器里到底能不能用。
 * 已知疑点：前端源码里没有任何登录逻辑，也不带 Authorization 头，
 *          而 /api/activity POST、/api/prize POST、/api/lottery/round POST 都要求 JWT。
 */
const { chromium } = require('playwright');
const { Recorder, grepBackend } = require('./lib');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const ADMIN = 'http://localhost/admin/';

(async () => {
  const rec = new Recorder('probe-admin');
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    slowMo: 250,
    args: ['--start-maximized', '--window-position=60,40']
  });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  rec.attach(page, 'admin');

  rec.step('打开管理后台首页');
  await page.goto(ADMIN, { waitUntil: 'networkidle' });
  await page.waitForTimeout(800);
  await rec.shot(page, 'admin-home');

  // 页面到底渲染出了什么？把可见文本抓出来，比截图更客观
  const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 500);
  console.log('  页面文本: ' + bodyText);

  const navItems = await page.locator('.sidebar-nav a').allInnerTexts();
  rec.check('侧边栏渲染正常', navItems.length >= 4, navItems);

  rec.step('检查是否有登录入口');
  const hasLoginInput = await page.locator('input[type="password"]').count();
  const hasTokenInStorage = await page.evaluate(() => {
    const keys = Object.keys(localStorage);
    return { keys, hasToken: keys.some(k => /token|jwt|auth/i.test(k)) };
  });
  rec.check('存在登录表单或已存 token', hasLoginInput > 0 || hasTokenInStorage.hasToken, {
    passwordInputs: hasLoginInput,
    localStorageKeys: hasTokenInStorage.keys
  });

  rec.step('尝试创建活动（真实点击，观察请求）');
  const nameInput = page.locator('input[placeholder="活动名称"]');
  const inputCount = await nameInput.count();
  rec.check('创建活动输入框存在', inputCount === 1, { count: inputCount });

  if (inputCount === 1) {
    await nameInput.fill('E2E探测活动');
    const before = rec.events.length;
    await page.locator('button:has-text("创建")').first().click();
    await page.waitForTimeout(2500);
    await rec.shot(page, 'after-create-activity');

    // 这次点击产生了哪些网络事件？
    const netEvents = rec.events.slice(before).filter(e => ['http', 'requestfailed', 'console-error', 'pageerror'].includes(e.kind));
    console.log('  点击后网络/异常事件: ' + JSON.stringify(netEvents, null, 2));

    // 页面有没有反馈（弹窗/提示）？Playwright 会自动关闭 alert，这里看 DOM 变化
    const afterText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 300);
    console.log('  点击后页面文本: ' + afterText);
  }

  rec.step('后端日志交叉核对');
  const lines = grepBackend('activity', 6);
  console.log('  后端日志中含 activity 的行:\n' + (lines.length ? lines.map(l => '    ' + l.slice(0, 200)).join('\n') : '    （无）'));

  await page.waitForTimeout(500);
  const r = rec.summary();
  await browser.close();
  process.exit(r.failed > 0 ? 2 : 0);
})().catch((e) => {
  console.error('脚本异常:', e);
  process.exit(1);
});
