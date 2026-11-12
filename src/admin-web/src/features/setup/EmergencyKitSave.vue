<script setup lang="ts">
// The "save your Emergency Kit" step of first-run server setup. It never shows the raw Secret Key: the user downloads
// the kit PDF, then confirms they stored it — only then is it "ready". The parent owns the follow-on action button and
// gates it on the v-model (ready = downloaded AND confirmed). Renders the kit PDF in the user's language, then saves it.
// Pass the intro copy via the default slot. The address the Server Owner signs in at is asked here, optionally: this page
// is served on localhost, so it cannot know it (`kitServerOf`); a kit downloaded before the address changed is no longer
// the one confirmed.
import { computed, ref, watch, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../shared/ui/VeyqCheckbox.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import { kitServerOf, renderKitPdf, saveKitPdf } from './emergencyKit'

const props = defineProps<{ email: string; secretKey: string }>()
const ready = defineModel<boolean>({ default: false })
const { t, locale } = useI18n()

const downloaded = ref(false)
const confirmed = ref(false)
const failed = ref(false)
const address = ref('')
const server = computed(() => kitServerOf(address.value))

// The kit on disk carries the address it was made with: a changed address asks for a new download and a new confirm.
watch(address, () => {
  downloaded.value = false
  confirmed.value = false
})

// Ready only once the kit is both downloaded and confirmed-saved (the confirm stays disabled until download).
watchEffect(() => {
  ready.value = downloaded.value && confirmed.value
})

async function download() {
  failed.value = false
  const typed = server.value
  if (typed === 'invalid') return
  try {
    const bytes = await renderKitPdf({ email: props.email, secretKey: props.secretKey, ...typed }, { t: (key, named) => t(key, named ?? {}), locale: locale.value })
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
    <label class="kit-server">
      {{ t('kit.serverLabel') }}
      <VeyqInput v-model="address" fluid inputmode="url" autocomplete="url" placeholder="familie.example.org" :invalid="server === 'invalid'" aria-describedby="kit-server-hint" />
      <span id="kit-server-hint" class="kit-server-hint">{{ server === 'invalid' ? t('kit.serverInvalid') : t('kit.serverHint') }}</span>
    </label>
    <VeyqButton type="button" :label="t('kit.download')" icon="download" variant="secondary" :disabled="server === 'invalid'" @click="download" />
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
.kit-save > * + * {
  margin-top: 0.85rem;
}
.kit-server {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.kit-server-hint {
  font-size: 0.85rem;
  font-weight: 400;
  color: var(--app-muted);
}
.kit-server:has([aria-invalid='true']) .kit-server-hint {
  color: var(--app-danger);
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
