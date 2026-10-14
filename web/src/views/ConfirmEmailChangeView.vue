<script setup lang="ts">
// uc.account.change-email (web) — the page the change mail's link opens. ConfirmEmailView's pattern, deliberately:
// the token rides the #fragment and is read and stripped on mount AND on `hashchange`; it is sent only with THIS
// device's own session (the proof is that the account holder reads the new mailbox), and with no session it is
// dropped, never kept. Not behind the unlock gate and not in the app shell: it changes operational state, not
// content.
//
// Once the change is done it offers a new Emergency Kit (uc.account.change-email step 5): the old kit's Secret Key is
// still valid, but the kit prints the old address. The password and the Secret Key are asked for here (the key typed,
// pasted, or imported from the old kit), and the kit — EmergencyKitSave's, with the address the server now holds — is
// offered ONLY once they pass the shared cold-credential check against the stored floor (`checkColdCredential`, the
// request's check, usable with a locked vault). A kit printed from a mistyped key fails exactly when it is needed, so
// a key that does not pass prints nothing.
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { confirmEmailChange, confirmRefusal, type ConfirmRefusal } from '../features/account/email/emailChange'
import { getAccountEmail } from '../features/account/email/emailConfirmation'
import { account, setAccount } from '../app/account'
import { readdressRemembered } from '../features/account/rememberMe/deviceKeyWrap'
import { readdressSessionVault } from '../features/account/rememberMe/sessionVault'
import { session } from '../app/session'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import Password from 'primevue/password'
import EmergencyKitSave from '../features/account/emergencyKit/EmergencyKitSave.vue'
import { ColdCredentialRefused, checkColdCredential } from '../features/account/coldCredential'
import { useAsyncAction } from '../shared/state/useAsyncAction'

const { t } = useI18n()
const router = useRouter()

type Phase = 'noLink' | 'confirming' | 'done' | ConfirmRefusal
const phase = ref<Phase>('noLink')
const newEmail = ref('')
const kitPassword = ref('')
const secretKey = ref('')
// The key the kit prints — set only by a passed check, and dropped the moment the typed key changes.
const checkedKey = ref('')
const kitError = ref('')
const kitReady = ref(false)

const { run: checkKit, pending: checking } = useAsyncAction(
  async () => {
    kitError.value = ''
    checkedKey.value = ''
    const key = secretKey.value.trim()
    await checkColdCredential(kitPassword.value, key)
    checkedKey.value = key
    kitPassword.value = ''
  },
  {
    onError: (e) => {
      kitError.value = t(e instanceof ColdCredentialRefused && e.reason === 'credentialRejected' ? 'emailChange.kitRefused' : 'emailChange.kitFailed')
    },
  },
)

function onKeyInput(value: string) {
  secretKey.value = value
  if (value.trim() !== checkedKey.value) checkedKey.value = ''
}

async function consumeFragment() {
  const token = window.location.hash.replace(/^#/, '')
  if (!token) return
  // Strip the secret from the address bar at once, keeping vue-router's own history.state (RecoverView's rule).
  history.replaceState(history.state, '', window.location.pathname)
  if (session.accessToken === null) {
    // The token is dropped with this frame — never stored, so a shared device holds nothing a later sign-in replays.
    phase.value = 'signInFirst'
    return
  }
  phase.value = 'confirming'
  // The address being replaced: this tab's, or the server's when the tab was opened from the mail and holds none.
  const oldEmail = account.email || (await getAccountEmail().then((a) => a.email, () => ''))
  try {
    await confirmEmailChange(token)
  } catch (e) {
    phase.value = await confirmRefusal(e)
    return
  }
  // The address the server now holds — what the new kit prints and what this session, and this device's remembered
  // sign-in, name the account by from here on (a stale one would derive every later proof under the wrong salt).
  try {
    newEmail.value = (await getAccountEmail()).email
    setAccount(newEmail.value)
    if (oldEmail) {
      readdressSessionVault(oldEmail, newEmail.value)
      await readdressRemembered(oldEmail, newEmail.value).catch(() => undefined)
    }
  } catch {
    newEmail.value = ''
  }
  phase.value = 'done'
}

onMounted(consumeFragment)
window.addEventListener('hashchange', consumeFragment)
onUnmounted(() => window.removeEventListener('hashchange', consumeFragment))
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('emailChange.pageTitle') }}</template>
    <template #content>
      <p v-if="phase === 'noLink'" class="veyq-form-copy">{{ t('emailChange.noLink') }}</p>
      <p v-if="phase === 'confirming'" class="veyq-form-copy">{{ t('emailChange.confirming') }}</p>

      <template v-if="phase === 'done'">
        <Message severity="success" :closable="false" class="form-message" data-testid="change-email-done">{{ t('emailChange.done') }}</Message>
        <p class="veyq-form-copy">{{ t('emailChange.doneNote') }}</p>

        <section v-if="newEmail" class="kit-offer" data-testid="change-email-kit">
          <h3>{{ t('emailChange.kitTitle') }}</h3>
          <p class="veyq-form-copy">{{ t('emailChange.kitSub') }}</p>
          <form class="veyq-form" data-testid="change-email-kit-check" @submit.prevent="checkKit()">
            <label class="settings-field">
              {{ t('emailChange.passwordLabel') }}
              <Password v-model="kitPassword" :feedback="false" toggle-mask autocomplete="current-password" :disabled="checking" />
            </label>
            <label class="settings-field">
              {{ t('emailChange.secretKeyLabel') }}
              <SecretKeyField :model-value="secretKey" :disabled="checking" @update:model-value="onKeyInput" />
            </label>
            <Message v-if="kitError" severity="error" :closable="false" data-testid="change-email-kit-error">{{ kitError }}</Message>
            <Button
              v-if="!checkedKey"
              type="submit"
              :label="t('emailChange.kitCheck')"
              icon="pi pi-check"
              severity="secondary"
              :disabled="!kitPassword || !secretKey.trim() || checking"
              :loading="checking"
            />
          </form>
          <EmergencyKitSave v-if="checkedKey" v-model="kitReady" :email="newEmail" :secret-key="checkedKey" />
        </section>

        <Button :label="t('emailConfirm.toSettings')" icon="pi pi-cog" @click="router.push('/settings/security')" />
      </template>

      <template v-if="phase === 'signInFirst'">
        <Message severity="info" :closable="false" class="form-message">{{ t('emailConfirm.signInFirst') }}</Message>
        <Button :label="t('emailConfirm.toSignIn')" icon="pi pi-sign-in" @click="router.push({ name: 'login' })" />
      </template>

      <Message v-if="phase === 'invalid'" severity="error" :closable="false" class="form-message">{{ t('emailChange.invalid') }}</Message>
      <Message v-if="phase === 'notMember'" severity="error" :closable="false" class="form-message">{{ t('emailConfirm.notMember') }}</Message>
      <Message v-if="phase === 'mailUnavailable'" severity="error" :closable="false" class="form-message">{{ t('emailChange.mailUnavailable') }}</Message>
      <Message v-if="phase === 'failed'" severity="error" :closable="false" class="form-message">{{ t('emailChange.failed') }}</Message>
    </template>
  </Card>
</template>

<style scoped>
.kit-offer {
  margin: 1rem 0;
}
.kit-offer h3 {
  margin: 0 0 0.25rem;
}
</style>
