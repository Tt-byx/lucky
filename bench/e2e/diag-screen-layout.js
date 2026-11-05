/**
 * 大屏布局诊断：只测量、不改动
 * 目的：查清为什么内容全挤在左上角
 */
const { chromium } = require('playwright');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

(async () => {
  const browser = await chromium.launch({
    headless: false,
    executablePath: CHROME,
    args: ['--window-position=60,40']
  });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 810 } });
  const page = await ctx.newPage();
  await page.goto('http://localhost/screen/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  const info = await page.evaluate(() => {
    const pick = (sel) => {
      const el = document.querySelector(sel);
      if (!el) return { found: false };
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return {
        found: true,
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
        display: s.display,
        position: s.position,
        flexDirection: s.flexDirection
      };
    };
    return {
      window: { innerW: window.innerWidth, innerH: window.innerHeight, dpr: window.devicePixelRatio },
      body: { scrollW: document.body.scrollWidth, scrollH: document.body.scrollHeight },
      screen: pick('.screen'),
      main: pick('.main'),
      leftPanel: pick('.left-panel'),
      rightPanel: pick('.right-panel'),
      stats: pick('.stats'),
      active: pick('.lottery-active'),
      // 命中的媒体查询
      mediaMax1200: window.matchMedia('(max-width: 1200px)').matches,
      // 样式表是否加载
      styleSheets: document.styleSheets.length,
      cssRules: (() => {
        try { return document.styleSheets[0]?.cssRules?.length ?? 0; } catch (e) { return 'blocked: ' + e.message; }
      })()
    };
  });

  console.log(JSON.stringify(info, null, 2));

  await page.screenshot({ path: 'bench/e2e/artifacts/diag-screen-layout.png' });
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
