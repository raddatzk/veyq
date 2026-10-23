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
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqButton from '../shared/ui/VeyqButton.vue'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
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
  <VeyqCard class="login-card">
    <template #title>{{ t('emailConfirm.pageTitle') }}</template>
    <template #default>
      <p v-if="phase === 'noLink'" class="veyq-form-copy">{{ t('emailConfirm.noLink') }}</p>
      <p v-if="phase === 'confirming'" class="veyq-form-copy">{{ t('emailConfirm.confirming') }}</p>

      <template v-if="phase === 'done'">
        <VeyqMessage tone="success" class="form-message">{{ t('emailConfirm.done') }}</VeyqMessage>
        <VeyqButton :label="t('emailConfirm.toSettings')" icon="cog" @click="router.push('/settings/security')" />
      </template>

      <template v-if="phase === 'signInFirst'">
        <VeyqMessage tone="info" class="form-message">{{ t('emailConfirm.signInFirst') }}</VeyqMessage>
        <VeyqButton :label="t('emailConfirm.toSignIn')" icon="sign-in" @click="router.push({ name: 'login' })" />
      </template>

      <VeyqMessage v-if="phase === 'invalid'" tone="error" class="form-message">{{ t('emailConfirm.invalid') }}</VeyqMessage>
      <VeyqMessage v-if="phase === 'failed'" tone="error" class="form-message">{{ t('emailConfirm.failed') }}</VeyqMessage>
    </template>
  </VeyqCard>
</template>
