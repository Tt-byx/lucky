/**
 * 多实例（水平扩容）验证
 *
 * 起两个后端实例（8081 / 8082），共享同一套 MySQL/Redis/RabbitMQ。
 * 关键验证：一条广播由实例 A 发出，连在实例 B 的客户端也必须收到。
 *
 * 测的正是单实例测不出来的三件事：
 *   1. WebSocket 广播能否跨实例（WsClusterPublisher/Subscriber + Redis Pub/Sub）
 *   2. 在线人数能否跨实例汇总（unscopedSessions 是实例本地集合）
 *   3. 抢奖库存能否跨实例一致（Redis 预扣，两实例共享）
 *
 * 启动：docker compose --profile multinode up -d --build backend-a backend-b
 */
const WebSocket = require('ws');

const A = 'http://localhost:8081';
const B = 'http://localhost:8082';
const WS_A = 'ws://localhost:8081/ws/lucky';
const WS_B = 'ws://localhost:8082/ws/lucky';

const results = [];
function check(name, cond, extra = '') {
  console.log('  ' + (cond ? 'PASS  ' : 'FAIL  ') + name + (extra ? '  → ' + extra : ''));
  results.push(cond);
  return cond;
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function api(base, method, path, body, auth) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers['Authorization'] = auth;
  const r = await fetch(base + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });
  const t = await r.text();
  try { return JSON.parse(t); } catch (e) { return { raw: t, status: r.status }; }
}

/** 建立一个 WS 连接，收集收到的 JSON 消息 */
function connect(url, extraQuery = '') {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url + extraQuery);
    const received = [];
    ws.on('message', (d) => {
      const s = String(d);
      if (s === 'pong') { received.push({ type: 'pong' }); return; }
      try { received.push(JSON.parse(s)); } catch (e) { received.push({ raw: s }); }
    });
    ws.on('error', reject);
    ws.on('open', () => resolve({ ws, received, url }));
  });
}

(async () => {
  try {
    // ============================================================
    console.log('=== 0) 准备场景（在实例 A 上建活动/奖品/轮次）===');
    const jwt = 'Bearer ' + (await api(A, 'POST', '/api/auth/login',
      { username: 'admin', password: 'admin123' })).data.token;

    const aid = (await api(A, 'POST', '/api/activity',
      { name: '多实例验证-' + Date.now() }, jwt)).data.id;
    await api(A, 'PUT', `/api/activity/${aid}/status?status=1`, null, jwt);

    const pid = (await api(A, 'POST', '/api/prize',
      { activityId: aid, name: '跨实例奖品', stock: 5, totalStock: 5 }, jwt)).data.id;

    const rid = (await api(A, 'POST', '/api/lottery/round',
      { activityId: aid, prizeId: pid, roundName: '跨实例轮', winnerCount: 2 }, jwt)).data.id;

    const vo = (await api(A, 'POST', '/api/scan/login',
      { activityId: aid, studentId: 'MN' + Date.now(), name: '跨实例用户' })).data;

    console.log(`  activityId=${aid} roundId=${rid} participantId=${vo.participantId}`);

    // ============================================================
    console.log('=== 1) 两个实例各连一个客户端 ===');
    // 实例 B 上的大屏（不带身份，模拟大屏）
    const screen = await connect(WS_B);
    // 实例 A 上的手机端（带身份）
    const mobile = await connect(WS_A,
      `?participantId=${vo.participantId}&activityId=${aid}&token=${encodeURIComponent(vo.token)}`);
    await sleep(800);

    check('两个实例都能建立 WS 连接',
      screen.ws.readyState === WebSocket.OPEN && mobile.ws.readyState === WebSocket.OPEN);

    // ============================================================
    console.log('=== 2) 跨实例广播：在实例 A 开始抢奖，实例 B 的大屏要收到 ===');
    const startRes = await api(A, 'POST', `/api/lottery/round/${rid}/start`, null, jwt);
    console.log('  实例 A 开始抢奖: ' + JSON.stringify(startRes).slice(0, 80));
    await sleep(2500);

    const screenGot = screen.received.filter(m => m.type === 'lottery_start');
    const mobileGot = mobile.received.filter(m => m.type === 'lottery_start');
    console.log('  实例 B 大屏收到 lottery_start: ' + screenGot.length + ' 条');
    console.log('  实例 A 手机端收到 lottery_start: ' + mobileGot.length + ' 条');

    check('【关键】实例 B 的客户端收到跨实例广播', screenGot.length > 0,
      JSON.stringify(screenGot.map(m => m.data && m.data.roundName)));
    check('实例 A 的客户端也收到（本实例）', mobileGot.length > 0);

    // ============================================================
    console.log('=== 3) 跨实例在线人数汇总 ===');
    // 在线人数事件是 online_update，两个客户端各算一个
    const onlineEvents = [
      ...screen.received.filter(m => m.type === 'online_update'),
      ...mobile.received.filter(m => m.type === 'online_update')
    ];
    const lastOnline = onlineEvents.length ? onlineEvents[onlineEvents.length - 1].data : null;
    console.log('  最近一次 online_update: ' + JSON.stringify(lastOnline));
    // 至少应该 > 0（大屏不计数，手机端计 1；跨实例汇总后应该看到 1 或 2）
    check('在线人数事件已广播（跨实例汇总）', onlineEvents.length > 0,
      '收到 ' + onlineEvents.length + ' 次事件，最后值=' + lastOnline);

    // ============================================================
    console.log('=== 4) 跨实例抢奖库存一致性 ===');
    // 在实例 A 抢奖
    const joinRes = await api(A, 'POST', `/api/lottery/join/${rid}`, null, vo.token);
    console.log('  实例 A 抢奖: ' + JSON.stringify(joinRes.data || joinRes).slice(0, 100));
    await sleep(1500);

    // 在实例 B 查剩余名额，应该和实例 A 一致（共享 Redis）
    const remB = await api(B, 'GET', `/api/lottery/remaining?roundId=${rid}`);
    const remA = await api(A, 'GET', `/api/lottery/remaining?roundId=${rid}`);
    console.log('  实例 A 剩余名额=' + JSON.stringify(remA.data) + '  实例 B 剩余名额=' + JSON.stringify(remB.data));
    check('两实例读到的剩余名额一致', JSON.stringify(remA.data) === JSON.stringify(remB.data),
      `A=${JSON.stringify(remA.data)} B=${JSON.stringify(remB.data)}`);
    check('抢奖成功且库存已扣减', remA.data === 1, 'remaining=' + remA.data);

    // ============================================================
    console.log('=== 5) 跨实例弹幕广播 ===');
    const danmakuRes = await api(A, 'POST', '/api/danmaku/send',
      { activityId: aid, participantId: vo.participantId, content: '来自实例A的弹幕' }, vo.token);
    console.log('  实例 A 发弹幕: code=' + (danmakuRes.code));
    await sleep(2500);

    const screenDanmaku = screen.received.filter(m =>
      (m.type === 'danmaku' || m.type === 'danmaku_batch') &&
      JSON.stringify(m).includes('来自实例A的弹幕'));
    check('【关键】实例 B 的大屏收到跨实例弹幕', screenDanmaku.length > 0,
      '收到 ' + screenDanmaku.length + ' 条');

    screen.ws.close(); mobile.ws.close();

  } catch (e) {
    console.error('脚本异常:', e);
    results.push(false);
  }

  const failed = results.filter(r => !r).length;
  console.log('\n' + '═'.repeat(52));
  console.log(' 汇总：' + results.length + ' 项断言，失败 ' + failed + ' 项');
  console.log('═'.repeat(52));
  process.exit(failed > 0 ? 2 : 0);
})();
