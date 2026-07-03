<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { message } from 'ant-design-vue'
import { SmileOutlined, PlusOutlined, LinkOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import {
  getAgents,
  getAgentProfile,
  generatePlayerCode,
  saveAgentProfile,
  deleteAgentProfile,
  seedDemoData,
  type AgentSummary,
  type AgentProfileView,
  type PlayerCodeView,
} from '@/services/agentProfile'
import {
  getProactiveConfig,
  toggleProactive,
  saveProactiveConfig,
  type ProactiveConfig,
} from '@/services/proactive'

const { t } = useI18n()

/** 档案字段白名单（需与后端 AgentProfileService.PROFILE_LABELS 一致） */
const FIELD_KEYS = [
  'nickname',
  'preferredCall',
  'storyType',
  'storyLength',
  'companionStyle',
  'activeTime',
  'importantDate',
] as const

const loading = ref(false)
const agents = ref<AgentSummary[]>([])
const selectedKey = ref<string | undefined>(undefined)
const profileView = ref<AgentProfileView | null>(null)
const playerCodeVisible = ref(false)
const playerCodeLoading = ref(false)
const playerCode = ref<PlayerCodeView | null>(null)
const seeding = ref(false)

// 待命主动搭话
const proactiveConfig = ref<ProactiveConfig | null>(null)
const proactiveLoading = ref(false)
const proactiveSaving = ref(false)

/** 各可控变量的默认值（与后端 ProactiveConfigService 默认常量对齐，仅用于“恢复默认”）。 */
const PROACTIVE_DEFAULTS = {
  allowLlm: 1,
  dailyLimit: 3,
  cooldownMinutes: 90,
  minIdleSeconds: 600,
  activeStart: '09:00',
  activeEnd: '21:00',
  quietStart: '22:00',
  quietEnd: '08:00',
}

/** 可编辑的策略参数草稿（不含 enabled，开关仍由上方 a-switch 单独管理）。 */
const proactiveForm = reactive({ ...PROACTIVE_DEFAULTS })

/** 后端时间可能带秒（HH:mm:ss），时间选择器只认 HH:mm，统一裁剪。 */
const toHm = (s?: string | null) => (s ? s.slice(0, 5) : '')

/**
 * 活跃 / 静默时段的区间选择器桥接。a-time-range-picker 的 value 类型基于 Dayjs，
 * 但配了 value-format=HH:mm 后运行时收发的是字符串，二者存在类型缺口——故此处用宽松类型，
 * 真实数据仍以 proactiveForm 中的字符串强类型保存。
 */
const activeRange = computed<any>({
  get: () => [proactiveForm.activeStart, proactiveForm.activeEnd],
  set: (v: string[]) => {
    proactiveForm.activeStart = v?.[0] ?? ''
    proactiveForm.activeEnd = v?.[1] ?? ''
  },
})
const quietRange = computed<any>({
  get: () => [proactiveForm.quietStart, proactiveForm.quietEnd],
  set: (v: string[]) => {
    proactiveForm.quietStart = v?.[0] ?? ''
    proactiveForm.quietEnd = v?.[1] ?? ''
  },
})

/** 用服务端返回的配置回填草稿（时间裁剪为 HH:mm）。 */
function hydrateProactiveForm(cfg: ProactiveConfig) {
  proactiveForm.allowLlm = cfg.allowLlm ?? PROACTIVE_DEFAULTS.allowLlm
  proactiveForm.dailyLimit = cfg.dailyLimit ?? PROACTIVE_DEFAULTS.dailyLimit
  proactiveForm.cooldownMinutes = cfg.cooldownMinutes ?? PROACTIVE_DEFAULTS.cooldownMinutes
  proactiveForm.minIdleSeconds = cfg.minIdleSeconds ?? PROACTIVE_DEFAULTS.minIdleSeconds
  proactiveForm.activeStart = toHm(cfg.activeStart) || PROACTIVE_DEFAULTS.activeStart
  proactiveForm.activeEnd = toHm(cfg.activeEnd) || PROACTIVE_DEFAULTS.activeEnd
  proactiveForm.quietStart = toHm(cfg.quietStart) || PROACTIVE_DEFAULTS.quietStart
  proactiveForm.quietEnd = toHm(cfg.quietEnd) || PROACTIVE_DEFAULTS.quietEnd
}

/** 最短待命时长以分钟展示，保存时换算回秒。 */
const minIdleMinutes = computed<number>({
  get: () => Math.round((proactiveForm.minIdleSeconds ?? 0) / 60),
  set: (v) => {
    proactiveForm.minIdleSeconds = Math.max(0, Math.round((v ?? 0) * 60))
  },
})

const selectedAgent = computed(() =>
  agents.value.find((a) => `${a.deviceId}::${a.roleId}` === selectedKey.value),
)

const agentOptions = computed(() =>
  agents.value.map((a) => ({
    value: `${a.deviceId}::${a.roleId}`,
    label: `${a.deviceName || a.deviceId}${a.roleName ? ' · ' + a.roleName : ''}`,
  })),
)

const fieldLabel = (key: string) => {
  const k = `happyPlanet.fields.${key}`
  const label = t(k)
  return label === k ? key : label
}

const profileRows = computed(() => {
  const p = profileView.value?.profile ?? {}
  return Object.keys(p).map((key) => ({ key, label: fieldLabel(key), value: p[key] }))
})

const profileColumns = [
  { title: t('happyPlanet.field'), dataIndex: 'label', width: '35%' },
  { title: t('happyPlanet.value'), dataIndex: 'value' },
  { title: '', key: 'action', width: 140, align: 'center' as const },
]

const energyStroke = computed(() => {
  const level = profileView.value?.energyLevel
  if (level === 'low') return '#ff7875'
  if (level === 'high') return '#52c41a'
  return '#1890ff'
})

const earnedBadges = computed(() => (profileView.value?.badges || []).filter((b) => b.earned))
const stageInfo = computed(() => profileView.value?.stage)
const streakInfo = computed(() => profileView.value?.streak)

async function fetchAgents() {
  loading.value = true
  try {
    const res = await getAgents()
    if (res.code === 200) {
      agents.value = res.data || []
      if (agents.value.length && !selectedAgent.value) {
        const firstAgent = agents.value[0]
        if (firstAgent) {
          selectedKey.value = `${firstAgent.deviceId}::${firstAgent.roleId}`
        }
      }
    } else {
      message.error(res.message || t('common.loadDataFailed'))
    }
  } catch (e) {
    console.error('加载智能体列表失败:', e)
  } finally {
    loading.value = false
  }
}

async function fetchProfile() {
  const agent = selectedAgent.value
  if (!agent) {
    profileView.value = null
    return
  }
  loading.value = true
  try {
    const res = await getAgentProfile(agent.deviceId, agent.roleId)
    if (res.code === 200) {
      profileView.value = res.data
    } else {
      message.error(res.message || t('common.loadDataFailed'))
    }
  } catch (e) {
    console.error('加载快乐星球档案失败:', e)
  } finally {
    loading.value = false
  }
}

async function fetchProactive() {
  const agent = selectedAgent.value
  if (!agent) {
    proactiveConfig.value = null
    return
  }
  try {
    const res = await getProactiveConfig(agent.deviceId, agent.roleId)
    if (res.code === 200) {
      proactiveConfig.value = res.data
      if (res.data) hydrateProactiveForm(res.data)
    }
  } catch (e) {
    console.error('加载主动搭话配置失败:', e)
  }
}

/** 保存全部策略参数（不含总开关）。校验：时段两端不可相同。 */
async function saveProactive() {
  const agent = selectedAgent.value
  if (!agent) return
  if (proactiveForm.activeStart === proactiveForm.activeEnd) {
    message.warning('活跃时段的开始与结束不能相同')
    return
  }
  if (proactiveForm.quietStart === proactiveForm.quietEnd) {
    message.warning('静默时段的开始与结束不能相同')
    return
  }
  proactiveSaving.value = true
  try {
    const res = await saveProactiveConfig({
      deviceId: agent.deviceId,
      roleId: agent.roleId,
      allowLlm: proactiveForm.allowLlm,
      dailyLimit: proactiveForm.dailyLimit,
      cooldownMinutes: proactiveForm.cooldownMinutes,
      minIdleSeconds: proactiveForm.minIdleSeconds,
      activeStart: proactiveForm.activeStart,
      activeEnd: proactiveForm.activeEnd,
      quietStart: proactiveForm.quietStart,
      quietEnd: proactiveForm.quietEnd,
    })
    if (res.code === 200) {
      if (res.data) {
        proactiveConfig.value = res.data
        hydrateProactiveForm(res.data)
      }
      message.success('主动搭话设置已保存')
    } else {
      message.error(res.message || '保存失败')
    }
  } catch (e) {
    console.error('保存主动搭话配置失败:', e)
    message.error('保存失败')
  } finally {
    proactiveSaving.value = false
  }
}

/** 把草稿恢复为默认值（仅前端，需再点“保存设置”才写库）。 */
function resetProactiveDefaults() {
  Object.assign(proactiveForm, PROACTIVE_DEFAULTS)
  message.info('已恢复为默认值，点“保存设置”后生效')
}

async function onToggleProactive(checked: boolean) {
  const agent = selectedAgent.value
  if (!agent) return
  proactiveLoading.value = true
  try {
    const res = await toggleProactive(agent.deviceId, agent.roleId, checked)
    if (res.code === 200) {
      if (proactiveConfig.value) proactiveConfig.value.enabled = checked ? 1 : 0
      message.success(checked ? '已开启主动搭话' : '已关闭主动搭话')
    } else {
      message.error(res.message || '操作失败')
    }
  } catch (e) {
    console.error('切换主动搭话失败:', e)
    message.error('操作失败')
  } finally {
    proactiveLoading.value = false
  }
}

watch(selectedKey, () => {
  fetchProfile()
  fetchProactive()
})

// —— 新增 / 编辑 ——
const editVisible = ref(false)
const editing = ref(false)
const submitLoading = ref(false)
const formData = reactive<{ field: string; value: string }>({ field: '', value: '' })

function openAdd() {
  editing.value = false
  formData.field = FIELD_KEYS.find((k) => !(k in (profileView.value?.profile ?? {}))) || FIELD_KEYS[0]
  formData.value = ''
  editVisible.value = true
}

function openEdit(row: { key: string; value: string }) {
  editing.value = true
  formData.field = row.key
  formData.value = row.value
  editVisible.value = true
}

async function handleSubmit() {
  const agent = selectedAgent.value
  if (!agent) return
  if (!formData.field || !formData.value.trim()) {
    message.warning(t('happyPlanet.inputValue'))
    return
  }
  submitLoading.value = true
  try {
    const res = await saveAgentProfile({
      deviceId: agent.deviceId,
      roleId: agent.roleId,
      field: formData.field,
      value: formData.value.trim(),
    })
    if (res.code === 200) {
      message.success(t('common.saveSuccess'))
      editVisible.value = false
      await fetchProfile()
    } else {
      message.error(res.message || '操作失败')
    }
  } catch (e) {
    console.error('保存档案失败:', e)
  } finally {
    submitLoading.value = false
  }
}

async function handleDelete(key: string) {
  const agent = selectedAgent.value
  if (!agent) return
  try {
    const res = await deleteAgentProfile(agent.deviceId, agent.roleId, key)
    if (res.code === 200) {
      message.success(t('common.deleteSuccess'))
      await fetchProfile()
    } else {
      message.error(res.message || '操作失败')
    }
  } catch (e) {
    console.error('删除档案项失败:', e)
  }
}

async function handleClear() {
  const agent = selectedAgent.value
  if (!agent) return
  try {
    const res = await deleteAgentProfile(agent.deviceId, agent.roleId)
    if (res.code === 200) {
      message.success(t('common.deleteSuccess'))
      await fetchProfile()
    } else {
      message.error(res.message || '操作失败')
    }
  } catch (e) {
    console.error('清空档案失败:', e)
  }
}

async function handleSeedDemo() {
  const agent = selectedAgent.value
  if (!agent) return
  seeding.value = true
  try {
    const res = await seedDemoData(agent.deviceId, agent.roleId)
    if (res.code === 200) {
      message.success('演示数据已填充')
      await fetchProfile()
    } else {
      message.error(res.message || t('common.operationFailed'))
    }
  } catch (e) {
    console.error('填充演示数据失败:', e)
  } finally {
    seeding.value = false
  }
}

async function handleGeneratePlayerCode() {
  const agent = selectedAgent.value
  if (!agent) return
  playerCodeLoading.value = true
  try {
    const res = await generatePlayerCode(agent.deviceId, agent.roleId)
    if (res.code === 200) {
      playerCode.value = res.data
      playerCodeVisible.value = true
    } else {
      message.error(res.message || '操作失败')
    }
  } catch (e) {
    console.error('生成玩家星球码失败:', e)
  } finally {
    playerCodeLoading.value = false
  }
}

onMounted(fetchAgents)
</script>

<template>
  <div class="happy-planet-view">
    <a-card :bordered="false" style="margin-bottom: 16px">
      <template #title>
        <a-space>
          <SmileOutlined style="color: #faad14" />
          <span>{{ t('happyPlanet.title') }}</span>
        </a-space>
      </template>
      <template #extra>
        <a-space>
          <a-button
            size="small"
            type="primary"
            ghost
            :disabled="!selectedAgent"
            :loading="seeding"
            @click="handleSeedDemo"
          >
            <template #icon><ThunderboltOutlined /></template>
            填充演示数据
          </a-button>
          <a-button
            size="small"
            :disabled="!selectedAgent"
            :loading="playerCodeLoading"
            @click="handleGeneratePlayerCode"
          >
            <template #icon><LinkOutlined /></template>
            生成星球码
          </a-button>
          <a-select
            v-model:value="selectedKey"
            :options="agentOptions"
            :placeholder="t('happyPlanet.selectAgent')"
            style="min-width: 240px"
            :loading="loading"
          />
        </a-space>
      </template>
      <div class="subtitle">{{ t('happyPlanet.subtitle') }}</div>
    </a-card>

    <a-empty v-if="!agents.length && !loading" :description="t('happyPlanet.noAgents')" />

    <template v-else-if="profileView">
      <!-- 陪伴状态 -->
      <a-card :bordered="false" :title="t('happyPlanet.stateTitle')" style="margin-bottom: 16px">
        <a-row :gutter="24">
          <a-col :xs="24" :md="10">
            <div class="metric-label">
              {{ t('happyPlanet.energy') }}
              <a-tag :bordered="false" style="margin-left: 8px">
                {{ t(`happyPlanet.energyLevel.${profileView.energyLevel}`) }}
              </a-tag>
            </div>
            <a-progress :percent="profileView.energy" :stroke-color="energyStroke" />
          </a-col>
          <a-col :xs="12" :md="7">
            <a-statistic
              :title="t('happyPlanet.companionDays')"
              :value="profileView.companionDays"
              :suffix="t('happyPlanet.days')"
            />
          </a-col>
          <a-col :xs="12" :md="7">
            <div class="metric-label">{{ t('happyPlanet.currentChannel') }}</div>
            <a-tag color="purple" style="font-size: 14px; padding: 4px 12px">
              {{ profileView.currentChannelLabel }}
            </a-tag>
          </a-col>
        </a-row>
      </a-card>

      <!-- 成长体系：羁绊阶段 / 连击 / 徽章 -->
      <a-card :bordered="false" :title="t('happyPlanet.growthTitle')" style="margin-bottom: 16px">
        <a-row :gutter="24" align="middle">
          <a-col :xs="24" :md="9">
            <div class="metric-label">
              {{ t('happyPlanet.stage') }}
              <a-tag color="gold" style="margin-left: 8px">{{ stageInfo?.name || '初识' }}</a-tag>
            </div>
            <a-progress :percent="stageInfo?.progress ?? 0" size="small" :stroke-color="'#faad14'" />
            <div class="metric-hint" v-if="stageInfo?.nextName">
              {{ t('happyPlanet.stageNext', { name: stageInfo.nextName }) }}
            </div>
          </a-col>
          <a-col :xs="8" :md="5">
            <a-statistic :title="t('happyPlanet.streak')" :value="streakInfo?.current ?? 0" :suffix="t('happyPlanet.days')" />
          </a-col>
          <a-col :xs="8" :md="5">
            <a-statistic :title="t('happyPlanet.bestStreak')" :value="streakInfo?.best ?? 0" :suffix="t('happyPlanet.days')" />
          </a-col>
          <a-col :xs="8" :md="5">
            <a-statistic :title="t('happyPlanet.repairCards')" :value="streakInfo?.repairLeft ?? 0" />
          </a-col>
        </a-row>
        <a-divider style="margin: 16px 0 12px" />
        <div class="metric-label">
          {{ t('happyPlanet.badges') }}
          <span style="margin-left: 8px; color: rgba(0, 0, 0, 0.45)">
            {{ earnedBadges.length }} / {{ (profileView.badges || []).length }}
          </span>
        </div>
        <a-space wrap v-if="earnedBadges.length">
          <a-tag v-for="b in earnedBadges" :key="b.key" color="processing" style="padding: 4px 10px">
            {{ b.icon }} {{ b.label }}
          </a-tag>
        </a-space>
        <a-empty v-else :image="undefined" :description="t('happyPlanet.badgeEmpty')" />
      </a-card>

      <!-- 今日星球任务 -->
      <a-card :bordered="false" :title="t('happyPlanet.todayTask')" style="margin-bottom: 16px">
        <template v-if="profileView.todayTask && profileView.todayTask.content">
          <a-space>
            <a-tag :color="profileView.todayTask.done ? 'green' : 'blue'">
              {{ profileView.todayTask.done ? t('happyPlanet.taskDone') : t('happyPlanet.taskTodo') }}
            </a-tag>
            <span>{{ profileView.todayTask.content }}</span>
          </a-space>
        </template>
        <a-empty v-else :image="undefined" :description="t('happyPlanet.taskNone')" />
      </a-card>

      <!-- 待命主动搭话 -->
      <a-card :bordered="false" title="待命主动搭话" style="margin-bottom: 16px">
        <template #extra>
          <a-space>
            <a-button size="small" :disabled="!selectedAgent" @click="resetProactiveDefaults">
              恢复默认
            </a-button>
            <a-button
              size="small"
              type="primary"
              :disabled="!selectedAgent"
              :loading="proactiveSaving"
              @click="saveProactive"
            >
              保存设置
            </a-button>
          </a-space>
        </template>

        <a-space style="margin-bottom: 4px">
          <a-switch
            :checked="proactiveConfig?.enabled === 1"
            :loading="proactiveLoading"
            @change="onToggleProactive"
          />
          <span>{{ proactiveConfig?.enabled === 1 ? '已开启（设备待命时会主动找用户）' : '已关闭' }}</span>
        </a-space>
        <div class="metric-hint" style="margin-bottom: 12px">
          下面的参数无论开关是否开启都可预先调整，保存后立即生效；总开关关闭时设备不会主动搭话。
        </div>

        <a-divider style="margin: 4px 0 16px" />

        <a-form layout="vertical" :model="proactiveForm">
          <a-row :gutter="16">
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="活跃时段（仅此段内考虑主动）">
                <a-time-range-picker
                  v-model:value="activeRange"
                  format="HH:mm"
                  value-format="HH:mm"
                  :order="false"
                  :allow-clear="false"
                  :minute-step="5"
                  style="width: 100%"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="静默时段（绝不打扰，可跨零点）">
                <a-time-range-picker
                  v-model:value="quietRange"
                  format="HH:mm"
                  value-format="HH:mm"
                  :order="false"
                  :allow-clear="false"
                  :minute-step="5"
                  style="width: 100%"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="每日次数上限">
                <a-input-number
                  v-model:value="proactiveForm.dailyLimit"
                  :min="0"
                  :max="50"
                  :precision="0"
                  addon-after="次 / 天"
                  style="width: 100%"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="冷却时间（两次主动之间的最短间隔）">
                <a-input-number
                  v-model:value="proactiveForm.cooldownMinutes"
                  :min="0"
                  :max="1440"
                  :step="10"
                  :precision="0"
                  addon-after="分钟"
                  style="width: 100%"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="最短待命时长（连续待命多久才可主动）">
                <a-input-number
                  v-model:value="minIdleMinutes"
                  :min="0"
                  :max="180"
                  :precision="0"
                  addon-after="分钟"
                  style="width: 100%"
                />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12" :md="8">
              <a-form-item label="LLM 增强开场（关闭则只用固定脚本台词）">
                <a-switch
                  :checked="proactiveForm.allowLlm === 1"
                  checked-children="增强"
                  un-checked-children="脚本"
                  @change="(v: boolean) => (proactiveForm.allowLlm = v ? 1 : 0)"
                />
              </a-form-item>
            </a-col>
          </a-row>
        </a-form>

        <div class="metric-hint" v-if="proactiveConfig">
          当前生效：活跃 {{ toHm(proactiveConfig.activeStart) }}–{{ toHm(proactiveConfig.activeEnd) }}，静默
          {{ toHm(proactiveConfig.quietStart) }}–{{ toHm(proactiveConfig.quietEnd) }}，每日最多
          {{ proactiveConfig.dailyLimit }} 次，冷却 {{ proactiveConfig.cooldownMinutes }} 分钟，最短待命
          {{ Math.round((proactiveConfig.minIdleSeconds ?? 0) / 60) }} 分钟。
        </div>
      </a-card>

      <!-- 快乐星球档案 -->
      <a-card :bordered="false">
        <template #title>{{ t('happyPlanet.profileTitle') }}</template>
        <template #extra>
          <a-space>
            <a-button type="primary" size="small" @click="openAdd">
              <template #icon><PlusOutlined /></template>
              {{ t('happyPlanet.addField') }}
            </a-button>
            <a-popconfirm
              :title="t('happyPlanet.confirmClear')"
              @confirm="handleClear"
              v-if="profileRows.length"
            >
              <a-button danger size="small">{{ t('happyPlanet.clearAll') }}</a-button>
            </a-popconfirm>
          </a-space>
        </template>
        <div class="profile-desc">{{ t('happyPlanet.profileDesc') }}</div>
        <a-table
          row-key="key"
          :columns="profileColumns"
          :data-source="profileRows"
          :loading="loading"
          :pagination="false"
          size="middle"
          :locale="{ emptyText: t('happyPlanet.empty') }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space>
                <a-button type="link" size="small" @click="openEdit(record)">
                  {{ t('common.edit') }}
                </a-button>
                <a-popconfirm :title="t('happyPlanet.confirmDelete')" @confirm="handleDelete(record.key)">
                  <a-button type="link" danger size="small">{{ t('common.delete') }}</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-card>
    </template>

    <!-- 新增 / 编辑弹窗 -->
    <a-modal
      v-model:open="editVisible"
      :title="editing ? t('happyPlanet.editField') : t('happyPlanet.addField')"
      :confirm-loading="submitLoading"
      @ok="handleSubmit"
    >
      <a-form :model="formData" layout="vertical">
        <a-form-item :label="t('happyPlanet.field')">
          <a-select
            v-model:value="formData.field"
            :disabled="editing"
            :placeholder="t('happyPlanet.selectField')"
          >
            <a-select-option v-for="k in FIELD_KEYS" :key="k" :value="k">
              {{ fieldLabel(k) }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="t('happyPlanet.value')">
          <a-input v-model:value="formData.value" :placeholder="t('happyPlanet.inputValue')" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="playerCodeVisible" title="玩家星球码" :footer="null">
      <div class="player-code-box">
        <div class="player-code">{{ playerCode?.code }}</div>
        <div class="player-code-desc">
          让玩家在 APP 的「我的AI」里输入这个星球码，即可把自己的玩家身份关联到该 AI；设备仍归控制台管理。
        </div>
        <a-tag color="blue">10 分钟内有效</a-tag>
      </div>
    </a-modal>

    <a-back-top />
  </div>
</template>

<style scoped lang="scss">
.happy-planet-view {
  padding: 24px;

  .subtitle {
    color: rgba(0, 0, 0, 0.45);
  }

  .metric-label {
    margin-bottom: 8px;
    color: rgba(0, 0, 0, 0.65);
  }

  .metric-hint {
    margin-top: 6px;
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }

  .profile-desc {
    margin-bottom: 12px;
    color: rgba(0, 0, 0, 0.45);
  }

  .player-code-box {
    text-align: center;
  }

  .player-code {
    margin: 8px 0 12px;
    font-size: 36px;
    font-weight: 700;
    letter-spacing: 6px;
  }

  .player-code-desc {
    margin-bottom: 12px;
    color: rgba(0, 0, 0, 0.65);
    line-height: 1.7;
  }
}
</style>
