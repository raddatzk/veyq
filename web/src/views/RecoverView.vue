<script setup lang="ts">
// uc.account.recover-access (web) — the three-step reset (ADR 0054).
//
// Which step is shown is decided by the URL fragment, not by local state: no token → ask for a link; token →
// enter the Secret Key, then the new password. The token rides the #fragment (like every other veyq secret in a
// link) so it never reaches the server as part of a navigation and never lands in an access log.
//
// Nothing here invents UI. The Secret-Key field is the same control as on the sign-in screen — same import and
// scan affordances — because a person opening their Emergency Kit under stress should meet what they already know.
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { completeRecovery, openWithSecretKey, releaseRecoveryMaterial, requestRecovery } from '../features/account/recovery/recovery'
import type { OpenedRecovery } from '../features/account/recovery/recovery'
import { parseKit } from '../features/account/emergencyKit/emergencyKit'
import { HttpStatus, isHttpStatus } from '../shared/httpError'
import type { RecoveryMaterial } from '../api/generated/auth'

const { t } = useI18n()
const router = useRouter()

const token = ref('')
const email = ref('')
const secretKey = ref('')
const newPassword = ref('')
const confirmPassword = ref('')

const busy = ref(false)
const error = ref('')
const sent = ref(false)
const done = ref(false)
const material = ref<RecoveryMaterial | null>(null)
const opened = ref<OpenedRecovery | null>(null)

/** Step 1 asks for a link; steps 2–3 need a token from one. */
const hasToken = computed(() => token.value.length > 0)

/** The card title names the current step — the public views carry no in-form headings. */
const stepTitle = computed(() => {
  if (opened.value) return t('recovery.stepPassword')
  if (hasToken.value && material.value) return t('recovery.stepKey')
  return t('recovery.title')
})

/**
 * Pick up a token from the fragment and exchange it for the wrap.
 *
 * Runs on mount AND on `hashchange`, because the two are different arrivals: a fresh tab mounts, but someone
 * who asked for the link on this very page and then clicks it **in the same tab** only changes the fragment —
 * vue-router treats that as the same route and never re-mounts. Without the listener that person sits on the
 * "a link is on its way" message forever, holding a link that works.
 */
async function consumeFragment() {
  const fragment = window.location.hash.replace(/^#/, '')
  if (!fragment || token.value === fragment) return
  token.value = fragment
  sent.value = false
  error.value = ''
  // Strip the secret from the address bar immediately — it has been read, and a token sitting in a shared
  // screenshot or a browser-history entry is a token someone else can present.
  history.replaceState(null, '', window.location.pathname)
  busy.value = true
  try {
    material.value = await releaseRecoveryMaterial(token.value)
  } catch {
    error.value = t('recovery.linkSpent')
    token.value = ''
  } finally {
    busy.value = false
  }
}

onMounted(consumeFragment)
window.addEventListener('hashchange', consumeFragment)
onUnmounted(() => window.removeEventListener('hashchange', consumeFragment))

/**
 * Ask for the link — and say so when the ask fails.
 *
 * There was no `catch` here at all, so a rejected request became an unhandled rejection: `sent` stayed false,
 * `error` stayed empty, and the form simply reappeared — pressing the button and never pressing it rendered
 * identically. The `503` is the branch ADR 0055 §4 requires a screen for (this instance has no mail transport, so
 * no link can exist), but the generic branch is the defect that was actually there: *every* other failure was
 * swallowed the same way. `503` needs no code check — the contract gives this endpoint exactly one meaning for
 * it, and a rate-limited request is a `202` by design, never an error.
 */
async function askForLink() {
  busy.value = true
  error.value = ''
  try {
    await requestRecovery(email.value)
    // Always the same message: the endpoint deliberately does not say whether the address is registered, and
    // this screen must not undo that by branching.
    sent.value = true
  } catch (e) {
    error.value = isHttpStatus(e, HttpStatus.serviceUnavailable)
      ? t('recovery.mailUnavailable')
      : t('recovery.requestFailed')
  } finally {
    busy.value = false
  }
}

/** Accept a pasted kit token as well as a bare Secret Key — the same leniency the unlock screen has. */
function normalizedSecretKey(): string {
  const raw = secretKey.value.trim()
  const kit = parseKit(raw)
  return kit?.secretKey ?? raw
}

async function openAccount() {
  busy.value = true
  error.value = ''
  try {
    opened.value = await openWithSecretKey(material.value!, normalizedSecretKey())
  } catch {
    // A wrong Secret Key fails on the device. The token is untouched, so say so rather than sending them back.
    error.value = t('recovery.keyFailed')
  } finally {
    busy.value = false
  }
}

async function setNewPassword() {
  if (newPassword.value !== confirmPassword.value) {
    error.value = t('recovery.mismatch')
    return
  }
  busy.value = true
  error.value = ''
  try {
    await completeRecovery(token.value, material.value!, opened.value!, normalizedSecretKey(), newPassword.value)
    done.value = true
    setTimeout(() => router.push({ name: 'login' }), 2500)
  } catch {
    error.value = t('recovery.proofFailed')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ stepTitle }}</template>
    <template #content>
      <p v-if="!hasToken" class="veyq-form-copy">{{ t('recovery.intro') }}</p>

      <!-- Step 1 — ask for a link. -->
      <form v-if="!hasToken && !sent" class="veyq-form" @submit.prevent="askForLink">
        <label class="veyq-field">
          {{ t('recovery.emailLabel') }}
          <InputText v-model="email" type="email" name="email" autocomplete="email" required />
        </label>
        <Button
          type="submit"
          :label="busy ? t('recovery.requestSubmitting') : t('recovery.requestSubmit')"
          icon="pi pi-envelope"
          :loading="busy"
        />
      </form>

      <Message v-if="sent" severity="info" :closable="false" class="form-message">
        {{ t('recovery.requestSent') }}
      </Message>

      <!-- Step 2 — the Secret Key. The link got us the wrap; this is what opens it. -->
      <form v-if="hasToken && material && !opened" class="veyq-form" @submit.prevent="openAccount">
        <p class="veyq-form-copy">{{ t('recovery.stepKeyIntro') }}</p>
        <p class="veyq-form-copy">{{ t('recovery.forAccount', { email: material.email }) }}</p>
        <label class="veyq-field">
          {{ t('unlock.secretKey') }}
          <InputText v-model="secretKey" name="secret-key" autocomplete="off" spellcheck="false" required />
          <small class="field-hint">{{ t('unlock.secretKeyHint') }}</small>
        </label>
        <Button type="submit" :label="t('recovery.continueLabel')" icon="pi pi-key" :loading="busy" />
      </form>

      <!-- Step 3 — the new password. -->
      <form v-if="opened && !done" class="veyq-form" @submit.prevent="setNewPassword">
        <label class="veyq-field">
          {{ t('recovery.newPassword') }}
          <Password v-model="newPassword" toggle-mask :feedback="false" input-id="recovery-password" required />
        </label>
        <label class="veyq-field">
          {{ t('recovery.confirmPassword') }}
          <Password v-model="confirmPassword" toggle-mask :feedback="false" input-id="recovery-confirm" required />
        </label>
        <p class="field-hint">{{ t('recovery.deviceNote') }}</p>
        <Button
          type="submit"
          :label="busy ? t('recovery.submitting') : t('recovery.submit')"
          icon="pi pi-check"
          :loading="busy"
        />
      </form>

      <Message v-if="done" severity="success" :closable="false" class="form-message">
        {{ t('recovery.done') }}
      </Message>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>

      <p class="field-hint">{{ t('recovery.noEscrow') }}</p>
    </template>
  </Card>
</template>
