<script setup lang="ts">
// The instance-wide queue (uc.moderation.review-a-case step 1) — rendered behind the gate, holding the moderation keys.
// Clear fields only, in the server's order (urgent categories first, then the oldest); opening a case swaps the list for
// its details, and coming back reads the queue again, since a decision may have moved the case.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import type { ModerationCaseSummary } from '../../api/generated/moderation'
import { listCases } from './cases'
import { caseFailureKey } from './caseFailure'
import CaseDetail from './CaseDetail.vue'

const { t, locale } = useI18n()

const cases = ref<ModerationCaseSummary[] | null>(null)
const showClosed = ref(false)
const loading = ref(false)
const error = ref('')
const selected = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    cases.value = await listCases(showClosed.value ? 'CLOSED' : undefined)
  } catch (e) {
    error.value = t(await caseFailureKey(e, 'moderation.queueLoadFailed'))
  } finally {
    loading.value = false
  }
}

function toggleClosed() {
  showClosed.value = !showClosed.value
  cases.value = null
  void load()
}

function back() {
  selected.value = null
  void load()
}

const when = (d: Date) => d.toLocaleString(locale.value)

onMounted(load)
</script>

<template>
  <CaseDetail v-if="selected" :case-id="selected" @back="back" />
  <div v-else class="veyq-section moderation-queue-list">
    <div class="veyq-section-header">
      <h3>{{ t('moderation.queue') }}</h3>
      <div class="veyq-action-group">
        <Button
          severity="secondary"
          class="moderation-queue-filter"
          :label="showClosed ? t('moderation.queueShowOpen') : t('moderation.queueShowClosed')"
          :disabled="loading"
          @click="toggleClosed"
        />
        <Button severity="secondary" class="moderation-queue-refresh" :label="t('moderation.queueRefresh')" icon="pi pi-refresh" :loading="loading" @click="load" />
      </div>
    </div>
    <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    <template v-if="cases">
      <p v-if="cases.length === 0" class="veyq-form-copy moderation-queue-empty">
        {{ showClosed ? t('moderation.queueClosedEmpty') : t('moderation.queueEmpty') }}
      </p>
      <div v-else class="veyq-list">
        <div v-for="c in cases" :key="c.id" class="veyq-list-row moderation-case-row">
          <div class="veyq-row-main">
            <strong class="veyq-row-title">{{ t(`report.categories.${c.category}`) }}</strong>
            <span class="veyq-row-subtitle">{{ t(`moderation.itemKinds.${c.itemKind}`) }} · {{ c.tenantDomain ?? t('moderation.caseRow.noDomain') }}</span>
            <span class="veyq-row-subtitle">
              {{ t('moderation.caseRow.firstReported', { time: when(c.firstReportedAt) }) }},
              {{ t('moderation.caseRow.lastReported', { time: when(c.lastReportedAt) }) }}
            </span>
            <div class="veyq-row-meta">
              <span class="veyq-badge" :class="{ warning: c.state === 'OPEN', muted: c.state === 'CLOSED' }">{{ t(`moderation.states.${c.state}`) }}</span>
              <span class="veyq-badge muted">{{ t('moderation.caseRow.reports', { count: c.reportCount }, c.reportCount) }}</span>
              <span v-if="c.objectionOpen" class="veyq-badge warning moderation-objection-badge">{{ t('moderation.caseRow.objection') }}</span>
              <span class="veyq-badge muted">
                {{ c.holdEndsAt ? t('moderation.caseRow.heldUntil', { time: when(c.holdEndsAt) }) : t('moderation.caseRow.notHeld') }}
              </span>
            </div>
          </div>
          <Button class="moderation-case-open" :label="t('moderation.openCase')" icon="pi pi-folder-open" @click="selected = c.id" />
        </div>
      </div>
    </template>
  </div>
</template>
