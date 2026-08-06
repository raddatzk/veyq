<script setup lang="ts">
// uc.account.unlock — scan the Emergency Kit QR with the device camera and emit the parsed kit. Entirely
// client-side: the camera frames are decoded in-browser with jsQR; nothing leaves the device.
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { parseKit, type KitData } from './emergencyKit'

const emit = defineEmits<{ found: [KitData]; close: [] }>()
const { t } = useI18n()

const video = ref<HTMLVideoElement | null>(null)
const error = ref('')
let stream: MediaStream | null = null
let raf = 0
let canvas: HTMLCanvasElement | null = null

async function start() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
    const v = video.value!
    v.srcObject = stream
    await v.play()
    canvas = document.createElement('canvas')
    raf = requestAnimationFrame(scan)
  } catch {
    // permission denied / no camera / insecure context — let the user fall back to upload or paste
    error.value = t('unlock.scanError')
  }
}

async function scan() {
  const v = video.value
  if (v && canvas && v.readyState === v.HAVE_ENOUGH_DATA) {
    canvas.width = v.videoWidth
    canvas.height = v.videoHeight
    const ctx = canvas.getContext('2d')
    if (ctx) {
      ctx.drawImage(v, 0, 0, canvas.width, canvas.height)
      const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height)
      const jsQR = (await import('jsqr')).default
      const code = jsQR(data, width, height)
      const kit = code && parseKit(code.data)
      if (kit) {
        stop()
        emit('found', kit)
        return
      }
    }
  }
  raf = requestAnimationFrame(scan)
}

function stop() {
  cancelAnimationFrame(raf)
  stream?.getTracks().forEach((track) => track.stop())
  stream = null
}

function cancel() {
  stop()
  emit('close')
}

onMounted(start)
onUnmounted(stop)
</script>

<template>
  <div class="scanner-overlay" @click.self="cancel">
    <div class="scanner-box">
      <p class="veyq-form-copy">{{ t('unlock.scanHint') }}</p>
      <video ref="video" playsinline muted class="scanner-video"></video>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" text @click="cancel" />
    </div>
  </div>
</template>

<style scoped>
.scanner-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.scanner-box {
  background: var(--app-surface);
  padding: 1.25rem;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-width: 92vw;
}
.scanner-video {
  width: min(80vw, 360px);
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 8px;
  background: #000;
}
</style>
