<script setup lang="ts">
// The "save your Emergency Kit" step — shared by first-run server setup and invite-accept registration. It never
// shows the raw Secret Key: the user downloads the kit PDF, then confirms they stored it — only then is it
// "ready". The parent owns the follow-on action button and gates it on the v-model (ready = downloaded AND
// confirmed). Reuses downloadKitPdf so both flows stay identical. Pass the intro copy via the default slot.
import { ref, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import { downloadKitPdf } from './emergencyKit'

const props = defineProps<{ email: string; secretKey: string }>()
const ready = defineModel<boolean>({ default: false })
const { t } = useI18n()

const downloaded = ref(false)
const confirmed = ref(false)
const failed = ref(false)

// Ready only once the kit is both downloaded and confirmed-saved (the confirm stays disabled until download).
watchEffect(() => {
  ready.value = downloaded.value && confirmed.value
})

async function download() {
  failed.value = false
  try {
    await downloadKitPdf({ email: props.email, secretKey: props.secretKey })
    downloaded.value = true
  } catch {
    failed.value = true
  }
}
</script>

<template>
  <div class="kit-save">
    <slot />
    <Button type="button" :label="t('kit.download')" icon="pi pi-download" severity="secondary" @click="download" />
    <p v-if="downloaded" class="kit-status">
      <span class="pi pi-check" aria-hidden="true" />
      {{ t('kit.downloaded') }}
    </p>
    <Message v-if="failed" severity="error" :closable="false">{{ t('kit.downloadFailed') }}</Message>
    <label class="kit-confirm">
      <Checkbox v-model="confirmed" :binary="true" :disabled="!downloaded" />
      <span>{{ t('kit.backupDownloadConfirm') }}</span>
    </label>
  </div>
</template>

<style scoped>
.kit-save > * + * {
  margin-top: 0.85rem;
}
.kit-status {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  color: var(--app-success);
}
.kit-confirm {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
</style>
