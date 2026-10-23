<script setup lang="ts">
// uc.account.onboard-device — the trusted device's half of "show a code" (ADR 0059 §1). `qrcode` is already a
// dependency and is imported lazily here for the same reason `renderKitPdf` does it: the encoder is heavy and
// only the screen that actually shows a code needs it.
//
// The QR is rendered ON A WHITE PLATE, unconditionally. A code is scanned by a camera, not read by a person, so
// it is rendered content rather than application UI — dark mode inverting it would produce a code no scanner
// reads (styleguide § Color: raw values are for rendered content).
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

// `alt` / `failedText` let another screen that shows one of OUR codes reuse this plate (the safety-number QR on a
// member's own profile, ADR 0028 Decision 3); the pairing screens pass neither.
const props = defineProps<{ payload: string; alt?: string; failedText?: string }>()
const { t } = useI18n()

const src = ref('')
const failed = ref(false)

async function render(payload: string) {
  failed.value = false
  src.value = ''
  try {
    const QRCode = await import('qrcode').then((m) => m.default)
    src.value = await QRCode.toDataURL(payload, { margin: 1, width: 320 })
  } catch {
    // Nothing to scan is not a state to hide: the whole screen is the code, so a failure here has to say so
    // rather than leave an empty box the user waits at.
    failed.value = true
  }
}

watch(() => props.payload, render, { immediate: true })
</script>

<template>
  <VeyqMessage v-if="failed" tone="error">{{ failedText ?? t('pairing.codeFailed') }}</VeyqMessage>
  <img v-else-if="src" class="pairing-code" :src="src" :alt="alt ?? t('pairing.codeAlt')" />
  <div v-else class="pairing-code pairing-code-pending" aria-hidden="true">
    <VeyqIcon name="spinner" spin />
  </div>
</template>

<style scoped>
.pairing-code {
  width: min(70vw, 260px);
  aspect-ratio: 1;
  border-radius: 8px;
  /* Rendered content, not a UI surface — the plate stays white in both themes so the code stays scannable. */
  background: #fff;
  padding: 0.5rem;
  align-self: center;
}
.pairing-code-pending {
  display: grid;
  place-items: center;
  background: var(--app-control-bg);
  color: var(--app-muted);
}
</style>
