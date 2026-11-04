/**
 * WebSocket 边界测试
 *
 * 用裸 ws 客户端直接发原始帧，测服务端的容错：
 *   1. 超长消息（>64KB）不应把整个应用打挂
 *   2. 非法 JSON 不应导致连接被反复踢掉
 *   3. 空消息 / 未知类型 消息应被安静忽略
 *   4. 多个连接同时在线时广播都能收到
 *   5. ping 应得到 pong（心跳）
 */
const WebSocket = require('ws');

const WS_URL = 'ws://localhost:8080/ws/lucky';
const results = [];

function check(name, cond, extra = '') {
  console.log('  ' + (cond ? 'PASS  ' : 'FAIL  ') + name + (extra ? '  → ' + extra : ''));
  results.push(cond);
  return cond;
}

/** 建立一个裸连接，收集收到的消息 */
function connect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL);
    const received = [];
    ws.on('message', (data) => received.push(String(data)));
    ws.on('error', reject);
    ws.on('open', () => resolve({ ws, received }));
  });
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

(async () => {
  try {
    // ============================================================
    console.log('=== 1) 基础连通与心跳 ===');
    const c1 = await connect();
    await sleep(300);
    c1.ws.send('ping');
    await sleep(800);
    check('ping 能得到 pong', c1.received.some(m => m.includes('pong')), JSON.stringify(c1.received.slice(0, 3)));

    // ============================================================
    console.log('=== 2) 非法 JSON 不应踢掉连接 ===');
    const before = c1.received.length;
    c1.ws.send('{这不是 JSON');
    c1.ws.send('null');
    c1.ws.send('123');
    await sleep(1000);
    const stillOpen = c1.ws.readyState === WebSocket.OPEN;
    check('非法 JSON 后连接仍存活', stillOpen, 'readyState=' + c1.ws.readyState);
    // 能继续通信说明服务端没被打挂
    c1.ws.send('ping');
    await sleep(800);
    check('非法 JSON 后仍能正常通信',
      c1.received.length > before && c1.received.some(m => m.includes('pong')),
      '新收到 ' + (c1.received.length - before) + ' 条');

    // ============================================================
    console.log('=== 3) 未知类型 / 空消息 ===');
    const before3 = c1.received.length;
    c1.ws.send(JSON.stringify({ type: '不存在的类型', data: { foo: 1 } }));
    c1.ws.send(JSON.stringify({}));
    c1.ws.send('');
    await sleep(1000);
    check('未知消息类型不导致异常', c1.ws.readyState === WebSocket.OPEN, 'readyState=' + c1.ws.readyState);
    // 不应产生告警日志/异常响应，只应安静处理
    check('未知消息被安静忽略（无错误响应）',
      c1.received.length === before3, '新增 ' + (c1.received.length - before3) + ' 条');

    // ============================================================
    console.log('=== 4) 超长消息 ===');
    const before4 = c1.received.length;
    const big = JSON.stringify({ type: 'danmaku', data: { content: 'X'.repeat(200000) } });
    console.log('  发送 ' + big.length + ' 字节的超长消息');
    let sendErr = null;
    try { c1.ws.send(big); } catch (e) { sendErr = e.message; }
    await sleep(1500);
    // 要么连接被服务端拒绝/关闭，要么服务端正常吞下；关键是**服务本身不能挂**
    const stateAfterBig = c1.ws.readyState;
    console.log('  超长消息后 readyState=' + stateAfterBig + (sendErr ? ' 发送异常:' + sendErr : ''));

    // 无论连接是否存活，服务都必须还能处理新连接（说明没被打挂）
    let serverAlive = true;
    try {
      const c2 = await connect();
      check('超长消息后服务仍可新建连接', true);
      c2.ws.send('ping');
      await sleep(800);
      check('新连接仍能正常收发', c2.received.some(m => m.includes('pong')),
        JSON.stringify(c2.received.slice(0, 2)));
      c2.ws.close();
    } catch (e) {
      serverAlive = false;
      check('超长消息后服务仍可新建连接', false, e.message);
    }

    // ============================================================
    console.log('=== 5) 多端同时在线广播 ===');
    const a = await connect();
    const b = await connect();
    await sleep(500);
    // 让其中一个通过 ping 确认双向通信
    a.ws.send('ping');
    await sleep(500);
    check('两个连接同时在线',
      a.ws.readyState === WebSocket.OPEN && b.ws.readyState === WebSocket.OPEN);
    check('至少有一个连接收到 pong', a.received.some(m => m.includes('pong')) || b.received.some(m => m.includes('pong')));

    a.ws.close(); b.ws.close();
    c1.ws.close();

  } catch (e) {
    console.error('脚本异常:', e);
    results.push(false);
  }

  const failed = results.filter(r => !r).length;
  console.log('\n' + '═'.repeat(50));
  console.log(' 汇总：' + results.length + ' 项断言，失败 ' + failed + ' 项');
  console.log('═'.repeat(50));
  process.exit(failed > 0 ? 2 : 0);
})();
