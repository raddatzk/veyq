<script setup lang="ts">
// uc.account.confirm-email-address (web, ADR 0074) — the page a confirmation mail's link opens.
//
// The token rides the #fragment like every veyq secret in a link, and is read and stripped exactly as RecoverView
// does it: on mount AND on `hashchange` (a link clicked in a tab already on this page only changes the fragment,
// and vue-router does not re-mount for that).
//
// **It confirms only with this device's own session** (Decision 5): the proof is that *the account holder* reads
// the mailbox, so whoever reads a mistyped one must not be able to click it into "confirmed". No session here →
// nothing is sent and the token is **not kept** on this device (A4); it stays live until it expires, for a device
// where its owner is signed in. Signed in as somebody else → the server's one generic refusal (A5). Not behind
// the unlock gate, and not in the app shell: this is operational state, not content.
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Card from 'primevue/card'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { confirmEmail } from '../features/account/email/emailConfirmation'
import { session } from '../app/session'
import { HttpStatus, httpStatus } from '../shared/httpError'

const { t } = useI18n()
const router = useRouter()

type Phase = 'noLink' | 'confirming' | 'done' | 'signInFirst' | 'invalid' | 'failed'
const phase = ref<Phase>('noLink')

async function consumeFragment() {
  const token = window.location.hash.replace(/^#/, '')
  if (!token) return
  // Strip the secret from the address bar at once, keeping vue-router's own history.state (RecoverView's rule).
  history.replaceState(history.state, '', window.location.pathname)
  if (session.accessToken === null) {
    // A4. The token is dropped with this function's frame — never stored, so a shared or stolen device holds
    // nothing a later sign-in could replay.
    phase.value = 'signInFirst'
    return
  }
  phase.value = 'confirming'
  try {
    await confirmEmail(token)
    phase.value = 'done'
  } catch (e) {
    const status = httpStatus(e)
    phase.value =
      status === HttpStatus.notFound ? 'invalid' : status === HttpStatus.unauthorized ? 'signInFirst' : 'failed'
  }
}

onMounted(consumeFragment)
window.addEventListener('hashchange', consumeFragment)
onUnmounted(() => window.removeEventListener('hashchange', consumeFragment))
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('emailConfirm.pageTitle') }}</template>
    <template #content>
      <p v-if="phase === 'noLink'" class="veyq-form-copy">{{ t('emailConfirm.noLink') }}</p>
      <p v-if="phase === 'confirming'" class="veyq-form-copy">{{ t('emailConfirm.confirming') }}</p>

      <template v-if="phase === 'done'">
        <Message severity="success" :closable="false" class="form-message">{{ t('emailConfirm.done') }}</Message>
        <Button :label="t('emailConfirm.toSettings')" icon="pi pi-cog" @click="router.push('/settings/security')" />
      </template>

      <template v-if="phase === 'signInFirst'">
        <Message severity="info" :closable="false" class="form-message">{{ t('emailConfirm.signInFirst') }}</Message>
        <Button :label="t('emailConfirm.toSignIn')" icon="pi pi-sign-in" @click="router.push({ name: 'login' })" />
      </template>

      <Message v-if="phase === 'invalid'" severity="error" :closable="false" class="form-message">{{ t('emailConfirm.invalid') }}</Message>
      <Message v-if="phase === 'failed'" severity="error" :closable="false" class="form-message">{{ t('emailConfirm.failed') }}</Message>
    </template>
  </Card>
</template>
