<script setup lang="ts">
// uc.admin.back-up-and-restore-instance A12 (admin-web, ADR 0056 decision 8) — Server → Moderation, beside the
// guest-write record's retention: how many days every backup is kept. What it keeps honest:
//   - only whole days from 7 to 365 are sent (the facade's `parseRetentionDays`, the rule the CLI shares);
//   - above thirty days the panel says a reason belongs in the record of processing — beside the value, before saving;
//   - what prunes, and when, is said: the console cannot see the captures, so it never claims one exists.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  DEFAULT_DAYS, getBackupRetention, MAX_DAYS, MIN_DAYS, needsReason, parseRetentionDays, setBackupRetention,
  type InstanceBackupRetention,
} from './backupRetention'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { isUnauthorized } from '../../shared/httpError'

const { t } = useI18n()
const on401 = handle401(useRouter())

const state = ref<InstanceBackupRetention | null>(null)
const days = ref(String(DEFAULT_DAYS))
const actionError = ref('')
const saved = ref(false)

function apply(s: InstanceBackupRetention) {
  state.value = s
  days.value = String(s.retentionDays)
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getBackupRetention()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const parsed = computed(() => parseRetentionDays(days.value))
const reasonOwed = computed(() => parsed.value !== undefined && needsReason(parsed.value))

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    apply(await setBackupRetention(parsed.value!))
    saved.value = true
  },
  {
    onError: (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      actionError.value = t('backupRetention.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  saved.value = false
  if (parsed.value === undefined) {
    actionError.value = t('backupRetention.invalid', { min: MIN_DAYS, max: MAX_DAYS })
    return
  }
  await runSave()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('backupRetention.title') }}</h2>
          <p>{{ t('backupRetention.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('backupRetention.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('release.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('backupRetention.loadFailed') }}</Message>

      <template v-else-if="state">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <Message v-else-if="saved" severity="success" :closable="false">{{ t('backupRetention.saved') }}</Message>
        <label>
          <span>{{ t('backupRetention.days', { min: MIN_DAYS, max: MAX_DAYS }) }}</span>
          <InputText v-model="days" class="backup-retention-days" inputmode="numeric" autocomplete="off" />
          <small class="settings-muted">{{ state.chosen ? t('backupRetention.chosen') : t('backupRetention.default', { days: DEFAULT_DAYS }) }}</small>
        </label>
        <Message v-if="reasonOwed" severity="warn" :closable="false" class="reason-owed">{{ t('backupRetention.reasonOwed') }}</Message>
        <p class="settings-muted">{{ t('backupRetention.effect') }}</p>
        <div>
          <Button type="button" class="backup-retention-save" :label="t('backupRetention.save')" icon="pi pi-check" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.backup-retention-days {
  max-width: 6rem;
}
</style>
