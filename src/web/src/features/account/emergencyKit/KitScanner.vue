<script setup lang="ts">
// The camera scanner (uc.account.unlock, uc.account.onboard-device since ADR 0059, the safety number of ADR 0028) — one overlay that owns
// the camera lifecycle, the permission failure and the no-camera fallback. Entirely client-side: the frames are
// decoded in-browser with jsQR; nothing leaves the device.
//
// **Decoder-agnostic on purpose.** It emits the RAW decoded string and knows nothing about what a payload means
// — each caller mounts its own scanner for its own code (the Secret-Key field a kit, the sign-in card a pairing
// code, the add-a-device panel a sign-in code, a profile a safety number) and says which strings it takes.
//
// `accepts` is why this stayed one component rather than becoming several. A camera sees whatever is in the room,
// so the loop must keep scanning past a QR that is not the caller's instead of stopping on it — and only the
// caller knows which strings are its own. Without the predicate the component would have to stop on the first
// decode of anything. `refusedText`, where a caller passes one, is said while the loop goes on: a readable code
// that is not this scanner's (owner 2026-11-12: the Secret-Key field's camera reads a kit and nothing else, and
// says "not an Emergency Kit" rather than staying silent, so nobody wonders why a code in view is not read).
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'

// `hint` / `errorText` are what the overlay says; a scan that is no Emergency-Kit scan (the safety-number compare on a
// member's profile, ADR 0028 Decision 3) passes its own, every other caller keeps the kit's words.
const props = defineProps<{ accepts?: (text: string) => boolean; hint?: string; errorText?: string; refusedText?: string }>()
const emit = defineEmits<{ found: [string]; close: [] }>()
const { t } = useI18n()

const video = ref<HTMLVideoElement | null>(null)
const error = ref('')
/** A readable code was in view that the caller does not take — shown while scanning goes on. */
const refused = ref(false)
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
    error.value = props.errorText ?? t('unlock.scanError')
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
      const text = jsQR(data, width, height)?.data
      if (text && (props.accepts?.(text) ?? true)) {
        stop()
        emit('found', text)
        return
      }
      if (text) refused.value = true
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
      <p class="veyq-form-copy">{{ hint ?? t('unlock.scanHint') }}</p>
      <video ref="video" playsinline muted class="scanner-video"></video>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <VeyqMessage v-else-if="refused && refusedText" tone="warn">{{ refusedText }}</VeyqMessage>
      <VeyqButton type="button" :label="t('common.cancel')" icon="times" variant="secondary" appearance="text" @click="cancel" />
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
