import { ref } from 'vue'

/**
 * 声纹录音：用 Web Audio 采集麦克风，实时电平反馈，停止时降采样为
 * 16k / 单声道 / 16bit PCM 并封装成 WAV Blob（讯飞声纹要求的格式），
 * 避免服务端转码。适用于浏览器与 Capacitor Android WebView（需 RECORD_AUDIO 权限）。
 */
const TARGET_RATE = 16000

export function useVoiceprintRecorder() {
  const recording = ref(false)
  const seconds = ref(0)
  const level = ref(0) // 麦克风电平 0~1，用于波形/动效

  let audioCtx: AudioContext | null = null
  let stream: MediaStream | null = null
  let source: MediaStreamAudioSourceNode | null = null
  let processor: ScriptProcessorNode | null = null
  let chunks: Float32Array[] = []
  let inputRate = TARGET_RATE
  let startedAt = 0
  let timer: ReturnType<typeof setInterval> | null = null

  function isSupported(): boolean {
    return typeof navigator !== 'undefined'
      && !!navigator.mediaDevices?.getUserMedia
      && typeof (window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext) !== 'undefined'
  }

  async function start(): Promise<void> {
    if (recording.value) return
    if (!isSupported()) {
      throw new Error('当前环境不支持录音')
    }
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    })
    const Ctx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    audioCtx = new Ctx()
    inputRate = audioCtx.sampleRate
    source = audioCtx.createMediaStreamSource(stream)
    processor = audioCtx.createScriptProcessor(4096, 1, 1)
    chunks = []
    processor.onaudioprocess = (e) => {
      const input = e.inputBuffer.getChannelData(0)
      chunks.push(new Float32Array(input))
      // 电平：均方根
      let sum = 0
      for (let i = 0; i < input.length; i++) { const v = input[i] ?? 0; sum += v * v }
      level.value = Math.min(1, Math.sqrt(sum / input.length) * 4)
    }
    source.connect(processor)
    processor.connect(audioCtx.destination)

    recording.value = true
    seconds.value = 0
    startedAt = Date.now()
    timer = setInterval(() => {
      seconds.value = (Date.now() - startedAt) / 1000
    }, 100)
  }

  /** 停止并返回 16k 单声道 16bit WAV。cancel=true 时丢弃录音返回 null。 */
  async function finish(cancel = false): Promise<Blob | null> {
    if (timer) { clearInterval(timer); timer = null }
    const captured = chunks
    const captureRate = inputRate
    teardown()
    recording.value = false
    level.value = 0
    if (cancel || captured.length === 0) return null

    const merged = mergeFloat32(captured)
    const down = downsample(merged, captureRate, TARGET_RATE)
    return encodeWav(down, TARGET_RATE)
  }

  function cancel(): void {
    if (timer) { clearInterval(timer); timer = null }
    teardown()
    recording.value = false
    seconds.value = 0
    level.value = 0
    chunks = []
  }

  function teardown() {
    try { processor?.disconnect() } catch { /* ignore */ }
    try { source?.disconnect() } catch { /* ignore */ }
    try { stream?.getTracks().forEach((t) => t.stop()) } catch { /* ignore */ }
    try { audioCtx?.close() } catch { /* ignore */ }
    processor = null
    source = null
    stream = null
    audioCtx = null
  }

  return { recording, seconds, level, isSupported, start, finish, cancel }
}

function mergeFloat32(chunks: Float32Array[]): Float32Array {
  let total = 0
  for (const c of chunks) total += c.length
  const out = new Float32Array(total)
  let offset = 0
  for (const c of chunks) { out.set(c, offset); offset += c.length }
  return out
}

/** 线性插值降采样（够用即可，声纹对轻微失真不敏感）。 */
function downsample(buffer: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (toRate >= fromRate) return buffer
  const ratio = fromRate / toRate
  const newLen = Math.round(buffer.length / ratio)
  const out = new Float32Array(newLen)
  for (let i = 0; i < newLen; i++) {
    const idx = i * ratio
    const i0 = Math.floor(idx)
    const i1 = Math.min(i0 + 1, buffer.length - 1)
    const frac = idx - i0
    out[i] = (buffer[i0] ?? 0) * (1 - frac) + (buffer[i1] ?? 0) * frac
  }
  return out
}

/** Float32 [-1,1] → 16bit PCM WAV Blob。 */
function encodeWav(samples: Float32Array, sampleRate: number): Blob {
  const bytesPerSample = 2
  const dataSize = samples.length * bytesPerSample
  const buffer = new ArrayBuffer(44 + dataSize)
  const view = new DataView(buffer)

  const writeString = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i))
  }

  writeString(0, 'RIFF')
  view.setUint32(4, 36 + dataSize, true)
  writeString(8, 'WAVE')
  writeString(12, 'fmt ')
  view.setUint32(16, 16, true) // fmt chunk size
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // mono
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * bytesPerSample, true) // byte rate
  view.setUint16(32, bytesPerSample, true) // block align
  view.setUint16(34, 16, true) // bits per sample
  writeString(36, 'data')
  view.setUint32(40, dataSize, true)

  let offset = 44
  for (let i = 0; i < samples.length; i++) {
    let s = Math.max(-1, Math.min(1, samples[i] ?? 0))
    s = s < 0 ? s * 0x8000 : s * 0x7fff
    view.setInt16(offset, s, true)
    offset += 2
  }
  return new Blob([view], { type: 'audio/wav' })
}
