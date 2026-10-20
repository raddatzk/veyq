<script setup lang="ts">
// The report dialog (uc.moderation.report, ADR 0093 § 1): mounted once in App.vue and driven by `reportRequest`
// (`./report.ts`), for a member and a guest alike. It asks for what DSA Art. 16(2) names — a category, a reason
// (required), a confirmation of good faith (required), an address (optional) — says on the first category that no name
// is needed, and above 100 items offers the enclosing node instead. For one node it asks what is reported: the node and
// everything in it, or only its name (ADR 0093 § 9, `NAME` — the report then hands over that name's key alone). Sending
// seals everything the reporter wrote on this device; the receipt it shows (a reference and the time) is the on-screen
// half of owner decision Q6.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import { ReportCategory, type ReportReceipt } from '../../api/generated/moderation'
import { PRIVACY_ANCHOR, privacyLink } from '../privacy/privacyNotice'
import {
  MAX_REPORT_ITEMS, REPORT_CATEGORIES, ReportRefused, closeReport, reportRequest, submitReport, type ReportTarget,
} from './report'

const { t, locale } = useI18n()

const category = ref<ReportCategory | null>(null)
const reason = ref('')
const goodFaith = ref(false)
const contact = ref('')
const loading = ref(false)
const error = ref('')
const receipt = ref<ReportReceipt | null>(null)
/** The items reported: the request's, the enclosing node once the reporter chose it above the cap, or the one node's name. */
const chosen = ref<readonly ReportTarget[]>([])
/** For one node: whether the whole node is reported, or only its name. */
const scope = ref<'whole' | 'name'>('whole')

watch(reportRequest, (r) => {
  category.value = null
  reason.value = ''
  goodFaith.value = false
  contact.value = ''
  error.value = ''
  receipt.value = null
  scope.value = 'whole'
  chosen.value = r ? r.targets : []
})

const nameOnly = computed(() => (chosen.value.length === 1 ? reportRequest.value?.nameOnly ?? null : null))
const scopeOptions = computed(() => [
  { value: 'whole', label: t('report.scopeWhole') },
  { value: 'name', label: t('report.scopeName') },
])
/** What is sent: the node's name alone where the reporter chose it, else the items as chosen. */
const sent = computed<readonly ReportTarget[]>(() => (scope.value === 'name' && nameOnly.value ? [nameOnly.value] : chosen.value))

const open = computed(() => reportRequest.value !== null)
const tooMany = computed(() => chosen.value.length > MAX_REPORT_ITEMS)
const options = computed(() => REPORT_CATEGORIES.map((c) => ({ value: c, label: t(`report.categories.${c}`) })))
const csam = computed(() => category.value === ReportCategory.ChildSexualAbuse)
const canSend = computed(() =>
  !loading.value && !tooMany.value && chosen.value.length > 0 && category.value !== null && reason.value.trim() !== '' && goodFaith.value,
)

const receivedAt = computed(() =>
  receipt.value ? new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(receipt.value.receivedAt) : '',
)

function useEnclosing(): void {
  const enclosing = reportRequest.value?.enclosing
  if (enclosing) chosen.value = [enclosing]
}

function close(): void {
  if (!loading.value) closeReport()
}

const REFUSALS: Record<string, string> = {
  too_many_open_reports: 'report.tooManyOpen',
  rate_limited: 'report.rateLimited',
  report_keys_unavailable: 'report.unavailable',
  report_item_not_found: 'report.notFound',
}

async function send(): Promise<void> {
  const request = reportRequest.value
  if (!request || !canSend.value || !category.value) return
  loading.value = true
  error.value = ''
  try {
    receipt.value = await submitReport(request, sent.value, { category: category.value, reason: reason.value, contact: contact.value })
  } catch (e) {
    const code = e instanceof ReportRefused ? e.code : null
    error.value = t(REFUSALS[code ?? ''] ?? 'report.failed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Dialog
    :visible="open"
    :header="t('report.title')"
    modal
    :closable="!loading"
    :close-on-escape="!loading"
    class="veyq-report-dialog"
    @update:visible="close"
  >
    <div v-if="receipt" class="veyq-report-receipt" role="status">
      <p>{{ t('report.sent') }}</p>
      <p>{{ t('report.receipt', { reference: receipt.reportId.slice(0, 8), time: receivedAt }) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('report.close')" @click="close" />
      </div>
    </div>
    <form v-else class="veyq-form" @submit.prevent="send">
      <p class="veyq-report-intro">{{ t('report.intro') }}</p>
      <p class="veyq-report-count">{{ t('report.items', { count: chosen.length }, chosen.length) }}</p>
      <div v-if="nameOnly" class="veyq-form-field veyq-report-scope">
        <label>{{ t('report.scope') }}</label>
        <SelectButton
          v-model="scope"
          :options="scopeOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
          :disabled="loading"
        />
        <span class="veyq-report-hint">{{ t(scope === 'name' ? 'report.scopeNameHint' : 'report.scopeWholeHint') }}</span>
      </div>

      <template v-if="tooMany">
        <Message severity="warn" :closable="false">{{ t('report.tooMany', { count: chosen.length }) }}</Message>
        <Button v-if="reportRequest?.enclosing" type="button" class="veyq-report-enclosing" :label="t('report.reportEnclosing')" outlined @click="useEnclosing" />
      </template>

      <template v-else>
        <label for="veyq-report-category">{{ t('report.category') }}</label>
        <Select
          v-model="category"
          input-id="veyq-report-category"
          :options="options"
          option-label="label"
          option-value="value"
          :placeholder="t('report.categoryPlaceholder')"
          :disabled="loading"
        />
        <Message v-if="csam" severity="info" :closable="false" class="veyq-report-csam">{{ t('report.csamNote') }}</Message>

        <label for="veyq-report-reason">{{ t('report.reason') }}</label>
        <textarea id="veyq-report-reason" v-model="reason" class="veyq-report-reason" rows="5" maxlength="2000" required :disabled="loading" />
        <span class="veyq-report-hint">{{ t('report.reasonHint') }}</span>

        <div class="veyq-report-good-faith">
          <Checkbox v-model="goodFaith" input-id="veyq-report-good-faith" binary :disabled="loading" />
          <label for="veyq-report-good-faith">{{ t('report.goodFaith') }}</label>
        </div>

        <label for="veyq-report-contact">{{ t('report.contact') }}</label>
        <InputText id="veyq-report-contact" v-model="contact" type="email" autocomplete="email" maxlength="254" :disabled="loading" />
        <span class="veyq-report-hint">{{ t('report.contactHint') }}</span>
        <!-- GDPR Art. 13 applies here, where a contact is taken: the notice's passage on it, in a tab of its own (a share
             page's key rides its fragment). -->
        <a class="veyq-report-privacy" :href="privacyLink(PRIVACY_ANCHOR.reporterContact)" target="_blank" rel="noopener">{{ t('privacyNotice.atReport') }}</a>
      </template>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="loading" @click="close" />
        <Button type="submit" class="veyq-report-send" :label="t('report.send')" icon="pi pi-flag" :loading="loading" :disabled="!canSend" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.veyq-report-intro,
.veyq-report-count {
  margin: 0;
}
.veyq-report-reason {
  width: 100%;
  font: inherit;
  padding: 0.5rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface);
  color: var(--app-text);
}
.veyq-report-hint {
  font-size: 0.85rem;
  color: var(--app-muted);
}
.veyq-report-privacy {
  font-size: 0.85rem;
  color: var(--app-accent);
}
.veyq-report-good-faith {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}
</style>
