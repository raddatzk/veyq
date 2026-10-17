<script setup lang="ts">
// uc.admin.operator-details (admin-web) — Server → Operating mode (ADR 0093 § 14, ADR 0088 decision 14): the mode in
// effect, the notice, and — before anything is set — every preset switch the chosen mode would change, from the
// server's own preset. Setting it again sets the preset again; *private* needs the tick (the facade refuses without it,
// `operatingModeRequest`). The mode never turns reporting or the moderators off, and no switch it sets is beyond the
// Capabilities panel's reach afterwards.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import OperatingModeChoice from './OperatingModeChoice.vue'
import {
  getOperatingMode, needsAcceptance, OperatingMode, presetChanges, setOperatingMode, type InstanceOperatingMode,
} from './operator'
import { inLocale, type CapabilityLocale } from './capabilities'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())

const current = ref<InstanceOperatingMode | null>(null)
const mode = ref<OperatingMode | null>(null)
const accepted = ref(false)
const actionError = ref('')
const saved = ref(false)

function apply(m: InstanceOperatingMode) {
  current.value = m
  mode.value = m.mode
  accepted.value = false
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getOperatingMode()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const modeLabel = (m: OperatingMode) => t(m === OperatingMode.Private ? 'operatingMode.private' : 'operatingMode.organisation')
const changes = computed(() => (current.value && mode.value ? presetChanges(current.value.preset, mode.value) : []))
const ready = computed(() => mode.value !== null && (!needsAcceptance(mode.value) || accepted.value))

const { run: runSet, pending: setting } = useAsyncAction(
  async () => {
    apply(await setOperatingMode(mode.value!, accepted.value))
    saved.value = true
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status } = await decodeHttpError(e)
      actionError.value = t(status === HttpStatus.forbidden ? 'operatingMode.notOwner' : 'operatingMode.saveFailed')
    },
  },
)

async function set() {
  if (!ready.value) return
  actionError.value = ''
  saved.value = false
  await runSet()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('operatingMode.navTitle') }}</h2>
          <p>{{ t('operatingMode.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('operatingMode.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('operatingMode.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('operatingMode.loadFailed') }}</Message>

      <template v-else-if="current">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <Message v-if="saved" severity="success" :closable="false" class="mode-saved">{{ t('operatingMode.saved') }}</Message>
        <p class="mode-current" :data-mode="current.mode">
          {{ current.chosen ? t('operatingMode.current', { mode: modeLabel(current.mode) }) : t('operatingMode.notChosen') }}
        </p>

        <OperatingModeChoice v-model:mode="mode" v-model:accepted="accepted" :disabled="setting" />

        <div class="mode-changes">
          <p v-if="!changes.length" class="settings-muted">{{ t('operatingMode.noChanges') }}</p>
          <template v-else>
            <p>{{ t('operatingMode.changes') }}</p>
            <ul>
              <li v-for="c in changes" :key="c.entry.id" :data-id="c.entry.id">
                {{ t(c.on ? 'operatingMode.changeOn' : 'operatingMode.changeOff', { title: inLocale(c.entry.title, locale as CapabilityLocale) }) }}
              </li>
            </ul>
          </template>
        </div>

        <div>
          <Button type="button" class="mode-set" :label="t('operatingMode.set')" icon="pi pi-check" :loading="setting" :disabled="!ready" @click="set" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.mode-current {
  margin: 0;
  font-weight: 600;
}
.mode-changes ul {
  margin: 0.25rem 0 0;
  padding-left: 1.2rem;
}
.mode-changes p {
  margin: 0;
}
</style>
