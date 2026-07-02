<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  HomeOutlined,
  IdcardOutlined,
  LinkOutlined,
  MessageOutlined,
  RobotOutlined,
  SendOutlined,
  ThunderboltOutlined,
  WifiOutlined,
  EnvironmentOutlined,
  ClockCircleOutlined,
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  CheckOutlined,
  CloseOutlined,
  RiseOutlined,
  FireOutlined,
  TrophyOutlined,
  StarOutlined,
  ShareAltOutlined,
  DownloadOutlined,
} from '@ant-design/icons-vue'
import { useUserStore } from '@/store/user'
import {
  appChatStream,
  closeAppChatSession,
  deleteAppPlanetProfile,
  getAppPlanet,
  getAppReport,
  linkAppAgent,
  openAppChatSession,
  queryAppAgents,
  queryAppRoles,
  saveAppPlanetProfile,
  type PlanetAppDevice,
  type PlanetAppRole,
  type PlanetProfileView,
  type WeeklyReportView,
} from '@/services/planetApp'

type TabKey = 'home' | 'growth' | 'profile' | 'chat' | 'device'

interface AppChatMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  statusText?: string
  streaming?: boolean
  timestamp: Date
}

const AVATAR_IMAGE = '/app-assets/happy-companion.png'

const userStore = useUserStore()
const activeTab = ref<TabKey>('home')
const devices = ref<PlanetAppDevice[]>([])
const roles = ref<PlanetAppRole[]>([])
const selectedDeviceId = ref('')
const planetProfile = ref<PlanetProfileView | null>(null)
const loading = ref(false)
const linking = ref(false)
const linkCode = ref('')
const avatarTilt = ref({ x: 0, y: 0 })
const profileEditorOpen = ref(false)
const profileEditing = ref(false)
const profileSubmitting = ref(false)
const profileForm = ref({ field: 'nickname', value: '' })

const chatInput = ref('')
const chatMessages = ref<AppChatMessage[]>([
  {
    id: 1,
    role: 'assistant',
    content: '我在。今天想把快乐能量放在哪里？',
    timestamp: new Date(),
  },
])
const chatSessionId = ref('')
const sending = ref(false)
const chatScroller = ref<HTMLDivElement>()
let chatAbort: AbortController | null = null
let messageId = 1

const demoProfile: PlanetProfileView = {
  deviceId: 'demo',
  roleId: 0,
  energy: 86,
  energyLevel: 'high',
  companionDays: 7,
  currentChannel: 'daily',
  currentChannelLabel: '日间陪伴',
  firstConnected: Date.now() - 7 * 86400000,
  profile: {
    nickname: '星球居民',
    companionStyle: '轻松、有趣、懂分寸',
    activeTime: '晚饭后',
    storyType: '宇宙冒险',
  },
  todayTask: {
    content: '给今天留下一个小小的高光瞬间。',
    done: false,
    date: new Date().toISOString().slice(0, 10),
  },
  stage: { level: 3, name: '老朋友', key: 'oldfriend', progress: 62, nextName: '星球密友', nextDays: 30 },
  streak: { current: 5, best: 9, repairLeft: 1 },
  badges: [
    { key: 'streak_3', label: '星球坚持者', desc: '连续完成星球任务 3 天', icon: '🔥', category: 'streak', earned: true, earnDate: '2026-06-28' },
    { key: 'streak_7', label: '一周之约', desc: '连续完成星球任务 7 天', icon: '🏅', category: 'streak', earned: false },
    { key: 'streak_21', label: '习惯养成者', desc: '连续完成星球任务 21 天', icon: '💎', category: 'streak', earned: false },
    { key: 'stage_familiar', label: '熟悉的伙伴', desc: '陪伴进入「熟悉」阶段', icon: '🤝', category: 'stage', earned: true, earnDate: '2026-06-25' },
    { key: 'stage_oldfriend', label: '老朋友', desc: '陪伴进入「老朋友」阶段', icon: '🧡', category: 'stage', earned: true, earnDate: '2026-06-30' },
    { key: 'stage_soulmate', label: '星球密友', desc: '陪伴进入「星球密友」阶段', icon: '🌟', category: 'stage', earned: false },
    { key: 'day_7', label: '相伴一周', desc: '累计陪伴满 7 天', icon: '🗓️', category: 'anniversary', earned: true, earnDate: '2026-06-27' },
    { key: 'day_30', label: '相伴一月', desc: '累计陪伴满 30 天', icon: '🌙', category: 'anniversary', earned: false },
  ],
  memoryStarMap: [
    { key: 'nickname', label: '昵称', minStage: 1, unlocked: true, filled: true, value: '星球居民' },
    { key: 'preferredCall', label: '喜欢的称呼', minStage: 1, unlocked: true, filled: false },
    { key: 'storyType', label: '喜欢的故事类型', minStage: 1, unlocked: true, filled: true, value: '宇宙冒险' },
    { key: 'storyLength', label: '故事长短偏好', minStage: 1, unlocked: true, filled: false },
    { key: 'companionStyle', label: '陪伴风格', minStage: 1, unlocked: true, filled: true, value: '轻松、有趣、懂分寸' },
    { key: 'activeTime', label: '常用互动时间', minStage: 1, unlocked: true, filled: true, value: '晚饭后' },
    { key: 'importantDate', label: '重要日期', minStage: 1, unlocked: true, filled: false },
    { key: 'favoriteFood', label: '喜欢的食物', minStage: 2, unlocked: true, filled: false },
    { key: 'hobby', label: '兴趣爱好', minStage: 2, unlocked: true, filled: true, value: '看星星' },
    { key: 'comfortTopic', label: '会被治愈的话题', minStage: 3, unlocked: true, filled: false },
    { key: 'recentGoal', label: '最近的小目标', minStage: 3, unlocked: true, filled: false },
    { key: 'dream', label: '心里的愿望', minStage: 4, unlocked: false, filled: false },
  ],
  energyCurve: [
    { date: '06-26', energy: 70 },
    { date: '06-27', energy: 76 },
    { date: '06-28', energy: 68 },
    { date: '06-29', energy: 82 },
    { date: '06-30', energy: 88 },
    { date: '07-01', energy: 84 },
    { date: '07-02', energy: 86 },
  ],
}

const tabs = [
  { key: 'home' as const, label: '星球', icon: HomeOutlined },
  { key: 'growth' as const, label: '成长', icon: RiseOutlined },
  { key: 'profile' as const, label: '档案', icon: IdcardOutlined },
  { key: 'chat' as const, label: '聊天', icon: MessageOutlined },
  { key: 'device' as const, label: '我的AI', icon: RobotOutlined },
]

const activeIndex = computed(() => tabs.findIndex(tab => tab.key === activeTab.value))

const quickPrompts = [
  '今天给我一个快乐任务',
  '陪我聊十分钟',
  '帮我整理今天的心情',
]

const profileLabels: Record<string, string> = {
  nickname: '昵称',
  preferredCall: '喜欢的称呼',
  storyType: '故事偏好',
  storyLength: '故事长度',
  companionStyle: '陪伴风格',
  activeTime: '活跃时间',
  importantDate: '重要日期',
}
const profileFieldKeys = Object.keys(profileLabels)

const selectedDevice = computed(() => {
  if (!devices.value.length) return null
  return devices.value.find(device => device.deviceId === selectedDeviceId.value) || devices.value[0]
})

const selectedRoleId = computed(() => {
  return selectedDevice.value?.roleId || roles.value.find(role => role.isDefault === '1')?.roleId || roles.value[0]?.roleId
})

const companionName = computed(() => {
  return selectedDevice.value?.roleName || roles.value[0]?.roleName || '小星'
})

const displayProfile = computed(() => planetProfile.value || demoProfile)
const userName = computed(() => userStore.userInfo?.name || userStore.userInfo?.username || '星球居民')
const stateInfo = computed(() => getStateInfo(selectedDevice.value?.state))
const energyLevelLabel = computed(() => {
  const level = displayProfile.value.energyLevel
  if (level === 'low') return '需要补能'
  if (level === 'high') return '高光在线'
  return '稳定陪伴'
})

const avatarStyle = computed(() => ({
  transform: `perspective(900px) rotateX(${avatarTilt.value.x}deg) rotateY(${avatarTilt.value.y}deg) translateZ(12px)`,
}))

const profileRows = computed(() => {
  const profile = displayProfile.value.profile || {}
  return Object.entries(profile).map(([key, value]) => ({
    key,
    label: profileLabels[key] || key,
    value,
  }))
})

const activeProfileFieldLabel = computed(() => profileLabels[profileForm.value.field] || '档案项')

// —— 成长体系（羁绊阶段 / 连击 / 徽章 / 能量曲线 / 周报）——
const stage = computed(() => displayProfile.value.stage)
const streak = computed(() => displayProfile.value.streak)
const badges = computed(() => displayProfile.value.badges || [])
const earnedBadgeCount = computed(() => badges.value.filter(b => b.earned).length)
const starMap = computed(() => displayProfile.value.memoryStarMap || [])
const filledStarCount = computed(() => starMap.value.filter(s => s.filled).length)
const energyCurveData = computed(() => displayProfile.value.energyCurve || [])

const stageRingStyle = computed(() => {
  const p = stage.value?.progress ?? 0
  return { background: `conic-gradient(#ffd166 ${p * 3.6}deg, rgba(255, 255, 255, 0.12) ${p * 3.6}deg)` }
})

function buildSparkline(points: { date: string; energy: number }[]) {
  if (!points.length) return { line: '', area: '' }
  const w = 100
  const h = 40
  const padX = 3
  const padY = 5
  const n = points.length
  const coords = points.map((pt, i) => {
    const x = n === 1 ? w / 2 : padX + (i / (n - 1)) * (w - padX * 2)
    const clamped = Math.max(0, Math.min(100, pt.energy))
    const y = padY + (1 - clamped / 100) * (h - padY * 2)
    return [Number(x.toFixed(2)), Number(y.toFixed(2))] as [number, number]
  })
  const line = coords.map(c => c.join(',')).join(' ')
  const first = coords[0]
  const last = coords[n - 1]
  if (!first || !last) return { line, area: '' }
  const area =
    `M ${first[0]},${first[1]} ` +
    coords.slice(1).map(c => `L ${c[0]},${c[1]}`).join(' ') +
    ` L ${last[0]},${h} L ${first[0]},${h} Z`
  return { line, area }
}

const sparkline = computed(() => buildSparkline(energyCurveData.value))

const report = ref<WeeklyReportView | null>(null)
const reportLoading = ref(false)
const shareOpen = ref(false)
const shareImage = ref('')

const demoReport: WeeklyReportView = {
  periodStart: '2026-06-26',
  periodEnd: '2026-07-02',
  energyStart: 70,
  energyEnd: 86,
  energyAvg: 79,
  energyMin: 68,
  energyMax: 88,
  energyCurve: demoProfile.energyCurve || [],
  tasksDone: 5,
  streakDays: 5,
  bestStreak: 9,
  companionDays: 7,
  stage: { level: 3, name: '老朋友' },
  badgesEarned: 2,
  newBadges: [{ key: 'stage_oldfriend', label: '老朋友', icon: '🧡' }],
  newMemories: [
    { label: '兴趣爱好', value: '看星星' },
    { label: '活跃时间', value: '晚饭后' },
  ],
  highlight: '这一周就先收进星球档案啦。下一周，我们慢慢来。',
}

const reportData = computed<WeeklyReportView | null>(
  () => report.value || (planetProfile.value ? null : demoReport),
)

async function loadReport() {
  const device = selectedDevice.value
  const roleId = selectedRoleId.value
  if (!device?.deviceId || !roleId) {
    report.value = null
    return
  }
  reportLoading.value = true
  try {
    const res = await getAppReport(device.deviceId, roleId)
    if (res.code === 200) {
      report.value = res.data
    }
  } catch (error) {
    console.error('加载星球周报失败:', error)
  } finally {
    reportLoading.value = false
  }
}

function reportRange(r?: WeeklyReportView | null) {
  if (!r) return ''
  return `${(r.periodStart || '').slice(5)} ~ ${(r.periodEnd || '').slice(5)}`
}

function openShare() {
  const r = reportData.value
  if (!r) {
    message.info('本周还没有可分享的报告')
    return
  }
  try {
    shareImage.value = buildShareImage(r)
    shareOpen.value = true
  } catch (error) {
    console.error('生成分享图失败:', error)
    message.error('生成分享图失败')
  }
}

function closeShare() {
  shareOpen.value = false
}

function downloadShare() {
  if (!shareImage.value) return
  const a = document.createElement('a')
  a.href = shareImage.value
  a.download = `快乐星球周报_${reportData.value?.periodEnd || ''}.png`
  a.click()
}

// —— 分享图（客户端 canvas 生成，含品牌与邀请文案）——
function buildShareImage(r: WeeklyReportView): string {
  const W = 720
  const H = 1120
  const canvas = document.createElement('canvas')
  canvas.width = W
  canvas.height = H
  const ctx = canvas.getContext('2d')
  if (!ctx) return ''

  const bg = ctx.createLinearGradient(0, 0, W, H)
  bg.addColorStop(0, '#101522')
  bg.addColorStop(0.5, '#14242a')
  bg.addColorStop(1, '#291d31')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, W, H)
  radialGlow(ctx, W * 0.2, H * 0.1, 300, 'rgba(255, 202, 96, 0.20)')
  radialGlow(ctx, W * 0.85, H * 0.04, 320, 'rgba(69, 198, 201, 0.18)')

  ctx.textBaseline = 'top'
  ctx.fillStyle = 'rgba(255, 209, 102, 0.92)'
  ctx.font = '700 24px system-ui, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('HAPPY PLANET · 星球周报', 56, 62)

  ctx.fillStyle = '#f8fbff'
  ctx.font = '800 56px system-ui, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText(truncate(companionName.value, 10), 56, 100)

  ctx.fillStyle = 'rgba(248, 251, 255, 0.6)'
  ctx.font = '500 24px system-ui, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText(`${reportRange(r)} · 与 ${truncate(userName.value, 8)} 的一周`, 56, 176)

  roundRect(ctx, 56, 228, W - 112, 208, 28, 'rgba(255, 255, 255, 0.06)')
  ctx.fillStyle = 'rgba(248, 251, 255, 0.6)'
  ctx.font = '700 22px system-ui, sans-serif'
  ctx.fillText('快乐能量', 88, 258)
  ctx.fillStyle = '#f8fbff'
  ctx.font = '800 58px system-ui, sans-serif'
  ctx.fillText(`${r.energyStart}  →  ${r.energyEnd}`, 88, 292)
  drawShareSpark(ctx, r.energyCurve, 88, 372, W - 176, 46)

  const stats: [string, string][] = [
    ['完成任务', `${r.tasksDone} 次`],
    ['连续打卡', `${r.streakDays} 天`],
    ['累计陪伴', `${r.companionDays} 天`],
    ['关系阶段', r.stage?.name || '初识'],
  ]
  const baseY = 468
  stats.forEach((s, i) => {
    const col = i % 2
    const row = Math.floor(i / 2)
    const cellW = (W - 112) / 2 - 6
    const x = 56 + col * (cellW + 12)
    const y = baseY + row * 116
    roundRect(ctx, x, y, cellW, 100, 22, 'rgba(255, 255, 255, 0.055)')
    ctx.fillStyle = 'rgba(248, 251, 255, 0.6)'
    ctx.font = '700 22px system-ui, sans-serif'
    ctx.fillText(s[0], x + 26, y + 22)
    ctx.fillStyle = '#ffd166'
    ctx.font = '800 34px system-ui, sans-serif'
    ctx.fillText(s[1], x + 26, y + 50)
  })

  let my = baseY + 2 * 116 + 22
  ctx.fillStyle = 'rgba(248, 251, 255, 0.6)'
  ctx.font = '700 24px system-ui, sans-serif'
  ctx.fillText('这一周新记住的', 56, my)
  my += 42
  const mems = (r.newMemories || []).slice(0, 3)
  if (!mems.length) {
    ctx.fillStyle = 'rgba(248, 251, 255, 0.5)'
    ctx.font = '500 24px system-ui, sans-serif'
    ctx.fillText('这一周静静陪着，也很好。', 56, my)
    my += 42
  }
  mems.forEach(m => {
    ctx.fillStyle = '#f8fbff'
    ctx.font = '600 26px system-ui, sans-serif'
    ctx.fillText(`· ${truncate(m.label, 8)}：${truncate(m.value, 14)}`, 56, my)
    my += 44
  })

  my += 12
  roundRect(ctx, 56, my, W - 112, 132, 24, 'rgba(73, 215, 209, 0.12)')
  ctx.fillStyle = '#eafcff'
  ctx.font = '600 26px system-ui, sans-serif'
  wrapText(ctx, r.highlight || '', 88, my + 28, W - 176, 40)

  ctx.fillStyle = 'rgba(255, 209, 102, 0.85)'
  ctx.font = '700 26px system-ui, sans-serif'
  ctx.fillText('来快乐星球，认领你的专属陪伴 →', 56, H - 76)

  return canvas.toDataURL('image/png')
}

function radialGlow(ctx: CanvasRenderingContext2D, cx: number, cy: number, radius: number, color: string) {
  const grad = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius)
  grad.addColorStop(0, color)
  grad.addColorStop(1, 'rgba(0, 0, 0, 0)')
  ctx.fillStyle = grad
  ctx.fillRect(cx - radius, cy - radius, radius * 2, radius * 2)
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number, fill: string) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
  ctx.fillStyle = fill
  ctx.fill()
}

function drawShareSpark(ctx: CanvasRenderingContext2D, points: { energy: number }[], x: number, y: number, w: number, h: number) {
  if (!points.length) return
  const n = points.length
  const coords = points.map((pt, i) => {
    const px = n === 1 ? x + w / 2 : x + (i / (n - 1)) * w
    const py = y + (1 - Math.max(0, Math.min(100, pt.energy)) / 100) * h
    return [px, py] as [number, number]
  })
  const start = coords[0]
  if (!start) return
  ctx.beginPath()
  ctx.moveTo(start[0], start[1])
  coords.slice(1).forEach(c => ctx.lineTo(c[0], c[1]))
  ctx.strokeStyle = '#49d7d1'
  ctx.lineWidth = 4
  ctx.lineJoin = 'round'
  ctx.stroke()
  coords.forEach(c => {
    ctx.beginPath()
    ctx.arc(c[0], c[1], 5, 0, Math.PI * 2)
    ctx.fillStyle = '#ffd166'
    ctx.fill()
  })
}

function wrapText(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, maxWidth: number, lineHeight: number) {
  const chars = Array.from(text)
  let line = ''
  let cursorY = y
  for (const ch of chars) {
    const test = line + ch
    if (ctx.measureText(test).width > maxWidth && line) {
      ctx.fillText(line, x, cursorY)
      line = ch
      cursorY += lineHeight
    } else {
      line = test
    }
  }
  if (line) ctx.fillText(line, x, cursorY)
}

function truncate(value: string | undefined, max: number): string {
  const s = String(value ?? '')
  return s.length > max ? s.slice(0, max) + '…' : s
}

function getStateInfo(state?: string | number) {
  const value = String(state ?? '')
  if (value === '1') {
    return { label: '在线', tone: 'online' }
  }
  if (value === '2') {
    return { label: '待机', tone: 'standby' }
  }
  if (value === '0') {
    return { label: '离线', tone: 'offline' }
  }
  return { label: selectedDevice.value ? '未知' : '待关联', tone: 'offline' }
}

function formatTime(value?: string) {
  if (!value) return '暂无'
  return value.replace('T', ' ').slice(0, 16)
}

function handleAvatarMove(event: PointerEvent) {
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  const x = ((event.clientY - rect.top) / rect.height - 0.5) * -12
  const y = ((event.clientX - rect.left) / rect.width - 0.5) * 16
  avatarTilt.value = { x, y }
}

function resetAvatarTilt() {
  avatarTilt.value = { x: 0, y: 0 }
}

async function loadDevices() {
  const res = await queryAppAgents()
  if (res.code === 200) {
    devices.value = res.data?.list || []
    if (devices.value.length && !selectedDeviceId.value) {
      const firstDevice = devices.value[0]
      if (firstDevice) {
        selectedDeviceId.value = firstDevice.deviceId
      }
    }
  }
}

async function loadRoles() {
  const res = await queryAppRoles()
  if (res.code === 200) {
    roles.value = res.data || []
  }
}

async function loadPlanet() {
  const device = selectedDevice.value
  const roleId = selectedRoleId.value
  if (!device?.deviceId || !roleId) {
    planetProfile.value = null
    report.value = null
    return
  }
  const res = await getAppPlanet(device.deviceId, roleId)
  if (res.code === 200) {
    planetProfile.value = res.data
  }
  await loadReport()
}

async function refreshApp() {
  loading.value = true
  try {
    await Promise.all([loadDevices(), loadRoles()])
    await loadPlanet()
  } catch (error) {
    console.error('加载移动端数据失败:', error)
  } finally {
    loading.value = false
  }
}

async function handleLinkAgent() {
  const code = linkCode.value.trim()
  if (!code) {
    message.warning('请输入星球码')
    return
  }
  linking.value = true
  try {
    const res = await linkAppAgent(code)
    if (res.code === 200) {
      message.success('已关联你的 AI')
      linkCode.value = ''
      await refreshApp()
      selectedDeviceId.value = res.data.deviceId
    } else {
      message.error(res.message || '关联失败')
    }
  } catch (error) {
    console.error('关联 AI 失败:', error)
  } finally {
    linking.value = false
  }
}

function openProfileAdd() {
  if (!selectedDevice.value?.deviceId || !selectedRoleId.value) {
    message.warning('先关联你的 AI，再修改星球档案')
    activeTab.value = 'device'
    return
  }
  const profile = planetProfile.value?.profile || {}
  const firstEmptyField = profileFieldKeys.find(key => !(key in profile)) || profileFieldKeys[0] || 'nickname'
  profileEditing.value = false
  profileForm.value = { field: firstEmptyField, value: '' }
  profileEditorOpen.value = true
}

function openProfileEdit(row: { key: string; value: string }) {
  if (!selectedDevice.value?.deviceId || !selectedRoleId.value) {
    message.warning('先关联你的 AI，再修改星球档案')
    activeTab.value = 'device'
    return
  }
  profileEditing.value = true
  profileForm.value = { field: row.key, value: row.value }
  profileEditorOpen.value = true
}

function closeProfileEditor() {
  profileEditorOpen.value = false
  profileSubmitting.value = false
}

async function saveProfileItem() {
  const deviceId = selectedDevice.value?.deviceId
  const roleId = selectedRoleId.value
  const field = profileForm.value.field
  const value = profileForm.value.value.trim()
  if (!deviceId || !roleId) {
    message.warning('先关联你的 AI，再修改星球档案')
    activeTab.value = 'device'
    return
  }
  if (!field || !value) {
    message.warning('请填写档案内容')
    return
  }

  profileSubmitting.value = true
  try {
    const res = await saveAppPlanetProfile({ deviceId, roleId, field, value })
    if (res.code === 200) {
      message.success('档案已更新')
      profileEditorOpen.value = false
      await loadPlanet()
    } else {
      message.error(res.message || '档案更新失败')
    }
  } catch (error) {
    console.error('更新星球档案失败:', error)
  } finally {
    profileSubmitting.value = false
  }
}

async function deleteProfileItem(row: { key: string; label: string }) {
  const deviceId = selectedDevice.value?.deviceId
  const roleId = selectedRoleId.value
  if (!deviceId || !roleId) {
    message.warning('先关联你的 AI，再修改星球档案')
    activeTab.value = 'device'
    return
  }
  if (!window.confirm(`确认删除「${row.label}」吗？`)) return

  try {
    const res = await deleteAppPlanetProfile(deviceId, roleId, row.key)
    if (res.code === 200) {
      message.success('档案已删除')
      await loadPlanet()
    } else {
      message.error(res.message || '档案删除失败')
    }
  } catch (error) {
    console.error('删除星球档案失败:', error)
  }
}

function usePrompt(prompt: string) {
  activeTab.value = 'chat'
  chatInput.value = prompt
  nextTick(() => sendChat())
}

function pushAssistant(content: string) {
  chatMessages.value.push({
    id: ++messageId,
    role: 'assistant',
    content,
    timestamp: new Date(),
  })
  scrollChatToBottom()
}

async function ensureChatSession(deviceId: string, roleId: number) {
  if (chatSessionId.value) return chatSessionId.value
  const resp = await openAppChatSession(deviceId, roleId)
  const data = resp as unknown as { sessionId?: string; data?: { sessionId?: string } }
  const sessionId = data.sessionId || data.data?.sessionId
  if (!sessionId) {
    throw new Error('未获取到会话')
  }
  chatSessionId.value = sessionId
  return sessionId
}

async function sendChat() {
  const text = chatInput.value.trim()
  if (!text || sending.value) return

  const roleId = selectedRoleId.value
  const deviceId = selectedDevice.value?.deviceId
  if (!roleId || !deviceId) {
    chatInput.value = ''
    pushAssistant('先关联你的 AI，我就能把聊天、档案和星球任务连起来。')
    return
  }

  chatMessages.value.push({
    id: ++messageId,
    role: 'user',
    content: text,
    timestamp: new Date(),
  })
  chatInput.value = ''

  const assistantDraft: AppChatMessage = {
    id: ++messageId,
    role: 'assistant',
    content: '',
    statusText: '正在连接星球频道...',
    streaming: true,
    timestamp: new Date(),
  }
  chatMessages.value.push(assistantDraft)
  const assistantMessage = chatMessages.value[chatMessages.value.length - 1]
  if (!assistantMessage) return
  scrollChatToBottom()

  sending.value = true
  chatAbort = new AbortController()
  let slowTimer: number | undefined
  let verySlowTimer: number | undefined
  try {
    const sessionId = await ensureChatSession(deviceId, roleId)
    assistantMessage.statusText = '正在准备星球记忆...'
    slowTimer = window.setTimeout(() => {
      if (assistantMessage.streaming && !assistantMessage.content) {
        assistantMessage.statusText = '模型响应有点慢，星球记忆已经准备好...'
      }
    }, 8000)
    verySlowTimer = window.setTimeout(() => {
      if (assistantMessage.streaming && !assistantMessage.content) {
        assistantMessage.statusText = '还在等待模型开口，这不是档案读取耗时。'
      }
    }, 18000)
    for await (const token of appChatStream(sessionId, text, chatAbort.signal)) {
      if (token.type === 'status') {
        if (!assistantMessage.content) {
          assistantMessage.statusText = token.text
        }
      } else if (token.type === 'content') {
        assistantMessage.statusText = ''
        assistantMessage.content += token.text
      }
      scrollChatToBottom()
    }
    if (!assistantMessage.content) {
      assistantMessage.content = localChatFallback(text)
    }
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) {
      assistantMessage.content = '刚刚没有连上星球频道。你可以再发一次，我会继续接住。'
      console.error('App 聊天失败:', error)
    }
  } finally {
    if (slowTimer) window.clearTimeout(slowTimer)
    if (verySlowTimer) window.clearTimeout(verySlowTimer)
    assistantMessage.streaming = false
    sending.value = false
    chatAbort = null
    scrollChatToBottom()
  }
}

function localChatFallback(text: string) {
  const normalized = text.toLowerCase().replace(/\s+/g, '')
  if (normalized.includes('聊') || normalized.includes('陪我')) {
    return '可以，我在。我们先不用聊很大的事，就从现在开始：你今天最想吐槽的，或者最想留住的一件小事是什么？'
  }
  if (normalized.includes('心情') || normalized.includes('难受') || normalized.includes('烦')) {
    return '可以，我们慢慢整理。你先不用说得很完整，只要告诉我：现在这份心情更像累、烦、委屈，还是说不清楚？'
  }
  return '刚刚模型没有返回有效内容。我们换个更直接的方式聊：你想先说发生了什么，还是只想让我陪你安静一会儿？'
}

function scrollChatToBottom() {
  nextTick(() => {
    if (chatScroller.value) {
      chatScroller.value.scrollTop = chatScroller.value.scrollHeight
    }
  })
}

function resetChatSession() {
  chatAbort?.abort()
  if (chatSessionId.value) {
    closeAppChatSession(chatSessionId.value).catch(() => {})
  }
  chatSessionId.value = ''
  sending.value = false
  chatMessages.value = [
    {
      id: ++messageId,
      role: 'assistant',
      content: '我在。今天想把快乐能量放在哪里？',
      timestamp: new Date(),
    },
  ]
}

watch(selectedDeviceId, resetChatSession)

watch([selectedDeviceId, selectedRoleId], () => {
  loadPlanet()
})

onMounted(refreshApp)

onBeforeUnmount(() => {
  chatAbort?.abort()
  if (chatSessionId.value) {
    closeAppChatSession(chatSessionId.value).catch(() => {})
  }
})
</script>

<template>
  <div class="planet-app">
    <main class="app-shell" :class="{ loading }">
      <header class="topbar">
        <div>
          <span class="eyebrow">Happy Planet</span>
          <h1>{{ companionName }}</h1>
        </div>
        <button class="icon-button" type="button" @click="refreshApp">
          <ThunderboltOutlined />
        </button>
      </header>

      <section class="content" v-show="activeTab === 'home'">
        <div class="hero-panel">
          <div class="hero-copy">
            <span class="welcome">Hi，{{ userName }}</span>
            <strong>{{ energyLevelLabel }}</strong>
            <p>{{ displayProfile.todayTask?.content || '今天的星球任务还在生成中。' }}</p>
          </div>
          <div class="avatar-stage" @pointermove="handleAvatarMove" @pointerleave="resetAvatarTilt">
            <span class="orbit orbit-a"></span>
            <span class="orbit orbit-b"></span>
            <img :src="AVATAR_IMAGE" alt="快乐星球智能体" :style="avatarStyle" />
          </div>
        </div>

        <div class="energy-panel">
          <div class="energy-head">
            <span>快乐能量</span>
            <strong>{{ displayProfile.energy }}%</strong>
          </div>
          <div class="energy-track">
            <span :style="{ width: `${displayProfile.energy}%` }"></span>
          </div>
          <div class="energy-meta">
            <span>{{ displayProfile.companionDays }} 天陪伴</span>
            <span>{{ displayProfile.currentChannelLabel || '日间陪伴' }}</span>
          </div>
        </div>

        <div class="device-strip">
          <div>
            <span class="muted">当前AI</span>
            <strong>{{ selectedDevice?.roleName || selectedDevice?.deviceName || '等待关联' }}</strong>
          </div>
          <span class="state-pill" :class="stateInfo.tone">{{ stateInfo.label }}</span>
        </div>

        <div class="quick-grid">
          <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt)">
            {{ prompt }}
          </button>
        </div>
      </section>

      <section class="content" v-show="activeTab === 'growth'">
        <!-- 羁绊阶段 -->
        <div class="growth-card stage-card">
          <div class="stage-ring" :style="stageRingStyle">
            <div class="stage-ring-hole">
              <strong>{{ stage?.progress ?? 0 }}%</strong>
              <span>Lv.{{ stage?.level ?? 1 }}</span>
            </div>
          </div>
          <div class="stage-meta">
            <span class="muted">羁绊阶段</span>
            <strong>{{ stage?.name || '初识' }}</strong>
            <p v-if="stage?.nextName">距离「{{ stage.nextName }}」再近一点，继续陪伴就能解锁。</p>
            <p v-else>已抵达最高羁绊阶段 · 星球密友。</p>
          </div>
        </div>

        <!-- 连击 -->
        <div class="growth-card streak-card">
          <div class="streak-flame"><FireOutlined /></div>
          <div class="streak-nums">
            <div><strong>{{ streak?.current ?? 0 }}</strong><span>连续打卡</span></div>
            <div><strong>{{ streak?.best ?? 0 }}</strong><span>历史最长</span></div>
            <div><strong>{{ streak?.repairLeft ?? 0 }}</strong><span>补签卡</span></div>
          </div>
        </div>

        <!-- 能量曲线 -->
        <div class="growth-card curve-card">
          <div class="growth-head">
            <span>近 7 天能量曲线</span>
            <strong>{{ displayProfile.energy }}%</strong>
          </div>
          <svg class="spark" viewBox="0 0 100 40" preserveAspectRatio="none">
            <path :d="sparkline.area" class="spark-area" />
            <polyline :points="sparkline.line" class="spark-line" />
          </svg>
          <div class="curve-axis">
            <span v-for="(pt, i) in energyCurveData" :key="i">{{ pt.date }}</span>
          </div>
        </div>

        <!-- 徽章墙 -->
        <div class="section-title">
          <div>
            <span>星球徽章</span>
            <strong>{{ earnedBadgeCount }} / {{ badges.length }} 已点亮</strong>
          </div>
          <TrophyOutlined class="title-icon" />
        </div>
        <div class="badge-wall">
          <div
            v-for="b in badges"
            :key="b.key"
            class="badge-cell"
            :class="{ locked: !b.earned }"
            :title="b.desc"
          >
            <div class="badge-emoji">{{ b.icon }}</div>
            <span>{{ b.label }}</span>
          </div>
        </div>

        <!-- 本周星球报告 -->
        <div class="section-title report-title">
          <div>
            <span>本周星球报告</span>
            <strong>{{ reportRange(reportData) || '汇总中' }}</strong>
          </div>
          <button class="mini-action" type="button" :disabled="!reportData" @click="openShare">
            <ShareAltOutlined />
          </button>
        </div>
        <div v-if="reportData" class="growth-card report-card">
          <div class="report-energy">
            <span class="muted">快乐能量</span>
            <strong>{{ reportData.energyStart }} → {{ reportData.energyEnd }}</strong>
          </div>
          <div class="report-stats">
            <div><strong>{{ reportData.tasksDone }}</strong><span>完成任务</span></div>
            <div><strong>{{ reportData.streakDays }}</strong><span>连续打卡</span></div>
            <div><strong>{{ reportData.companionDays }}</strong><span>陪伴天数</span></div>
            <div><strong>{{ reportData.stage?.name }}</strong><span>关系阶段</span></div>
          </div>
          <div v-if="reportData.newMemories?.length" class="report-mems">
            <span class="muted">这一周新记住</span>
            <p v-for="(m, i) in reportData.newMemories" :key="i">· {{ m.label }}：{{ m.value }}</p>
          </div>
          <p class="report-quote">{{ reportData.highlight }}</p>
          <button class="share-btn" type="button" @click="openShare">
            <ShareAltOutlined />
            <span>生成分享图</span>
          </button>
        </div>
        <div v-else class="empty-memory">
          本周报告还在汇总中。多陪 AI 聊聊、完成星球任务，周日会自动生成一份成长报告。
        </div>
      </section>

      <section class="content" v-show="activeTab === 'profile'">
        <div class="section-title">
          <div>
            <span>星球档案</span>
            <strong>{{ profileRows.length }} 项记忆</strong>
          </div>
          <button class="mini-action" type="button" @click="openProfileAdd">
            <PlusOutlined />
          </button>
        </div>

        <div class="profile-summary">
          <img :src="AVATAR_IMAGE" alt="" />
          <div>
            <span>{{ companionName }}</span>
            <strong>{{ displayProfile.currentChannelLabel || '日间陪伴' }}</strong>
            <p>{{ displayProfile.todayTask?.done ? '今日任务已完成' : displayProfile.todayTask?.content }}</p>
          </div>
        </div>

        <div v-if="starMap.length" class="starmap-card">
          <div class="growth-head">
            <span>记忆星图</span>
            <strong>{{ filledStarCount }} / {{ starMap.length }} 已点亮</strong>
          </div>
          <div class="starmap">
            <div
              v-for="s in starMap"
              :key="s.key"
              class="star"
              :class="{ filled: s.filled, locked: !s.unlocked }"
              :title="s.filled ? `${s.label}：${s.value}` : (s.unlocked ? `${s.label}（还没记住）` : `${s.label}（Lv.${s.minStage} 解锁）`)"
            >
              <StarOutlined />
              <em>{{ s.label }}</em>
            </div>
          </div>
        </div>

        <div v-if="profileEditorOpen" class="profile-editor">
          <div class="editor-head">
            <div>
              <span>{{ profileEditing ? '修改档案' : '新增档案' }}</span>
              <strong>{{ activeProfileFieldLabel }}</strong>
            </div>
            <button class="mini-action ghost" type="button" @click="closeProfileEditor">
              <CloseOutlined />
            </button>
          </div>
          <label>
            <span>档案项</span>
            <select v-model="profileForm.field" :disabled="profileEditing">
              <option v-for="key in profileFieldKeys" :key="key" :value="key">
                {{ profileLabels[key] }}
              </option>
            </select>
          </label>
          <label>
            <span>内容</span>
            <textarea
              v-model="profileForm.value"
              maxlength="500"
              rows="3"
              placeholder="写下希望 AI 记住的偏好"
            ></textarea>
          </label>
          <button class="save-profile" type="button" :disabled="profileSubmitting" @click="saveProfileItem">
            <CheckOutlined />
            <span>{{ profileSubmitting ? '保存中' : '保存档案' }}</span>
          </button>
        </div>

        <div class="memory-list">
          <div v-for="row in profileRows" :key="row.key" class="memory-item">
            <div>
              <span>{{ row.label }}</span>
              <strong>{{ row.value }}</strong>
            </div>
            <div class="memory-actions">
              <button type="button" @click="openProfileEdit(row)">
                <EditOutlined />
              </button>
              <button type="button" @click="deleteProfileItem(row)">
                <DeleteOutlined />
              </button>
            </div>
          </div>
          <div v-if="!profileRows.length" class="empty-memory">
            还没有档案。你可以手动添加，也可以在聊天里告诉 AI 你的偏好。
          </div>
        </div>
      </section>

      <section class="content chat-content" v-show="activeTab === 'chat'">
        <div class="chat-head">
          <img :src="AVATAR_IMAGE" alt="" />
          <div>
            <span>{{ companionName }}</span>
            <strong>{{ stateInfo.label }} · {{ displayProfile.currentChannelLabel || '日间陪伴' }}</strong>
          </div>
        </div>

        <div ref="chatScroller" class="chat-messages">
          <div v-for="item in chatMessages" :key="item.id" class="chat-row" :class="item.role">
            <div class="bubble">
              <span v-if="item.streaming && !item.content" class="typing-state">
                <span>{{ item.statusText || '正在连接星球频道...' }}</span>
                <span class="typing"><i></i><i></i><i></i></span>
              </span>
              <template v-else>{{ item.content }}</template>
            </div>
          </div>
        </div>

        <form class="chat-input" @submit.prevent="sendChat">
          <input v-model="chatInput" type="text" placeholder="和小星说点什么" />
          <button type="submit" :disabled="sending || !chatInput.trim()">
            <SendOutlined />
          </button>
        </form>
      </section>

      <section class="content" v-show="activeTab === 'device'">
        <div class="section-title">
          <span>我的AI</span>
          <strong>{{ devices.length }} 个</strong>
        </div>

        <div v-if="devices.length" class="device-list">
          <button
            v-for="device in devices"
            :key="device.deviceId"
            type="button"
            :class="{ active: selectedDeviceId === device.deviceId }"
            @click="selectedDeviceId = device.deviceId"
          >
            <RobotOutlined />
            <span>{{ device.deviceName || device.deviceId }}</span>
            <em>{{ getStateInfo(device.state).label }}</em>
          </button>
        </div>

        <div class="device-detail">
          <div>
            <WifiOutlined />
            <span>网络</span>
            <strong>{{ selectedDevice?.wifiName || '暂无' }}</strong>
          </div>
          <div>
            <EnvironmentOutlined />
            <span>位置</span>
            <strong>{{ selectedDevice?.location || '暂无' }}</strong>
          </div>
          <div>
            <ClockCircleOutlined />
            <span>活跃</span>
            <strong>{{ formatTime(selectedDevice?.updateTime) }}</strong>
          </div>
          <div>
            <RobotOutlined />
            <span>版本</span>
            <strong>{{ selectedDevice?.version || selectedDevice?.type || '暂无' }}</strong>
          </div>
        </div>

        <div class="bind-panel">
          <div>
            <span>关联我的AI</span>
            <strong>对你的 AI 说“绑定 APP”，再输入它播报的星球码</strong>
          </div>
          <div class="bind-row">
            <input v-model="linkCode" type="text" maxlength="64" placeholder="星球码 / AI码" />
            <button type="button" :disabled="linking" @click="handleLinkAgent">
              <LinkOutlined />
            </button>
          </div>
        </div>
      </section>

      <div v-if="shareOpen" class="share-overlay" @click.self="closeShare">
        <div class="share-modal">
          <img v-if="shareImage" :src="shareImage" alt="星球周报分享图" />
          <div class="share-actions">
            <button type="button" class="ghost" @click="closeShare">
              <CloseOutlined />
              <span>关闭</span>
            </button>
            <button type="button" @click="downloadShare">
              <DownloadOutlined />
              <span>保存图片</span>
            </button>
          </div>
        </div>
      </div>

      <nav class="tabbar">
        <span class="tab-indicator" :style="{ '--active-index': activeIndex }" aria-hidden="true"></span>
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          <component :is="tab.icon" />
          <span>{{ tab.label }}</span>
        </button>
      </nav>
    </main>
  </div>
</template>

<style scoped lang="scss">
/* ============================================================
   Liquid Glass · iOS 26 design system (scoped to the planet app)
   Material = blur + saturation over a low-opacity fill, a specular
   top edge, continuous corners, tint for prominence, spring motion.
   ============================================================ */
.planet-app {
  /* —— motion —— */
  --ease-spring: cubic-bezier(0.32, 0.72, 0, 1);
  --ease-out: cubic-bezier(0.22, 0.9, 0.32, 1);
  --dur: 0.34s;

  /* —— continuous ("squircle") corner radii —— */
  --r-hero: 30px;
  --r-card: 24px;
  --r-panel: 22px;
  --r-control: 16px;
  --r-chip: 14px;
  --r-pill: 999px;

  /* —— brand accents (kept as tints on glass) —— */
  --accent: #ffd166;
  --accent-2: #ffb347;
  --accent-ink: #2a1e04;
  --teal: #49d7d1;
  --coral: #ff7a72;
  --mint: #6fe6b2;

  /* —— foreground ink —— */
  --ink: #f4f7fb;
  --ink-2: rgba(244, 247, 251, 0.66);
  --ink-3: rgba(244, 247, 251, 0.42);

  /* —— Liquid Glass material —— */
  --glass-fill: rgba(255, 255, 255, 0.08);
  --glass-fill-2: rgba(255, 255, 255, 0.13);
  --glass-stroke: rgba(255, 255, 255, 0.14);
  --glass-highlight: rgba(255, 255, 255, 0.5);
  --glass-lowlight: rgba(0, 0, 0, 0.22);
  --glass-blur: 24px;
  --glass-sat: 180%;

  /* —— elevation —— */
  --shadow-1: 0 8px 24px rgba(0, 0, 0, 0.26);
  --shadow-2: 0 20px 48px rgba(0, 0, 0, 0.34);
  --accent-glow: 0 12px 30px rgba(255, 199, 92, 0.28);

  /* —— canvas —— */
  --bg-1: #0e1420;
  --bg-2: #14222a;
  --bg-3: #241a30;

  min-height: 100dvh;
  display: flex;
  justify-content: center;
  color: var(--ink);
  background:
    radial-gradient(120% 80% at 18% 6%, rgba(255, 199, 92, 0.22), transparent 42%),
    radial-gradient(120% 80% at 88% 0%, rgba(73, 215, 209, 0.22), transparent 46%),
    radial-gradient(120% 90% at 50% 108%, rgba(150, 92, 220, 0.2), transparent 55%),
    linear-gradient(160deg, var(--bg-1) 0%, var(--bg-2) 48%, var(--bg-3) 100%);
  background-attachment: fixed;
  font-family:
    -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'SF Pro Display',
    system-ui, 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', Inter, sans-serif;
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
  letter-spacing: -0.01em;
}

/* Adaptive light appearance — Liquid Glass is defined for both modes. */
@media (prefers-color-scheme: light) {
  .planet-app {
    --accent-ink: #3a2a00;
    --ink: #10151d;
    --ink-2: rgba(16, 21, 29, 0.62);
    --ink-3: rgba(16, 21, 29, 0.4);
    --glass-fill: rgba(255, 255, 255, 0.55);
    --glass-fill-2: rgba(255, 255, 255, 0.72);
    --glass-stroke: rgba(255, 255, 255, 0.7);
    --glass-highlight: rgba(255, 255, 255, 0.95);
    --glass-lowlight: rgba(15, 22, 40, 0.06);
    --shadow-1: 0 8px 24px rgba(31, 41, 74, 0.12);
    --shadow-2: 0 22px 48px rgba(31, 41, 74, 0.18);
    --bg-1: #eef2f8;
    --bg-2: #e6f1f2;
    --bg-3: #efe9f6;
  }
}

/* Liquid Glass surface — the signature material. */
@mixin glass($radius: var(--r-card)) {
  border-radius: $radius;
  background: var(--glass-fill);
  backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-sat));
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-sat));
  border: 1px solid var(--glass-stroke);
  box-shadow:
    inset 0 1px 0 var(--glass-highlight),
    inset 0 -1px 1px var(--glass-lowlight),
    var(--shadow-1);
}

/* Interactive glass — presses lift the highlight & fill (the .interactive() analogue). */
@mixin tappable {
  transition:
    transform var(--dur) var(--ease-spring),
    background-color var(--dur) var(--ease-out),
    box-shadow var(--dur) var(--ease-out),
    filter var(--dur) var(--ease-out);
  -webkit-tap-highlight-color: transparent;

  &:active {
    transform: scale(0.955);
  }
}

.app-shell {
  width: min(100%, 440px);
  height: 100dvh;
  min-height: 100dvh;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  isolation: isolate;
  background: transparent;
  padding-top: env(safe-area-inset-top, 0px);

  &.loading {
    cursor: progress;
  }
}

/* iOS large-title header — light chrome, content scrolls beneath. */
.topbar {
  padding: 14px 20px 10px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
  z-index: 5;

  h1 {
    margin: 3px 0 0;
    font-size: 30px;
    line-height: 1.05;
    font-weight: 800;
    letter-spacing: -0.022em;
  }
}

.eyebrow {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--accent);
}

.welcome,
.muted,
.section-title strong,
.energy-meta,
.profile-summary p,
.device-detail span,
.bind-panel strong,
.chat-head strong {
  color: var(--ink-2);
}

.welcome,
.muted {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

/* Prominent glass — the accent-tinted primary control. */
.icon-button,
.chat-input button,
.bind-row button {
  @include tappable;
  width: 46px;
  height: 46px;
  border: 0;
  border-radius: var(--r-control);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: var(--accent-ink);
  background: linear-gradient(150deg, #ffe08a, var(--accent) 55%, var(--accent-2));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.7),
    inset 0 -1px 2px rgba(150, 96, 0, 0.28),
    var(--accent-glow);

  &:active {
    filter: saturate(1.12) brightness(1.04);
  }
}

.content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  padding: 8px 18px calc(112px + env(safe-area-inset-bottom, 0px));

  &::-webkit-scrollbar {
    display: none;
  }
}

.hero-panel {
  @include glass(var(--r-hero));
  min-height: 332px;
  position: relative;
  overflow: hidden;
  padding: 24px;
  margin-top: 4px;
  background:
    linear-gradient(150deg, rgba(73, 215, 209, 0.28), transparent 52%),
    radial-gradient(140% 120% at 100% 100%, rgba(255, 199, 92, 0.22), transparent 60%),
    var(--glass-fill);
}

.hero-copy {
  position: relative;
  z-index: 2;
  max-width: 214px;

  strong {
    display: block;
    margin-top: 10px;
    font-size: 32px;
    line-height: 1.06;
    font-weight: 800;
    letter-spacing: -0.02em;
  }

  p {
    margin: 12px 0 0;
    color: var(--ink-2);
    line-height: 1.62;
  }
}

.avatar-stage {
  position: absolute;
  right: -34px;
  bottom: -26px;
  width: 270px;
  height: 300px;
  display: grid;
  place-items: center;
  touch-action: none;

  img {
    width: 232px;
    height: 290px;
    object-fit: cover;
    object-position: center;
    border-radius: 40px;
    transition: transform 220ms var(--ease-spring);
    filter: drop-shadow(0 24px 40px rgba(0, 0, 0, 0.4));
  }
}

.orbit {
  position: absolute;
  border: 1px solid rgba(105, 226, 224, 0.55);
  border-radius: 50%;
  transform: rotate(-18deg);
  animation: orbit 6s linear infinite;
}

.orbit-a {
  width: 192px;
  height: 54px;
  top: 50px;
}

.orbit-b {
  width: 236px;
  height: 72px;
  top: 104px;
  border-color: rgba(255, 209, 102, 0.42);
  animation-duration: 9s;
}

.energy-panel,
.profile-summary,
.device-strip,
.device-detail,
.bind-panel,
.chat-head {
  @include glass(var(--r-panel));
  margin-top: 14px;
}

.energy-panel {
  padding: 18px;
}

.energy-head,
.energy-meta,
.device-strip,
.section-title,
.bind-row,
.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.energy-head strong {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.energy-track {
  height: 12px;
  margin: 14px 0 10px;
  overflow: hidden;
  border-radius: var(--r-pill);
  background: rgba(0, 0, 0, 0.22);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.3);

  span {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: linear-gradient(90deg, var(--teal), var(--accent) 62%, var(--coral));
    box-shadow: 0 0 14px rgba(255, 199, 92, 0.5);
    transition: width 0.6s var(--ease-spring);
  }
}

.energy-meta {
  font-size: 12px;
}

.quick-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  margin-top: 14px;

  button {
    @include glass(var(--r-control));
    @include tappable;
    min-height: 50px;
    padding: 0 16px;
    text-align: left;
    font-size: 15px;
    font-weight: 600;
    color: var(--ink);
    background:
      linear-gradient(120deg, rgba(255, 122, 114, 0.18), rgba(73, 215, 209, 0.12)),
      var(--glass-fill);
  }
}

.device-strip {
  min-height: 74px;
  padding: 14px 16px;

  strong {
    display: block;
    margin-top: 4px;
    font-weight: 700;
  }
}

.state-pill {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 13px;
  border-radius: var(--r-pill);
  font-size: 12px;
  font-weight: 800;
  border: 1px solid transparent;

  &.online {
    color: #06301f;
    background: linear-gradient(180deg, #8ff0c4, var(--mint));
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6), 0 6px 14px rgba(111, 230, 178, 0.28);
  }

  &.standby {
    color: var(--accent-ink);
    background: linear-gradient(180deg, #ffe08a, var(--accent));
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6), 0 6px 14px rgba(255, 199, 92, 0.28);
  }

  &.offline {
    color: #ffb7b1;
    background: rgba(255, 122, 114, 0.16);
    border-color: rgba(255, 122, 114, 0.32);
  }
}

.section-title {
  margin: 6px 2px 14px;

  > div {
    min-width: 0;
  }

  span {
    display: block;
    font-size: 22px;
    font-weight: 800;
    letter-spacing: -0.02em;
  }

  strong {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    font-weight: 600;
    color: var(--ink-3);
  }
}

.mini-action {
  @include tappable;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border: 0;
  border-radius: var(--r-chip);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--accent-ink);
  background: linear-gradient(150deg, #ffe08a, var(--accent) 60%, var(--accent-2));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.66), var(--accent-glow);

  &.ghost {
    color: var(--ink);
    background: var(--glass-fill-2);
    border: 1px solid var(--glass-stroke);
    box-shadow: inset 0 1px 0 var(--glass-highlight);
    backdrop-filter: blur(var(--glass-blur));
  }
}

.profile-summary {
  min-height: 122px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;

  img {
    width: 86px;
    height: 86px;
    object-fit: cover;
    border-radius: 26px;
    box-shadow: 0 8px 20px rgba(0, 0, 0, 0.28);
  }

  span,
  strong {
    display: block;
  }

  strong {
    margin-top: 4px;
    font-size: 21px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  p {
    margin: 8px 0 0;
    line-height: 1.5;
  }
}

.memory-list {
  display: grid;
  gap: 10px;
  margin-top: 14px;
}

.profile-editor {
  @include glass(var(--r-panel));
  margin-top: 14px;
  padding: 16px;

  label {
    display: block;
    margin-top: 12px;

    > span {
      display: block;
      margin-bottom: 8px;
      color: var(--ink-2);
      font-size: 12px;
      font-weight: 700;
    }
  }

  select,
  textarea {
    width: 100%;
    border: 1px solid var(--glass-stroke);
    border-radius: var(--r-chip);
    color: var(--ink);
    background: rgba(0, 0, 0, 0.2);
    outline: none;
    font-family: inherit;
    transition: border-color var(--dur) var(--ease-out), box-shadow var(--dur) var(--ease-out);

    &:focus {
      border-color: rgba(255, 199, 92, 0.7);
      box-shadow: 0 0 0 3px rgba(255, 199, 92, 0.18);
    }
  }

  select {
    height: 44px;
    padding: 0 12px;
  }

  textarea {
    min-height: 90px;
    resize: vertical;
    padding: 12px;
    line-height: 1.5;
  }
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span,
  strong {
    display: block;
  }

  span {
    color: var(--ink-2);
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    margin-top: 4px;
    font-weight: 700;
  }
}

.save-profile {
  @include tappable;
  width: 100%;
  height: 48px;
  margin-top: 16px;
  border: 0;
  border-radius: var(--r-control);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--accent-ink);
  font-size: 15px;
  font-weight: 800;
  background: linear-gradient(150deg, #ffe08a, var(--accent) 55%, var(--accent-2));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.66), var(--accent-glow);

  &:disabled {
    opacity: 0.55;
  }
}

.memory-item {
  @include glass(var(--r-control));
  @include tappable;
  min-height: 72px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  &:active {
    transform: none;
  }

  > div:first-child {
    min-width: 0;
  }

  span,
  strong {
    display: block;
  }

  span {
    color: var(--ink-2);
    font-size: 12px;
  }

  strong {
    margin-top: 6px;
    line-height: 1.4;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
}

.memory-actions {
  display: inline-flex;
  gap: 6px;
  flex-shrink: 0;

  button {
    @include tappable;
    width: 36px;
    height: 36px;
    border: 0;
    border-radius: var(--r-chip);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: var(--ink-2);
    background: var(--glass-fill-2);
    border: 1px solid var(--glass-stroke);
  }
}

.empty-memory {
  padding: 18px;
  border-radius: var(--r-panel);
  color: var(--ink-2);
  line-height: 1.6;
  background: var(--glass-fill);
  border: 1px dashed var(--glass-stroke);
}

.chat-content {
  display: flex;
  flex-direction: column;
}

.chat-head {
  min-height: 76px;
  padding: 12px 14px;
  flex-shrink: 0;

  img {
    width: 52px;
    height: 52px;
    border-radius: 18px;
    object-fit: cover;
    box-shadow: 0 6px 14px rgba(0, 0, 0, 0.28);
  }

  div {
    flex: 1;
  }

  span,
  strong {
    display: block;
  }

  span {
    font-weight: 700;
    letter-spacing: -0.01em;
  }
}

.chat-messages {
  flex: 1;
  min-height: 320px;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  padding: 16px 2px 12px;

  &::-webkit-scrollbar {
    display: none;
  }
}

.chat-row {
  display: flex;
  margin-bottom: 12px;
  animation: bubble-in 0.32s var(--ease-spring) both;

  &.user {
    justify-content: flex-end;
  }
}

.bubble {
  max-width: 82%;
  padding: 12px 15px;
  border-radius: 22px 22px 22px 7px;
  line-height: 1.58;
  white-space: pre-wrap;
  color: var(--ink);
  background: var(--glass-fill-2);
  backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-sat));
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(var(--glass-sat));
  border: 1px solid var(--glass-stroke);
  box-shadow: inset 0 1px 0 var(--glass-highlight), var(--shadow-1);
}

.chat-row.user .bubble {
  color: var(--accent-ink);
  border: 0;
  border-radius: 22px 22px 7px 22px;
  background: linear-gradient(150deg, #ffe08a, var(--accent) 60%, var(--accent-2));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6), var(--accent-glow);
}

.typing-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-2);
}

.typing {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 20px;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--ink);
    animation: typing 1s ease-in-out infinite;

    &:nth-child(2) {
      animation-delay: 120ms;
    }

    &:nth-child(3) {
      animation-delay: 240ms;
    }
  }
}

.chat-input {
  @include glass(var(--r-pill));
  min-height: 60px;
  padding: 8px 8px 8px 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-top: 8px;

  input {
    flex: 1;
    min-width: 0;
    height: 44px;
    border: 0;
    outline: 0;
    padding: 0 14px;
    font-size: 15px;
    color: var(--ink);
    background: transparent;

    &::placeholder {
      color: var(--ink-3);
    }
  }

  button:disabled {
    opacity: 0.4;
  }
}

.device-list {
  display: grid;
  gap: 10px;
}

.device-list button {
  @include glass(var(--r-control));
  @include tappable;
  min-height: 60px;
  padding: 0 16px;
  display: grid;
  grid-template-columns: 26px 1fr auto;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  text-align: left;

  &.active {
    border-color: rgba(255, 199, 92, 0.5);
    background:
      linear-gradient(120deg, rgba(255, 199, 92, 0.24), rgba(255, 199, 92, 0.1)),
      var(--glass-fill);
    box-shadow: inset 0 1px 0 var(--glass-highlight), var(--accent-glow);
  }

  svg {
    font-size: 20px;
    color: var(--accent);
  }

  em {
    font-style: normal;
    color: var(--ink-2);
    font-size: 12px;
    font-weight: 500;
  }
}

.device-detail {
  padding: 10px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;

  div {
    min-height: 92px;
    padding: 13px;
    border-radius: var(--r-chip);
    background: var(--glass-lowlight);
    border: 1px solid var(--glass-stroke);
  }

  svg {
    font-size: 18px;
    color: var(--accent);
  }

  span,
  strong {
    display: block;
  }

  span {
    margin-top: 10px;
    font-size: 12px;
  }

  strong {
    margin-top: 4px;
    line-height: 1.35;
    font-weight: 600;
    word-break: break-word;
  }
}

.bind-panel {
  padding: 16px;

  span,
  strong {
    display: block;
  }

  strong {
    margin-top: 4px;
    font-size: 13px;
  }
}

.bind-row {
  margin-top: 14px;

  input {
    flex: 1;
    min-width: 0;
    height: 46px;
    border: 1px solid var(--glass-stroke);
    outline: 0;
    border-radius: var(--r-control);
    padding: 0 14px;
    font-size: 15px;
    color: var(--ink);
    background: rgba(0, 0, 0, 0.2);
    transition: border-color var(--dur) var(--ease-out), box-shadow var(--dur) var(--ease-out);

    &::placeholder {
      color: var(--ink-3);
    }

    &:focus {
      border-color: rgba(255, 199, 92, 0.7);
      box-shadow: 0 0 0 3px rgba(255, 199, 92, 0.18);
    }
  }
}

/* Floating Liquid Glass tab bar — content scrolls beneath it. */
.tabbar {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: calc(14px + env(safe-area-inset-bottom, 0px));
  height: 66px;
  padding: 6px;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  align-items: stretch;
  border-radius: 26px;
  background: var(--glass-fill-2);
  backdrop-filter: blur(30px) saturate(var(--glass-sat));
  -webkit-backdrop-filter: blur(30px) saturate(var(--glass-sat));
  border: 1px solid var(--glass-stroke);
  box-shadow:
    inset 0 1px 0 var(--glass-highlight),
    inset 0 -1px 1px var(--glass-lowlight),
    var(--shadow-2);
}

/* The morphing pill that slides to the active tab. */
.tab-indicator {
  position: absolute;
  top: 6px;
  bottom: 6px;
  left: 6px;
  width: calc((100% - 12px) / 5);
  border-radius: 20px;
  background: linear-gradient(150deg, #ffe08a, var(--accent) 58%, var(--accent-2));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7), var(--accent-glow);
  transform: translateX(calc(var(--active-index, 0) * 100%));
  transition: transform var(--dur) var(--ease-spring);
  z-index: 0;
}

.tabbar button {
  position: relative;
  z-index: 1;
  border: 0;
  border-radius: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  color: var(--ink-2);
  background: transparent;
  font-size: 11px;
  font-weight: 600;
  transition: color var(--dur) var(--ease-out), transform 0.2s var(--ease-spring);
  -webkit-tap-highlight-color: transparent;

  svg {
    font-size: 19px;
  }

  &:active {
    transform: scale(0.9);
  }

  &.active {
    color: var(--accent-ink);
    font-weight: 700;
  }
}

/* —— 成长体系 (Growth) —— */
.growth-card {
  @include glass(var(--r-card));
  margin-top: 14px;
  padding: 16px;
}

.growth-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;

  span {
    color: var(--ink-2);
    font-size: 13px;
    font-weight: 700;
  }

  strong {
    font-size: 15px;
    font-weight: 700;
  }
}

.title-icon {
  font-size: 20px;
  color: var(--accent);
}

.stage-card {
  display: flex;
  align-items: center;
  gap: 18px;
}

.stage-ring {
  width: 92px;
  height: 92px;
  flex-shrink: 0;
  border-radius: 50%;
  display: grid;
  place-items: center;
  box-shadow: 0 0 20px rgba(255, 199, 92, 0.25);
}

.stage-ring-hole {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  text-align: center;
  background: radial-gradient(circle at 50% 28%, #1b2942, #0b111d);
  box-shadow: inset 0 2px 6px rgba(0, 0, 0, 0.5);

  strong {
    font-size: 20px;
    line-height: 1;
    font-weight: 800;
    color: #f4f7fb;
  }

  span {
    margin-top: 2px;
    font-size: 11px;
    color: rgba(244, 247, 251, 0.58);
  }
}

.stage-meta {
  min-width: 0;

  .muted {
    font-size: 12px;
  }

  strong {
    display: block;
    margin-top: 2px;
    font-size: 24px;
    font-weight: 800;
    letter-spacing: -0.02em;
  }

  p {
    margin: 8px 0 0;
    color: var(--ink-2);
    line-height: 1.5;
    font-size: 13px;
  }
}

.streak-card {
  display: flex;
  align-items: center;
  gap: 16px;
}

.streak-flame {
  width: 56px;
  height: 56px;
  flex-shrink: 0;
  border-radius: var(--r-control);
  display: grid;
  place-items: center;
  font-size: 26px;
  color: var(--coral);
  background: linear-gradient(135deg, rgba(255, 122, 114, 0.32), rgba(255, 199, 92, 0.22));
  box-shadow: inset 0 1px 0 var(--glass-highlight), 0 6px 16px rgba(255, 122, 114, 0.22);
}

.streak-nums {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;

  div {
    text-align: center;
  }

  strong {
    display: block;
    font-size: 26px;
    font-weight: 800;
    letter-spacing: -0.02em;
  }

  span {
    color: var(--ink-2);
    font-size: 12px;
  }
}

.curve-card {
  .spark {
    width: 100%;
    height: 84px;
    margin: 12px 0 6px;
    display: block;
    overflow: visible;
  }

  .spark-line {
    fill: none;
    stroke: var(--teal);
    stroke-width: 2.5;
    stroke-linejoin: round;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
    filter: drop-shadow(0 2px 6px rgba(73, 215, 209, 0.5));
  }

  .spark-area {
    fill: rgba(73, 215, 209, 0.18);
    stroke: none;
  }
}

.curve-axis {
  display: flex;
  justify-content: space-between;
  color: var(--ink-3);
  font-size: 10px;
}

.report-title {
  margin-top: 18px;
}

.badge-wall {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-top: 4px;
}

.badge-cell {
  @include tappable;
  padding: 12px 6px;
  border-radius: var(--r-control);
  text-align: center;
  background:
    radial-gradient(120% 90% at 50% 0%, rgba(255, 199, 92, 0.16), transparent 70%),
    var(--glass-fill);
  border: 1px solid rgba(255, 199, 92, 0.32);
  box-shadow: inset 0 1px 0 var(--glass-highlight);

  .badge-emoji {
    font-size: 26px;
    line-height: 1.1;
    filter: drop-shadow(0 3px 6px rgba(0, 0, 0, 0.3));
  }

  span {
    display: block;
    margin-top: 6px;
    font-size: 11px;
    color: var(--ink);
    line-height: 1.3;
  }

  &.locked {
    border-color: var(--glass-stroke);
    background: var(--glass-lowlight);
    box-shadow: none;

    .badge-emoji {
      filter: grayscale(1);
      opacity: 0.3;
    }

    span {
      color: var(--ink-3);
    }
  }
}

.report-card {
  .report-energy {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;

    strong {
      font-size: 24px;
      font-weight: 800;
      letter-spacing: -0.02em;
    }
  }

  .report-stats {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    margin-top: 14px;

    div {
      text-align: center;
    }

    strong {
      display: block;
      font-size: 20px;
      font-weight: 800;
      color: var(--accent);
      overflow-wrap: anywhere;
    }

    span {
      color: var(--ink-2);
      font-size: 11px;
    }
  }

  .report-mems {
    margin-top: 14px;
    padding-top: 12px;
    border-top: 1px solid var(--glass-stroke);

    .muted {
      font-size: 12px;
    }

    p {
      margin: 8px 0 0;
      line-height: 1.5;
      overflow-wrap: anywhere;
    }
  }

  .report-quote {
    margin: 14px 0 0;
    padding: 12px 14px;
    border-radius: var(--r-chip);
    line-height: 1.6;
    color: var(--ink);
    background: rgba(73, 215, 209, 0.14);
    border: 1px solid rgba(73, 215, 209, 0.24);
  }
}

.share-btn {
  @include tappable;
  width: 100%;
  height: 48px;
  margin-top: 14px;
  border: 0;
  border-radius: var(--r-control);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--accent-ink);
  font-size: 15px;
  font-weight: 800;
  background: linear-gradient(150deg, #ffe08a, var(--accent) 55%, var(--accent-2));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.66), var(--accent-glow);
}

.starmap-card {
  @include glass(var(--r-card));
  margin-top: 14px;
  padding: 16px;
  background:
    radial-gradient(circle at 30% 20%, rgba(73, 215, 209, 0.14), transparent 42%),
    var(--glass-fill);
}

.starmap {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px 6px;
  margin-top: 14px;
}

.star {
  text-align: center;
  color: var(--ink-3);
  transition: transform var(--dur) var(--ease-spring);

  svg {
    font-size: 22px;
  }

  em {
    display: block;
    margin-top: 5px;
    font-style: normal;
    font-size: 10px;
    line-height: 1.3;
    color: var(--ink-3);
  }

  &.filled {
    color: var(--accent);
    text-shadow: 0 0 14px rgba(255, 199, 92, 0.65);

    em {
      color: var(--ink);
    }
  }

  &.locked {
    opacity: 0.32;
  }
}

.share-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 24px;
  background: rgba(6, 10, 16, 0.6);
  backdrop-filter: blur(20px) saturate(140%);
  -webkit-backdrop-filter: blur(20px) saturate(140%);
  animation: fade-in 0.24s var(--ease-out) both;
}

.share-modal {
  width: 100%;
  max-width: 340px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  animation: bubble-in 0.36s var(--ease-spring) both;

  img {
    width: 100%;
    border-radius: 22px;
    box-shadow: var(--shadow-2);
  }
}

.share-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;

  button {
    @include tappable;
    height: 48px;
    border: 0;
    border-radius: var(--r-control);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 800;
    color: var(--accent-ink);
    background: linear-gradient(150deg, #ffe08a, var(--accent) 55%, var(--accent-2));
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.66), var(--accent-glow);

    &.ghost {
      color: var(--ink);
      background: var(--glass-fill-2);
      border: 1px solid var(--glass-stroke);
      box-shadow: inset 0 1px 0 var(--glass-highlight);
      backdrop-filter: blur(var(--glass-blur));
    }
  }
}

@keyframes orbit {
  from {
    transform: rotate(-18deg);
  }

  to {
    transform: rotate(342deg);
  }
}

@keyframes typing {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }

  50% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

@keyframes bubble-in {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }

  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@keyframes fade-in {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@media (max-width: 380px) {
  .topbar {
    padding-inline: 16px;
  }

  .content {
    padding-inline: 14px;
  }

  .avatar-stage {
    right: -70px;
  }
}

/* Respect the system reduce-motion setting. */
@media (prefers-reduced-motion: reduce) {
  .planet-app *,
  .planet-app *::before,
  .planet-app *::after {
    animation-duration: 0.001ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.001ms !important;
  }
}
</style>
