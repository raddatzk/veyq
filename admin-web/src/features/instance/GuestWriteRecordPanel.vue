<script setup lang="ts">
// en.moderation.guest-write-record (admin-web, ADR 0093 § 10, ADR 0092 § 2) — Server → Moderation, below the roster: how
// many days a guest's write keeps its address, port and time. What it keeps honest:
//   - only whole days from 1 to 30 are sent (the facade's `parseRetentionDays`, the rule the CLI shares);
//   - above seven days the panel says a reason belongs in the record of processing — beside the value, before saving;
//   - whether anything is kept at all is the Capabilities panel's switch, whose default follows the operating mode: the
//     panel says so instead of offering a second switch.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  DEFAULT_DAYS, getGuestWriteRecord, MAX_DAYS, MIN_DAYS, needsReason, parseRetentionDays, setGuestWriteRecord,
  type InstanceGuestWriteRecord,
} from './guestWriteRecord'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { isUnauthorized } from '../../shared/httpError'

const { t } = useI18n()
const on401 = handle401(useRouter())

const state = ref<InstanceGuestWriteRecord | null>(null)
const days = ref(String(DEFAULT_DAYS))
const actionError = ref('')
const saved = ref(false)

function apply(s: InstanceGuestWriteRecord) {
  state.value = s
  days.value = String(s.retentionDays)
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getGuestWriteRecord()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const parsed = computed(() => parseRetentionDays(days.value))
const reasonOwed = computed(() => parsed.value !== undefined && needsReason(parsed.value))

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    apply(await setGuestWriteRecord(parsed.value!))
    saved.value = true
  },
  {
    onError: (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      actionError.value = t('guestWriteRecord.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  saved.value = false
  if (parsed.value === undefined) {
    actionError.value = t('guestWriteRecord.invalid', { min: MIN_DAYS, max: MAX_DAYS })
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
          <h2>{{ t('guestWriteRecord.title') }}</h2>
          <p>{{ t('guestWriteRecord.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('guestWriteRecord.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('release.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('guestWriteRecord.loadFailed') }}</Message>

      <template v-else-if="state">
        <p class="settings-muted switch-hint">{{ t('guestWriteRecord.switchHint') }}</p>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <Message v-else-if="saved" severity="success" :closable="false">{{ t('guestWriteRecord.saved') }}</Message>
        <label>
          <span>{{ t('guestWriteRecord.days', { min: MIN_DAYS, max: MAX_DAYS }) }}</span>
          <InputText v-model="days" class="retention-days" inputmode="numeric" autocomplete="off" />
          <small class="settings-muted">{{ state.chosen ? t('guestWriteRecord.chosen') : t('guestWriteRecord.default', { days: DEFAULT_DAYS }) }}</small>
        </label>
        <Message v-if="reasonOwed" severity="warn" :closable="false" class="reason-owed">{{ t('guestWriteRecord.reasonOwed') }}</Message>
        <p class="settings-muted">{{ t('guestWriteRecord.effect') }}</p>
        <div>
          <Button type="button" class="retention-save" :label="t('guestWriteRecord.save')" icon="pi pi-check" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.retention-days {
  max-width: 6rem;
}
</style>
