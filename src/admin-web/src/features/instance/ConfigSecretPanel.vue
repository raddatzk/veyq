<script setup lang="ts">
// ADR 0050 — the "Config-secret key" rubric of the server settings, beside Release and Object store and built on
// the store panel's shape (styleguide § Reuse first: the same kind of value, a state the operator acts on).
//
// A read, and only a read — the server's answer changes nothing, so the operator can open this as often as a
// runbook asks. It is the verification step of a restore and an upgrade (uc.admin.back-up-and-restore-instance
// step 11): a restore that lost the key file starts, serves content and looks healthy, and this is where that
// becomes something the operator is told. Each state says what it means for them and what they do next.
//
// The read is owner-gated server-side, so a 403 means "not the Server Owner" and gets the read-only notice
// rather than a scary error. Nothing here is user content or key material: it is a state.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Message from 'primevue/message'
import { getInstanceConfigSecret, InstanceConfigSecretStateEnum } from './configSecret'
import type { InstanceConfigSecret } from './configSecret'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'

const { t } = useI18n()
const on401 = handle401(useRouter())

const state = ref<InstanceConfigSecret['state'] | null>(null)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  state.value = (await getInstanceConfigSecret()).state
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/** A total map over the wire vocabulary: ABSENT and MISMATCH are different repairs, so neither folds into the other. */
const RENDER = {
  [InstanceConfigSecretStateEnum.Ok]: { severity: 'success', key: 'configSecret.ok' },
  [InstanceConfigSecretStateEnum.Absent]: { severity: 'warn', key: 'configSecret.absent' },
  [InstanceConfigSecretStateEnum.Mismatch]: { severity: 'error', key: 'configSecret.mismatch' },
} as const

const shown = computed(() => (state.value ? RENDER[state.value] : null))
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('configSecret.navTitle') }}</h2>
          <p>{{ t('configSecret.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('configSecret.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('configSecret.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('configSecret.loadFailed') }}</Message>

      <template v-else-if="shown">
        <Message :severity="shown.severity" :closable="false">{{ t(shown.key) }}</Message>
        <!-- The state code itself, verbatim: it is what a runbook asks for, and the string the API and
             `veyq-admin config-secret` answer with. -->
        <p class="settings-muted">
          {{ t('configSecret.stateLabel') }} <strong class="config-secret-state">{{ state }}</strong>
        </p>
      </template>
    </section>
  </section>
</template>

<style scoped>
.config-secret-state {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
</style>
