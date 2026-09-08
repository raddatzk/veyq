<script setup lang="ts">
// uc.admin.identify-instance-release (web, cr-release-identity, ADR 0057) — the "Release" rubric of the server
// settings. A read, and only a read: the Server Owner sees which build this instance is actually running, so an
// upgrade has a rollback target that can be stated rather than guessed.
//
// This is NOT the pre-auth `version: { major, build }` that `useCapabilities` already holds (ADR 0032). That one
// answers "can this client and this server talk to each other?" and is public; this one answers "which bits are
// running, and what do I roll back to?" and is Server-Owner-only, because a precise release number is what maps
// an instance to a list of known vulnerabilities. The two are never merged (ADR 0057 §4) — which is why this
// panel calls its own owner-gated endpoint instead of reading a value the client already has.
//
// The read is owner-gated server-side, so a 403 means "not the Server Owner" and gets the read-only notice
// rather than a scary error. Nothing here is user content: a release version is a property of the software.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Message from 'primevue/message'
import { getInstanceRelease } from './release'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { handle401 } from '../../shared/handle401'

const { t } = useI18n()
const on401 = handle401(useRouter())

// `null` is a real answer here (A1: an unpublished build carries no stamp), never a "not loaded yet" — the
// loading and error states above it are what say that.
const release = ref<string | null>(null)

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  release.value = (await getInstanceRelease()).release
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('release.navTitle') }}</h2>
          <p>{{ t('release.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('release.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('release.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('release.loadFailed') }}</Message>

      <template v-else>
        <!-- A published build: the version, stated plainly. It is the string an operator pins and rolls back to,
             so it is shown verbatim and never decorated with a prefix the deployment file would not accept. -->
        <div v-if="release" class="release-running">
          <span class="settings-muted">{{ t('release.running') }}</span>
          <strong class="release-value">{{ release }}</strong>
        </div>
        <!-- A1 — absence is a first-class answer, not an empty field. -->
        <Message v-else severity="warn" :closable="false">{{ t('release.unstamped') }}</Message>
        <p class="settings-muted">{{ t('release.rollbackHint') }}</p>
      </template>
    </section>
  </section>
</template>

<style scoped>
.release-running {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.release-value {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 1.5rem;
}
</style>
