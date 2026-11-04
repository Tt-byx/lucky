/**
 * E2E 测试公共设施
 *
 * 职责：把「浏览器侧的一切可观测信号」统一收集起来，并和后端日志按时间戳对齐。
 *  - console      : 页面 console 输出（error / warning 单独标红）
 *  - pageerror    : 未捕获的 JS 异常
 *  - requestfailed: 请求根本没发出/连接被拒
 *  - http         : 响应状态码 >= 400
 *  - ws           : WebSocket 帧（可选，调试广播问题用）
 *
 * 设计取舍：只收集、不判定。判定放在场景脚本里显式断言，
 * 避免「监听器把正常业务分支当错误」这类误报。
 */
const fs = require('fs');
const path = require('path');

const OUT_DIR = path.join(__dirname, 'artifacts');

function ensureDir() {
  if (!fs.existsSync(OUT_DIR)) fs.mkdirSync(OUT_DIR, { recursive: true });
}

function ts() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

class Recorder {
  constructor(runName) {
    ensureDir();
    this.runName = runName;
    this.events = [];
    this.stepNo = 0;
    this.shotNo = 0;
  }

  /** 记录一条事件。kind 用于后续归类统计 */
  log(kind, role, detail) {
    const ev = { t: ts(), kind, role, ...detail };
    this.events.push(ev);
    return ev;
  }

  /** 步骤分隔：在日志和截图文件名里都打上标记 */
  step(title) {
    this.stepNo += 1;
    const label = `[步骤 ${this.stepNo}] ${title}`;
    console.log(`\n── ${label}`);
    this.events.push({ t: ts(), kind: 'step', role: 'runner', title: label });
    return this.stepNo;
  }

  /** 断言记录（不抛异常，统一在最后汇总，避免一个失败中断整轮探测） */
  check(name, ok, detail) {
    const flag = ok ? 'PASS' : 'FAIL';
    console.log(`  ${flag}  ${name}${detail !== undefined ? '  → ' + JSON.stringify(detail) : ''}`);
    this.events.push({ t: ts(), kind: 'check', role: 'runner', name, ok, detail });
    return ok;
  }

  /** 截图，文件名带步骤号，方便按顺序回看 */
  async shot(page, name) {
    this.shotNo += 1;
    const file = path.join(OUT_DIR, `s${String(this.stepNo).padStart(2, '0')}-${String(this.shotNo).padStart(2, '0')}-${name}.png`);
    try {
      await page.screenshot({ path: file, fullPage: false });
      return file;
    } catch (e) {
      this.log('error', 'runner', { message: '截图失败: ' + e.message });
      return null;
    }
  }

  /** 给一个页面挂上全部监听器 */
  attach(page, role, options = {}) {
    const withWs = options.withWs !== false;

    page.on('console', (msg) => {
      const type = msg.type();
      // 忽略 Vue devtools 提示这类噪音
      const text = msg.text();
      if (text.includes('Download the Vue Devtools')) return;
      this.log(type === 'error' ? 'console-error' : 'console', role, { level: type, text });
      if (type === 'error') console.log(`  [${role}] console.error: ${text.slice(0, 200)}`);
    });

    page.on('pageerror', (err) => {
      this.log('pageerror', role, { message: err.message, stack: (err.stack || '').split('\n').slice(0, 3).join(' | ') });
      console.log(`  [${role}] 未捕获异常: ${err.message.slice(0, 200)}`);
    });

    page.on('requestfailed', (req) => {
      const f = req.failure();
      this.log('requestfailed', role, {
        url: req.url(),
        method: req.method(),
        error: f ? f.errorText : 'unknown'
      });
      // WebSocket 握手失败也算 requestfailed，但不该刷屏
      console.log(`  [${role}] 请求失败: ${req.method()} ${req.url().slice(0, 120)} → ${f ? f.errorText : ''}`);
    });

    page.on('response', async (resp) => {
      const status = resp.status();
      if (status < 400) return;
      const req = resp.request();
      let body = '';
      try {
        // 只对 JSON 读 body，避免大文件拖慢
        const ct = resp.headers()['content-type'] || '';
        if (ct.includes('json')) body = (await resp.text()).slice(0, 300);
      } catch (_) { /* body 可能已被消费 */ }
      this.log('http', role, { url: resp.url(), status, method: req.method(), body });
      console.log(`  [${role}] HTTP ${status}  ${req.method()} ${resp.url().slice(0, 120)}${body ? '  body=' + body.slice(0, 160) : ''}`);
    });

    if (withWs) {
      page.on('websocket', (ws) => {
        this.log('ws-open', role, { url: ws.url() });
        console.log(`  [${role}] WebSocket 已连接: ${ws.url().slice(0, 140)}`);
        ws.on('socketerror', (err) => {
          this.log('ws-error', role, { url: ws.url(), error: String(err) });
        });
        ws.on('close', () => this.log('ws-close', role, { url: ws.url() }));
      });
    }
  }

  /** 汇总：只统计「故障类」事件，正常 console.log 不算 */
  summary() {
    const failKinds = ['console-error', 'pageerror', 'requestfailed', 'http', 'ws-error'];
    const problems = this.events.filter((e) => failKinds.includes(e.kind));
    const checks = this.events.filter((e) => e.kind === 'check');
    const failed = checks.filter((c) => !c.ok);

    console.log('\n' + '═'.repeat(60));
    console.log(` 汇总：${checks.length} 项断言，失败 ${failed.length} 项`);
    console.log(`      浏览器侧异常信号 ${problems.length} 条`);
    if (failed.length) {
      console.log(' 失败项：');
      failed.forEach((f) => console.log(`   ✗ ${f.name}  → ${JSON.stringify(f.detail)}`));
    }
    if (problems.length) {
      console.log(' 异常信号：');
      problems.slice(0, 40).forEach((p) => {
        const detail = p.text || p.message || `${p.status || ''} ${p.url || ''}` || p.error || '';
        console.log(`   ! [${p.role}] ${p.kind}: ${String(detail).slice(0, 200)}`);
      });
      if (problems.length > 40) console.log(`   ... 另有 ${problems.length - 40} 条`);
    }
    console.log('═'.repeat(60));

    fs.writeFileSync(
      path.join(OUT_DIR, `events-${this.runName}.json`),
      JSON.stringify(this.events, null, 2)
    );
    console.log(`明细: ${path.join(OUT_DIR, `events-${this.runName}.json`)}`);
    return { total: checks.length, failed: failed.length, problems: problems.length };
  }
}

/** 读后端日志尾部，用于「前端点击后后端有没有真的处理」这类对齐 */
function tailBackend(lines = 30) {
  try {
    const { execSync } = require('child_process');
    const out = execSync(`docker logs lucky-backend --tail ${lines}`, {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 20000
    });
    return out.trim();
  } catch (e) {
    return '(读取后端日志失败: ' + e.message + ')';
  }
}

/** 在控制台打印后端日志里带某个关键词的行，方便看清楚请求有没有打到后端 */
function grepBackend(keyword, lines = 8) {
  try {
    const { execSync } = require('child_process');
    // 多取一些再过滤，避免关键词命中的行刚好在被截断的窗口之外
    const out = execSync('docker logs lucky-backend --tail 800', {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 20000
    });
    const hit = out.split('\n').filter((l) => l.includes(keyword));
    return hit.slice(-lines);
  } catch (e) {
    return [];
  }
}

module.exports = { Recorder, tailBackend, grepBackend, OUT_DIR };
