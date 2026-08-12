<script setup lang="ts">
// uc.account.sessions §E3 (web, cr-locked-sign-out-entry-point) — what a LOCKED device is offered once it has
// logged in without unlocking. It renders inside the sign-in card, not behind it: every shell route requires an
// unlocked vault, and a route that needed only a session would be the first session-gated-but-not-unlock-gated
// route in the router — a new security class in exchange for a screen this action does not need.
//
// **One action, and the absence of a list is the design.** Without an unlocked vault there are no names, so a
// per-row pick on an anonymous list is guessing by date — and the dates drift, because `lastUsedAt` is rewritten
// on every refresh, so a lost laptop that kept refreshing looks recent. Worse, the one input that would justify
// SPARING a row — "that is my phone, which will otherwise ask for my Emergency Kit" — lives in the sealed
// register's `wraps` map and is therefore unreadable in exactly this state. The failure modes are asymmetric:
// the bulk action cannot miss the lost device, an anonymous per-row pick can, and leaves the user believing they
// cut it off. Precision would only pay for someone who wants to KEEP other sessions, which is not the goal
// anyone arrives here with.
//
// **Then this device signs out too, and that ordering is load-bearing.** Revoke first — that is the urgent half
// and the only one that needs the session — then drop this one. Nothing remains to do on this screen afterwards:
// no unlock (no kit, by premise), no list, and a second revoke is idempotently pointless, so leaving a live
// authenticated session standing on a stranger's browser as a receipt would trade the exact asset the flow
// protects for a UI nicety. A logout that FAILS is reported rather than swallowed: the caller keeps its session
// flag, so the sign-in card's own sign-out button stays reachable as the visible fallback.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { revokeOtherSessionFamilies } from './sessions'
import { logout } from '../login/login'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const { t } = useI18n()

const emit = defineEmits<{
  /** The action ran. `signedOut` is whether THIS device's own session could be ended too. */
  (e: 'done', signedOut: boolean): void
  /** The way back to unlocking here — the caller re-shows the Secret-Key field, persisting nothing. */
  (e: 'unlock'): void
}>()

const error = ref('')

const { run, pending } = useAsyncAction(
  async () => {
    // Revoke first: it is the half that stops whoever is holding the lost device, and it is the half that needs
    // the session we are about to give up. `logout()` does more than its label — it also clears the device key
    // and the session vault — which on this path are no-ops, since the entry enrolled neither; on a foreign
    // machine doing them anyway is exactly right.
    await revokeOtherSessionFamilies()
    const signedOut = await logout().then(() => true).catch(() => false)
    emit('done', signedOut)
  },
  // A failed revoke stops here and says so: nothing was cut off, and signing this device out would only take
  // away the session needed to retry.
  { onError: () => { error.value = t('sessions.revokeFailed') } },
)

async function signOutEverywhere() {
  // The confirmation carries the consequence, as on every other destructive action of this use-case: a device
  // that was set to stay signed in needs the Emergency Kit at its next start.
  if (!window.confirm(t('sessions.lockedConfirm'))) return
  error.value = ''
  await run()
}
</script>

<template>
  <div class="veyq-form locked-signout">
    <p>{{ t('sessions.lockedNote') }}</p>
    <!-- Decision 3: the "stay signed in on this device" toggle is visible on the form this was pressed from and
         is forced off here. Saying nothing would leave a visible control lying. -->
    <p class="field-hint">{{ t('sessions.lockedScopeNote') }}</p>

    <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>

    <Button
      type="button"
      :label="t('sessions.lockedAction')"
      icon="pi pi-sign-out"
      severity="danger"
      :loading="pending"
      @click="signOutEverywhere"
    />

    <!-- The way back, for the person whose kit was in their bag after all. It re-uses the form's own Secret-Key
         path but persists nothing at any tier — the caller owns that flag, because the enrolment lives there. -->
    <Button
      type="button"
      :label="t('sessions.lockedBack')"
      icon="pi pi-key"
      severity="secondary"
      text
      :disabled="pending"
      @click="emit('unlock')"
    />
    <small class="field-hint">{{ t('sessions.lockedBackHint') }}</small>
  </div>
</template>
