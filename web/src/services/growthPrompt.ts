import { http } from './request'
import api from './api'

/** 成长状态提示词槽位（管理端视图） */
export interface GrowthSlot {
  slotKey: string
  /** 分组枚举名（FRAME/ENERGY/STAGE/...） */
  category: string
  /** 分组中文名 */
  categoryLabel: string
  label: string
  desc: string
  vars: string[]
  content: string
  enabled: boolean
  /** 当前文案来源：role=角色自定义 global=全局自定义 default=系统默认 */
  source: 'role' | 'global' | 'default'
}

/** 查询某角色（roleId=0 为全局默认）的全部成长提示词槽位 */
export function queryGrowthSlots(roleId: number) {
  return http.get<GrowthSlot[]>(api.growthPrompt.query, { roleId })
}

/** 保存某角色某槽位的覆盖文案与启用状态 */
export function saveGrowthSlot(data: {
  roleId: number
  slotKey: string
  content: string
  enabled: boolean
}) {
  return http.put(api.growthPrompt.save, data)
}

/** 重置某角色某槽位，回退到全局或系统默认 */
export function resetGrowthSlot(roleId: number, slotKey: string) {
  return http.delete(api.growthPrompt.reset, { roleId, slotKey })
}
