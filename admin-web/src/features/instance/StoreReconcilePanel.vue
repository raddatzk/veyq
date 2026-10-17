<script setup lang="ts">
// uc.admin.configure-object-store A7 — the bucket reconcile, under the Object store rubric (ADR 0062 § Consequences).
// Its own component so the store form stays the store form; StorePanel shows it while a store is in use. Built on the
// Moderation panel's shape (a state the server reports, and a few switches that answer with the new state).
//
// What it must keep honest — the owner's three conditions for deleting anything (2026-10-17):
//   - nothing is deleted until the Server Owner confirms the bucket is this server's alone, and the confirm dialog says
//     what that arms, in the server's own numbers (the grace, the candidates);
//   - the candidates are shown with their age and the date the first goes, never as a bare count;
//   - a pause stops the deleting and keeps the counting, and the panel says which of those is happening.
// Every verdict and every failure reason is a total map below, so a new enum value is a type error here rather than a
// blank on the screen. Counts and dates only: the server sends no key.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import {
  confirmExclusiveBucket,
  getStoreReconcile,
  graceDays,
  pauseReconcile,
  resumeReconcile,
  StoreReconcileClocksRestartReasonEnum,
  StoreReconcileFailureReasonEnum,
  StoreReconcileVerdictEnum,
  withdrawExclusiveBucket,
  type StoreReconcile,
} from './storeReconcile'
import { formatBytes } from '../../shared/formatBytes'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized } from '../../shared/httpError'

const { t, locale } = useI18n()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const state = ref<StoreReconcile | null>(null)
const error = ref('')
const busy = ref(false)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  state.value = await getStoreReconcile()
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const VERDICT = {
  [StoreReconcileVerdictEnum.Armed]: { severity: 'warn', key: 'reconcile.verdict.ARMED' },
  [StoreReconcileVerdictEnum.NotConfirmed]: { severity: 'info', key: 'reconcile.verdict.NOT_CONFIRMED' },
  [StoreReconcileVerdictEnum.Paused]: { severity: 'info', key: 'reconcile.verdict.PAUSED' },
  [StoreReconcileVerdictEnum.NoStore]: { severity: 'secondary', key: 'reconcile.verdict.NO_STORE' },
} as const

const FAILURE = {
  [StoreReconcileFailureReasonEnum.StoreUnreachable]: 'reconcile.failure.STORE_UNREACHABLE',
  [StoreReconcileFailureReasonEnum.ListDenied]: 'reconcile.failure.LIST_DENIED',
  [StoreReconcileFailureReasonEnum.NoStore]: 'reconcile.failure.NO_STORE',
  [StoreReconcileFailureReasonEnum.NoStoreClock]: 'reconcile.failure.NO_STORE_CLOCK',
  [StoreReconcileFailureReasonEnum.Database]: 'reconcile.failure.DATABASE',
} as const

const RESTART = {
  [StoreReconcileClocksRestartReasonEnum.Gap]: 'reconcile.restart.GAP',
  [StoreReconcileClocksRestartReasonEnum.StoreChanged]: 'reconcile.restart.STORE_CHANGED',
} as const

const at = (d: Date | null | undefined) => d?.toLocaleString(locale.value) ?? ''
const size = (b: number) => formatBytes(b, locale.value)
const verdict = computed(() => (state.value ? VERDICT[state.value.verdict] : null))
const days = computed(() => (state.value ? graceDays(state.value) : 0))
const candidates = computed(() => state.value?.candidates ?? null)
const confirmed = computed(() => state.value?.exclusiveBucketConfirmedAt != null)
const armed = computed(() => state.value?.verdict === StoreReconcileVerdictEnum.Armed)
const paused = computed(() => state.value?.verdict === StoreReconcileVerdictEnum.Paused)
const noStore = computed(() => state.value?.verdict === StoreReconcileVerdictEnum.NoStore)

/** Run one switch; show the reconcile as the server now reports it, or the refusal in words. */
async function act(change: () => Promise<StoreReconcile>) {
  error.value = ''
  busy.value = true
  try {
    state.value = await change()
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await decodeHttpError(e)
    error.value = t(code === 'no_store' ? 'reconcile.errors.noStore' : 'reconcile.errors.failed')
  } finally {
    busy.value = false
  }
}

async function confirm() {
  const c = candidates.value
  const accepted = await ask({
    header: t('reconcile.confirmTitle'),
    message: t('reconcile.confirmBody', { days: days.value, keys: c?.storageKeys ?? 0, due: c?.dueKeys ?? 0 }),
    acceptLabel: t('reconcile.confirm'),
    acceptIcon: 'pi pi-lock',
  })
  if (accepted) await act(confirmExclusiveBucket)
}

async function withdraw() {
  await act(withdrawExclusiveBucket)
}

async function pause() {
  await act(pauseReconcile)
}

async function resume() {
  await act(resumeReconcile)
}
</script>

<template>
  <section class="settings-form reconcile">
    <div class="settings-section-header">
      <div>
        <h3>{{ t('reconcile.title') }}</h3>
        <p>{{ t('reconcile.sub') }}</p>
      </div>
    </div>

    <p v-if="loading" class="settings-muted">{{ t('reconcile.loading') }}</p>
    <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('reconcile.notOwner') }}</Message>
    <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('reconcile.loadFailed') }}</Message>

    <template v-else-if="state && verdict">
      <Message :severity="verdict.severity" :closable="false" class="reconcile-verdict" :data-verdict="state.verdict">
        {{ t(verdict.key, { days }) }}
      </Message>
      <Message v-if="state.failure" severity="error" :closable="false" class="reconcile-failure" :data-reason="state.failure.reason">
        {{ t(FAILURE[state.failure.reason], { at: at(state.failure.at) }) }}
      </Message>

      <p v-if="state.lastPass" class="reconcile-pass">
        {{
          t('reconcile.lastPass', {
            at: at(state.lastPass.completedAt),
            objects: state.lastPass.objects,
            orphans: state.lastPass.orphanObjects,
            size: size(state.lastPass.orphanBytes),
            notVeyq: state.lastPass.notVeyqObjects,
          })
        }}
        <template v-if="state.lastPass.enqueuedKeys">{{ t('reconcile.enqueued', { keys: state.lastPass.enqueuedKeys }) }}</template>
      </p>
      <p v-else-if="!noStore" class="settings-muted reconcile-no-pass">{{ t('reconcile.noPass') }}</p>
      <p v-if="state.passInProgress" class="settings-muted reconcile-progress">
        {{ t('reconcile.inProgress', { at: at(state.passInProgress.startedAt), objects: state.passInProgress.objects }) }}
      </p>

      <div v-if="candidates && candidates.storageKeys > 0" class="reconcile-candidates">
        <p>
          {{
            t('reconcile.candidates', {
              keys: candidates.storageKeys,
              objects: candidates.objects,
              size: size(candidates.bytes),
              oldest: at(candidates.oldestFirstSeenAt),
              newest: at(candidates.newestFirstSeenAt),
            })
          }}
        </p>
        <p v-if="armed" class="reconcile-due">{{ t('reconcile.firstDue', { at: at(candidates.firstDueAt) }) }}</p>
        <p v-else class="settings-muted reconcile-due">{{ t('reconcile.notDeleting', { at: at(candidates.firstDueAt) }) }}</p>
      </div>

      <p class="settings-muted reconcile-reclaim">
        {{ t('reconcile.reclaim', { pending: state.reclaim.pending, abandoned: state.reclaim.abandoned }) }}
      </p>
      <p v-if="state.clocksRestartedAt && state.clocksRestartReason" class="settings-muted reconcile-restart">
        {{ t(RESTART[state.clocksRestartReason], { at: at(state.clocksRestartedAt) }) }}
      </p>
      <p v-if="confirmed" class="settings-muted reconcile-confirmed">
        {{ t('reconcile.confirmedAt', { at: at(state.exclusiveBucketConfirmedAt) }) }}
      </p>

      <Message v-if="error" severity="error" :closable="false" class="reconcile-error">{{ error }}</Message>
      <div v-if="!noStore" class="veyq-dialog-action-row">
        <Button
          v-if="!confirmed"
          type="button"
          :label="t('reconcile.confirm')"
          icon="pi pi-lock"
          severity="secondary"
          outlined
          :disabled="busy"
          class="reconcile-confirm"
          @click="confirm"
        />
        <template v-else>
          <Button
            v-if="armed"
            type="button"
            :label="t('reconcile.pause')"
            icon="pi pi-pause"
            severity="secondary"
            outlined
            :disabled="busy"
            class="reconcile-pause"
            @click="pause"
          />
          <Button
            v-if="paused"
            type="button"
            :label="t('reconcile.resume')"
            icon="pi pi-play"
            severity="secondary"
            outlined
            :disabled="busy"
            class="reconcile-resume"
            @click="resume"
          />
          <Button
            type="button"
            :label="t('reconcile.withdraw')"
            icon="pi pi-lock-open"
            severity="secondary"
            text
            :disabled="busy"
            class="reconcile-withdraw"
            @click="withdraw"
          />
        </template>
      </div>
    </template>
  </section>
</template>
