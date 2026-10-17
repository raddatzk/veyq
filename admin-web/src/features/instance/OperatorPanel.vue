<script setup lang="ts">
// uc.admin.operator-details (admin-web) — Server → Operator: the instance contact with its languages, the address
// statements name, the private notification address with its code confirmation (ADR 0097 decision 5, the
// `InstanceMailPanel` shape), the provider identification and the supervisory authority (ADR 0093 § 11, ADR 0035
// decisions 6–7). What it keeps honest:
//   - nothing is required: every field may stay empty, and saving never waits on a warning (ADR 0093 § 14);
//   - the two warnings are the facade's (`operatorWarnings`): only in the organisation mode, the contact one only while
//     public links are on — read from the register's effective state, not assumed;
//   - the confirmation is the server's answer after each save; this panel never claims an address is confirmed.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Textarea from 'primevue/textarea'
import {
  clearOperator, confirmOperatorNotification, getOperator, InstanceOperatorNotificationConfirmationEnum as Confirmation,
  operatorWarnings, parseLanguages, PUBLIC_SHARES_ID, setOperator, type InstanceOperator,
} from './operator'
import { listInstanceCapabilities } from './capabilities'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())
const { ask } = useConfirm()

const op = ref<InstanceOperator | null>(null)
// Null until read, and when the read failed: then the contact warning assumes public links are on — a warning too many
// beats one the Server Owner never sees.
const publicSharesOn = ref<boolean | null>(null)
const contactEmail = ref('')
const contactLanguages = ref('')
const statementEmail = ref('')
const notificationEmail = ref('')
const providerName = ref('')
const providerAddress = ref('')
const providerEmail = ref('')
const providerRepresentative = ref('')
const supervisoryAuthority = ref('')
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
}

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  const [o] = await Promise.all([
    getOperator(),
    listInstanceCapabilities()
      .then((caps) => { publicSharesOn.value = caps.find((c) => c.id === PUBLIC_SHARES_ID)?.effective ?? null })
      .catch(() => { publicSharesOn.value = null }),
  ])
  apply(o)
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

const warnings = computed(() => (op.value ? operatorWarnings(op.value, publicSharesOn.value ?? true) : null))

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
  if (!(await ask({ header: t('operator.clearTitle'), message: t('operator.clearConfirm'), acceptLabel: t('operator.clear'), acceptIcon: 'pi pi-trash' }))) return
  actionError.value = ''
  await runClear()
}

async function confirm() {
  codeError.value = ''
  await runConfirm()
}

/** Where the notification address's confirmation stands, in words and with the severity it deserves. */
const confirmation = computed(() => {
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
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('operator.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('operator.loadFailed') }}</Message>

      <template v-else-if="op">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <Message v-if="warnings?.contact" severity="warn" :closable="false" class="operator-warn-contact">{{ t('operator.warnContact') }}</Message>
        <Message v-if="warnings?.identification" severity="warn" :closable="false" class="operator-warn-identification">{{ t('operator.warnIdentification') }}</Message>

        <h3 class="operator-heading">{{ t('operator.contactTitle') }}</h3>
        <label>
          <span>{{ t('operator.contactEmail') }}</span>
          <InputText v-model="contactEmail" class="operator-contact" type="email" autocomplete="off" />
          <small class="settings-muted">{{ t('operator.contactHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.contactLanguages') }}</span>
          <InputText v-model="contactLanguages" class="operator-languages" autocomplete="off" placeholder="de, en" />
          <small class="settings-muted">{{ t('operator.contactLanguagesHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.statementEmail') }}</span>
          <InputText v-model="statementEmail" type="email" autocomplete="off" />
          <small class="settings-muted">{{ t('operator.statementHint') }}</small>
        </label>
        <label>
          <span>{{ t('operator.notificationEmail') }}</span>
          <InputText v-model="notificationEmail" class="operator-notification" type="email" autocomplete="off" />
          <small class="settings-muted">{{ t('operator.notificationHint') }}</small>
        </label>
        <Message v-if="confirmation" :severity="confirmation.severity" :closable="false" class="operator-confirmation" :data-state="op.notificationConfirmation">
          {{ confirmation.text }}
        </Message>
        <form v-if="op.notificationConfirmation === Confirmation.CodeSent" class="operator-code" @submit.prevent="confirm">
          <label>
            <span>{{ t('operator.code') }}</span>
            <InputText v-model="code" class="operator-code__input" inputmode="numeric" autocomplete="one-time-code" maxlength="16" />
          </label>
          <Message v-if="codeError" severity="error" :closable="false">{{ codeError }}</Message>
          <div>
            <Button type="submit" class="operator-code__confirm" :label="t('operator.confirm')" icon="pi pi-check" :loading="confirming" :disabled="!code.trim()" />
          </div>
        </form>

        <h3 class="operator-heading">{{ t('operator.identificationTitle') }}</h3>
        <label>
          <span>{{ t('operator.providerName') }}</span>
          <InputText v-model="providerName" class="operator-provider-name" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('operator.providerAddress') }}</span>
          <Textarea v-model="providerAddress" class="operator-provider-address" rows="3" auto-resize />
        </label>
        <label>
          <span>{{ t('operator.providerEmail') }}</span>
          <InputText v-model="providerEmail" class="operator-provider-email" type="email" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('operator.providerRepresentative') }}</span>
          <InputText v-model="providerRepresentative" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('operator.supervisoryAuthority') }}</span>
          <InputText v-model="supervisoryAuthority" autocomplete="off" />
        </label>

        <div class="operator-actions">
          <Button type="button" class="operator-save" :label="t('operator.save')" icon="pi pi-check" :loading="saving" @click="save" />
          <Button type="button" severity="secondary" text class="operator-clear" :label="t('operator.clear')" icon="pi pi-trash" :loading="clearing" @click="clearAll" />
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
.operator-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
</style>
