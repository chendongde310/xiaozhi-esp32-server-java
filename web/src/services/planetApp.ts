import { http } from './request'
import { useUserStore } from '@/store/user'
import type { ChatToken } from '@/types/chat'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

export interface PlanetAppRole {
  roleId: number
  roleName?: string
  roleDesc?: string
  avatar?: string
  isDefault?: string
}

export interface PlanetAppDevice {
  agentLinkId?: number
  playerUserId?: number
  relationState?: string
  linkedAt?: string
  deviceId: string
  sessionId?: string
  deviceName?: string
  roleId?: number
  roleName?: string
  state?: string | number
  totalMessage?: number
  wifiName?: string
  ip?: string
  chipModelName?: string
  type?: string
  version?: string
  location?: string
  createTime?: string
  updateTime?: string
}

export interface PlanetTask {
  content?: string | null
  done?: boolean
  date?: string | null
}

/** 羁绊阶段 */
export interface StageView {
  level: number
  name: string
  key: string
  progress: number
  nextName?: string | null
  nextDays?: number | null
}

/** 任务连击 */
export interface StreakView {
  current: number
  best: number
  repairLeft: number
}

/** 星球徽章（含未点亮） */
export interface BadgeView {
  key: string
  label: string
  desc: string
  icon: string
  category: string
  earned: boolean
  earnDate?: string | null
}

/** 记忆星图节点 */
export interface MemoryStar {
  key: string
  label: string
  minStage: number
  unlocked: boolean
  filled: boolean
  value?: string | null
}

/** 能量曲线点 */
export interface EnergyPoint {
  date: string
  energy: number
}

export interface PlanetProfileView {
  deviceId: string
  roleId: number
  energy: number
  energyLevel: 'high' | 'mid' | 'low'
  companionDays: number
  currentChannel?: string
  currentChannelLabel?: string
  firstConnected?: number
  profile: Record<string, string>
  todayTask?: PlanetTask
  // —— 成长体系（P1+P2）——
  stage?: StageView
  streak?: StreakView
  badges?: BadgeView[]
  memoryStarMap?: MemoryStar[]
  energyCurve?: EnergyPoint[]
}

/** 星球周报 */
export interface WeeklyReportView {
  periodStart: string
  periodEnd: string
  energyStart: number
  energyEnd: number
  energyAvg: number
  energyMin: number
  energyMax: number
  energyCurve: EnergyPoint[]
  tasksDone: number
  streakDays: number
  bestStreak?: number
  companionDays: number
  stage: { level: number; name: string }
  badgesEarned: number
  newBadges: Array<{ key: string; label: string; icon: string }>
  newMemories: Array<{ label: string; value: string }>
  highlight: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  pageNo: number
  pageSize: number
}

export function queryAppDevices() {
  return queryAppAgents()
}

export function queryAppAgents() {
  return http.get<PageResult<PlanetAppDevice>>('/app/agents')
}

export function getAppDevice(deviceId: string) {
  return http.get<PlanetAppDevice>(`/app/devices/${deviceId}`)
}

export function bindAppDevice(code: string) {
  return linkAppAgent(code)
}

export function linkAppAgent(code: string) {
  return http.post<PlanetAppDevice>('/app/agents/link', { code })
}

export function queryAppRoles() {
  return http.get<PlanetAppRole[]>('/app/roles')
}

export function getAppPlanet(deviceId: string, roleId: number) {
  return http.get<PlanetProfileView>('/app/planet', { deviceId, roleId })
}

/** 本周星球周报（滚动 7 天） */
export function getAppReport(deviceId: string, roleId: number) {
  return http.get<WeeklyReportView>('/app/report', { deviceId, roleId })
}

/** 历史星球周报 */
export function getAppReports(deviceId: string, roleId: number) {
  return http.get<WeeklyReportView[]>('/app/reports', { deviceId, roleId })
}

export function saveAppPlanetProfile(payload: {
  deviceId: string
  roleId: number
  field: string
  value: string
}) {
  return http.post('/app/planet/profile', payload)
}

export function deleteAppPlanetProfile(deviceId: string, roleId: number, field: string) {
  return http.delete<{ removed: number }>('/app/planet/profile', { deviceId, roleId, field })
}

export function openAppChatSession(deviceId: string, roleId: number, sessionId?: string) {
  return http.post<{ sessionId: string }>('/app/chat/open', null, {
    params: sessionId ? { deviceId, roleId, sessionId } : { deviceId, roleId },
  })
}

export function closeAppChatSession(sessionId: string) {
  return http.post('/app/chat/close', null, {
    params: { sessionId },
  })
}

export async function* appChatStream(
  sessionId: string,
  text: string,
  signal?: AbortSignal
): AsyncGenerator<ChatToken> {
  const userStore = useUserStore()
  const url = `${BASE_URL}/app/chat/stream?sessionId=${encodeURIComponent(sessionId)}&text=${encodeURIComponent(text)}`

  const response = await fetch(url, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      Authorization: userStore.token ? `Bearer ${userStore.token}` : '',
    },
    signal,
  })

  if (!response.ok) {
    throw new Error(`聊天连接失败: ${response.status}`)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('无法读取聊天响应')
  }

  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (!data) continue
        try {
          yield JSON.parse(data) as ChatToken
        } catch {
          yield { type: 'content', text: data } as ChatToken
        }
      }
    }

    if (buffer.startsWith('data:')) {
      const data = buffer.slice(5).trim()
      if (data) {
        try {
          yield JSON.parse(data) as ChatToken
        } catch {
          yield { type: 'content', text: data } as ChatToken
        }
      }
    }
  } finally {
    reader.releaseLock()
  }
}
