<script setup lang="ts">
// uc.admin.learn-of-new-releases (admin-web, ADR 0097 decisions 3 and 5) — the "Mail" rubric of the server settings:
// the instance's own SMTP settings, which send release notices to the Server Owner and nothing else. A copy of web's
// tenant mail panel's transport half (src/admin-web/CLAUDE.md: copies, not a shared UI package), without what only a
// tenant has (the switch, the invite template), and with the confirmation step a tenant's settings do not have.
// What it keeps honest:
//   - the password is write-only: the read carries `passwordSet`, a blank field keeps the stored one, a typed one is
//     cleared from component state the moment it is sent;
//   - the address the code and the notices go to is the server's answer (the Server Owner's account), shown, never
//     typed here — nothing in this form can send mail anywhere else;
//   - every save asks again, so after a save the panel shows where the confirmation stands as the server says it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ToggleSwitch from 'primevue/toggleswitch'
import {
  confirmInstanceMail, getInstanceMail, InstanceMailSettingsConfirmationEnum as Confirmation, updateInstanceMail,
  type InstanceMailSettings, type InstanceMailSettingsUpdate,
} from './instanceMail'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { handle401 } from '../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../shared/httpError'

const { t, locale } = useI18n()
const on401 = handle401(useRouter())

const host = ref('')
const port = ref('')
const from = ref('')
const username = ref('')
const password = ref('')
const tls = ref(true)
const settings = ref<InstanceMailSettings | null>(null)
const code = ref('')
const actionError = ref('')
const codeError = ref('')

function apply(s: InstanceMailSettings) {
  settings.value = s
  host.value = s.host ?? ''
  port.value = s.port != null ? String(s.port) : ''
  from.value = s.from ?? ''
  username.value = s.username ?? ''
  tls.value = s.tls
}

const { pending: loading, error: loadErr } = useAsyncData(async () => apply(await getInstanceMail()), { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

// The key only matters to a relay that logs in (auth is keyed on the username, as the server's send gate is).
const needsAuth = computed(() => username.value.trim().length > 0)
const keyState = computed(() => settings.value?.configSecretState ?? 'OK')

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    const update: InstanceMailSettingsUpdate = {
      host: host.value.trim() || null,
      port: port.value.trim() ? Number(port.value) : null,
      from: from.value.trim() || null,
      username: username.value.trim() || null,
      tls: tls.value,
    }
    // Write-only: sent only when typed; an omitted password keeps the stored one. Never an empty string.
    if (password.value) update.password = password.value
    const saved = await updateInstanceMail(update)
    password.value = '' // consumed — the plaintext never lingers in component state
    code.value = ''
    codeError.value = ''
    apply(saved)
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status, code: c } = await decodeHttpError(e)
      actionError.value = t(
        c === 'mail_secret_unavailable' ? 'instanceMail.keyUnavailable'
          : c === 'invalid_request' ? 'instanceMail.invalid'
            : status === HttpStatus.forbidden ? 'instanceMail.notOwner'
              : 'instanceMail.saveFailed',
      )
    },
  },
)

const { run: runConfirm, pending: confirming } = useAsyncAction(
  async () => {
    apply(await confirmInstanceMail(code.value.trim()))
    code.value = ''
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status, code: c } = await decodeHttpError(e)
      codeError.value = t(
        c === 'mail_code_invalid' ? 'instanceMail.codeInvalid'
          : status === HttpStatus.tooManyRequests ? 'instanceMail.codeRateLimited'
            : 'instanceMail.confirmFailed',
      )
    },
  },
)

async function save() {
  actionError.value = ''
  await runSave()
}

async function confirm() {
  codeError.value = ''
  await runConfirm()
}

/** Where the confirmation stands, in words and with the severity it deserves. */
const confirmation = computed(() => {
  const s = settings.value
  if (!s) return null
  switch (s.confirmation) {
    case Confirmation.Confirmed:
      return { severity: 'success', text: t('instanceMail.confirmed', { address: s.address, at: s.confirmedAt?.toLocaleString(locale.value) ?? '' }) }
    case Confirmation.CodeSent:
      return { severity: 'info', text: t('instanceMail.codeSent', { address: s.address }) }
    case Confirmation.SendFailed:
      return { severity: 'error', text: t('instanceMail.sendFailed', { address: s.address }) }
    case Confirmation.Unconfirmed:
      return { severity: 'warn', text: t('instanceMail.unconfirmed', { address: s.address }) }
    default:
      return { severity: 'secondary', text: t('instanceMail.notConfigured', { address: s.address }) }
  }
})
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('instanceMail.navTitle') }}</h2>
          <p>{{ t('instanceMail.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('instanceMail.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('instanceMail.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('instanceMail.loadFailed') }}</Message>

      <template v-else-if="settings">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>
        <Message v-if="needsAuth && keyState === 'MISMATCH'" severity="error" :closable="false" class="mail-key">{{ t('instanceMail.keyMismatch') }}</Message>
        <Message v-else-if="needsAuth && keyState === 'ABSENT'" severity="warn" :closable="false" class="mail-key">{{ t('instanceMail.keyUnavailable') }}</Message>
        <Message v-if="confirmation" :severity="confirmation.severity" :closable="false" class="mail-confirmation" :data-state="settings.confirmation">
          {{ confirmation.text }}
        </Message>

        <form v-if="settings.confirmation === Confirmation.CodeSent" class="mail-code" @submit.prevent="confirm">
          <label>
            <span>{{ t('instanceMail.code') }}</span>
            <InputText v-model="code" class="mail-code__input" inputmode="numeric" autocomplete="one-time-code" maxlength="16" />
          </label>
          <Message v-if="codeError" severity="error" :closable="false">{{ codeError }}</Message>
          <div>
            <Button type="submit" class="mail-code__confirm" :label="t('instanceMail.confirm')" icon="pi pi-check" :loading="confirming" :disabled="!code.trim()" />
          </div>
        </form>

        <label>
          <span>{{ t('instanceMail.host') }}</span>
          <InputText v-model="host" class="mail-host" placeholder="smtp.example.com" autocomplete="off" />
        </label>
        <div class="mail-field-row">
          <label>
            <span>{{ t('instanceMail.port') }}</span>
            <InputText v-model="port" inputmode="numeric" placeholder="587" autocomplete="off" />
          </label>
          <label>
            <span>{{ t('instanceMail.from') }}</span>
            <InputText v-model="from" class="mail-from" :placeholder="t('instanceMail.fromPlaceholder')" autocomplete="off" />
          </label>
        </div>
        <label>
          <span>{{ t('instanceMail.username') }}</span>
          <InputText v-model="username" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('instanceMail.password') }}</span>
          <InputText v-model="password" class="mail-password" type="password" autocomplete="new-password" :placeholder="settings.passwordSet ? t('instanceMail.passwordKeep') : ''" />
          <small class="settings-muted">{{ settings.passwordSet ? t('instanceMail.passwordSetHint') : t('instanceMail.passwordHint') }}</small>
        </label>
        <div class="mail-toggle-row">
          <ToggleSwitch v-model="tls" input-id="instance-mail-tls" />
          <label for="instance-mail-tls">{{ t('instanceMail.tls') }}</label>
        </div>
        <p class="settings-muted">{{ t('instanceMail.saveHint') }}</p>
        <div>
          <Button type="button" class="mail-save" :label="t('instanceMail.save')" icon="pi pi-send" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.mail-field-row {
  display: grid;
  grid-template-columns: minmax(6rem, 12rem) 1fr;
  gap: 0.75rem;
}
.mail-toggle-row {
  display: flex;
  gap: 0.6rem;
  align-items: center;
}
.mail-code {
  display: grid;
  gap: 0.5rem;
}
</style>
