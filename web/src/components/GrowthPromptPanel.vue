<template>
  <div class="growth-panel">
    <a-alert type="info" show-icon :message="t('template.growth.intro')" style="margin-bottom: 16px" />

    <div class="growth-toolbar">
      <span class="growth-toolbar-label">{{ t('template.growth.selectRole') }}</span>
      <a-select
        v-model:value="roleId"
        style="width: 260px"
        :loading="rolesLoading"
        @change="loadSlots"
      >
        <a-select-option :value="0">{{ t('template.growth.globalDefault') }}</a-select-option>
        <a-select-option v-for="r in roles" :key="r.roleId" :value="r.roleId">
          {{ r.roleName }}
        </a-select-option>
      </a-select>
      <a-button :loading="loading" @click="loadSlots">
        <template #icon><ReloadOutlined /></template>
        {{ t('common.refresh') }}
      </a-button>
    </div>

    <a-spin :spinning="loading">
      <a-card
        v-for="group in grouped"
        :key="group.label"
        size="small"
        :title="group.label"
        class="growth-group"
      >
        <div v-for="slot in group.items" :key="slot.slotKey" class="growth-slot">
          <div class="growth-slot-head">
            <span class="growth-slot-title">{{ slot.label }}</span>
            <a-tag :color="sourceColor(slot)">{{ sourceLabel(slot) }}</a-tag>
            <span class="growth-slot-switch">
              <a-switch v-model:checked="slot.enabled" size="small" />
              <span class="growth-slot-switch-text">{{ t('template.growth.enabled') }}</span>
            </span>
          </div>

          <div class="growth-slot-desc">{{ slot.desc }}</div>

          <div v-if="slot.vars.length" class="growth-slot-vars">
            <span class="growth-slot-vars-label">{{ t('template.growth.variables') }}：</span>
            <a-tag v-for="v in slot.vars" :key="v" class="growth-var-tag">{{ varToken(v) }}</a-tag>
          </div>

          <a-textarea
            v-model:value="slot.content"
            :auto-size="{ minRows: 2, maxRows: 8 }"
            :disabled="!slot.enabled"
          />

          <div class="growth-slot-actions">
            <a-button
              v-permission="'system:prompt-template:update'"
              type="primary"
              size="small"
              :loading="savingKey === slot.slotKey"
              @click="save(slot)"
            >
              {{ t('template.growth.save') }}
            </a-button>
            <a-popconfirm
              v-if="hasOverride(slot)"
              :title="t('template.growth.resetConfirm')"
              @confirm="reset(slot)"
            >
              <a-button v-permission="'system:prompt-template:update'" size="small" danger>
                {{ t('template.growth.reset') }}
              </a-button>
            </a-popconfirm>
          </div>
        </div>
      </a-card>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { useI18n } from 'vue-i18n'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { queryRoles } from '@/services/role'
import {
  queryGrowthSlots,
  saveGrowthSlot,
  resetGrowthSlot,
  type GrowthSlot,
} from '@/services/growthPrompt'

const { t } = useI18n()

const roleId = ref(0)
const roles = ref<{ roleId: number; roleName: string }[]>([])
const rolesLoading = ref(false)
const slots = ref<GrowthSlot[]>([])
const loading = ref(false)
const savingKey = ref<string | null>(null)

const grouped = computed(() => {
  const map = new Map<string, GrowthSlot[]>()
  for (const s of slots.value) {
    if (!map.has(s.categoryLabel)) map.set(s.categoryLabel, [])
    map.get(s.categoryLabel)!.push(s)
  }
  return Array.from(map, ([label, items]) => ({ label, items }))
})

/** 当前作用域是否存在“可重置”的覆盖：全局作用域看 global 行，角色作用域看 role 行。 */
function hasOverride(slot: GrowthSlot): boolean {
  return roleId.value === 0 ? slot.source === 'global' : slot.source === 'role'
}

function sourceLabel(slot: GrowthSlot): string {
  if (slot.source === 'role') return t('template.growth.sourceRole')
  if (slot.source === 'global') return t('template.growth.sourceGlobal')
  return t('template.growth.sourceDefault')
}

/** 把变量名渲染成 {{name}} 展示（避免在模板里直接写双花括号触发解析冲突）。 */
function varToken(v: string): string {
  return `{{${v}}}`
}

function sourceColor(slot: GrowthSlot): string {
  if (slot.source === 'role') return 'blue'
  if (slot.source === 'global') return 'orange'
  return 'default'
}

async function loadRoles() {
  rolesLoading.value = true
  try {
    const res = await queryRoles({ pageNo: 1, pageSize: 200 })
    if (res.code === 200 && res.data?.list) {
      roles.value = res.data.list.map((r) => ({ roleId: r.roleId, roleName: r.roleName }))
    }
  } finally {
    rolesLoading.value = false
  }
}

async function loadSlots() {
  loading.value = true
  try {
    const res = await queryGrowthSlots(roleId.value)
    if (res.code === 200 && res.data) {
      slots.value = res.data
    } else {
      message.error(res.message || t('template.growth.loadFailed'))
    }
  } catch {
    message.error(t('template.growth.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function save(slot: GrowthSlot) {
  if (slot.enabled && !slot.content.trim()) {
    message.warning(t('template.growth.emptyContent'))
    return
  }
  savingKey.value = slot.slotKey
  try {
    const res = await saveGrowthSlot({
      roleId: roleId.value,
      slotKey: slot.slotKey,
      content: slot.content,
      enabled: slot.enabled,
    })
    if (res.code === 200) {
      message.success(t('template.growth.saveSuccess'))
      await loadSlots()
    } else {
      message.error(res.message || t('template.growth.loadFailed'))
    }
  } finally {
    savingKey.value = null
  }
}

async function reset(slot: GrowthSlot) {
  const res = await resetGrowthSlot(roleId.value, slot.slotKey)
  if (res.code === 200) {
    message.success(t('template.growth.resetSuccess'))
    await loadSlots()
  } else {
    message.error(res.message || t('template.growth.loadFailed'))
  }
}

onMounted(async () => {
  await loadRoles()
  await loadSlots()
})
</script>

<style scoped>
.growth-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.growth-toolbar-label {
  color: var(--ant-color-text-secondary);
}
.growth-group {
  margin-bottom: 16px;
}
.growth-slot {
  padding: 12px 0;
  border-bottom: 1px dashed var(--ant-color-border-secondary, #f0f0f0);
}
.growth-slot:last-child {
  border-bottom: none;
}
.growth-slot-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.growth-slot-title {
  font-weight: 600;
}
.growth-slot-switch {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.growth-slot-switch-text {
  color: var(--ant-color-text-tertiary);
  font-size: 12px;
}
.growth-slot-desc {
  color: var(--ant-color-text-tertiary);
  font-size: 12px;
  margin-bottom: 6px;
}
.growth-slot-vars {
  margin-bottom: 6px;
}
.growth-slot-vars-label {
  color: var(--ant-color-text-tertiary);
  font-size: 12px;
}
.growth-var-tag {
  font-family: monospace;
}
.growth-slot-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>
