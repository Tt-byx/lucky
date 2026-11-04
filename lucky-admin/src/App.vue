<template>
  <div class="admin-app">
    <!-- 侧边栏 -->
    <div class="sidebar">
      <div class="sidebar-header">
        <h2>抽奖后台</h2>
      </div>
      <nav class="sidebar-nav">
        <a :class="{ active: currentTab === 'activity' }" @click="currentTab = 'activity'">活动管理</a>
        <a :class="{ active: currentTab === 'history' }" @click="currentTab = 'history'">历史活动</a>
        <a :class="{ active: currentTab === 'sensitive' }" @click="currentTab = 'sensitive'">敏感词</a>
        <a :class="{ active: currentTab === 'screen' }" @click="currentTab = 'screen'">屏幕设置</a>
        <a :class="{ active: currentTab === 'users' }" @click="currentTab = 'users'">用户管理</a>
      </nav>
    </div>

    <!-- 主内容区 -->
    <div class="main-content">
      <!-- 活动管理 -->
      <div v-if="currentTab === 'activity'" class="panel">
        <h3>活动管理</h3>

        <div class="card">
          <h4>创建新活动</h4>
          <div class="form-row">
            <input v-model="newActivityName" type="text" placeholder="活动名称" />
            <button @click="createActivity">创建</button>
          </div>
        </div>

        <div class="card" v-if="currentActivity">
          <h4>当前活动</h4>
          <div class="info-row">
            <span>名称: {{ currentActivity.name }}</span>
            <span>状态: {{ statusText(currentActivity.status) }}</span>
          </div>
          <div class="btn-group">
            <button @click="updateActivityStatus(1)" v-if="currentActivity.status === 0">开始活动</button>
            <button @click="updateActivityStatus(2)" v-if="currentActivity.status === 1">结束活动</button>
          </div>
          <div class="info-row" style="margin-top: 10px;">
            <span>参与人数: {{ participantCount }}</span>
          </div>
        </div>

        <!-- 活动进行中：奖品配置 -->
        <div v-if="currentActivity && currentActivity.status === 1" class="card">
          <h4>奖品配置</h4>
          <div class="form-row">
            <input v-model="newPrizeName" type="text" placeholder="奖品名称（如：无线耳机）" />
            <input v-model.number="newPrizeStock" type="number" min="1" placeholder="数量" style="width: 90px;" />
            <button @click="createPrize">添加奖品</button>
          </div>
          <div v-if="prizes.length > 0" class="prize-list">
            <span v-for="p in prizes" :key="p.id" class="prize-chip">
              {{ p.name }} · 剩余 {{ p.stock }} / {{ p.totalStock }}
            </span>
          </div>
          <p v-else class="prize-empty">还没有奖品，先添加奖品才能创建轮次</p>
        </div>

        <!-- 活动进行中：快速抽奖 -->
        <div v-if="currentActivity && currentActivity.status === 1" class="card">
          <h4>限时抢奖</h4>
          <div class="form-row">
            <select v-model.number="newPrizeId" style="padding: 8px; border: 1px solid #ddd; border-radius: 4px;">
              <option :value="null" disabled>选择奖品</option>
              <option v-for="p in prizes" :key="p.id" :value="p.id">
                {{ p.name }}（剩余 {{ p.stock }}）
              </option>
            </select>
            <input v-model="newRoundName" type="text" placeholder="奖项名称（如：一等奖）" />
            <input v-model.number="newWinnerCount" type="number" placeholder="名额" min="1" style="width: 80px;" />
            <button @click="createRound">创建轮次</button>
          </div>

          <div v-if="rounds.length > 0" style="margin-top: 16px;">
            <h5 style="margin-bottom: 10px; color: #666;">抢奖轮次</h5>
            <div v-for="round in rounds" :key="round.id" class="round-item">
              <div class="round-info">
                <strong>{{ round.roundName }}</strong>
                <span>{{ prizeName(round.prizeId) }}</span>
                <span>名额 {{ round.winnerCount }}</span>
                <span :class="round.status === 2 ? 'done' : 'pending'">
                  {{ roundStatusText(round.status) }}
                </span>
                <span v-if="round.status === 1" style="color: #e6534a;">
                  剩余 {{ remainingMap[round.id] ?? '-' }}
                </span>
              </div>
              <button
                v-if="round.status === 0"
                @click="startRound(round)"
                class="btn-draw"
              >
                开始抢奖
              </button>
              <button
                v-if="round.status === 1"
                @click="endRound(round)"
                class="btn-draw"
                style="background: #888;"
              >
                结束本轮
              </button>
            </div>
          </div>

          <div v-if="winnerRecords.length > 0" style="margin-top: 16px;">
            <h5 style="margin-bottom: 10px; color: #666;">中奖记录</h5>
            <div v-for="record in winnerRecords" :key="record.id" class="winner-record">
              <span>{{ record.participantName }}</span>
              <span class="winner-student-id">{{ record.studentId }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 历史活动 -->
      <div v-if="currentTab === 'history'" class="panel">
        <h3>历史活动</h3>
        <div class="card">
          <div v-if="historyActivities.length === 0" class="empty">暂无历史活动</div>
          <table v-else class="history-table">
            <thead>
              <tr>
                <th>活动名</th>
                <th>创建时间</th>
                <th>参与人数</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="act in historyActivities" :key="act.id">
                <td>{{ act.name }}</td>
                <td>{{ formatTime(act.createdAt) }}</td>
                <td>
                  <span class="link-text" @click="showParticipants(act)">{{ act.participantCount || 0 }}</span>
                </td>
                <td class="action-cell">
                  <button class="btn-sm btn-info" @click="showHistoryDetail(act)">详情</button>
                  <button class="btn-sm btn-danger" @click="deleteActivity(act)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 活动详情弹窗 -->
        <div v-if="showDetailModal" class="modal-overlay" @click.self="showDetailModal = false">
          <div class="modal-content">
            <div class="modal-header">
              <h4>{{ detailActivity?.name }} - 活动详情</h4>
              <button class="btn-close" @click="showDetailModal = false">&times;</button>
            </div>
            <div class="modal-body">
              <div class="detail-stats">
                <div class="stat-item">
                  <span class="stat-label">参与人数</span>
                  <span class="stat-value">{{ detailActivity?.participantCount || 0 }}</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">创建时间</span>
                  <span class="stat-value">{{ formatTime(detailActivity?.createdAt) }}</span>
                </div>
              </div>

              <h5 class="section-title">抽奖轮次</h5>
              <div v-if="detailRounds.length === 0" class="empty">该活动没有抽奖记录</div>
              <div v-for="round in detailRounds" :key="round.id" class="round-detail">
                <div class="round-header">
                  <strong>{{ round.roundName }}</strong>
                  <span class="round-count">抽取 {{ round.winnerCount }} 人</span>
                </div>
                <div v-if="detailWinners[round.id]?.length" class="winner-list">
                  <span v-for="w in detailWinners[round.id]" :key="w.id" class="winner-tag">
                    {{ w.name || w.participantName || '未知' }}
                  </span>
                </div>
                <div v-else class="empty" style="padding: 8px 0;">暂无中奖者</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 参与者详情弹窗 -->
        <div v-if="showParticipantModal" class="modal-overlay" @click.self="showParticipantModal = false">
          <div class="modal-content modal-large">
            <div class="modal-header">
              <h4>{{ participantActivity?.name }} - 参与者列表</h4>
              <button class="btn-close" @click="showParticipantModal = false">&times;</button>
            </div>
            <div class="modal-body">
              <div v-if="participants.length === 0" class="empty">暂无参与者</div>
              <table v-else class="participant-table">
                <thead>
                  <tr>
                    <th>姓名</th>
                    <th>学号</th>
                    <th>参与时间</th>
                    <th>弹幕数</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="p in participants" :key="p.id">
                    <td>{{ p.name }}</td>
                    <td>{{ p.studentId }}</td>
                    <td>{{ formatTime(p.createdAt) }}</td>
                    <td>{{ p.danmakuCount || 0 }}</td>
                    <td>
                      <button class="btn-sm btn-info" @click="showDanmakuDetail(p)">查看弹幕</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- 弹幕详情弹窗 -->
        <div v-if="showDanmakuModal" class="modal-overlay" @click.self="showDanmakuModal = false">
          <div class="modal-content">
            <div class="modal-header">
              <h4>{{ danmakuParticipant?.name }} - 弹幕记录</h4>
              <button class="btn-close" @click="showDanmakuModal = false">&times;</button>
            </div>
            <div class="modal-body">
              <div v-if="participantDanmakus.length === 0" class="empty">该用户没有发送过弹幕</div>
              <div v-else class="danmaku-list">
                <div v-for="d in participantDanmakus" :key="d.id" class="danmaku-item">
                  <span class="danmaku-time">{{ formatTime(d.createdAt) }}</span>
                  <span class="danmaku-text">{{ d.content }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 敏感词管理 -->
      <div v-if="currentTab === 'sensitive'" class="panel">
        <h3>敏感词管理</h3>

        <div class="card">
          <h4>添加敏感词</h4>
          <div class="form-row">
            <input v-model="newSensitiveWord" type="text" placeholder="输入敏感词" />
            <button @click="addSensitiveWord">添加</button>
          </div>
        </div>

        <div class="card">
          <h4>敏感词列表 ({{ sensitiveWords.length }})</h4>
          <div v-if="sensitiveWords.length === 0" class="empty">暂无敏感词</div>
          <div v-for="w in sensitiveWords" :key="w.id" class="sensitive-word-item">
            <span>{{ w.word }}</span>
            <button class="btn-delete" @click="deleteSensitiveWord(w.id)">删除</button>
          </div>
        </div>
      </div>

      <!-- 屏幕设置 -->
      <div v-if="currentTab === 'screen'" class="panel">
        <h3>屏幕设置</h3>

        <div class="card">
          <h4>当前背景</h4>
          <div v-if="screenConfig.backgroundType" class="current-bg-info">
            <p>类型: {{ screenConfig.backgroundType === 'image' ? '图片' : '视频' }}</p>
            <div class="bg-preview">
              <img v-if="screenConfig.backgroundType === 'image'" :src="screenConfig.fullUrl" alt="背景预览" class="bg-preview-img" />
              <video v-else :src="screenConfig.fullUrl" muted loop autoplay class="bg-preview-video"></video>
            </div>
          </div>
          <p v-else class="empty">当前使用默认背景</p>
        </div>

        <div class="card">
          <h4>自定义背景</h4>
          <div class="form-row">
            <select v-model="bgType" class="bg-type-select">
              <option value="image">图片</option>
              <option value="video">视频</option>
            </select>
          </div>
          <div class="form-row" style="margin-top: 10px;">
            <input type="file" ref="fileInput" @change="onFileSelected" accept="image/*,video/*" />
            <button @click="uploadBackground" :disabled="!selectedFile || uploading">
              {{ uploading ? '上传中...' : '上传背景' }}
            </button>
          </div>
          <p class="upload-hint">支持 JPG/PNG/GIF/WebP 图片，MP4/WebM/OGG 视频</p>
        </div>

        <div class="card" v-if="screenConfig.backgroundType">
          <h4>恢复默认</h4>
          <button class="btn-danger" @click="clearBackground">清除自定义背景</button>
        </div>

        <!-- 手机端背景 -->
        <div class="card">
          <h4>手机端背景</h4>
          <div v-if="screenConfig.mobileBackgroundType" class="current-bg-info">
            <p>类型: {{ screenConfig.mobileBackgroundType === 'image' ? '图片' : '视频' }}</p>
            <div class="bg-preview">
              <img v-if="screenConfig.mobileBackgroundType === 'image'" :src="screenConfig.mobileBackgroundUrl" alt="手机端背景预览" class="bg-preview-img" />
              <video v-else :src="screenConfig.mobileBackgroundUrl" muted loop autoplay class="bg-preview-video"></video>
            </div>
          </div>
          <p v-else class="empty">当前使用默认背景</p>
          <div class="form-row" style="margin-top: 10px;">
            <select v-model="mobileBgType" class="bg-type-select">
              <option value="image">图片</option>
              <option value="video">视频</option>
            </select>
          </div>
          <div class="form-row" style="margin-top: 10px;">
            <input type="file" ref="mobileFileInput" @change="onMobileFileSelected" accept="image/*,video/*" />
            <button v-if="mobileBgType === 'video'" @click="uploadMobileBackground" :disabled="!mobileSelectedFile || mobileUploading">
              {{ mobileUploading ? '上传中...' : '上传手机端背景' }}
            </button>
            <span v-else class="upload-hint" style="margin: 0;">选择图片后将自动弹出裁剪窗口</span>
          </div>
          <p class="upload-hint">支持 JPG/PNG/GIF/WebP 图片，MP4/WebM/OGG 视频</p>
          <div v-if="screenConfig.mobileBackgroundType" style="margin-top: 10px;">
            <button class="btn-danger" @click="clearMobileBackground">清除手机端背景</button>
          </div>
        </div>

        <!-- 图片裁剪模态框 -->
        <div v-if="showMobileCropper" class="cropper-modal">
          <div class="cropper-modal-content">
            <h4>裁剪手机端背景（比例 9:20）</h4>
            <p class="cropper-hint">拖动裁剪框选择显示区域，手机端将显示裁剪后的图片</p>
            <div class="cropper-container">
              <img ref="mobileCropperImage" :src="mobileCropImageUrl" />
            </div>
            <div class="cropper-actions">
              <button class="btn-cancel" @click="cancelCrop">取消</button>
              <button class="btn-confirm" @click="confirmCrop">确认裁剪并上传</button>
            </div>
          </div>
        </div>

        <!-- 弹幕设置 -->
        <div class="card">
          <h4>弹幕设置</h4>
          <div class="setting-row">
            <label>显示区域</label>
            <select v-model="danmakuSettings.area">
              <option value="full">全屏</option>
              <option value="top">上半屏</option>
              <option value="bottom">下半屏</option>
            </select>
          </div>
          <div class="setting-row">
            <label>不透明度</label>
            <input type="range" v-model.number="danmakuSettings.opacity" min="20" max="100" step="5" />
            <span>{{ danmakuSettings.opacity }}%</span>
          </div>
          <div class="setting-row">
            <label>字号大小</label>
            <input type="range" v-model.number="danmakuSettings.fontSize" min="16" max="48" step="2" />
            <span>{{ danmakuSettings.fontSize }}px</span>
          </div>
          <div class="setting-row">
            <label>滚动速度</label>
            <input type="range" v-model.number="danmakuSettings.speed" min="4" max="16" step="1" />
            <span>{{ danmakuSettings.speed }}s</span>
          </div>
          <button @click="saveDanmakuSettings" class="btn-primary">保存设置</button>
        </div>
      </div>

      <!-- 用户管理 -->
      <div v-if="currentTab === 'users'" class="panel">
        <h3>用户管理</h3>

        <!-- 在线用户 -->
        <div class="card">
          <h4>在线用户 ({{ onlineUsers.length }})</h4>
          <div v-if="onlineUsers.length === 0" class="empty">暂无在线用户</div>
          <table v-else class="user-table">
            <thead>
              <tr>
                <th>姓名</th>
                <th>学号</th>
                <th>手机号</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in onlineUsers" :key="u.id">
                <td>{{ u.name }}</td>
                <td>{{ u.studentId }}</td>
                <td>{{ u.phone || '-' }}</td>
                <td>
                  <span class="tag tag-online">在线</span>
                  <span v-if="u.isMuted" class="tag tag-muted">已禁言</span>
                </td>
                <td class="action-cell">
                  <button class="btn-sm btn-warn" @click="toggleMute(u)">
                    {{ u.isMuted ? '取消禁言' : '禁言' }}
                  </button>
                  <button class="btn-sm btn-danger" @click="toggleBan(u)">
                    {{ u.isBanned ? '恢复' : '移出直播间' }}
                  </button>
                  <button class="btn-sm btn-orange" @click="toggleLottery(u)">
                    {{ u.status === 3 ? '恢复抽奖' : '移除抽奖' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 所有用户 -->
        <div class="card">
          <h4>所有参与者 ({{ allUsers.length }})</h4>
          <div class="form-row" style="margin-bottom: 12px;">
            <input v-model="userSearch" type="text" placeholder="搜索姓名或学号" />
            <button @click="fetchOnlineUsers" class="btn-refresh">刷新在线</button>
          </div>
          <div v-if="filteredUsers.length === 0" class="empty">暂无参与者</div>
          <table v-else class="user-table">
            <thead>
              <tr>
                <th>姓名</th>
                <th>学号</th>
                <th>手机号</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in filteredUsers" :key="u.id"
                  :class="{ 'row-online': isOnline(u.id), 'row-banned': u.isBanned }">
                <td>{{ u.name }}</td>
                <td>{{ u.studentId }}</td>
                <td>{{ u.phone || '-' }}</td>
                <td>
                  <span v-if="isOnline(u.id)" class="tag tag-online">在线</span>
                  <span v-if="u.isMuted" class="tag tag-muted">禁言</span>
                  <span v-if="u.isBanned" class="tag tag-banned">已移出</span>
                  <span v-if="u.status === 3" class="tag tag-removed">无抽奖资格</span>
                  <span v-if="u.status === 0" class="tag tag-winner">已中奖</span>
                </td>
                <td class="action-cell">
                  <button class="btn-sm btn-warn" @click="toggleMute(u)">
                    {{ u.isMuted ? '取消禁言' : '禁言' }}
                  </button>
                  <button class="btn-sm btn-danger" @click="toggleBan(u)">
                    {{ u.isBanned ? '恢复' : '移出直播间' }}
                  </button>
                  <button class="btn-sm btn-orange" @click="toggleLottery(u)">
                    {{ u.status === 3 ? '恢复抽奖' : '移除抽奖' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'

// 当前 tab
const currentTab = ref(localStorage.getItem('admin_currentTab') || 'activity')

// 活动相关
const newActivityName = ref('')
const currentActivity = ref(null)
const participantCount = ref(0)

// 抽奖相关
const newRoundName = ref('')
const newWinnerCount = ref(1)
const newPrizeId = ref(null)
const newPrizeName = ref('')
const newPrizeStock = ref(1)
const prizes = ref([])
const rounds = ref([])
const winnerRecords = ref([])
const remainingMap = ref({})

// 敏感词相关
const newSensitiveWord = ref('')
const sensitiveWords = ref([])

// 历史活动相关
const historyActivities = ref([])
const historySelectedId = ref(null)
const historyRounds = ref([])
const historyWinners = ref({})

// 详情弹窗相关
const showDetailModal = ref(false)
const detailActivity = ref(null)
const detailRounds = ref([])
const detailWinners = ref({})

// 参与者详情弹窗相关
const showParticipantModal = ref(false)
const participantActivity = ref(null)
const participants = ref([])

// 弹幕详情弹窗相关
const showDanmakuModal = ref(false)
const danmakuParticipant = ref(null)
const participantDanmakus = ref([])

// 屏幕设置相关
const screenConfig = ref({ backgroundType: null, backgroundUrl: null })
const bgType = ref('image')
const selectedFile = ref(null)
const uploading = ref(false)
const fileInput = ref(null)

// 手机端背景
const mobileBgType = ref('image')
const mobileSelectedFile = ref(null)
const mobileUploading = ref(false)
const mobileFileInput = ref(null)

// 图片裁剪相关
const showMobileCropper = ref(false)
const mobileCropImageUrl = ref('')
const mobileCropper = ref(null)
const mobileCropperImage = ref(null)

// 弹幕设置
const danmakuSettings = ref({
  area: 'full',
  opacity: 80,
  fontSize: 28,
  speed: 10
})

// 用户管理相关
const allUsers = ref([])
const onlineUsers = ref([])
const onlineUserIds = ref(new Set())
const userSearch = ref('')

// 获取当前活动
async function fetchCurrentActivity() {
  try {
    const res = await fetch(`${API_BASE}/api/activity/current`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      currentActivity.value = data.data
      fetchParticipantCount()
      fetchRounds()
    }
  } catch (e) {
    console.error('获取活动失败:', e)
  }
}

// 创建活动
async function createActivity() {
  if (!newActivityName.value.trim()) {
    alert('请输入活动名称')
    return
  }
  try {
    const res = await fetch(`${API_BASE}/api/activity?name=${encodeURIComponent(newActivityName.value)}`, {
      method: 'POST'
    })
    const data = await res.json()
    if (data.code === 200) {
      currentActivity.value = data.data
      newActivityName.value = ''
      alert('活动创建成功')
      fetchParticipantCount()
      fetchRounds()
    } else {
      alert(data.message || '创建失败')
    }
  } catch (e) {
    console.error('创建活动失败:', e)
    alert('创建失败：' + e.message)
  }
}

// 更新活动状态
async function updateActivityStatus(status) {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/activity/${currentActivity.value.id}/status?status=${status}`, {
      method: 'PUT'
    })
    const data = await res.json()
    if (data.code === 200) {
      currentActivity.value.status = status
    } else {
      alert(data.message || '更新失败')
    }
  } catch (e) {
    console.error('更新活动状态失败:', e)
    alert('更新失败：' + e.message)
  }
}

// 获取参与人数
async function fetchParticipantCount() {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/participant/count?activityId=${currentActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      participantCount.value = data.data
    }
  } catch (e) {
    console.error(e)
  }
}

// 创建抽奖轮次（必须绑定奖品）
async function createRound() {
  if (!newRoundName.value || !currentActivity.value) {
    alert('请输入轮次名称')
    return
  }
  if (!newPrizeId.value) {
    alert('请先选择本轮绑定的奖品')
    return
  }
  try {
    const res = await fetch(`${API_BASE}/api/lottery/round`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        activityId: currentActivity.value.id,
        prizeId: newPrizeId.value,
        roundName: newRoundName.value,
        winnerCount: newWinnerCount.value
      })
    })
    const data = await res.json()
    if (data.code === 200) {
      rounds.value.push(data.data)
      newRoundName.value = ''
      newWinnerCount.value = 1
      fetchPrizes()
    } else {
      alert(data.message || '创建失败')
    }
  } catch (e) {
    console.error('创建轮次失败:', e)
    alert('创建失败：' + e.message)
  }
}

// 添加奖品
async function createPrize() {
  if (!currentActivity.value) return
  if (!newPrizeName.value.trim()) {
    alert('请输入奖品名称')
    return
  }
  if (!newPrizeStock.value || newPrizeStock.value < 1) {
    alert('数量至少为 1')
    return
  }

  try {
    const res = await fetch(`${API_BASE}/api/prize`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        activityId: currentActivity.value.id,
        name: newPrizeName.value.trim(),
        stock: newPrizeStock.value,
        totalStock: newPrizeStock.value
      })
    })
    const data = await res.json()
    if (data.code === 200) {
      newPrizeName.value = ''
      newPrizeStock.value = 1
      fetchPrizes()
    } else {
      alert(data.message || '添加失败')
    }
  } catch (e) {
    alert('添加失败：' + e.message)
  }
}

// 奖品列表
async function fetchPrizes() {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/prize/list?activityId=${currentActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      prizes.value = data.data || []
    }
  } catch (e) {
    console.error('获取奖品失败:', e)
  }
}

function prizeName(prizeId) {
  const p = prizes.value.find(item => item.id === prizeId)
  return p ? p.name : '未绑定奖品'
}

function roundStatusText(status) {
  const map = { 0: '待开始', 1: '抢奖中', 2: '已结束', 3: '已取消' }
  return map[status] || '未知'
}

// 开始抢奖（管理端只负责开轮，中奖由用户抢）
async function startRound(round) {
  if (!confirm(`确定要开始"${round.roundName}"的抢奖吗？开始后用户可立即点击抢奖。`)) return

  try {
    const res = await fetch(`${API_BASE}/api/lottery/round/${round.id}/start`, {
      method: 'POST'
    })
    const data = await res.json()
    if (data.code === 200) {
      round.status = 1
      fetchRemaining(round.id)
      fetchRounds()
    } else {
      alert(data.message || '开始失败')
    }
  } catch (e) {
    alert('开始失败')
  }
}

// 结束本轮
async function endRound(round) {
  if (!confirm(`确定要结束"${round.roundName}"吗？结束后用户将无法继续抢奖。`)) return

  try {
    const res = await fetch(`${API_BASE}/api/lottery/round/${round.id}/end`, {
      method: 'POST'
    })
    const data = await res.json()
    if (data.code === 200) {
      round.status = 2
      fetchRounds()
    } else {
      alert(data.message || '结束失败')
    }
  } catch (e) {
    alert('结束失败')
  }
}

// 查询剩余名额
async function fetchRemaining(roundId) {
  try {
    const res = await fetch(`${API_BASE}/api/lottery/remaining?roundId=${roundId}`)
    const data = await res.json()
    if (data.code === 200) {
      remainingMap.value = { ...remainingMap.value, [roundId]: data.data }
    }
  } catch (e) {
    console.error(e)
  }
}

// 轮询进行中轮次的剩余名额
let remainingTimer = null
function startRemainingPolling() {
  if (remainingTimer) return
  remainingTimer = setInterval(() => {
    const active = rounds.value.filter(r => r.status === 1)
    active.forEach(r => fetchRemaining(r.id))
  }, 2000)
}

// 获取轮次列表
async function fetchRounds() {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/lottery/rounds?activityId=${currentActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      rounds.value = data.data
      startRemainingPolling()
      rounds.value.filter(r => r.status === 1).forEach(r => fetchRemaining(r.id))
    }
  } catch (e) {
    console.error(e)
  }
}

// 获取敏感词列表
async function fetchSensitiveWords() {
  try {
    const res = await fetch(`${API_BASE}/api/sensitive-word/list`)
    const data = await res.json()
    if (data.code === 200) {
      sensitiveWords.value = data.data
    }
  } catch (e) {
    console.error(e)
  }
}

// 添加敏感词
async function addSensitiveWord() {
  if (!newSensitiveWord.value.trim()) {
    alert('请输入敏感词')
    return
  }
  try {
    const res = await fetch(`${API_BASE}/api/sensitive-word?word=${encodeURIComponent(newSensitiveWord.value)}`, {
      method: 'POST'
    })
    const data = await res.json()
    if (data.code === 200) {
      newSensitiveWord.value = ''
      fetchSensitiveWords()
    } else {
      alert(data.message || '添加失败')
    }
  } catch (e) {
    console.error('添加敏感词失败:', e)
    alert('添加失败：' + e.message)
  }
}

// 删除敏感词
async function deleteSensitiveWord(id) {
  try {
    const res = await fetch(`${API_BASE}/api/sensitive-word/${id}`, {
      method: 'DELETE'
    })
    const data = await res.json()
    if (data.code === 200) {
      sensitiveWords.value = sensitiveWords.value.filter(w => w.id !== id)
    } else {
      alert(data.message || '删除失败')
    }
  } catch (e) {
    console.error('删除敏感词失败:', e)
    alert('删除失败：' + e.message)
  }
}

// 获取历史活动列表
async function fetchHistoryActivities() {
  try {
    const res = await fetch(`${API_BASE}/api/activity/history`)
    const data = await res.json()
    if (data.code === 200) {
      historyActivities.value = data.data
      for (const act of data.data) {
        const countRes = await fetch(`${API_BASE}/api/participant/count?activityId=${act.id}`)
        const countData = await countRes.json()
        if (countData.code === 200) {
          act.participantCount = countData.data
        }
      }
    }
  } catch (e) {
    console.error('获取历史活动失败:', e)
  }
}

// 删除活动
async function deleteActivity(activity) {
  if (!confirm(`确定要删除活动"${activity.name}"吗？删除后将无法恢复。`)) return
  try {
    const res = await fetch(`${API_BASE}/api/activity/${activity.id}`, {
      method: 'DELETE'
    })
    const data = await res.json()
    if (data.code === 200) {
      historyActivities.value = historyActivities.value.filter(a => a.id !== activity.id)
      alert('删除成功')
    } else {
      alert(data.message || '删除失败')
    }
  } catch (e) {
    console.error('删除活动失败:', e)
    alert('删除失败：' + e.message)
  }
}

// 显示活动详情
async function showHistoryDetail(activity) {
  detailActivity.value = activity
  showDetailModal.value = true
  detailRounds.value = []
  detailWinners.value = {}
  try {
    const res = await fetch(`${API_BASE}/api/lottery/rounds?activityId=${activity.id}`)
    const data = await res.json()
    if (data.code === 200) {
      detailRounds.value = data.data
      for (const round of data.data) {
        const winRes = await fetch(`${API_BASE}/api/lottery/winners?roundId=${round.id}`)
        const winData = await winRes.json()
        if (winData.code === 200) {
          const winners = winData.data
          for (const winner of winners) {
            if (winner.participantId && !winner.name) {
              const participantRes = await fetch(`${API_BASE}/api/participant/list?activityId=${activity.id}`)
              const participantData = await participantRes.json()
              if (participantData.code === 200) {
                const participant = participantData.data.find(p => p.id === winner.participantId)
                if (participant) {
                  winner.name = participant.name
                }
              }
              break
            }
          }
          detailWinners.value[round.id] = winners
        }
      }
    }
  } catch (e) {
    console.error('加载活动详情失败:', e)
  }
}

// 显示参与者列表
async function showParticipants(activity) {
  participantActivity.value = activity
  showParticipantModal.value = true
  participants.value = []
  try {
    const res = await fetch(`${API_BASE}/api/participant/list?activityId=${activity.id}`)
    const data = await res.json()
    if (data.code === 200) {
      participants.value = data.data
      for (const p of participants.value) {
        const danmakuRes = await fetch(`${API_BASE}/api/danmaku/approved?activityId=${activity.id}`)
        const danmakuData = await danmakuRes.json()
        if (danmakuData.code === 200) {
          p.danmakuCount = danmakuData.data.filter(d => d.participantId === p.id).length
        }
      }
    }
  } catch (e) {
    console.error('加载参与者列表失败:', e)
  }
}

// 显示弹幕详情
async function showDanmakuDetail(participant) {
  danmakuParticipant.value = participant
  showDanmakuModal.value = true
  participantDanmakus.value = []
  try {
    const res = await fetch(`${API_BASE}/api/danmaku/approved?activityId=${participantActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      participantDanmakus.value = data.data.filter(d => d.participantId === participant.id)
    }
  } catch (e) {
    console.error('加载弹幕详情失败:', e)
  }
}

// 格式化时间
function formatTime(time) {
  if (!time) return '-'
  const date = new Date(time)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
}

// 获取屏幕配置
async function fetchScreenConfig() {
  try {
    const res = await fetch(`${API_BASE}/api/screen/config`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      screenConfig.value = {
        ...data.data,
        fullUrl: data.data.backgroundUrl || ''
      }
    }
  } catch (e) {
    console.error('获取屏幕配置失败:', e)
  }
}

// 文件选择
function onFileSelected(e) {
  selectedFile.value = e.target.files[0]
}

// 上传背景
async function uploadBackground() {
  if (!selectedFile.value) {
    alert('请选择文件')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('type', bgType.value)
    formData.append('file', selectedFile.value)
    const res = await fetch(`${API_BASE}/api/screen/background`, {
      method: 'POST',
      body: formData
    })
    const data = await res.json()
    if (data.code === 200) {
      screenConfig.value = {
        ...data.data,
        fullUrl: data.data.backgroundUrl || ''
      }
      selectedFile.value = null
      if (fileInput.value) fileInput.value.value = ''
      alert('背景上传成功')
    } else {
      alert(data.message || '上传失败')
    }
  } catch (e) {
    console.error('上传背景失败:', e)
    alert('上传失败：' + e.message)
  } finally {
    uploading.value = false
  }
}

// 清除背景
async function clearBackground() {
  if (!confirm('确定要清除自定义背景，恢复默认吗？')) return
  try {
    const res = await fetch(`${API_BASE}/api/screen/background`, {
      method: 'DELETE'
    })
    const data = await res.json()
    if (data.code === 200) {
      screenConfig.value = { ...screenConfig.value, backgroundType: null, backgroundUrl: null }
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    console.error('清除背景失败:', e)
    alert('操作失败：' + e.message)
  }
}

// 手机端背景 - 文件选择
function onMobileFileSelected(e) {
  const file = e.target.files[0]
  if (!file) return

  // 如果是图片类型，显示裁剪器
  if (mobileBgType.value === 'image') {
    const reader = new FileReader()
    reader.onload = (event) => {
      mobileCropImageUrl.value = event.target.result
      showMobileCropper.value = true
      // 等待 DOM 更新后初始化 Cropper
      setTimeout(() => {
        initMobileCropper()
      }, 100)
    }
    reader.readAsDataURL(file)
  } else {
    // 视频类型直接设置文件
    mobileSelectedFile.value = file
  }
}

// 初始化裁剪器
function initMobileCropper() {
  if (mobileCropper.value) {
    mobileCropper.value.destroy()
  }
  if (mobileCropperImage.value) {
    mobileCropper.value = new Cropper(mobileCropperImage.value, {
      aspectRatio: 9 / 20,  // 手机屏幕比例
      viewMode: 1,
      dragMode: 'move',
      autoCropArea: 1,
      responsive: true,
      ready() {
        console.log('裁剪器初始化完成')
      }
    })
  }
}

// 确认裁剪
async function confirmCrop() {
  if (!mobileCropper.value) return

  const canvas = mobileCropper.value.getCroppedCanvas({
    width: 1080,
    height: 2400
  })

  canvas.toBlob(async (blob) => {
    mobileSelectedFile.value = new File([blob], 'mobile-bg.png', { type: 'image/png' })
    showMobileCropper.value = false
    mobileCropper.value.destroy()
    mobileCropper.value = null

    // 自动上传
    await uploadMobileBackground()
  }, 'image/png')
}

// 取消裁剪
function cancelCrop() {
  showMobileCropper.value = false
  if (mobileCropper.value) {
    mobileCropper.value.destroy()
    mobileCropper.value = null
  }
  mobileSelectedFile.value = null
  if (mobileFileInput.value) mobileFileInput.value.value = ''
}

// 手机端背景 - 上传
async function uploadMobileBackground() {
  if (!mobileSelectedFile.value) {
    alert('请选择文件')
    return
  }
  mobileUploading.value = true
  try {
    const formData = new FormData()
    formData.append('type', mobileBgType.value)
    formData.append('file', mobileSelectedFile.value)
    const res = await fetch(`${API_BASE}/api/screen/mobile-background`, {
      method: 'POST',
      body: formData
    })
    const data = await res.json()
    if (data.code === 200) {
      screenConfig.value = {
        ...screenConfig.value,
        ...data.data
      }
      mobileSelectedFile.value = null
      if (mobileFileInput.value) mobileFileInput.value.value = ''
      alert('手机端背景上传成功')
    } else {
      alert(data.message || '上传失败')
    }
  } catch (e) {
    console.error('上传手机端背景失败:', e)
    alert('上传失败：' + e.message)
  } finally {
    mobileUploading.value = false
  }
}

// 手机端背景 - 清除
async function clearMobileBackground() {
  if (!confirm('确定要清除手机端自定义背景吗？')) return
  try {
    const res = await fetch(`${API_BASE}/api/screen/mobile-background`, {
      method: 'DELETE'
    })
    const data = await res.json()
    if (data.code === 200) {
      screenConfig.value = {
        ...screenConfig.value,
        mobileBackgroundType: null,
        mobileBackgroundUrl: null
      }
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    console.error('清除手机端背景失败:', e)
    alert('操作失败：' + e.message)
  }
}

// 保存弹幕设置
async function saveDanmakuSettings() {
  try {
    const res = await fetch(`${API_BASE}/api/screen/danmaku-settings`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(danmakuSettings.value)
    })
    const data = await res.json()
    if (data.code === 200) {
      alert('弹幕设置保存成功')
    } else {
      alert(data.message || '保存失败')
    }
  } catch (e) {
    console.error('保存弹幕设置失败:', e)
    alert('保存失败：' + e.message)
  }
}

// 获取弹幕设置
async function fetchDanmakuSettings() {
  try {
    const res = await fetch(`${API_BASE}/api/screen/danmaku-settings`)
    const data = await res.json()
    if (data.code === 200 && data.data) {
      danmakuSettings.value = data.data
    }
  } catch (e) {
    console.error('获取弹幕设置失败:', e)
  }
}

// 状态文本
function statusText(status) {
  return { 0: '未开始', 1: '进行中', 2: '已结束' }[status] || '未知'
}

// 用户管理相关方法
function isOnline(id) {
  return onlineUserIds.value.has(id)
}

const filteredUsers = computed(() => {
  if (!userSearch.value.trim()) return allUsers.value
  const q = userSearch.value.trim().toLowerCase()
  return allUsers.value.filter(u =>
    (u.name && u.name.toLowerCase().includes(q)) ||
    (u.studentId && u.studentId.toLowerCase().includes(q))
  )
})

async function fetchAllUsers() {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/admin/user/list?activityId=${currentActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      allUsers.value = data.data
    }
  } catch (e) {
    console.error('获取用户列表失败:', e)
  }
}

async function fetchOnlineUsers() {
  if (!currentActivity.value) return
  try {
    const res = await fetch(`${API_BASE}/api/admin/user/online?activityId=${currentActivity.value.id}`)
    const data = await res.json()
    if (data.code === 200) {
      onlineUsers.value = data.data
      onlineUserIds.value = new Set(data.data.map(u => u.id))
    }
  } catch (e) {
    console.error('获取在线用户失败:', e)
  }
}

async function toggleMute(user) {
  const muted = !user.isMuted
  try {
    const res = await fetch(`${API_BASE}/api/admin/user/${user.id}/mute?muted=${muted}`, { method: 'PUT' })
    const data = await res.json()
    if (data.code === 200) {
      user.isMuted = muted ? 1 : 0
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    alert('操作失败')
  }
}

async function toggleBan(user) {
  const banned = !user.isBanned
  if (banned && !confirm(`确定要将 ${user.name} 移出直播间吗？`)) return
  try {
    const res = await fetch(`${API_BASE}/api/admin/user/${user.id}/ban?banned=${banned}`, { method: 'PUT' })
    const data = await res.json()
    if (data.code === 200) {
      user.isBanned = banned ? 1 : 0
      if (banned) fetchOnlineUsers()
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    alert('操作失败')
  }
}

async function toggleLottery(user) {
  const removed = user.status !== 3
  if (removed && !confirm(`确定要移除 ${user.name} 的抽奖资格吗？`)) return
  try {
    const res = await fetch(`${API_BASE}/api/admin/user/${user.id}/lottery?removed=${removed}`, { method: 'PUT' })
    const data = await res.json()
    if (data.code === 200) {
      user.status = removed ? 3 : 1
    } else {
      alert(data.message || '操作失败')
    }
  } catch (e) {
    alert('操作失败')
  }
}

// 监听 tab 切换加载数据
watch(currentTab, (tab) => {
  localStorage.setItem('admin_currentTab', tab)
  if (tab === 'sensitive') fetchSensitiveWords()
  if (tab === 'screen') fetchScreenConfig()
  if (tab === 'activity') fetchRounds()
  if (tab === 'history') fetchHistoryActivities()
  if (tab === 'users') {
    fetchAllUsers()
    fetchOnlineUsers()
  }
})

onMounted(() => {
  fetchCurrentActivity().then(() => {
    // 加载当前 tab 的数据（刷新后 watch 不触发，需手动加载）
    // 部分 tab 依赖 currentActivity，需等其加载完成
    fetchPrizes()
    const tab = currentTab.value
    if (tab === 'screen') fetchScreenConfig()
    if (tab === 'history') fetchHistoryActivities()
    if (tab === 'users') {
      fetchAllUsers()
      fetchOnlineUsers()
    }
  })
  fetchSensitiveWords()
})
</script>

<style>
/* 全局 CSS 变量（必须在非 scoped 样式中） */
:root {
  --color-primary: #3b82f6;
  --color-primary-dark: #2563eb;
  --color-success: #10b981;
  --color-warning: #f59e0b;
  --color-danger: #ef4444;
  --color-bg: #f8fafc;
  --color-sidebar: #0f172a;
  --color-sidebar-active: rgba(59, 130, 246, 0.2);
  --color-card: #ffffff;
  --color-border: #e2e8f0;
  --color-text: #1e293b;
  --color-text-secondary: #64748b;
  --color-text-muted: #94a3b8;
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
  --shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.05);
  --shadow-md: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
  --shadow-lg: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
}
</style>

<style scoped>

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

.admin-app {
  display: flex;
  min-height: 100vh;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background: var(--color-bg);
  color: var(--color-text);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

/* 侧边栏 */
.sidebar {
  width: 240px;
  background: var(--color-sidebar);
  color: #fff;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 24px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-header h2 {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.5px;
}

.sidebar-nav {
  padding: 16px 12px;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar-nav a {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  color: rgba(255, 255, 255, 0.6);
  text-decoration: none;
  cursor: pointer;
  transition: all 0.2s ease;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 500;
}

.sidebar-nav a:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}

.sidebar-nav a.active {
  background: var(--color-sidebar-active);
  color: #3b82f6;
}

/* 主内容 */
.main-content {
  flex: 1;
  padding: 32px;
  overflow-y: auto;
  max-height: 100vh;
}

.panel h3 {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 24px;
  color: var(--color-text);
  letter-spacing: -0.5px;
}

.card {
  background: var(--color-card);
  border-radius: var(--radius-lg);
  padding: 24px;
  margin-bottom: 20px;
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.card:hover {
  box-shadow: var(--shadow-md);
}

.card h4 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 16px;
  color: var(--color-text);
}

.form-row {
  display: flex;
  gap: 12px;
  align-items: center;
}

.form-row input[type="text"],
.form-row input[type="tel"],
.form-row input[type="number"] {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 14px;
  outline: none;
  transition: all 0.2s ease;
  background: var(--color-card);
}

.form-row input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.form-row button,
.card button {
  padding: 10px 20px;
  background: var(--color-primary);
  color: #fff;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s ease;
}

.form-row button:hover,
.card button:hover {
  background: var(--color-primary-dark);
  transform: translateY(-1px);
}

.form-row button:active,
.card button:active {
  transform: translateY(0);
}

.btn-group {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}

.info-row {
  display: flex;
  gap: 24px;
  font-size: 14px;
  color: var(--color-text-secondary);
}

.empty {
  color: var(--color-text-muted);
  font-size: 14px;
  padding: 16px 0;
  text-align: center;
}

/* 轮次列表 */
.round-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border);
}

.round-item:last-child {
  border-bottom: none;
}

.round-info {
  display: flex;
  gap: 20px;
  align-items: center;
  font-size: 14px;
}

.done {
  color: var(--color-success);
  font-weight: 500;
}

.pending {
  color: var(--color-warning);
  font-weight: 500;
}

.btn-draw {
  background: linear-gradient(135deg, #f59e0b, #ef4444) !important;
  font-weight: 600;
  padding: 10px 24px !important;
}

.btn-draw:hover {
  transform: scale(1.02) !important;
  box-shadow: 0 4px 12px rgba(245, 158, 11, 0.3);
}

/* 中奖记录 */
.winner-record {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--color-border);
  font-size: 14px;
}

.winner-student-id {
  color: var(--color-text-muted);
}

/* 敏感词 */
.sensitive-word-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
  font-size: 14px;
}

.btn-delete {
  background: var(--color-danger) !important;
  padding: 6px 14px !important;
  font-size: 12px !important;
}

.btn-delete:hover {
  background: #dc2626 !important;
}

/* 屏幕设置 */
.bg-type-select {
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 14px;
  outline: none;
  cursor: pointer;
  background: var(--color-card);
  transition: all 0.2s ease;
}

.bg-type-select:focus {
  border-color: var(--color-primary);
}

.upload-hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--color-text-muted);
}

.bg-preview {
  margin-top: 14px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
}

.bg-preview-img {
  width: 100%;
  max-height: 200px;
  object-fit: cover;
  display: block;
}

.bg-preview-video {
  width: 100%;
  max-height: 200px;
  display: block;
}

.current-bg-info p {
  font-size: 14px;
  color: var(--color-text-secondary);
  margin-bottom: 10px;
}

.btn-danger {
  background: var(--color-danger) !important;
}

.btn-danger:hover {
  background: #dc2626 !important;
}

.btn-primary {
  background: var(--color-primary) !important;
  font-weight: 600;
}

.btn-primary:hover {
  background: var(--color-primary-dark) !important;
}

/* 裁剪器模态框 */
.cropper-modal {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cropper-modal-content {
  background: var(--color-card);
  border-radius: var(--radius-lg);
  padding: 28px;
  max-width: 520px;
  width: 90%;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
}

.cropper-modal-content h4 {
  font-size: 18px;
  color: var(--color-text);
  margin-bottom: 8px;
}

.cropper-hint {
  font-size: 13px;
  color: var(--color-text-muted);
  margin-bottom: 20px;
}

.cropper-container {
  width: 100%;
  max-height: 500px;
  overflow: hidden;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.cropper-container img {
  max-width: 100%;
  display: block;
}

.cropper-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}

.btn-cancel {
  padding: 10px 20px;
  background: var(--color-text-muted) !important;
  color: #fff;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 14px;
}

.btn-cancel:hover {
  background: #64748b !important;
}

.btn-confirm {
  padding: 10px 24px;
  background: linear-gradient(135deg, var(--color-primary), var(--color-success)) !important;
  color: #fff;
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.btn-confirm:hover {
  transform: scale(1.02);
  box-shadow: 0 4px 12px rgba(59, 130, 246, 0.3);
}

/* 用户管理表格 */
.user-table,
.history-table,
.participant-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.user-table th,
.user-table td,
.history-table th,
.history-table td,
.participant-table th,
.participant-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.user-table th,
.history-table th,
.participant-table th {
  background: #f8fafc;
  color: var(--color-text-secondary);
  font-weight: 600;
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.user-table tbody tr:hover,
.history-table tbody tr:hover,
.participant-table tbody tr:hover {
  background: #f8fafc;
}

.row-online {
  background: #f0fdf4;
}

.row-banned {
  background: #fef2f2;
  opacity: 0.7;
}

.action-cell {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-sm {
  padding: 6px 12px !important;
  font-size: 12px !important;
  border-radius: var(--radius-sm) !important;
}

.btn-warn {
  background: var(--color-warning) !important;
  color: #000;
}

.btn-orange {
  background: var(--color-danger) !important;
}

.btn-refresh {
  background: var(--color-success) !important;
}

.tag {
  display: inline-block;
  padding: 4px 10px;
  border-radius: var(--radius-full, 9999px);
  font-size: 11px;
  font-weight: 500;
  margin-right: 6px;
}

.tag-online {
  background: #d1fae5;
  color: #065f46;
}

.tag-muted {
  background: #fef3c7;
  color: #92400e;
}

.tag-banned {
  background: #fee2e2;
  color: #991b1b;
}

.tag-removed {
  background: #e2e8f0;
  color: #475569;
}

.tag-winner {
  background: #d1fae5;
  color: #065f46;
}

.history-table th:nth-child(3),
.history-table td:nth-child(3) {
  text-align: center;
}

.btn-info {
  background: var(--color-primary) !important;
}

.btn-info:hover {
  background: var(--color-primary-dark) !important;
}

/* 详情弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-content {
  background: var(--color-card);
  border-radius: var(--radius-lg);
  width: 90%;
  max-width: 600px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: var(--shadow-lg);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border);
}

.modal-header h4 {
  font-size: 18px;
  color: var(--color-text);
  margin: 0;
  font-weight: 600;
}

.btn-close {
  background: none;
  border: none;
  font-size: 24px;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 0;
  line-height: 1;
  transition: color 0.2s ease;
}

.btn-close:hover {
  color: var(--color-text);
}

.modal-body {
  padding: 24px;
}

.detail-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-item {
  background: #f8fafc;
  border-radius: var(--radius-md);
  padding: 18px;
  text-align: center;
}

.stat-label {
  display: block;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.stat-value {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: var(--color-text);
}

.section-title {
  font-size: 16px;
  color: var(--color-text-secondary);
  margin-bottom: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--color-border);
}

.round-detail {
  background: #f8fafc;
  border-radius: var(--radius-md);
  padding: 18px;
  margin-bottom: 14px;
}

.round-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.round-header strong {
  font-size: 15px;
  color: var(--color-text);
}

.round-count {
  font-size: 13px;
  color: var(--color-text-muted);
}

.winner-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.winner-tag {
  display: inline-block;
  padding: 6px 14px;
  background: #dbeafe;
  border-radius: var(--radius-full, 9999px);
  font-size: 13px;
  color: var(--color-primary);
  font-weight: 500;
}

.link-text {
  color: var(--color-primary);
  cursor: pointer;
  text-decoration: none;
  display: inline-block;
  min-width: 40px;
  text-align: center;
  font-weight: 500;
  transition: color 0.2s ease;
}

.link-text:hover {
  color: var(--color-primary-dark);
  text-decoration: underline;
}

.modal-large {
  max-width: 800px;
}

/* 弹幕列表 */
.danmaku-list {
  max-height: 400px;
  overflow-y: auto;
}

.danmaku-item {
  display: flex;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border);
}

.danmaku-item:last-child {
  border-bottom: none;
}

.danmaku-time {
  font-size: 12px;
  color: var(--color-text-muted);
  white-space: nowrap;
  min-width: 140px;
}

.danmaku-text {
  font-size: 14px;
  color: var(--color-text);
  flex: 1;
}

/* 设置行 */
.setting-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--color-border);
}

.setting-row:last-of-type {
  border-bottom: none;
  margin-bottom: 16px;
}

.setting-row label {
  min-width: 80px;
  font-size: 14px;
  color: var(--color-text-secondary);
}

.setting-row input[type="range"] {
  flex: 1;
  height: 6px;
  background: var(--color-border);
  border-radius: 3px;
  outline: none;
  -webkit-appearance: none;
}

.setting-row input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 18px;
  height: 18px;
  background: var(--color-primary);
  border-radius: 50%;
  cursor: pointer;
}

.setting-row span {
  min-width: 50px;
  font-size: 14px;
  color: var(--color-text);
  font-weight: 500;
}

.setting-row select {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: 14px;
  outline: none;
  background: var(--color-card);
  cursor: pointer;
}

/* 响应式调整 */
@media (max-width: 1024px) {
  .sidebar {
    width: 200px;
  }

  .main-content {
    padding: 24px;
  }
}

@media (max-width: 768px) {
  .admin-app {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
    flex-direction: row;
    overflow-x: auto;
  }

  .sidebar-nav {
    flex-direction: row;
    padding: 8px;
    gap: 8px;
  }

  .sidebar-nav a {
    white-space: nowrap;
    padding: 8px 16px;
  }

  .main-content {
    padding: 16px;
    max-height: none;
  }

  .form-row {
    flex-direction: column;
    align-items: stretch;
  }

  .user-table,
  .history-table,
  .participant-table {
    display: block;
    overflow-x: auto;
  }
}

/* 奖品配置 */
.prize-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.prize-chip {
  padding: 4px 10px;
  background: #f5f3ff;
  border: 1px solid #ddd6fe;
  border-radius: 12px;
  font-size: 13px;
  color: #5b21b6;
}

.prize-empty {
  margin-top: 10px;
  font-size: 13px;
  color: #999;
}
</style>
