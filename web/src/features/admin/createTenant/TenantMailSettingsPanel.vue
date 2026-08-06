<script setup lang="ts">
// uc.admin.tenant-mail-settings (web, cr-email-tenant-mail-settings-web, ADR 0048/0049/0050) — the "Email" rubric
// of the tenant settings. The tenant OWNER configures per-tenant SMTP + authors the invite subject/body template,
// so member invites are emailed from the tenant's own domain (else the copy-link fallback, unchanged). The read is
// owner-gated (a non-owner member is 403 → a read-only notice; a non-member 404 → generic failed, ADR 0042), so a
// successful load already means the caller is the owner. Operator-blind: the panel edits only operator-visible
// config — SMTP transport + owner-authored template prose over the CLOSED set {{link}}/{{domain}}/{{expiry}} — and
// never touches a sealed field; no content crypto here. The SMTP password is WRITE-ONLY (invariant): the read
// carries only `passwordSet`, the field starts empty, a stored one is kept by leaving it blank, and a freshly typed
// one is consumed on submit (cleared right after) so no plaintext lingers in component state.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import InputText from 'primevue/inputtext'
import ToggleSwitch from 'primevue/toggleswitch'
import { getTenantMailSettings, updateTenantMailSettings, type TenantMailSettings, type TenantMailSettingsUpdate } from './tenants'
import { activeSpace } from '../../../app/activeSpace'
import { useAsyncData, loadErrorKind } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useToast } from '../../../shared/state/useToast'
import { handle401 } from '../../../shared/handle401'
import { decodeHttpError, isUnauthorized, HttpStatus } from '../../../shared/httpError'

const { t } = useI18n()
const toast = useToast()
const on401 = handle401(useRouter())

// The closed variable set, kept OUT of the i18n messages: vue-i18n uses single-brace `{x}` interpolation, so a
// literal `{{link}}` in a message would break. We pass the tokens as PARAMS instead — param values are not
// re-interpolated, so they render verbatim. `{{tag}}` names mirror the backend renderer's closed set (ADR 0048 §6).
const TEMPLATE_TOKENS = { link: '{{link}}', domain: '{{domain}}', expiry: '{{expiry}}' }
const TEMPLATE_VARS = '{{link}}, {{domain}}, {{expiry}}'

// SMTP transport (operator config). `port` is kept as a string for the input and coerced on save.
const host = ref('')
const port = ref('')
const from = ref('')
const username = ref('')
const tls = ref(true)
const enabled = ref(false)
// Write-only password: starts empty; `passwordSet` reflects the STORED one (never the value). Cleared after save.
const password = ref('')
const passwordSet = ref(false)
// Owner-authored templates over the closed variable set (empty ⇒ omit ⇒ veyq's EN+DE default).
const subjectTemplate = ref('')
const bodyTemplate = ref('')
const actionError = ref('')
// The server's config-secret KEK state (ADR 0050 amendment): OK = sealing works; ABSENT = the server can't seal;
// MISMATCH = the key changed so the stored password can't be decrypted. Drives the proactive banners below.
const configSecretState = ref<TenantMailSettings['configSecretState']>('OK')

function applyView(s: TenantMailSettings) {
  host.value = s.host ?? ''
  port.value = s.port != null ? String(s.port) : ''
  from.value = s.from ?? ''
  username.value = s.username ?? ''
  tls.value = s.tls
  enabled.value = s.enabled
  passwordSet.value = s.passwordSet
  subjectTemplate.value = s.subjectTemplate ?? ''
  bodyTemplate.value = s.bodyTemplate ?? ''
  configSecretState.value = s.configSecretState
}

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  if (!activeSpace.tenantId) throw new Error('no-active-tenant') // no HTTP status → classified 'failed' below
  applyView(await getTenantMailSettings(activeSpace.tenantId))
}, { onError: on401 })
// 'notAuthorized' (403) ⇒ a non-owner member: owner-only notice, not a scary error. 'failed' ⇒ load error. null ⇒
// loaded (the caller is the owner — the read is owner-gated) or a 401 the redirect already handles.
const loadError = computed(() => loadErrorKind(loadErr.value))

// Whether the saved-or-about-to-be-saved config can actually send. Mirrors the backend send-gate
// (SmtpTenantInviteMailer): SMTP auth is keyed on `username`, so an auth-less host (no username — e.g. a trusted
// LAN relay) sends with NO login, and a password + an openable KEK are required ONLY when a username is set.
const needsAuth = computed(() => username.value.trim().length > 0)
// A stored `passwordSet: true` already implies the KEK was present at seal time (without it the seal 503s and
// nothing is stored). A freshly typed (unsaved) password counts — it stores on save (the KEK reality surfaces as
// the 503 below).
const hasPassword = computed(() => passwordSet.value || password.value.trim().length > 0)
// Auth-less ⇒ send-ready on `enabled ∧ host` alone. Authenticating ⇒ also needs a password AND a KEK that can open
// it (`configSecretState === 'OK'`, ADR 0050 amendment); ABSENT/MISMATCH there ⇒ not send-ready (their own banners).
const sendReady = computed(
  () =>
    enabled.value &&
    host.value.trim().length > 0 &&
    (!needsAuth.value || (hasPassword.value && configSecretState.value === 'OK')),
)
// What still blocks sending, in order — so an owner who only ticked `enabled` sees why no real mail goes out. A
// password only blocks an AUTHENTICATING host (one with a username); an auth-less host never lists it.
const missing = computed(() => {
  const m: string[] = []
  if (!enabled.value) m.push(t('mail.missingEnabled'))
  if (!host.value.trim()) m.push(t('mail.missingHost'))
  if (needsAuth.value && !hasPassword.value) m.push(t('mail.missingPassword'))
  return m
})

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    const update: TenantMailSettingsUpdate = {
      host: host.value.trim() || null,
      port: port.value.trim() ? Number(port.value) : null,
      from: from.value.trim() || null,
      username: username.value.trim() || null,
      tls: tls.value,
      enabled: enabled.value,
      subjectTemplate: subjectTemplate.value.trim() || null,
      bodyTemplate: bodyTemplate.value.trim() || null,
    }
    // Write-only: send the password ONLY when the owner typed one; an empty field omits it ⇒ the backend keeps the
    // stored ciphertext (the "leave blank to keep" pattern). Never send an empty string (that is not "keep").
    if (password.value) update.password = password.value
    const saved = await updateTenantMailSettings(activeSpace.tenantId!, update)
    applyView(saved)
    password.value = '' // consume — the plaintext never lingers in component state (invariant)
    toast.success(t('mail.saved'))
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { status, code } = await decodeHttpError(e)
      actionError.value =
        code === 'invalid_mail_template'
          ? t('mail.invalidTemplate', { vars: TEMPLATE_VARS })
          : code === 'mail_secret_unavailable'
            ? t('mail.kekUnavailable')
            : status === HttpStatus.forbidden
              ? t('mail.notOwner')
              : t('mail.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  await runSave()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('mail.navTitle') }}</h2>
          <p>{{ t('mail.editorSub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('mail.loading') }}</p>
      <Message v-else-if="loadError === 'notAuthorized'" severity="info" :closable="false">{{ t('mail.notOwner') }}</Message>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">{{ t('mail.loadFailed') }}</Message>

      <template v-else>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

        <!-- Config-secret KEK state (ADR 0050 amendment) dominates the readiness view when not OK — but ONLY for an
             AUTHENTICATING host: a changed key (MISMATCH) or an absent one means the stored password can't be used.
             An auth-less host (no username) has no password to unseal, so the KEK is irrelevant and it falls through
             to the send-readiness view below (mirrors the backend gate, which keys auth on `username`). -->
        <Message v-if="needsAuth && configSecretState === 'MISMATCH'" severity="error" :closable="false" class="mail-readiness">{{ t('mail.keyMismatch') }}</Message>
        <Message v-else-if="needsAuth && configSecretState === 'ABSENT'" severity="warn" :closable="false" class="mail-readiness">{{ t('mail.kekUnavailable') }}</Message>
        <template v-else>
          <!-- Send-readiness (the brief's point 2): `enabled` alone doesn't send — make what's still missing visible. -->
          <Message v-if="sendReady" severity="success" :closable="false" class="mail-readiness">{{ t('mail.sendReady') }}</Message>
          <Message v-else severity="warn" :closable="false" class="mail-readiness">
            <div>{{ t('mail.notSendReady') }}</div>
            <ul class="mail-missing">
              <li v-for="item in missing" :key="item">{{ item }}</li>
            </ul>
          </Message>
        </template>

        <!-- SMTP transport -->
        <label>
          <span>{{ t('mail.host') }}</span>
          <InputText v-model="host" :placeholder="t('mail.hostPlaceholder')" autocomplete="off" />
        </label>
        <div class="mail-field-row">
          <label>
            <span>{{ t('mail.port') }}</span>
            <InputText v-model="port" inputmode="numeric" :placeholder="t('mail.portPlaceholder')" autocomplete="off" />
          </label>
          <label>
            <span>{{ t('mail.from') }}</span>
            <InputText v-model="from" :placeholder="t('mail.fromPlaceholder')" autocomplete="off" />
          </label>
        </div>
        <label>
          <span>{{ t('mail.username') }}</span>
          <InputText v-model="username" autocomplete="off" />
        </label>
        <label>
          <span>{{ t('mail.password') }}</span>
          <InputText v-model="password" type="password" autocomplete="new-password" :placeholder="passwordSet ? t('mail.passwordKeepPlaceholder') : ''" />
          <small class="field-hint">{{ passwordSet ? t('mail.passwordSetHint') : t('mail.passwordHint') }}</small>
        </label>

        <div class="mail-toggle-row">
          <ToggleSwitch v-model="tls" input-id="mail-tls" />
          <label class="mail-toggle-label" for="mail-tls">
            <span>{{ t('mail.tls') }}</span>
            <small class="field-hint">{{ t('mail.tlsHint') }}</small>
          </label>
        </div>
        <div class="mail-toggle-row">
          <ToggleSwitch v-model="enabled" input-id="mail-enabled" />
          <label class="mail-toggle-label" for="mail-enabled">
            <span>{{ t('mail.enabled') }}</span>
            <small class="field-hint">{{ t('mail.enabledHint') }}</small>
          </label>
        </div>

        <!-- Invite template over the CLOSED variable set. The unknown-tag rule lives server-side (400) — surfaced above. -->
        <h3>{{ t('mail.templateTitle') }}</h3>
        <small class="field-hint">{{ t('mail.templateVarsHint', { vars: TEMPLATE_VARS }) }}</small>
        <label>
          <span>{{ t('mail.subject') }}</span>
          <InputText v-model="subjectTemplate" :placeholder="t('mail.subjectPlaceholder', TEMPLATE_TOKENS)" />
        </label>
        <label>
          <span>{{ t('mail.body') }}</span>
          <textarea v-model="bodyTemplate" rows="8" :placeholder="t('mail.bodyPlaceholder', TEMPLATE_TOKENS)"></textarea>
        </label>

        <div class="mail-actions">
          <Button :label="t('mail.save')" icon="pi pi-save" :loading="saving" @click="save" />
        </div>
      </template>
    </section>
  </section>
</template>

<style scoped>
.mail-readiness {
  margin-bottom: 0.25rem;
}
.mail-missing {
  margin: 0.35rem 0 0;
  padding-left: 1.2rem;
}
.mail-missing li {
  margin: 0.1rem 0;
}
.mail-field-row {
  display: grid;
  grid-template-columns: minmax(6rem, 12rem) 1fr;
  gap: 0.75rem;
}
.mail-toggle-row {
  display: flex;
  gap: 0.6rem;
  align-items: flex-start;
}
.mail-toggle-label {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  cursor: pointer;
}
.settings-form textarea {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
  line-height: 1.5;
}
.mail-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
