import { http } from './request'
import api from './api'

/** 智能体（设备×角色）状态摘要，用于选择器 */
export interface AgentSummary {
  deviceId: string
  roleId: number
  deviceName?: string
  roleName?: string | null
  energy: number
  companionDays: number
  currentChannel: string
  currentChannelLabel: string
}

export interface TodayTask {
  content: string | null
  done: boolean
  date?: string | null
}

export interface StageView {
  level: number
  name: string
  key: string
  progress: number
  nextName?: string | null
  nextDays?: number | null
}

export interface StreakView {
  current: number
  best: number
  repairLeft: number
}

export interface BadgeView {
  key: string
  label: string
  desc: string
  icon: string
  category: string
  earned: boolean
  earnDate?: string | null
}

export interface MemoryStar {
  key: string
  label: string
  minStage: number
  unlocked: boolean
  filled: boolean
  value?: string | null
}

export interface EnergyPoint {
  date: string
  energy: number
}

/** 快乐星球档案 + 运行状态组合视图 */
export interface AgentProfileView {
  deviceId: string
  roleId: number
  energy: number
  energyLevel: 'high' | 'mid' | 'low'
  companionDays: number
  currentChannel: string
  currentChannelLabel: string
  firstConnected: number
  profile: Record<string, string>
  todayTask: TodayTask
  // —— 成长体系（P1+P2）——
  stage?: StageView
  streak?: StreakView
  badges?: BadgeView[]
  memoryStarMap?: MemoryStar[]
  energyCurve?: EnergyPoint[]
}

export interface PlayerCodeView {
  code: string
  deviceId: string
  roleId: number
  roleName?: string
  expiresInSeconds: number
}

/** GET /api/happyplanet/agents */
export function getAgents() {
  return http.get<AgentSummary[]>(api.happyPlanet.agents)
}

/** GET /api/happyplanet/profile?deviceId=&roleId= */
export function getAgentProfile(deviceId: string, roleId: number) {
  return http.get<AgentProfileView>(api.happyPlanet.profile, { deviceId, roleId })
}

/** GET /api/happyplanet/player-code?deviceId=&roleId= */
export function generatePlayerCode(deviceId: string, roleId: number) {
  return http.get<PlayerCodeView>(api.happyPlanet.playerCode, { deviceId, roleId })
}

/** POST /api/happyplanet/demo-seed?deviceId=&roleId= 一键填充演示数据 */
export function seedDemoData(deviceId: string, roleId: number) {
  return http.post<AgentProfileView>(api.happyPlanet.demoSeed, null, { params: { deviceId, roleId } })
}

/** POST /api/happyplanet/profile */
export function saveAgentProfile(payload: {
  deviceId: string
  roleId: number
  field: string
  value: string
}) {
  return http.post(api.happyPlanet.profile, payload)
}

/** DELETE /api/happyplanet/profile?deviceId=&roleId=&field= (field 为空清空全部) */
export function deleteAgentProfile(deviceId: string, roleId: number, field?: string) {
  return http.delete(api.happyPlanet.profile, { deviceId, roleId, field })
}
