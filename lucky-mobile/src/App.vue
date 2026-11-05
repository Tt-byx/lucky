<template>
  <div class="mobile-app">
    <!-- 加载中 -->
    <div v-if="loading" class="loading-page">
      <div class="loading-content">
        <div class="loading-spinner">
          <div class="spinner-ring"></div>
          <div class="spinner-ring delay-1"></div>
          <div class="spinner-ring delay-2"></div>
        </div>
        <h2>加载中</h2>
        <p>正在获取活动信息</p>
      </div>
    </div>

    <!-- 已注册：直播间界面 -->
    <div v-else-if="registered" class="live-room" :style="liveRoomStyle">
      <!-- 视频背景 -->
      <video
        v-if="screenConfig.mobileBackgroundType === 'video' && screenConfig.mobileBackgroundUrl"
        class="live-bg-video"
        :src="screenConfig.mobileBackgroundUrl"
        muted loop autoplay playsinline
      ></video>

      <!-- 消息滚动区：手机端不接收全场弹幕（服务端只推大屏，省的是真金白银的公网带宽），
           这里只展示「我自己发出的弹幕」作为发送成功的本地回显 -->
      <div class="message-area" ref="messageArea">
        <div v-for="(msg, idx) in liveMessages" :key="idx" class="live-msg">
          <span class="msg-sender">{{ msg.sender }}</span>
          <span class="msg-content">{{ msg.content }}</span>
        </div>
        <div v-if="liveMessages.length === 0" class="empty-hint">
          <div class="empty-icon">◈</div>
          <p>弹幕将在会场大屏展示</p>
          <p class="empty-sub">在下方发言，全场都能在大屏上看到</p>
        </div>
      </div>

      <!-- 限时抢奖区：只有轮次进行中才出现 -->
      <div v-if="activeRound" class="seckill-bar">
        <div class="seckill-head">
          <span class="seckill-round">{{ activeRound.roundName }}</span>
          <span class="seckill-prize">{{ activeRound.prizeName }}</span>
        </div>
        <div class="seckill-meta">
          <span>剩余名额 {{ activeRound.remaining ?? '-' }}</span>
          <span v-if="countdown > 0" class="seckill-countdown">{{ countdown }}s</span>
        </div>
        <button
          class="seckill-btn"
          :class="{ won: joinState === 'won', lose: joinState === 'lose' }"
          :disabled="joining || joinState !== 'idle'"
          @click="joinRound"
        >
          <span v-if="joining">抢奖中...</span>
          <span v-else-if="joinState === 'won'">已抢到，领奖码 {{ joinResult?.claimCode || '生成中' }}</span>
          <span v-else-if="joinState === 'lose'">未抢到</span>
          <span v-else>立即抢奖</span>
        </button>
      </div>

      <!-- 中奖结果常驻卡片
           轮次结束后 activeRound 会在 3 秒后被置空，整个 .seckill-bar 随之消失，
           中奖者就再也看不到自己的领奖码了。这里与轮次状态解耦，只要中奖就一直保留。 -->
      <div v-if="!activeRound && joinState === 'won'" class="my-prize-card">
        <div class="my-prize-emoji">🎉</div>
        <div class="my-prize-title">恭喜中奖</div>
        <div class="my-prize-sub">{{ joinResult?.prizeName || '奖品' }}</div>
        <div class="my-prize-code">{{ joinResult?.claimCode || '生成中...' }}</div>
        <div class="my-prize-tip">凭此领奖码到领奖台兑换</div>
      </div>

      <!-- 未抢到提示，同样不随轮次消失而丢失 -->
      <div v-else-if="!activeRound && joinState === 'lose'" class="my-lose-card">
        这一轮没抢到，下一轮手速再快一点 💪
      </div>

      <!-- 底部输入栏 -->
      <div class="live-input-bar">
        <div class="quick-bar">
          <span
            v-for="msg in quickMessages"
            :key="msg"
            class="quick-chip"
            @click="quickSend(msg)"
          >{{ msg }}</span>
        </div>
        <div class="input-row">
          <input
            v-model="danmakuText"
            type="text"
            placeholder="说点什么..."
            maxlength="50"
            @keyup.enter="sendDanmaku"
          />
          <button @click="sendDanmaku" :disabled="!danmakuText.trim() || sending">
            <span v-if="sending" class="btn-loading"></span>
            <span v-else>发送</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 无活动 -->
    <div v-else-if="noActivity" class="empty-page">
      <div class="empty-content">
        <div class="empty-icon-large">◈</div>
        <h2>暂无活动</h2>
        <p>当前没有进行中的抽奖活动</p>
        <p class="sub-text">请稍后再试</p>
      </div>
    </div>

    <!-- 未注册：显示注册表单 -->
    <div v-else class="register-page">
      <!-- 背景装饰 -->
      <div class="bg-decoration">
        <div class="circle circle-1"></div>
        <div class="circle circle-2"></div>
        <div class="circle circle-3"></div>
      </div>

      <div class="register-content">
        <!-- Logo区域 -->
        <div class="logo-area">
          <div class="logo-ornament">
            <div class="ornament-line"></div>
            <div class="ornament-diamond"></div>
            <div class="ornament-line"></div>
          </div>
          <h1>{{ activityName }}</h1>
          <p>填写信息参与抽奖</p>
        </div>

        <!-- 快速进入：只需输入学号 -->
        <div v-if="!showFullForm" class="register-form">
          <div class="form-header">
            <h3>快速进入</h3>
            <p>输入学号直接参与</p>
          </div>

          <div class="form-group">
            <label>学号</label>
            <div class="input-wrapper">
              <input v-model="quickStudentId" type="text" placeholder="输入学号快速进入" @keyup.enter="quickEnter" />
              <button class="btn-quick" @click="quickEnter" :disabled="!quickStudentId.trim() || quickChecking">
                <span v-if="quickChecking" class="btn-loading"></span>
                <span v-else>→</span>
              </button>
            </div>
          </div>

          <p class="switch-form" @click="showFullForm = true">
            首次参加？<span class="link">点此注册</span>
          </p>

          <div v-if="errorMsg" class="error-msg">
            <p>{{ errorMsg }}</p>
          </div>
        </div>

        <!-- 完整注册表单 -->
        <form v-else class="register-form" @submit.prevent="register">
          <div class="form-header">
            <h3>注册参与</h3>
            <p>填写信息参与抽奖</p>
          </div>

          <div class="form-group">
            <label>姓名 <span class="required">*</span></label>
            <input v-model="form.name" type="text" placeholder="请输入姓名" required />
          </div>

          <div class="form-group">
            <label>学号 <span class="required">*</span></label>
            <input v-model="form.studentId" type="text" placeholder="请输入学号" required />
          </div>

          <div class="form-group">
            <label>手机号</label>
            <input v-model="form.phone" type="tel" placeholder="请输入手机号（可选）" />
          </div>

          <button type="submit" class="btn-register" :disabled="registering || !activityId">
            <span v-if="registering" class="btn-loading"></span>
            <span v-else>参与抽奖</span>
          </button>

          <p class="switch-form" @click="showFullForm = false">
            已有账号？<span class="link">快速进入</span>
          </p>

          <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'

// 空字符串表示「同源」，由 nginx 把 /api/ 与 /ws/ 代理到后端；
// 开发环境 .env.development 会覆盖成 http://localhost:8080。
// 不能用 `||`：空字符串是 falsy，会静默回落到 localhost，生产环境就打到用户自己电脑了。
const API_BASE = import.meta.env.VITE_API_BASE ?? ''
// WebSocket 地址按当前页面的协议和主机推导，避免写死 IP（换环境就要重新打包）
const WS_URL = import.meta.env.VITE_WS_URL
  || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/lucky`

// 状态
const loading = ref(true)
const activityName = ref('幸运抽奖')
const activityId = ref(null)
const registered = ref(false)
const participant = ref({})
const registering = ref(false)
const errorMsg = ref('')
const noActivity = ref(false)

// 弹幕相关
const danmakuText = ref('')
const sending = ref(false)

// 快速进入
const showFullForm = ref(false)
const quickStudentId = ref('')
const quickChecking = ref(false)

// 直播间相关
const liveMessages = ref([])
const messageArea = ref(null)

// 抢奖相关
const participantToken = ref('')
const activeRound = ref(null)
const joining = ref(false)
const joinState = ref('idle')   // idle | won | lose
const joinResult = ref(null)
const countdown = ref(0)
let countdownTimer = null

// 背景配置
const screenConfig = ref({ mobileBackgroundType: null, mobileBackgroundUrl: null })

// WebSocket
let ws = null
let reconnectTimer = null
let heartbeatTimer = null
let wsClosedByUs = false

const quickMessages = [
  '太棒了！', '恭喜恭喜！', '加油！', '666', '中奖！',
  '哈哈哈', '厉害了', '冲冲冲', '好期待', '稳了'
]

// 表单
const form = ref({
  name: '',
  studentId: '',
  phone: ''
})

// 直播间背景样式
const liveRoomStyle = computed(() => {
  if (screenConfig.value.mobileBackgroundType === 'image' && screenConfig.value.mobileBackgroundUrl) {
    return {
      backgroundImage: `url(${screenConfig.value.mobileBackgroundUrl})`,
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat'
    }
  }
  return {}
})

// 获取当前活动
async function fetchActivity() {
  try {
    const res = await fetch(`${API_BASE}/api/activity/current`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      activityName.value = data.data.name
      activityId.value = data.data.id
      noActivity.value = false
    } else {
      noActivity.value = true
    }
  } catch (e) {
    console.error('获取活动失败:', e)
    noActivity.value = true
  } finally {
    loading.value = false
  }
}

// 获取屏幕配置
async function fetchScreenConfig() {
  try {
    const res = await fetch(`${API_BASE}/api/screen/config`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      screenConfig.value = data.data
    }
  } catch (e) {
    console.error('获取屏幕配置失败:', e)
  }
}

// 连接 WebSocket
function connectWebSocket() {
  if (ws && ws.readyState === WebSocket.OPEN) return

  // 重新连接时要把「主动关闭」标志复位，否则断一次之后就再也连不回来了
  wsClosedByUs = false

  // 握手需要带扫码令牌，后端会用它反查身份，防止冒充
  const url = `${WS_URL}?participantId=${participant.value.id}&activityId=${activityId.value}&token=${encodeURIComponent(participantToken.value || '')}`
  ws = new WebSocket(url)

  ws.onopen = () => {
    console.log('WebSocket 连接成功')
    // 每 25 秒发一次心跳。服务端 90 秒收不到心跳就判定为僵尸连接并断开，
    // 原先前端从不发 ping，导致长连接被周期性掐断、错过抢奖广播。
    if (heartbeatTimer) clearInterval(heartbeatTimer)
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send('ping')
      }
    }, 25000)
  }

  ws.onmessage = (event) => {
    // 心跳响应是纯文本 "pong"，不是 JSON；不拦掉会每条心跳抛一次解析异常
    if (event.data === 'pong') return
    try {
      const msg = JSON.parse(event.data)
      switch (msg.type) {
        // danmaku / danmaku_batch 分支已移除：服务端不再向手机端推送弹幕（只推大屏）。
        // 自己发的弹幕由 sendDanmaku 成功后本地回显。
        case 'muted':
          alert(msg.data || '您已被管理员禁言')
          break
        case 'banned':
          alert(msg.data || '您已被移出直播间')
          disconnectWebSocket()
          registered.value = false
          localStorage.removeItem('lucky_participant')
          break
        case 'lottery_removed':
          alert(msg.data || '您的抽奖资格已被移除')
          break
        case 'lottery_start':
          enterRound(msg.data)
          break
        case 'lottery_winner':
          if (activeRound.value && msg.data.roundId === activeRound.value.roundId
              && msg.data.remaining !== undefined && msg.data.remaining !== null) {
            activeRound.value.remaining = msg.data.remaining
          }
          break
        case 'lottery_round_end':
          if (!activeRound.value || msg.data.roundId === activeRound.value.roundId) {
            stopCountdown()
            setTimeout(() => { activeRound.value = null }, 3000)
          }
          break
      }
    } catch (e) {
      console.error('解析 WebSocket 消息失败:', e)
    }
  }

  ws.onclose = () => {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    // 主动关闭（页面卸载/切活动）时不要再重连，否则定时器会一直循环
    if (wsClosedByUs) return
    console.log('WebSocket 断开，3秒后重连')
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(connectWebSocket, 3000)
  }

  ws.onerror = (err) => {
    console.error('WebSocket 错误:', err)
  }
}

// 断开 WebSocket
function disconnectWebSocket() {
  wsClosedByUs = true
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (heartbeatTimer) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
  if (ws) {
    ws.close()
    ws = null
  }
}

// 注册
async function register() {
  if (!form.value.name || !form.value.studentId) {
    errorMsg.value = '请填写姓名和学号'
    return
  }

  registering.value = true
  errorMsg.value = ''

  try {
    const res = await fetch(`${API_BASE}/api/scan/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        activityId: activityId.value,
        ...form.value
      })
    })
    const data = await res.json()

    if (data.code === 200) {
      const vo = data.data
      participant.value = {
        id: vo.participantId,
        name: vo.name,
        studentId: vo.studentId,
        activityId: vo.activityId
      }
      participantToken.value = vo.token
      registered.value = true
      localStorage.setItem('lucky_participant', JSON.stringify({
        ...participant.value,
        token: vo.token
      }))
      connectWebSocket()
      fetchScreenConfig()
      fetchActiveRound()
    } else {
      errorMsg.value = data.message || '注册失败，请重试'
    }
  } catch (e) {
    errorMsg.value = '网络错误，请检查网络连接'
  } finally {
    registering.value = false
  }
}

// 学号快速进入
async function quickEnter() {
  const sid = quickStudentId.value.trim()
  if (!sid || quickChecking.value) return

  quickChecking.value = true
  errorMsg.value = ''

  try {
    // 统一走扫码登录：已注册用户只传学号即可拿令牌，未注册会提示补姓名
    const res = await fetch(`${API_BASE}/api/scan/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        activityId: activityId.value,
        studentId: sid,
        name: ''
      })
    })
    const data = await res.json()

    if (data.code === 200) {
      const vo = data.data
      participant.value = {
        id: vo.participantId,
        name: vo.name,
        studentId: vo.studentId,
        activityId: vo.activityId
      }
      participantToken.value = vo.token
      registered.value = true
      localStorage.setItem('lucky_participant', JSON.stringify({
        ...participant.value,
        token: vo.token
      }))
      connectWebSocket()
      fetchScreenConfig()
      fetchActiveRound()
    } else {
      errorMsg.value = data.message || '该学号未注册，请先注册'
      showFullForm.value = true
      form.value.studentId = sid
    }
  } catch (e) {
    errorMsg.value = '网络错误，请重试'
  } finally {
    quickChecking.value = false
  }
}

// ==================== 抢奖 ====================

// 查询当前进行中的轮次（防止进房时错过了 lottery_start 广播）
async function fetchActiveRound() {
  if (!activityId.value) return
  try {
    const res = await fetch(`${API_BASE}/api/lottery/rounds?activityId=${activityId.value}`)
    const data = await res.json()
    if (data.code === 200) {
      const active = (data.data || []).find(r => r.status === 1)
      if (active) {
        enterRound({
          roundId: active.id,
          roundName: active.roundName,
          prizeName: active.prizeName,
          quota: active.winnerCount
        })
        fetchRemaining(active.id)
      }
    }
  } catch (e) {
    console.error('查询进行中轮次失败:', e)
  }
}

// 进入抢奖状态
function enterRound(payload) {
  activeRound.value = {
    roundId: payload.roundId,
    roundName: payload.roundName || '限时抢奖',
    prizeName: payload.prizeName || '',
    remaining: payload.quota ?? null
  }
  joinState.value = 'idle'
  joinResult.value = null
  startCountdown(30)
}

// 倒计时（纯前端体验，真正的截止由服务端库存与轮次状态决定）
function startCountdown(seconds) {
  stopCountdown()
  countdown.value = seconds
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopCountdown()
  }, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
  countdown.value = 0
}

// 抢奖
async function joinRound() {
  if (!activeRound.value || joining.value || joinState.value !== 'idle') return
  if (!participantToken.value) {
    alert('登录已失效，请重新进入')
    return
  }

  joining.value = true
  try {
    const res = await fetch(`${API_BASE}/api/lottery/join/${activeRound.value.roundId}`, {
      method: 'POST',
      headers: { 'Authorization': participantToken.value }
    })
    const data = await res.json()

    if (data.code !== 200) {
      alert(data.message || '抢奖失败')
      return
    }

    const result = data.data
    if (result.code === 1) {
      joinState.value = 'won'
      joinResult.value = result
      if (result.remaining !== undefined && result.remaining !== null) {
        activeRound.value.remaining = result.remaining
      }
      // 领奖码由 MQ 落库后生成，稍等再查一次结果
      setTimeout(pollResult, 800)
    } else if (result.code === -6) {
      // 点击过快：这不等于没抢到，按钮要保持可点，否则用户会误以为自己落选了
      alert(result.message)
    } else {
      joinState.value = 'lose'
      alert(result.message)
    }
  } catch (e) {
    alert('网络错误，请重试')
  } finally {
    joining.value = false
  }
}

// 轮询中奖结果，补齐领奖码
async function pollResult() {
  if (!activeRound.value) return
  try {
    const res = await fetch(`${API_BASE}/api/lottery/result?roundId=${activeRound.value.roundId}`, {
      headers: { 'Authorization': participantToken.value }
    })
    const data = await res.json()
    if (data.code === 200 && data.data) {
      joinResult.value = { ...joinResult.value, ...data.data }
    }
  } catch (e) {
    console.error(e)
  }
}

async function fetchRemaining(roundId) {
  try {
    const res = await fetch(`${API_BASE}/api/lottery/remaining?roundId=${roundId}`)
    const data = await res.json()
    if (data.code === 200 && activeRound.value) {
      activeRound.value.remaining = data.data
    }
  } catch (e) {
    console.error(e)
  }
}

// 本地回显自己发出的弹幕。
// 手机端已不接收全场弹幕（服务端只推大屏），所以发送成功后必须在这里
// 手动插一条，否则「发出去了」这件事没有任何视觉反馈。
function appendLiveMessage(data) {
  if (!data || !data.content) return
  liveMessages.value.push({
    sender: data.participantName || '匿名',
    content: data.content
  })
  if (liveMessages.value.length > 100) {
    liveMessages.value = liveMessages.value.slice(-100)
  }
  nextTick(() => {
    if (messageArea.value) {
      messageArea.value.scrollTop = messageArea.value.scrollHeight
    }
  })
}

// 发送弹幕
async function sendDanmaku() {
  const text = danmakuText.value.trim()
  if (!text || sending.value) return

  sending.value = true
  try {
    const res = await fetch(`${API_BASE}/api/danmaku/send`, {
      method: 'POST',
      // 必须带扫码令牌：服务端会校验它与 participantId 是否匹配，
      // 否则任何人拿到别人的 participantId 就能冒充他发弹幕
      headers: {
        'Content-Type': 'application/json',
        'Authorization': participantToken.value || ''
      },
      body: JSON.stringify({
        activityId: activityId.value,
        participantId: participant.value.id,
        content: text
      })
    })
    const data = await res.json()

    if (data.code === 200) {
      danmakuText.value = ''
      // 发送成功本地回显：服务端不向手机端推送弹幕，不补这条的话
      // 自己刚发的消息自己永远看不到
      appendLiveMessage({ participantName: '我', content: text })
    } else {
      alert(data.message || '发送失败')
    }
  } catch (e) {
    alert('网络错误')
  } finally {
    sending.value = false
  }
}

// 快捷发送
function quickSend(msg) {
  danmakuText.value = msg
  sendDanmaku()
}

onMounted(() => {
  fetchActivity().then(() => {
    if (noActivity.value) {
      localStorage.removeItem('lucky_participant')
      participant.value = {}
      registered.value = false
      return
    }

    const saved = localStorage.getItem('lucky_participant')
    if (saved) {
      try {
        const savedParticipant = JSON.parse(saved)
        if (savedParticipant.activityId === activityId.value) {
          participant.value = savedParticipant
          participantToken.value = savedParticipant.token || ''
          registered.value = true
          connectWebSocket()
          fetchScreenConfig()
          fetchActiveRound()
        } else {
          localStorage.removeItem('lucky_participant')
          participant.value = {}
          registered.value = false
        }
      } catch (e) {
        localStorage.removeItem('lucky_participant')
        participant.value = {}
        registered.value = false
      }
    }
  })
})

onUnmounted(() => {
  stopCountdown()
  disconnectWebSocket()
})
</script>

<!-- 设计系统变量必须放在非 scoped 样式里。
     写在 <style scoped> 中会被编译成 :root[data-v-xxx]，而 <html> 没有这个属性，
     所有 var(--color-*) 都会失效：深色背景、白字、圆角全部回落成浏览器默认样式。 -->
<style>
:root {
  /* ============================================================
     移动端设计令牌 —— 与大屏同一套「暗夜香槟金」调性
     毕业典礼手机端：参与感、仪式感、抢奖的紧张感
     ============================================================ */

  /* --- 暖调近黑 --- */
  --color-bg: #0e0d0c;
  --color-bg-light: #171614;
  --color-bg-deep: #07070a;

  /* --- 暖白文字 --- */
  --color-text: #f5f1ea;
  --color-text-secondary: rgba(245, 241, 234, 0.78);
  --color-text-muted: rgba(245, 241, 234, 0.46);

  /* --- 香槟金：唯一强调色 --- */
  --color-primary: #d4af37;
  --color-primary-bright: #f0c75e;
  --color-primary-dark: #a8872a;
  --color-primary-dim: rgba(212, 175, 55, 0.16);
  --color-border-focus: rgba(212, 175, 55, 0.5);

  /* --- 玻璃质感 --- */
  --color-bg-card: rgba(245, 241, 234, 0.035);
  --color-bg-card-strong: rgba(245, 241, 234, 0.065);
  --color-border: rgba(245, 241, 234, 0.10);
  --color-border-strong: rgba(245, 241, 234, 0.18);

  /* --- 错误：赤陶色（与香槟金同族，不是刺眼红） --- */
  --color-error: #c9784f;
  --color-error-bg: rgba(201, 120, 79, 0.14);

  /* --- 字体栈：系统衬线，不依赖 CDN --- */
  --font-display: Georgia, 'Times New Roman', 'Songti SC', 'STSong', 'SimSun', serif;
  --font-body: 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', sans-serif;
  --font-mono: 'SF Mono', 'JetBrains Mono', 'Consolas', monospace;

  /* --- 字阶（1.25 小三度，适合小屏） --- */
  --text-xs: 0.75rem;
  --text-sm: 0.875rem;
  --text-base: 1rem;
  --text-lg: 1.25rem;
  --text-xl: 1.5625rem;
  --text-2xl: 1.953rem;
  --text-3xl: 2.441rem;

  /* --- 圆角 --- */
  --radius-sm: 6px;
  --radius-md: 12px;
  --radius-lg: 18px;
  --radius-xl: 24px;
  --radius-full: 9999px;

  /* --- 阴影三级 --- */
  --shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.28);
  --shadow-md: 0 8px 28px rgba(0, 0, 0, 0.34);
  --shadow-lg: 0 24px 64px rgba(0, 0, 0, 0.42);

  /* --- 金色光晕 --- */
  --glow-gold: 0 0 36px rgba(212, 175, 55, 0.22);
  --glow-gold-strong: 0 0 72px rgba(212, 175, 55, 0.36);

  /* --- 动效 --- */
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --dur-fast: 160ms;
  --dur-base: 320ms;
  --dur-slow: 640ms;
}
</style>

<style scoped>
/* 设计系统 - 移动端优化 */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

.mobile-app {
  min-height: 100vh;
  background: var(--color-bg);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  color: var(--color-text);
  position: relative;
  overflow-x: hidden;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* 加载页面 */
.loading-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
}

.loading-content {
  text-align: center;
}

.loading-spinner {
  position: relative;
  width: 60px;
  height: 60px;
  margin: 0 auto 24px;
}

.spinner-ring {
  position: absolute;
  inset: 0;
  border: 3px solid transparent;
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 1.2s linear infinite;
}

.spinner-ring.delay-1 {
  inset: 8px;
  border-top-color: rgba(245, 158, 11, 0.6);
  animation-delay: 0.15s;
}

.spinner-ring.delay-2 {
  inset: 16px;
  border-top-color: rgba(245, 158, 11, 0.3);
  animation-delay: 0.3s;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-content h2 {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 8px;
}

.loading-content p {
  font-size: 14px;
  color: var(--color-text-secondary);
}

/* 空页面 */
.empty-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
}

.empty-content {
  text-align: center;
  padding: 40px;
}

.empty-icon-large {
  font-family: var(--font-display);
  font-size: 48px;
  line-height: 1;
  margin-bottom: var(--space-5);
  color: var(--color-primary);
  text-shadow: 0 0 36px rgba(212, 175, 55, 0.42);
}

.empty-content h2 {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 12px;
}

.empty-content p {
  font-size: 15px;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
}

.sub-text {
  font-size: 13px !important;
  color: var(--color-text-muted) !important;
}

/* 直播间 */
.live-room {
  display: flex;
  flex-direction: column;
  height: 100vh;
  position: relative;
  z-index: 1;
  background: var(--color-bg);
}

.live-bg-video {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: -1;
  opacity: 0.3;
}

.message-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px 16px;
  -webkit-overflow-scrolling: touch;
}

.live-msg {
  margin-bottom: 16px;
  animation: fadeInUp 0.3s ease;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-sender {
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 600;
}

.msg-content {
  color: var(--color-text);
  font-size: 15px;
  line-height: 1.5;
  padding: 10px 14px;
  background: var(--color-bg-card);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.empty-hint {
  text-align: center;
  color: var(--color-text-muted);
  font-size: 14px;
  padding: 60px 0;
}

.empty-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-text-muted);
  opacity: 0.7;
}

.empty-icon {
  font-family: var(--font-display);
  font-size: 32px;
  line-height: 1;
  margin-bottom: var(--space-4);
  color: var(--color-primary);
  opacity: 0.62;
}

.live-input-bar {
  flex-shrink: 0;
  padding: 12px 16px 20px;
  background: rgba(15, 15, 15, 0.95);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-top: 1px solid var(--color-border);
}

.quick-bar {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 12px;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}

.quick-bar::-webkit-scrollbar {
  display: none;
}

.quick-chip {
  white-space: nowrap;
  padding: 8px 16px;
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  font-size: 13px;
  color: var(--color-text-secondary);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

.quick-chip:active {
  background: var(--color-border);
  transform: scale(0.95);
}

.input-row {
  display: flex;
  gap: 10px;
}

.input-row input {
  flex: 1;
  padding: 12px 16px;
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-full);
  font-size: 15px;
  color: var(--color-text);
  outline: none;
  transition: all 0.2s ease;
}

.input-row input::placeholder {
  color: var(--color-text-muted);
}

.input-row input:focus {
  border-color: var(--color-border-focus);
  background: rgba(245, 158, 11, 0.05);
}

.input-row button {
  padding: 12px 24px;
  background: var(--color-primary);
  color: #000;
  border: none;
  border-radius: var(--radius-full);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
  min-width: 70px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.input-row button:active:not(:disabled) {
  background: var(--color-primary-dark);
  transform: scale(0.95);
}

.input-row button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-loading {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid rgba(0, 0, 0, 0.2);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

/* 注册页面 */
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  z-index: 1;
  background: var(--color-bg);
  overflow: hidden;
}

.bg-decoration {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
}

.circle {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(245, 158, 11, 0.1) 0%, transparent 70%);
}

.circle-1 {
  width: 300px;
  height: 300px;
  top: -100px;
  right: -100px;
}

.circle-2 {
  width: 200px;
  height: 200px;
  bottom: -50px;
  left: -50px;
}

.circle-3 {
  width: 150px;
  height: 150px;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.register-content {
  width: 100%;
  max-width: 400px;
  position: relative;
  z-index: 1;
}

.logo-area {
  text-align: center;
  margin-bottom: 40px;
}

.logo-ornament {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 24px;
}

.ornament-line {
  width: 50px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--color-primary), transparent);
}

.ornament-diamond {
  width: 10px;
  height: 10px;
  background: var(--color-primary);
  transform: rotate(45deg);
}

.logo-area h1 {
  font-size: 28px;
  font-weight: 700;
  color: var(--color-text);
  margin-bottom: 12px;
  letter-spacing: 0.5px;
}

.logo-area p {
  color: var(--color-text-secondary);
  font-size: 15px;
}

/* 注册表单 */
.register-form {
  width: 100%;
  background: var(--color-bg-light);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 28px 24px;
  animation: fadeInUp 0.4s ease-out;
}

.form-header {
  text-align: center;
  margin-bottom: 28px;
}

.form-header h3 {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 8px;
}

.form-header p {
  font-size: 14px;
  color: var(--color-text-secondary);
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  margin-bottom: 8px;
}

.required {
  color: var(--color-error);
}

.input-wrapper {
  position: relative;
  display: flex;
  gap: 10px;
}

.form-group input,
.input-wrapper input {
  width: 100%;
  padding: 14px 16px;
  background: var(--color-bg-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 15px;
  color: var(--color-text);
  transition: all 0.2s ease;
  outline: none;
}

.form-group input::placeholder,
.input-wrapper input::placeholder {
  color: var(--color-text-muted);
}

.form-group input:focus,
.input-wrapper input:focus {
  border-color: var(--color-border-focus);
  background: rgba(245, 158, 11, 0.05);
}

.btn-quick {
  padding: 14px 18px;
  background: var(--color-primary);
  color: #000;
  border: none;
  border-radius: var(--radius-md);
  font-size: 18px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.btn-quick:active:not(:disabled) {
  background: var(--color-primary-dark);
  transform: scale(0.95);
}

.btn-quick:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-register {
  width: 100%;
  padding: 16px;
  background: var(--color-primary);
  color: #000;
  border: none;
  border-radius: var(--radius-md);
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.btn-register:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

.btn-register:active:not(:disabled) {
  transform: scale(0.98);
}

.btn-register:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.error-msg {
  margin-top: 16px;
  padding: 12px 16px;
  background: var(--color-error-bg);
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: var(--radius-md);
}

.error-msg p {
  color: var(--color-error);
  font-size: 13px;
  text-align: center;
  margin: 0;
}

.switch-form {
  margin-top: 20px;
  font-size: 14px;
  color: var(--color-text-secondary);
  text-align: center;
  cursor: pointer;
}

.switch-form:hover {
  color: var(--color-text);
}

.link {
  color: var(--color-primary);
  font-weight: 500;
}

/* 入场动画 */
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 响应式调整 */
@media (max-width: 375px) {
  .register-content {
    padding: 0 8px;
  }

  .register-form {
    padding: 24px 20px;
  }

  .logo-area h1 {
    font-size: 24px;
  }
}

/* 限时抢奖区 */
/* 中奖结果常驻卡片（轮次结束后依然显示，保证能记下领奖码） */
.my-prize-card {
  margin: 0 12px 8px;
  padding: 22px 18px;
  background:
    radial-gradient(80% 120% at 50% 0%, rgba(212, 175, 55, 0.22), transparent 70%),
    linear-gradient(160deg, rgba(212, 175, 55, 0.16), rgba(245, 241, 234, 0.04));
  border: 1px solid rgba(212, 175, 55, 0.5);
  border-radius: var(--radius-xl);
  text-align: center;
  backdrop-filter: blur(20px) saturate(140%);
  -webkit-backdrop-filter: blur(20px) saturate(140%);
  box-shadow: var(--shadow-md), var(--glow-gold);
  animation: prizeIn var(--dur-slow) var(--ease-out);
}

@keyframes prizeIn {
  from { opacity: 0; transform: translateY(18px) scale(0.96); filter: brightness(1.3); }
  to { opacity: 1; transform: translateY(0) scale(1); filter: brightness(1); }
}

.my-prize-emoji {
  font-size: 30px;
  line-height: 1;
  margin-bottom: var(--space-2);
}

.my-prize-title {
  font-family: var(--font-display);
  font-size: var(--text-2xl);
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--color-primary-bright);
  text-shadow: 0 0 32px rgba(212, 175, 55, 0.5);
}

.my-prize-sub {
  margin-top: var(--space-2);
  font-family: var(--font-body);
  font-size: var(--text-sm);
  letter-spacing: 0.12em;
  color: var(--color-text-secondary);
}

.my-prize-code {
  margin: var(--space-3) 0 var(--space-2);
  /* 领奖码是用户要记下来的东西，必须够大够醒目 */
  font-family: var(--font-mono);
  font-size: var(--text-3xl);
  font-weight: 700;
  letter-spacing: 0.22em;
  color: var(--color-primary-bright);
  font-variant-numeric: tabular-nums;
  text-shadow:
    0 0 28px rgba(212, 175, 55, 0.6),
    0 0 60px rgba(212, 175, 55, 0.32);
}

.my-prize-tip {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

/* 未抢到提示 */
.my-lose-card {
  margin: 0 12px 8px;
  padding: 12px 14px;
  background: rgba(148, 163, 184, 0.14);
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  text-align: center;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
}

.seckill-bar {
  margin: 0 12px 8px;
  padding: 12px 14px;
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.45);
  border-radius: 12px;
  backdrop-filter: blur(8px);
}

.seckill-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}

.seckill-round {
  font-size: 16px;
  font-weight: 600;
  color: #fbbf24;
}

.seckill-prize {
  font-size: 13px;
  color: #fde68a;
  opacity: 0.9;
}

.seckill-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #fcd34d;
  margin-bottom: 10px;
}

.seckill-countdown {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.seckill-btn {
  width: 100%;
  min-height: 52px;  /* 触摸目标 ≥ 44px */
  padding: 16px 20px;
  border: none;
  border-radius: var(--radius-lg);
  font-family: var(--font-display);
  font-size: var(--text-lg);
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #1f1400;
  /* 香槟金渐变：主 CTA 的仪式感 */
  background: linear-gradient(148deg,
    var(--color-primary-bright) 0%,
    var(--color-primary) 52%,
    var(--color-primary-dark) 100%);
  box-shadow:
    var(--shadow-md),
    0 0 0 1px rgba(212, 175, 55, 0.32),
    var(--glow-gold);
  cursor: pointer;
  transition: transform var(--dur-fast) ease-out,
              box-shadow var(--dur-fast) ease-out,
              filter var(--dur-fast) ease-out;
  position: relative;
  overflow: hidden;
}

/* 顶部高光线，增加金属感 */
.seckill-btn::before {
  content: "";
  position: absolute;
  top: 0;
  left: 8%;
  right: 8%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent,
    rgba(255, 255, 255, 0.6),
    transparent);
}

.seckill-btn:hover {
  filter: brightness(1.06);
  box-shadow:
    var(--shadow-md),
    0 0 0 1px rgba(212, 175, 55, 0.5),
    var(--glow-gold-strong);
}

.seckill-btn:active {
  transform: scale(0.97);
  filter: brightness(0.96);
}

.seckill-btn:disabled {
  cursor: not-allowed;
  filter: saturate(0.4) brightness(0.78);
  box-shadow: var(--shadow-sm);
}

/* 未抢到时的次级状态 */
.seckill-btn.lose {
  background: linear-gradient(148deg,
    rgba(245, 241, 234, 0.10) 0%,
    rgba(245, 241, 234, 0.05) 100%);
  color: var(--color-text-secondary);
  box-shadow: var(--shadow-sm), 0 0 0 1px var(--color-border);
}

/* 中奖时的高亮状态 */
.seckill-btn.won {
  background: linear-gradient(148deg,
    var(--color-primary-bright) 0%,
    var(--color-primary) 100%);
  color: #1f1400;
  box-shadow: var(--shadow-md), var(--glow-gold-strong);
  animation: winnerPulse 1.6s var(--ease-out) 2;
}

@keyframes winnerPulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.02); box-shadow: var(--shadow-lg), var(--glow-gold-strong); }
}

@media (prefers-reduced-motion: reduce) {
  .seckill-btn { transition: none; animation: none; }
  .seckill-btn.won { animation: none; }
}

.seckill-btn:active:not(:disabled) {
  transform: scale(0.98);
}

.seckill-btn:disabled {
  opacity: 0.75;
}

.seckill-btn.won {
  background: linear-gradient(135deg, #34d399, #10b981);
  color: #04231a;
}

.seckill-btn.lose {
  background: #4b5563;
  color: #e5e7eb;
}
</style>
