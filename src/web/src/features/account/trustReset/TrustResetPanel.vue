<script setup lang="ts">
// "Start a list over" — the settings hub's one place for the reset (ADR 0028 Decision 7's way back, inbox #192).
// The hub shows this section only while this device refuses a document; every refusal notice links here
// (`TrustResetLink.vue`). It lists what this device refuses, and why, and opens the confirm dialog — the one file
// that may start a document over. This panel never writes: it reads through `refusedDocuments`, like every read.
//
// Markup mirrors KeyRotationPanel (veyq-surface + settings-form + settings-section-header) and its result rows
// mirror SessionsPanel's veyq-list-row — the built panels are the design source.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import TrustResetDialog from './TrustResetDialog.vue'
import { refusedDocuments, type DocumentRefusal } from './refusedDocuments'
import type { AccountDocument } from '../trustMarks'
import type { ResetOutcome } from './trustReset'

const emit = defineEmits<{ 'trust-changed': [] }>()
const { t } = useI18n()

const refusals = ref<DocumentRefusal[] | null>(null)
const loadFailed = ref(false)
const target = ref<AccountDocument | null>(null)
const dialogOpen = ref(false)
const outcome = ref<ResetOutcome | null>(null)

async function load() {
  loadFailed.value = false
  try {
    refusals.value = await refusedDocuments()
  } catch {
    loadFailed.value = true
    refusals.value = []
  }
}
onMounted(load)

function open(document: AccountDocument) {
  outcome.value = null
  target.value = document
  dialogOpen.value = true
}

async function onDone(result: ResetOutcome) {
  outcome.value = result
  await load()
  emit('trust-changed')
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('trustReset.title') }}</h2>
          <p>{{ t('trustReset.sub') }}</p>
        </div>
      </div>

      <VeyqMessage v-if="outcome" tone="success">
        {{ outcome === 'reset' ? t('trustReset.done') : t('trustReset.alreadyReadable') }}
      </VeyqMessage>
      <VeyqMessage v-if="loadFailed" tone="warn">{{ t('trustReset.loadFailed') }}</VeyqMessage>

      <ul v-if="refusals && refusals.length" class="trust-reset-list">
        <li v-for="r in refusals" :key="r.document" class="veyq-list-row">
          <div class="trust-reset-row-text">
            <strong>{{ t(`trustReset.document.${r.document}`) }}</strong>
            <small>{{ t(`trustReset.code.${r.code}`) }}</small>
          </div>
          <VeyqButton
            type="button"
            :label="t('trustReset.action')"
            icon="refresh"
            variant="danger"
            appearance="outlined"
            :data-test="`trust-reset-open-${r.document}`"
            @click="open(r.document)"
          />
        </li>
      </ul>
      <p v-else-if="refusals && !loadFailed" class="trust-reset-none">{{ t('trustReset.none') }}</p>
    </section>

    <TrustResetDialog v-if="target" v-model:visible="dialogOpen" :document="target" @done="onDone" />
  </section>
</template>

<style scoped>
.trust-reset-list {
  list-style: none;
  margin: 1rem 0 0;
  padding: 0;
}
.trust-reset-row-text {
  display: grid;
  gap: 0.2rem;
  flex: 1 1 auto;
  min-width: 0;
}
.trust-reset-row-text small {
  color: var(--app-muted);
}
.trust-reset-none {
  color: var(--app-muted);
}
</style>
