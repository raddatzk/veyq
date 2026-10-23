<script setup lang="ts">
// uc.admin.operator-details (admin-web) — Server → Operator: the instance contact with its languages, the address
// statements name, the private notification address with its code confirmation (ADR 0097 decision 5, the
// `InstanceMailPanel` shape), the provider identification and the supervisory authority (ADR 0093 § 11, ADR 0035
// decisions 6–7), and for the instance privacy notice the recipients and a data-protection officer (decision 8). What it
// keeps honest:
//   - nothing is required and nothing warns: every field may stay empty, shown as not stated, and published as "not
//     stated by this operator" (ADR 0093 § 14); what a Server Owner may owe is information beside the values
//     (`OperatorDuties`, the same block first-run setup shows), never a check on them;
//   - a recipient the instance suggests (an external store's host, the mail relay's host) is only offered: it becomes a
//     row of its kind the Server Owner completes, and nothing is published until the row is saved (cr-instance-privacy-notice 3);
//   - what a recipient does is a kind chosen from a list, which the notice names in its own words on each language's page;
//     only *Other* asks for the operator's words, and a description is never sent with another kind;
//   - where they are is a country picked from a searchable list, named in the console's language and sent as its ISO
//     3166-1 alpha-2 code (`./countries`) — the notice names it in each page's own language, never in the one typed;
//   - the confirmation is the server's answer after each save; this panel never claims an address is confirmed.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage, { type VeyqMessageTone } from '../../shared/ui/VeyqMessage.vue'
import VeyqCombobox from '../../shared/ui/VeyqCombobox.vue'
import VeyqSelect from '../../shared/ui/VeyqSelect.vue'
import VeyqTextarea from '../../shared/ui/VeyqTextarea.vue'
import {
  clearOperator, confirmOperatorNotification, getOperator, InstanceOperatorNotificationConfirmationEnum as Confirmation,
  OperatorRecipientKind, parseLanguages, RECIPIENT_KINDS, setOperator,
  suggestionRow, suggestionTaken, type InstanceOperator, type OperatorRecipientSuggestion,
} from './operator'
import OperatorDuties from './OperatorDuties.vue'
import { countryOptions as countriesIn } from './countries'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())
const { ask } = useConfirm()

const op = ref<InstanceOperator | null>(null)
const contactEmail = ref('')
const contactLanguages = ref('')
const statementEmail = ref('')
const notificationEmail = ref('')
const providerName = ref('')
const providerAddress = ref('')
const providerEmail = ref('')
const providerRepresentative = ref('')
const supervisoryAuthority = ref('')
/** The recipients as edited: a row's fields as typed, its kind and its country (a code) null until chosen, the
 *  description empty where not given. */
const recipients = ref<{ name: string; kind: OperatorRecipientKind | null; description: string; country: string | null }[]>([])
const dataProtectionOfficer = ref('')
const code = ref('')
const actionError = ref('')
const codeError = ref('')

function apply(o: InstanceOperator) {
  op.value = o
  contactEmail.value = o.contactEmail ?? ''
  contactLanguages.value = o.contactLanguages.join(', ')
  statementEmail.value = o.statementEmail ?? ''
  notificationEmail.value = o.notificationEmail ?? ''
  providerName.value = o.providerName ?? ''
  providerAddress.value = o.providerAddress ?? ''
  providerEmail.value = o.providerEmail ?? ''
  providerRepresentative.value = o.providerRepresentative ?? ''
  supervisoryAuthority.value = o.supervisoryAuthority ?? ''
  recipients.value = o.recipients.map((r) => ({ name: r.name, kind: r.kind, description: r.description ?? '', country: r.country ?? null }))
  dataProtectionOfficer.value = o.dataProtectionOfficer ?? ''
}

/** The kinds as the console names them, in its own language — the notice names them in each of its own. */
const kindOptions = computed(() => RECIPIENT_KINDS.map((k) => ({ value: k, label: t(`operator.recipientKind.${k}`) })))

/** The countries by their names in the console's language, each sending its code. */
const countryOptions = computed(() => countriesIn(locale.value))

/** The suggestions no row names yet — what the instance itself knows of, for the Server Owner to confirm. */
const openSuggestions = computed(() => {
  return (op.value?.recipientSuggestions ?? []).filter((s) => !suggestionTaken(s, recipients.value))
})

function addRecipient() {
  recipients.value.push({ name: '', kind: null, description: '', country: null })
}

function adoptSuggestion(s: OperatorRecipientSuggestion) {
  const row = suggestionRow(s)
  recipients.value.push({ name: row.name, kind: row.kind, description: '', country: null })
}

function removeRecipient(i: number) {
  recipients.value.splice(i, 1)
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getOperator()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/** What an empty public field shows: not stated, as the texts that name it publish it (ADR 0093 § 14). */
const notStated = computed(() => t('operator.notStated'))

const blank = (v: string) => v.trim() || null

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    apply(await setOperator({
      contactEmail: blank(contactEmail.value),
      contactLanguages: parseLanguages(contactLanguages.value),
      statementEmail: blank(statementEmail.value),
      notificationEmail: blank(notificationEmail.value),
      providerName: blank(providerName.value),
      providerAddress: blank(providerAddress.value),
      providerEmail: blank(providerEmail.value),
      providerRepresentative: blank(providerRepresentative.value),
      supervisoryAuthority: blank(supervisoryAuthority.value),
      // A row with no name or no kind says nothing a reader could use, and is not sent. A description goes only with
      // Other: one left over from a kind changed since is dropped, and Other without one is the server's to refuse.
      recipients: recipients.value
        .filter((r) => r.name.trim() && r.kind)
        .map((r) => ({
          name: r.name.trim(), kind: r.kind!,
          description: r.kind === OperatorRecipientKind.Other ? blank(r.description) : null, country: r.country || null,
        })),
      dataProtectionOfficer: blank(dataProtectionOfficer.value),
    }))
    code.value = ''
    codeError.value = ''
  },
  { onError: (e) => fail(e, 'operator.saveFailed') },
)

const { run: runClear, pending: clearing } = useAsyncAction(
  async () => {
    apply(await clearOperator())
    code.value = ''
    codeError.value = ''
  },
  { onError: (e) => fail(e, 'operator.saveFailed') },
)

const { run: runConfirm, pending: confirming } = useAsyncAction(
  async () => {
    apply(await confirmOperatorNotification(code.value.trim()))
    code.value = ''
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status, code: c } = await decodeHttpError(e)
      codeError.value = t(
        c === 'operator_code_invalid' ? 'operator.codeInvalid'
          : status === HttpStatus.tooManyRequests ? 'operator.codeRateLimited'
            : 'operator.confirmFailed',
      )
    },
  },
)

async function fail(e: unknown, fallback: string) {
  on401(e)
  if (isUnauthorized(e)) return
  const { status, code: c } = await decodeHttpError(e)
  actionError.value = t(
    c === 'invalid_request' ? 'operator.invalid'
      : status === HttpStatus.forbidden ? 'operator.notOwner'
        : fallback,
  )
}

async function save() {
  actionError.value = ''
  await runSave()
}

async function clearAll() {
  if (!(await ask({ header: t('operator.clearTitle'), message: t('operator.clearConfirm'), acceptLabel: t('operator.clear'), acceptIcon: 'trash' }))) return
  actionError.value = ''
  await runClear()
}

async function confirm() {
  codeError.value = ''
  await runConfirm()
}

/** Where the notification address's confirmation stands, in words and with the severity it deserves. */
const confirmation = computed<{ severity: VeyqMessageTone; text: string } | null>(() => {
  const o = op.value
  if (!o || !o.notificationEmail) return null
  const address = o.notificationEmail
  switch (o.notificationConfirmation) {
    case Confirmation.Confirmed:
      return { severity: 'success', text: t('operator.confirmed', { address, at: o.notificationConfirmedAt?.toLocaleString(locale.value) ?? '' }) }
    case Confirmation.CodeSent:
      return { severity: 'info', text: t('operator.codeSent', { address }) }
    case Confirmation.SendFailed:
      return { severity: 'error', text: t('operator.sendFailed', { address }) }
    default:
      return { severity: 'warn', text: t(o.instanceMailReady ? 'operator.unconfirmed' : 'operator.mailNotReady', { address }) }
  }
})
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('operator.navTitle') }}</h2>
          <p>{{ t('operator.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('operator.loading') }}</p>
      <VeyqMessage v-else-if="loadError === 'notAuthorized'" tone="info">{{ t('operator.notOwner') }}</VeyqMessage>
      <VeyqMessage v-else-if="loadError === 'failed'" tone="error">{{ t('operator.loadFailed') }}</VeyqMessage>

      <template v-else-if="op">
        <VeyqMessage v-if="actionError" tone="error">{{ actionError }}</VeyqMessage>
        <h3 class="operator-heading">{{ t('operatorDuties.title') }}</h3>
        <OperatorDuties />

        <h3 class="operator-heading">{{ t('operator.contactTitle') }}</h3>
        <label>
          <span>{{ t('operator.contactEmail') }}</span>
          <VeyqInput v-model="contactEmail" class="operator-contact" type="email" autocomplete="off" :placeholder="notStated" />
          <small class="settings-muted">{{ t('operator.contactHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.contactLanguages') }}</span>
          <VeyqInput v-model="contactLanguages" class="operator-languages" autocomplete="off" :placeholder="notStated" />
          <small class="settings-muted">{{ t('operator.contactLanguagesHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.statementEmail') }}</span>
          <VeyqInput v-model="statementEmail" type="email" autocomplete="off" :placeholder="notStated" />
          <small class="settings-muted">{{ t('operator.statementHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.notificationEmail') }}</span>
          <VeyqInput v-model="notificationEmail" class="operator-notification" type="email" autocomplete="off" />
          <small class="settings-muted">{{ t('operator.notificationHint') }}</small>
        </label>
        <VeyqMessage v-if="confirmation" :tone="confirmation.severity" class="operator-confirmation" :data-state="op.notificationConfirmation">
          {{ confirmation.text }}
        </VeyqMessage>
        <form v-if="op.notificationConfirmation === Confirmation.CodeSent" class="operator-code" @submit.prevent="confirm">
          <label>
            <span>{{ t('operator.code') }}</span>
            <VeyqInput v-model="code" class="operator-code__input" inputmode="numeric" autocomplete="one-time-code" maxlength="16" />
          </label>
          <VeyqMessage v-if="codeError" tone="error">{{ codeError }}</VeyqMessage>
          <div>
            <VeyqButton type="submit" class="operator-code__confirm" :label="t('operator.confirm')" icon="check" :loading="confirming" :disabled="!code.trim()" />
          </div>
        </form>

        <h3 class="operator-heading">{{ t('operator.identificationTitle') }}</h3>
        <label>
          <span>{{ t('operator.providerName') }}</span>
          <VeyqInput v-model="providerName" class="operator-provider-name" autocomplete="off" :placeholder="notStated" />
        </label>
        <label>
          <span>{{ t('operator.providerAddress') }}</span>
          <VeyqTextarea v-model="providerAddress" class="operator-provider-address" rows="3" auto-resize :placeholder="notStated" />
        </label>
        <label>
          <span>{{ t('operator.providerEmail') }}</span>
          <VeyqInput v-model="providerEmail" class="operator-provider-email" type="email" autocomplete="off" :placeholder="notStated" />
        </label>
        <label>
          <span>{{ t('operator.providerRepresentative') }}</span>
          <VeyqInput v-model="providerRepresentative" autocomplete="off" :placeholder="notStated" />
        </label>
        <label>
          <span>{{ t('operator.supervisoryAuthority') }}</span>
          <VeyqInput v-model="supervisoryAuthority" class="operator-supervisory-authority" autocomplete="off" :placeholder="notStated" />
        </label>

        <h3 class="operator-heading">{{ t('operator.privacyTitle') }}</h3>
        <p class="settings-muted">{{ t('operator.privacySub') }}</p>
        <p v-if="!recipients.length" class="settings-muted operator-no-recipient">{{ t('operator.noRecipient') }}</p>
        <div v-for="(r, i) in recipients" :key="i" class="operator-recipient" data-testid="operator-recipient">
          <label>
            <span>{{ t('operator.recipientName') }}</span>
            <VeyqInput v-model="r.name" class="operator-recipient__name" autocomplete="off" maxlength="200" />
          </label>
          <label>
            <span :id="`operator-recipient-kind-${i}`">{{ t('operator.recipientPurpose') }}</span>
            <VeyqSelect v-model="r.kind" :options="kindOptions"
                        :aria-labelledby="`operator-recipient-kind-${i}`" :placeholder="t('operator.recipientKindPick')"
                        class="operator-recipient__kind" />
          </label>
          <label>
            <span :id="`operator-recipient-country-${i}`">{{ t('operator.recipientCountry') }}</span>
            <VeyqCombobox v-model="r.country" :options="countryOptions" clearable
                          :aria-labelledby="`operator-recipient-country-${i}`" :placeholder="t('operator.recipientCountryPick')"
                          class="operator-recipient__country" />
          </label>
          <VeyqButton variant="secondary" appearance="text" icon="times" class="operator-recipient__remove"
                      :aria-label="t('operator.recipientRemove')" @click="removeRecipient(i)" />
          <label v-if="r.kind === OperatorRecipientKind.Other" class="operator-recipient__other">
            <span>{{ t('operator.recipientDescription') }}</span>
            <VeyqInput v-model="r.description" class="operator-recipient__description" autocomplete="off" maxlength="120"
                       :invalid="!r.description.trim()" />
            <small class="settings-muted">{{ t('operator.recipientDescriptionHint') }}</small>
          </label>
        </div>
        <VeyqMessage v-for="s in openSuggestions" :key="s.kind" tone="info" class="operator-suggestion" :data-kind="s.kind">
          <span>{{ t('operator.suggested', { host: s.host }) }}</span>
          <VeyqButton size="small" class="operator-suggestion__add" :label="t('operator.suggestionAdd')" icon="plus" @click="adoptSuggestion(s)" />
        </VeyqMessage>
        <div>
          <VeyqButton variant="secondary" appearance="outlined" class="operator-add-recipient" :label="t('operator.addRecipient')" icon="plus"
                      :disabled="recipients.length >= 20" @click="addRecipient" />
        </div>
        <label>
          <span>{{ t('operator.dataProtectionOfficer') }}</span>
          <VeyqInput v-model="dataProtectionOfficer" class="operator-dpo" autocomplete="off" maxlength="500" :placeholder="notStated" />
          <small class="settings-muted">{{ t('operator.dataProtectionOfficerHint') }}</small>
        </label>

        <div class="operator-actions">
          <VeyqButton class="operator-save" :label="t('operator.save')" icon="check" :loading="saving" @click="save" />
          <VeyqButton variant="secondary" appearance="text" class="operator-clear" :label="t('operator.clear')" icon="trash" :loading="clearing" @click="clearAll" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.operator-heading {
  margin: 0.5rem 0 0;
  font-size: 1rem;
}
.operator-code {
  display: grid;
  gap: 0.5rem;
}
.operator-recipient {
  display: grid;
  grid-template-columns: minmax(8rem, 1fr) minmax(8rem, 1.4fr) minmax(6rem, 0.7fr) auto;
  gap: 0.5rem;
  align-items: end;
}
.operator-recipient__other {
  grid-column: 1 / -1;
}
@media (max-width: 640px) {
  .operator-recipient {
    grid-template-columns: 1fr;
  }
}
.operator-suggestion :deep(.veyq-message__text) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}
.operator-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
</style>
