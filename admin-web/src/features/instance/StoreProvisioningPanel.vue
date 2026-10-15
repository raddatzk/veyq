<script setup lang="ts">
// ADR 0067 — the "Object store" rubric of the server settings, beside the Release one and built on its shape
// (styleguide § Reuse first: the release panel is the nearest built screen, and this is the same kind of value).
//
// A read, and only a read. Provisioning runs in the background and can never fail the boot (ADR 0067
// Decision 3, following ADR 0063's projector restart-loop), which means an instance with a broken store
// STARTS and looks healthy. Without this panel the operator's first notice would be a photo that will not
// upload — so what this screen is for is turning that into something they are told.
//
// All four states get their own message, including the two that are not outcomes. `NOT_ATTEMPTED` is the
// permanent, correct answer of a `type: s3` instance and is rendered as information, never as a warning: the
// operator prepared that bucket themselves and there is nothing here for them to do.
//
// The read is owner-gated server-side, so a 403 means "not the Server Owner" and gets the read-only notice
// rather than a scary error. Nothing here is user content: it describes the operator's own infrastructure.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Message from 'primevue/message'
import { getInstanceStoreProvisioning, InstanceStoreProvisioningStateEnum } from './storeProvisioning'
import type { InstanceStoreProvisioning } from './storeProvisioning'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'

const { t } = useI18n()
const on401 = handle401(useRouter())

const state = ref<InstanceStoreProvisioning['state'] | null>(null)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  state.value = (await getInstanceStoreProvisioning()).state
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/**
 * A total map over the wire vocabulary rather than an `if PROVISIONED … else problem`. Folding the two
 * non-outcomes into a failure would tell a correctly configured operator that something is wrong with their
 * store, which is the lie the enum was widened in phase 1 to make impossible.
 */
const RENDER = {
  [InstanceStoreProvisioningStateEnum.NotAttempted]: { severity: 'info', key: 'store.notAttempted' },
  [InstanceStoreProvisioningStateEnum.InProgress]: { severity: 'info', key: 'store.inProgress' },
  [InstanceStoreProvisioningStateEnum.Provisioned]: { severity: 'success', key: 'store.provisioned' },
  [InstanceStoreProvisioningStateEnum.Failed]: { severity: 'error', key: 'store.failed' },
} as const

const shown = computed(() => (state.value ? RENDER[state.value] : null))
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('store.provisioningTitle') }}</h2>
          <p>{{ t('store.provisioningSub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('store.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('store.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('store.loadFailed') }}</Message>

      <template v-else-if="shown">
        <Message :severity="shown.severity" :closable="false">{{ t(shown.key) }}</Message>
        <!-- The state code itself, verbatim: it is what an operator quotes in a bug report, and it is the
             string the API answers with. -->
        <p class="settings-muted">
          {{ t('store.stateLabel') }} <strong class="store-state">{{ state }}</strong>
        </p>
      </template>
    </section>
  </section>
</template>

<style scoped>
.store-state {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
</style>
