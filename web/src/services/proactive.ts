import { http } from './request'
import api from './api'

/** 待命主动搭话配置（可控变量 + 开关，默认关闭） */
export interface ProactiveConfig {
  deviceId: string
  roleId: number
  /** 总开关：0-关 1-开 */
  enabled: number
  /** 是否允许 LLM 增强开场：0-仅脚本 1-脚本为主+LLM增强 */
  allowLlm: number
  /** 每日主动搭话次数上限 */
  dailyLimit: number
  /** 两次主动之间的最小冷却分钟数 */
  cooldownMinutes: number
  /** 需连续待命多少秒才可主动 */
  minIdleSeconds: number
  /** 活跃时段开始 HH:mm(:ss) */
  activeStart: string
  /** 活跃时段结束 */
  activeEnd: string
  /** 硬静默时段开始（绝不主动） */
  quietStart: string
  /** 硬静默时段结束（跨零点） */
  quietEnd: string
}

/** GET /api/proactive/config?deviceId=&roleId=（未配置时返回默认预览，enabled=0） */
export function getProactiveConfig(deviceId: string, roleId: number) {
  return http.get<ProactiveConfig>(api.proactive.config, { deviceId, roleId })
}

/** POST /api/proactive/config：仅更新提供的字段 */
export function saveProactiveConfig(payload: Partial<ProactiveConfig> & { deviceId: string; roleId: number }) {
  return http.post<ProactiveConfig>(api.proactive.config, payload)
}

/** POST /api/proactive/toggle：仅切换总开关 */
export function toggleProactive(deviceId: string, roleId: number, enabled: boolean) {
  return http.post<{ enabled: number }>(api.proactive.toggle, { deviceId, roleId, enabled })
}
