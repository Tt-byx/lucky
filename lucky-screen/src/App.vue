<template>
  <div class="screen" :style="screenBackgroundStyle">
    <!-- 自定义背景 -->
    <div v-if="screenConfig.backgroundType === 'video'" class="bg-video-wrapper">
      <video
        class="bg-video"
        :src="getBgUrl()"
        muted
        loop
        autoplay
        playsinline
      ></video>
    </div>

    <!-- 粒子背景效果 -->
    <div class="particles-container">
      <div v-for="i in 30" :key="i" class="particle" :style="getParticleStyle(i)"></div>
    </div>

    <!-- 全屏弹幕层 -->
    <div class="danmaku-layer" ref="danmakuLayer">
      <div
        v-for="d in visibleDanmakus"
        :key="d.id"
        :data-danmaku-id="d.id"
        class="danmaku-item"
        :style="d.style"
      >
        {{ d.content }}
      </div>
    </div>

    <!-- 左上角统计信息 - 玻璃拟态风格 -->
    <div class="stats">
      <div class="stat-card">
        <div class="stat-icon">👥</div>
        <div class="stat-info">
          <div class="stat-value">{{ participantCount }}</div>
          <div class="stat-label">参与人数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon pulse">🟢</div>
        <div class="stat-info">
          <div class="stat-value">{{ onlineCount }}</div>
          <div class="stat-label">在线人数</div>
        </div>
      </div>
    </div>

    <!-- 主内容区 -->
    <div class="main">
      <!-- 左侧：二维码区域 -->
      <div class="left-panel">
        <div class="qrcode-box">
          <div class="qrcode-glow"></div>
          <h3>扫码参与</h3>
          <canvas ref="qrcodeCanvas" class="qrcode-canvas"></canvas>
          <p class="qrcode-tip">微信扫一扫 · 即刻参与</p>
        </div>
      </div>

      <!-- 右侧：抢奖区域 -->
      <div class="right-panel">
        <!-- 等待状态 -->
        <div v-if="lotteryState === 'idle'" class="lottery-idle">
          <div class="idle-content">
            <h2 class="idle-title">等待抢奖开始</h2>
            <p class="idle-subtitle">管理员开启轮次后，打开手机点击「立即抢奖」</p>
            <div class="idle-decoration">
              <div class="decoration-ring"></div>
              <div class="decoration-ring delay-1"></div>
              <div class="decoration-ring delay-2"></div>
            </div>
          </div>
        </div>

        <!-- 抢奖进行中：倒计时 + 实时中奖滚动 -->
        <div v-if="lotteryState === 'active'" class="lottery-active">
          <div class="active-header">
            <h2 class="active-round">{{ activeRound.roundName }}</h2>
            <p class="active-prize">{{ activeRound.prizeName }}</p>
          </div>

          <div class="active-stats">
            <div class="active-stat">
              <div class="active-stat-value">{{ activeRound.remaining ?? '-' }}</div>
              <div class="active-stat-label">剩余名额</div>
            </div>
            <div class="active-stat highlight">
              <div class="active-stat-value">{{ countdown }}</div>
              <div class="active-stat-label">倒计时</div>
            </div>
            <div class="active-stat">
              <div class="active-stat-value">{{ winnerList.length }}</div>
              <div class="active-stat-label">已抢到</div>
            </div>
          </div>

          <div class="active-winners">
            <div v-if="winnerList.length === 0" class="active-empty">等待第一位幸运儿...</div>
            <div
              v-for="(w, index) in winnerList"
              :key="w.participantId"
              class="active-winner-row"
            >
              <span class="row-index">{{ index + 1 }}</span>
              <span class="row-name">{{ w.participantName }}</span>
              <span class="row-student">{{ w.studentId }}</span>
              <span class="row-code">{{ w.claimCode }}</span>
            </div>
          </div>

          <p class="active-hint">手机端点击「立即抢奖」，先到先得</p>
        </div>

        <!-- 本轮结束：完整名单 -->
        <div v-if="lotteryState === 'result'" class="lottery-result">
          <div class="result-container">
            <!-- 背景光效 -->
            <div class="result-glow"></div>

            <div class="result-card">
              <div class="result-header">
                <div class="trophy-icon">🏆</div>
                <h2>{{ activeRound.roundName }} 中奖名单</h2>
                <div class="confetti">
                  <div v-for="i in 30" :key="i" class="confetti-piece" :style="getConfettiStyle(i)"></div>
                </div>
              </div>

              <div class="winners-grid">
                <div
                  v-for="(w, index) in winnerList"
                  :key="w.participantId"
                  class="winner-card"
                  :style="{ animationDelay: index * 0.1 + 's' }"
                >
                  <div class="winner-rank">{{ index + 1 }}</div>
                  <div class="winner-avatar">{{ (w.participantName || '幸').charAt(0) }}</div>
                  <div class="winner-name">{{ w.participantName }}</div>
                  <div class="winner-info">{{ w.studentId }}</div>
                </div>
              </div>

              <button class="btn-continue" @click="resetLottery">
                <span>继续</span>
                <div class="btn-shine"></div>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import QRCode from 'qrcode'

// 配置
const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'
const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws/lucky'
const MOBILE_URL = import.meta.env.VITE_MOBILE_URL || `${window.location.protocol}//${window.location.hostname}:5174`

// 状态
const participantCount = ref(0)
const onlineCount = ref(0)
const qrcodeCanvas = ref(null)
const danmakuLayer = ref(null)

// 背景配置
const screenConfig = ref({ backgroundType: null, backgroundUrl: null })
let bgPollTimer = null

// 弹幕相关
const danmakus = ref([])
const visibleDanmakus = ref([])
let danmakuIdCounter = 0

// 弹幕设置
const danmakuSettings = ref({
  area: 'full',
  opacity: 80,
  fontSize: 28,
  speed: 10
})

// 抢奖状态
const lotteryState = ref('idle') // idle, active, result
const activeRound = ref({})
const winnerList = ref([])
const countdown = ref(0)
let countdownTimer = null

// 生成粒子样式
function getParticleStyle(index) {
  const size = Math.random() * 4 + 2
  return {
    width: size + 'px',
    height: size + 'px',
    left: Math.random() * 100 + '%',
    top: Math.random() * 100 + '%',
    animationDuration: (Math.random() * 10 + 10) + 's',
    animationDelay: (Math.random() * 5) + 's',
    opacity: Math.random() * 0.5 + 0.1
  }
}

// 生成滚动粒子样式
function getRollingParticleStyle(index) {
  const angle = (index / 20) * Math.PI * 2
  const radius = 200 + Math.random() * 100
  return {
    left: `calc(50% + ${Math.cos(angle) * radius}px)`,
    top: `calc(50% + ${Math.sin(angle) * radius}px)`,
    animationDuration: (Math.random() * 2 + 1) + 's',
    animationDelay: (Math.random() * 0.5) + 's'
  }
}

// 生成彩带样式
function getConfettiStyle(index) {
  const colors = ['#feca57', '#ff6b6b', '#48dbfb', '#ff9ff3', '#54a0ff', '#5f27cd', '#01a3a4', '#00d2d3']
  return {
    left: Math.random() * 100 + '%',
    animationDuration: (Math.random() * 2 + 1) + 's',
    animationDelay: (Math.random() * 0.5) + 's',
    backgroundColor: colors[index % colors.length]
  }
}

// 生成二维码
async function generateQRCode() {
  if (qrcodeCanvas.value) {
    try {
      await QRCode.toCanvas(qrcodeCanvas.value, MOBILE_URL, {
        width: 200,
        margin: 2,
        color: {
          dark: '#ffffff',
          light: '#00000000'
        }
      })
    } catch (err) {
      console.error('生成二维码失败:', err)
    }
  }
}

// WebSocket 连接
let ws = null
function connectWebSocket() {
  try {
    ws = new WebSocket(WS_URL)

    ws.onopen = () => {
      console.log('WebSocket 已连接')
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        switch (msg.type) {
          case 'participant_update':
            participantCount.value = msg.data
            break
          case 'online_update':
            onlineCount.value = msg.data
            break
          case 'danmaku':
            addDanmaku(msg.data)
            break
          case 'lottery_start':
            startSeckill(msg.data)
            break
          case 'lottery_winner':
            addWinner(msg.data)
            break
          case 'lottery_round_end':
            finishRound(msg.data)
            break
          case 'lottery_result':
            // 兼容旧版广播：直接当成一轮结束
            finishRound(null)
            break
        }
      } catch (e) {
        console.error('解析消息失败:', e)
      }
    }

    ws.onclose = () => {
      console.log('WebSocket 已断开，3秒后重连...')
      setTimeout(connectWebSocket, 3000)
    }

    ws.onerror = (err) => {
      console.error('WebSocket 错误:', err)
    }
  } catch (e) {
    console.error('连接 WebSocket 失败:', e)
    setTimeout(connectWebSocket, 3000)
  }
}

// 添加弹幕
function addDanmaku(data) {
  const id = ++danmakuIdCounter
  const settings = danmakuSettings.value

  // 根据弹幕区域计算 top 位置
  let top
  const area = settings.area || 'full'
  if (area === 'top') {
    top = Math.random() * 30 + 5
  } else if (area === 'bottom') {
    top = Math.random() * 30 + 65
  } else {
    top = Math.random() * 75 + 5
  }

  // 随机颜色
  const colors = ['#ff6b6b', '#feca57', '#48dbfb', '#ff9ff3', '#54a0ff', '#5f27cd', '#01a3a4', '#00d2d3', '#ff9f43', '#10ac84']
  const color = colors[Math.floor(Math.random() * colors.length)]
  const fontSizeVar = settings.fontSize + (Math.random() - 0.5) * 8

  const danmaku = {
    id,
    content: data.content,
    style: {
      top: top + '%',
      color,
      fontSize: fontSizeVar + 'px',
      animationDuration: duration + 's',
      opacity: settings.opacity / 100
    }
  }

  visibleDanmakus.value.push(danmaku)

  nextTick(() => {
    const el = document.querySelector(`[data-danmaku-id="${id}"]`)
    if (el) {
      el.addEventListener('animationend', () => {
        const idx = visibleDanmakus.value.findIndex(d => d.id === id)
        if (idx !== -1) visibleDanmakus.value.splice(idx, 1)
      }, { once: true })
    }
  })
}

// 开始抢奖：进入倒计时状态
function startSeckill(payload) {
  if (!payload) return
  activeRound.value = {
    roundId: payload.roundId,
    roundName: payload.roundName || '限时抢奖',
    prizeName: payload.prizeName || '',
    remaining: payload.quota ?? null
  }
  winnerList.value = []
  lotteryState.value = 'active'
  startCountdown(30)
}

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

// 有人抢到：实时追加到大屏
function addWinner(payload) {
  if (!payload || payload.roundId !== activeRound.value.roundId) return
  if (winnerList.value.some(w => w.participantId === payload.participantId)) return

  winnerList.value.push({
    participantId: payload.participantId,
    participantName: payload.participantName || '幸运儿',
    studentId: payload.studentId || '',
    claimCode: payload.claimCode || ''
  })

  if (payload.remaining !== undefined && payload.remaining !== null) {
    activeRound.value.remaining = payload.remaining
  }
}

// 本轮结束：切到完整名单
function finishRound(payload) {
  if (payload && payload.roundId && payload.roundId !== activeRound.value.roundId) return
  stopCountdown()
  if (winnerList.value.length > 0) {
    lotteryState.value = 'result'
  } else {
    lotteryState.value = 'idle'
  }
}

// 重置回等待状态
function resetLottery() {
  lotteryState.value = 'idle'
  activeRound.value = {}
  winnerList.value = []
}

// 获取参与人数
async function fetchParticipantCount() {
  try {
    const res = await fetch(`${API_BASE}/api/activity/current`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      const countRes = await fetch(`${API_BASE}/api/participant/count?activityId=${data.data.id}`)
      const countData = await countRes.json()
      if (countData.code === 200) {
        participantCount.value = countData.data
      }
    }
  } catch (e) {
    console.error('获取参与人数失败:', e)
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

// 获取背景完整URL
function getBgUrl() {
  if (screenConfig.value.backgroundUrl) {
    return screenConfig.value.backgroundUrl
  }
  return ''
}

// 屏幕背景样式
const screenBackgroundStyle = computed(() => {
  if (screenConfig.value.backgroundType === 'image' && screenConfig.value.backgroundUrl) {
    return {
      backgroundImage: `url(${screenConfig.value.backgroundUrl})`,
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat'
    }
  }
  return {}
})

onMounted(() => {
  generateQRCode()
  connectWebSocket()
  fetchParticipantCount()
  fetchScreenConfig()
  bgPollTimer = setInterval(() => {
    fetchScreenConfig()
  }, 30000)
})

onUnmounted(() => {
  if (ws) ws.close()
  stopCountdown()
  if (bgPollTimer) clearInterval(bgPollTimer)
})
</script>

<style scoped>
/* 设计系统 - 使用CSS变量 */
:root {
  --color-primary: #feca57;
  --color-secondary: #ff6b6b;
  --color-accent: #48dbfb;
  --color-bg-dark: #0a0a1a;
  --color-bg-medium: #1a1a3a;
  --color-text-primary: #ffffff;
  --color-text-secondary: rgba(255, 255, 255, 0.8);
  --color-text-muted: rgba(255, 255, 255, 0.6);
  --glass-bg: rgba(255, 255, 255, 0.05);
  --glass-border: rgba(255, 255, 255, 0.1);
  --glass-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

/* 基础屏幕样式 */
.screen {
  width: 100vw;
  height: 100vh;
  background: linear-gradient(135deg, var(--color-bg-dark) 0%, var(--color-bg-medium) 50%, var(--color-bg-dark) 100%);
  color: var(--color-text-primary);
  font-family: 'PingFang SC', 'Microsoft YaHei', -apple-system, BlinkMacSystemFont, sans-serif;
  position: relative;
  overflow: hidden;
}

/* 粒子背景效果 */
.particles-container {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.particle {
  position: absolute;
  background: radial-gradient(circle, var(--color-primary) 0%, transparent 70%);
  border-radius: 50%;
  animation: float linear infinite;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0) translateX(0);
  }
  25% {
    transform: translateY(-20px) translateX(10px);
  }
  50% {
    transform: translateY(-10px) translateX(-10px);
  }
  75% {
    transform: translateY(-30px) translateX(5px);
  }
}

/* 统计信息 - 玻璃拟态 */
.stats {
  position: absolute;
  top: 30px;
  left: 40px;
  display: flex;
  gap: 20px;
  z-index: 10;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--glass-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--glass-border);
  border-radius: 16px;
  padding: 16px 24px;
  box-shadow: var(--glass-shadow);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
}

.stat-icon {
  font-size: 28px;
  line-height: 1;
}

.stat-icon.pulse {
  animation: pulse-icon 2s ease-in-out infinite;
}

@keyframes pulse-icon {
  0%, 100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.1);
    opacity: 0.8;
  }
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-primary);
  line-height: 1;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 12px;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 1px;
}

/* 主内容 */
.main {
  display: flex;
  height: 100vh;
  position: relative;
  z-index: 1;
}

.left-panel {
  width: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
}

.qrcode-box {
  position: relative;
  text-align: center;
  background: var(--glass-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 24px;
  padding: 40px;
  border: 1px solid var(--glass-border);
  box-shadow: var(--glass-shadow);
  overflow: hidden;
}

.qrcode-glow {
  position: absolute;
  top: -50%;
  left: -50%;
  width: 200%;
  height: 200%;
  background: radial-gradient(circle, rgba(254, 202, 87, 0.1) 0%, transparent 50%);
  animation: rotate 10s linear infinite;
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.qrcode-box h3 {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 24px;
  color: var(--color-primary);
  position: relative;
  z-index: 1;
}

.qrcode-canvas {
  border-radius: 12px;
  position: relative;
  z-index: 1;
}

.qrcode-tip {
  margin-top: 20px;
  font-size: 14px;
  color: var(--color-text-muted);
  position: relative;
  z-index: 1;
  letter-spacing: 0.5px;
}

.right-panel {
  flex: 1;
  position: relative;
}

/* 等待状态 */
.lottery-idle {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.idle-content {
  text-align: center;
}

.idle-title {
  font-size: 48px;
  font-weight: 700;
  color: var(--color-text-primary);
  margin-bottom: 16px;
  text-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
}

.idle-subtitle {
  font-size: 20px;
  color: var(--color-text-muted);
  margin-bottom: 60px;
}

.idle-decoration {
  position: relative;
  width: 200px;
  height: 200px;
  margin: 0 auto;
}

.decoration-ring {
  position: absolute;
  inset: 0;
  border: 2px solid rgba(254, 202, 87, 0.3);
  border-radius: 50%;
  animation: pulse-ring 3s ease-in-out infinite;
}

.decoration-ring.delay-1 {
  animation-delay: 1s;
  inset: 20px;
}

.decoration-ring.delay-2 {
  animation-delay: 2s;
  inset: 40px;
}

@keyframes pulse-ring {
  0%, 100% {
    transform: scale(1);
    opacity: 0.3;
  }
  50% {
    transform: scale(1.1);
    opacity: 0.6;
  }
}

/* 弹幕层 */
.danmaku-layer {
  position: fixed;
  inset: 0;
  z-index: 100;
  overflow: hidden;
  pointer-events: none;
}

.danmaku-item {
  position: absolute;
  left: 100%;
  white-space: nowrap;
  font-weight: 600;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.8), 0 0 20px rgba(0, 0, 0, 0.5);
  animation: danmaku-fly linear forwards;
  will-change: transform;
}

@keyframes danmaku-fly {
  from {
    transform: translateX(0);
  }
  to {
    transform: translateX(calc(-100vw - 100%));
  }
}

/* 抽奖滚动 */
.lottery-rolling {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.8);
  z-index: 150;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.rolling-container {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rolling-bg {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(254, 202, 87, 0.2) 0%, transparent 60%);
  animation: pulse-bg 2s ease-in-out infinite;
}

@keyframes pulse-bg {
  0%, 100% {
    transform: scale(1);
    opacity: 0.5;
  }
  50% {
    transform: scale(1.2);
    opacity: 0.8;
  }
}

.rolling-names {
  text-align: center;
  position: relative;
  z-index: 2;
}

.rolling-name {
  font-size: 120px;
  font-weight: 800;
  color: var(--color-primary);
  text-shadow: 0 0 40px rgba(254, 202, 87, 0.6), 0 0 80px rgba(254, 202, 87, 0.3);
  animation: pulse 0.1s ease-in-out infinite alternate;
}

@keyframes pulse {
  from {
    transform: scale(0.95);
    opacity: 0.9;
  }
  to {
    transform: scale(1.05);
    opacity: 1;
  }
}

.rolling-particles {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.rolling-particle {
  position: absolute;
  width: 8px;
  height: 8px;
  background: var(--color-primary);
  border-radius: 50%;
  animation: particle-fly 2s ease-out infinite;
}

@keyframes particle-fly {
  0% {
    transform: scale(0) translate(0, 0);
    opacity: 1;
  }
  100% {
    transform: scale(1) translate(var(--tx, 100px), var(--ty, -100px));
    opacity: 0;
  }
}

/* 中奖结果 */
.lottery-result {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.9);
  z-index: 200;
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  animation: fadeIn 0.5s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.result-container {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.result-glow {
  position: absolute;
  inset: 0;
  background: radial-gradient(circle at center, rgba(254, 202, 87, 0.3) 0%, transparent 60%);
  animation: result-glow-pulse 2s ease-in-out infinite;
}

@keyframes result-glow-pulse {
  0%, 100% {
    transform: scale(1);
    opacity: 0.5;
  }
  50% {
    transform: scale(1.1);
    opacity: 0.8;
  }
}

.result-card {
  text-align: center;
  position: relative;
  z-index: 2;
}

.result-header {
  margin-bottom: 50px;
  position: relative;
}

.trophy-icon {
  font-size: 80px;
  margin-bottom: 20px;
  animation: trophy-bounce 1s ease infinite;
}

@keyframes trophy-bounce {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-20px);
  }
}

.result-header h2 {
  font-size: 56px;
  font-weight: 800;
  color: var(--color-primary);
  margin-bottom: 20px;
  text-shadow: 0 0 30px rgba(254, 202, 87, 0.5);
}

.confetti {
  position: absolute;
  inset: -100px;
  pointer-events: none;
  overflow: hidden;
}

.confetti-piece {
  position: absolute;
  top: -10px;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  animation: confetti-fall linear infinite;
}

@keyframes confetti-fall {
  0% {
    transform: translateY(-100px) rotate(0deg);
    opacity: 1;
  }
  100% {
    transform: translateY(100vh) rotate(720deg);
    opacity: 0;
  }
}

.winners-grid {
  display: flex;
  gap: 30px;
  justify-content: center;
  flex-wrap: wrap;
  margin-bottom: 50px;
}

.winner-card {
  background: linear-gradient(135deg, rgba(254, 202, 87, 0.2), rgba(255, 107, 107, 0.2));
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 2px solid rgba(254, 202, 87, 0.5);
  border-radius: 20px;
  padding: 30px;
  min-width: 180px;
  position: relative;
  overflow: hidden;
  animation: popIn 0.5s cubic-bezier(0.68, -0.55, 0.265, 1.55) both;
}

@keyframes popIn {
  from {
    transform: scale(0) rotate(-10deg);
    opacity: 0;
  }
  to {
    transform: scale(1) rotate(0deg);
    opacity: 1;
  }
}

.winner-rank {
  position: absolute;
  top: 10px;
  right: 10px;
  width: 30px;
  height: 30px;
  background: var(--color-primary);
  color: var(--color-bg-dark);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
}

.winner-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--color-primary), var(--color-secondary));
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 36px;
  font-weight: 700;
  margin: 0 auto 20px;
  box-shadow: 0 8px 30px rgba(254, 202, 87, 0.4);
}

.winner-name {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
}

.winner-info {
  font-size: 16px;
  color: var(--color-text-muted);
}

.btn-continue {
  position: relative;
  padding: 16px 50px;
  font-size: 18px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--color-primary), var(--color-secondary));
  color: var(--color-bg-dark);
  border: none;
  border-radius: 12px;
  cursor: pointer;
  overflow: hidden;
  transition: transform 0.2s, box-shadow 0.2s;
}

.btn-continue:hover {
  transform: scale(1.05);
  box-shadow: 0 10px 40px rgba(254, 202, 87, 0.4);
}

.btn-shine {
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.3), transparent);
  animation: shine 3s infinite;
}

@keyframes shine {
  0% {
    left: -100%;
  }
  100% {
    left: 100%;
  }
}

/* 视频背景 */
.bg-video-wrapper {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
}

.bg-video {
  position: absolute;
  top: 50%;
  left: 50%;
  min-width: 100%;
  min-height: 100%;
  transform: translate(-50%, -50%);
  object-fit: cover;
}

/* 响应式调整 */
@media (max-width: 1200px) {
  .left-panel {
    width: 280px;
    padding: 20px;
  }

  .qrcode-box {
    padding: 30px;
  }

  .stat-card {
    padding: 12px 16px;
  }

  .stat-value {
    font-size: 20px;
  }

  .idle-title {
    font-size: 36px;
  }

  .rolling-name {
    font-size: 80px;
  }

  .result-header h2 {
    font-size: 42px;
  }

  .winner-card {
    min-width: 150px;
    padding: 20px;
  }

  .winner-avatar {
    width: 60px;
    height: 60px;
    font-size: 28px;
  }

  .winner-name {
    font-size: 22px;
  }
}
</style>
