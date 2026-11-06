<script setup lang="ts">
// The "save your Emergency Kit" step — shared by first-run server setup and invite-accept registration. It never
// shows the raw Secret Key: the user downloads the kit PDF, then confirms they stored it — only then is it
// "ready". The parent owns the follow-on action button and gates it on the v-model (ready = downloaded AND
// confirmed). Renders the kit PDF in the user's language, then saves it. Pass the intro copy via the default slot.
import { ref, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { renderKitPdf, saveKitPdf } from './emergencyKit'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const props = defineProps<{ email: string; secretKey: string }>()
const ready = defineModel<boolean>({ default: false })
const { t, locale } = useI18n()

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
    // The address the kit is made on is the one this page was served from: the member's way back in.
    const bytes = await renderKitPdf(
      { email: props.email, secretKey: props.secretKey },
      { t: (key, named) => t(key, named ?? {}), locale: locale.value },
      { createdOn: location.host },
    )
    saveKitPdf(bytes, props.email)
    downloaded.value = true
  } catch {
    failed.value = true
  }
}
</script>

<template>
  <div class="kit-save">
    <slot />
    <VeyqButton type="button" :label="t('kit.download')" icon="download" variant="secondary" @click="download" />
    <p v-if="downloaded" class="kit-status">
      <VeyqIcon name="check" />
      {{ t('kit.downloaded') }}
    </p>
    <VeyqMessage v-if="failed" tone="error">{{ t('kit.downloadFailed') }}</VeyqMessage>
    <label class="kit-confirm">
      <VeyqCheckbox v-model="confirmed" :disabled="!downloaded" />
      <span>{{ t('kit.backupDownloadConfirm') }}</span>
    </label>
  </div>
</template>

<style scoped>
/* The parent's action button follows the confirm row directly: keep it off the checkbox. */
.kit-save {
  margin-bottom: 1.25rem;
}
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
