<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  getVoiceprint,
  enrollVoiceprint,
  deleteVoiceprint,
  type VoiceprintStatus,
} from '@/services/planetApp'
import { useVoiceprintRecorder } from '@/composables/useVoiceprintRecorder'

const MAX_SECONDS = 6
const MIN_SECONDS = 2

const status = ref<VoiceprintStatus | null>(null)
const loading = ref(true)
const phase = ref<'idle' | 'recording' | 'recorded' | 'saving'>('idle')
const confirmingDelete = ref(false)
let recordedBlob: Blob | null = null
let autoStopTimer: ReturnType<typeof setTimeout> | null = null

const { recording, seconds, level, isSupported, start, finish, cancel } = useVoiceprintRecorder()

const enabled = computed(() => status.value?.enabled !== false)
const enrolled = computed(() => !!status.value?.enrolled)

const statusText = computed(() => {
  if (loading.value) return '加载中…'
  if (!enabled.value) return '未开启'
  return enrolled.value ? '已录入' : '未录入'
})
const statusTone = computed(() => {
  if (!enabled.value) return 'muted'
  return enrolled.value ? 'ok' : 'warn'
})

async function load() {
  loading.value = true
  try {
    const res = await getVoiceprint()
    status.value = res.data
  } catch {
    // 静默：服务不可用时按未开启处理
    status.value = { enabled: false, enrolled: false }
  } finally {
    loading.value = false
  }
}

function clearAutoStop() {
  if (autoStopTimer) { clearTimeout(autoStopTimer); autoStopTimer = null }
}

async function onRecord() {
  if (!isSupported()) {
    message.warning('当前设备不支持录音')
    return
  }
  try {
    recordedBlob = null
    confirmingDelete.value = false
    await start()
    phase.value = 'recording'
    autoStopTimer = setTimeout(() => { void onStop() }, MAX_SECONDS * 1000)
  } catch (e) {
    const err = e as Error
    message.error(err?.message?.includes('Permission') || err?.name === 'NotAllowedError'
      ? '请允许使用麦克风后再录制'
      : '无法开始录音：' + (err?.message || '未知错误'))
    phase.value = 'idle'
  }
}

async function onStop() {
  clearAutoStop()
  if (!recording.value) return
  const dur = seconds.value
  const blob = await finish(false)
  if (!blob || dur < MIN_SECONDS) {
    message.warning(`录音太短，请说满约 ${MIN_SECONDS} 秒（建议 3-5 秒）`)
    phase.value = 'idle'
    return
  }
  recordedBlob = blob
  phase.value = 'recorded'
}

function onDiscard() {
  clearAutoStop()
  if (recording.value) cancel()
  recordedBlob = null
  phase.value = 'idle'
}

async function onSave() {
  if (!recordedBlob) return
  phase.value = 'saving'
  try {
    const res = await enrollVoiceprint(recordedBlob)
    status.value = res.data
    recordedBlob = null
    phase.value = 'idle'
    message.success('声纹录入成功')
  } catch (e) {
    const err = e as Error
    message.error(err?.message || '声纹录入失败')
    phase.value = 'recorded'
  }
}

async function onDelete() {
  if (!confirmingDelete.value) {
    confirmingDelete.value = true
    setTimeout(() => { confirmingDelete.value = false }, 3000)
    return
  }
  confirmingDelete.value = false
  try {
    const res = await deleteVoiceprint()
    status.value = res.data
    message.success('已删除声纹')
  } catch (e) {
    const err = e as Error
    message.error(err?.message || '删除失败')
  }
}

onMounted(load)
onBeforeUnmount(() => { clearAutoStop(); if (recording.value) cancel() })
</script>

<template>
  <div class="vp-card">
    <div class="vp-head">
      <div class="vp-title">
        <span class="vp-ico">🎙️</span>
        <span>我的声纹</span>
      </div>
      <span class="vp-pill" :class="statusTone">{{ statusText }}</span>
    </div>

    <p class="vp-desc">
      录入你的声音后，AI 每次对话会核对是不是你本人；对陌生声音，它会温和而有分寸地保持边界。每人仅保留一条声纹。
    </p>

    <div v-if="!enabled && !loading" class="vp-muted">
      声纹功能暂未开启，请联系管理员配置讯飞声纹凭证。
    </div>

    <template v-else-if="!loading">
      <!-- 录音中：电平 + 倒计时 -->
      <div v-if="phase === 'recording'" class="vp-recording">
        <div class="vp-wave">
          <span
            v-for="i in 5"
            :key="i"
            class="vp-bar"
            :style="{ transform: `scaleY(${0.25 + Math.min(1, level * (0.6 + i * 0.15)) })` }"
          ></span>
        </div>
        <div class="vp-timer">{{ seconds.toFixed(1) }}s / {{ MAX_SECONDS }}s</div>
        <button class="vp-btn primary" type="button" @click="onStop">完成录制</button>
        <button class="vp-btn ghost" type="button" @click="onDiscard">取消</button>
        <p class="vp-hint">请用平常的语气说一句话，比如「你好呀，我是你的小主人」</p>
      </div>

      <!-- 录制完成：确认录入 -->
      <div v-else-if="phase === 'recorded' || phase === 'saving'" class="vp-actions">
        <button class="vp-btn primary" type="button" :disabled="phase === 'saving'" @click="onSave">
          {{ phase === 'saving' ? '正在录入…' : (enrolled ? '确认重录' : '确认录入') }}
        </button>
        <button class="vp-btn ghost" type="button" :disabled="phase === 'saving'" @click="onRecord">重录</button>
        <button class="vp-btn ghost" type="button" :disabled="phase === 'saving'" @click="onDiscard">放弃</button>
      </div>

      <!-- 空闲 -->
      <div v-else class="vp-actions">
        <button class="vp-btn primary" type="button" @click="onRecord">
          {{ enrolled ? '重新录入声纹' : '录制我的声纹' }}
        </button>
        <button
          v-if="enrolled"
          class="vp-btn danger"
          :class="{ confirming: confirmingDelete }"
          type="button"
          @click="onDelete"
        >
          {{ confirmingDelete ? '再点一次确认删除' : '删除声纹' }}
        </button>
      </div>
    </template>

    <div v-else class="vp-muted">加载中…</div>
  </div>
</template>

<style scoped>
.vp-card {
  margin-top: 14px;
  padding: 16px;
  border-radius: var(--r-card, 22px);
  background: var(--glass-fill, rgba(255, 255, 255, 0.08));
  border: 1px solid var(--glass-stroke, rgba(255, 255, 255, 0.12));
}
.vp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.vp-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 15px;
}
.vp-ico { font-size: 18px; }
.vp-pill {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: var(--r-pill, 999px);
  background: rgba(255, 255, 255, 0.12);
}
.vp-pill.ok { background: rgba(111, 230, 178, 0.22); color: var(--mint, #6fe6b2); }
.vp-pill.warn { background: rgba(255, 209, 102, 0.2); color: var(--accent, #ffd166); }
.vp-pill.muted { opacity: 0.6; }
.vp-desc {
  margin: 10px 0 12px;
  font-size: 12.5px;
  line-height: 1.6;
  opacity: 0.72;
}
.vp-muted {
  font-size: 12.5px;
  opacity: 0.6;
  padding: 6px 0;
}
.vp-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.vp-btn {
  flex: 1 1 auto;
  min-width: 120px;
  border: none;
  cursor: pointer;
  padding: 11px 16px;
  border-radius: var(--r-control, 14px);
  font-size: 14px;
  font-weight: 600;
  transition: transform 0.15s ease, opacity 0.15s ease;
}
.vp-btn:active { transform: scale(0.97); }
.vp-btn:disabled { opacity: 0.6; cursor: default; }
.vp-btn.primary {
  background: linear-gradient(120deg, var(--accent, #ffd166), var(--accent-2, #ffb347));
  color: var(--accent-ink, #2a1e04);
}
.vp-btn.ghost {
  background: rgba(255, 255, 255, 0.1);
  color: inherit;
}
.vp-btn.danger {
  background: rgba(255, 122, 114, 0.16);
  color: var(--coral, #ff7a72);
}
.vp-btn.danger.confirming {
  background: var(--coral, #ff7a72);
  color: #fff;
}
.vp-recording { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.vp-wave { display: flex; align-items: center; gap: 6px; height: 40px; }
.vp-bar {
  width: 6px;
  height: 36px;
  border-radius: 3px;
  background: var(--accent, #ffd166);
  transform-origin: center;
  transition: transform 0.08s linear;
}
.vp-timer { font-variant-numeric: tabular-nums; font-weight: 700; opacity: 0.85; }
.vp-recording .vp-btn { width: 100%; }
.vp-hint { font-size: 12px; opacity: 0.6; text-align: center; margin: 2px 0 0; }
</style>
