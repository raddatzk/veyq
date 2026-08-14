<script setup lang="ts">
// uc.account.onboard-device § Main flow — add a new device, steps 2–7 (web, ADR 0059). The half that runs on
// the device being let in: mint a single-use transport key, show the number to compare, answer the channel,
// wait for the grant, then run the shared tail.
//
// **It has no route, and that is the point.** It renders inside the sign-in card, replacing the form the way
// `LockedSignOut` does — this device has no session and no vault, so every shell route is closed to it, and a
// route that needed neither would be the first session-gated-but-not-unlock-gated route in the router
// (`scripts/validate_shell_route_gate.py`). Nothing is added to `router.ts` by this screen.
//
// **The number is derived at mint time, from this device's own public bundle** — never from anything the server
// sent back. So it is on screen before the answer is posted, and it is still the right number to compare when
// the answer was refused: an attacker who answered the channel first cannot see it, because it never left here.
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Message from 'primevue/message'
import { mintTransportKey, transportAnswer, checkNumber, openGrant } from './pairing'
import { answerPairingChannel } from './pairingChannel'
import { startGrantEnvelopePolling, stopGrantEnvelopePolling } from './grantEnvelopePolling'
import { runOnboardTail, OnboardTailError } from './onboardTail'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { ACCOUNT_DEVICE_TRUST } from '../../platform/capabilities'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import type * as v1 from '../../../shared/crypto/v1'

// `gone`, `occupied` and `failed` are all terminal, and none of them offers a way back into the ceremony. Two
// of them cannot be retried at all — after the envelope arrives the grant token is spent, and re-presenting it
// is ADR 0004's clone case, which revokes the family of the device standing right here (see `onboardTail`).
type Step = 'answering' | 'waiting' | 'finishing' | 'gone' | 'occupied' | 'failed'

const props = defineProps<{ handle: string }>()

const emit = defineEmits<{
  /** The tail completed: this device holds its own session and an open vault. The caller routes into the shell. */
  (e: 'done'): void
  /** Back to the sign-in form — nothing was granted, or nothing more can be done here. */
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const { hasCapability } = useCapabilities()

const step = ref<Step>('answering')
const error = ref('')

/** The single-use transport keypair. Minted once, here: its private half never leaves this page load, and the
 *  grant is unreadable without it. */
const transportKey: v1.Identity = mintTransportKey()
const number = checkNumber(transportKey.pub)

// The same gate the sign-in form puts on its own "remember me" (ADR 0032): without IndexedDB or with device
// trust switched off instance-wide there is nowhere to enrol a wrap, so the question is not asked — and the
// declined arm is then the only reachable tier (ADR 0005 §22).
const staySupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
const stay = ref(false)
/** ADR 0059 §2 / ADR 0004 § web refresh cookie lifetimes: answered HERE, before the session exists, because
 *  adoption fixes the lifetime and no later refresh can move it. It is also ADR 0005 §22's tier choice. */
const staySignedIn = computed(() => staySupported && stay.value)

const showNumber = computed(() => step.value === 'answering' || step.value === 'waiting' || step.value === 'finishing')
/** The one state that locks the controls, and the only one that may: the tail is running, the lifetime the
 *  checkbox chooses is already fixed at adoption, and there is nothing left to cancel. Locking `answering` too
 *  would disable the question during the very window it has to be answered in — the card opens in that state. */
const settling = computed(() => step.value === 'finishing')

/** Every terminal server state answers `404`, deliberately indistinguishably (E1) — so there is one message and
 *  it is never a retry. */
function onGone() {
  stopGrantEnvelopePolling()
  step.value = 'gone'
}

/** The grant arrived. From here the tail runs exactly once: a failure inside it is terminal, because its first
 *  call spent the only token this device will ever be handed. */
async function onEnvelope(envelope: string) {
  step.value = 'finishing'
  try {
    const grant = openGrant(transportKey.priv, envelope)
    await runOnboardTail(grant, staySignedIn.value)
    emit('done')
  } catch (e) {
    if (e instanceof OnboardTailError && e.reason === 'session_present') {
      step.value = 'occupied'
      return
    }
    if (e instanceof OnboardTailError) {
      step.value = 'gone'
      return
    }
    error.value = t('pairing.joinFailed')
    step.value = 'failed'
  }
}

/** Set on unmount. The answer POST is in flight when the user cancels, so without this the loop would be
 *  started *after* the card is gone — and an orphaned loop does not merely leak a timer here: its next tick
 *  collects the envelope and spends the channel, leaving the real attempt with nothing. */
let disposed = false

async function begin() {
  try {
    await answerPairingChannel(props.handle, transportAnswer(transportKey.pub))
  } catch (e) {
    if (disposed) return
    // A refused answer is a channel that is already spent, expired or superseded — the number on screen is
    // still this device's own, so the person can see it does not match whatever their other device shows.
    if (isHttpStatus(e, HttpStatus.notFound)) return onGone()
    error.value = t('pairing.joinAnswerFailed')
    step.value = 'failed'
    return
  }
  if (disposed) return
  step.value = 'waiting'
  startGrantEnvelopePolling(props.handle, (envelope) => void onEnvelope(envelope), onGone)
}

void begin()

onUnmounted(() => {
  disposed = true
  stopGrantEnvelopePolling()
})
</script>

<template>
  <div class="veyq-form join-device">
    <template v-if="showNumber">
      <p>{{ t('pairing.joinIntro') }}</p>
      <p class="join-number" data-testid="join-check-number">{{ number }}</p>
      <p class="field-hint">{{ t('pairing.joinCompare') }}</p>

      <!-- Answered before the first call, not at the end: adoption fixes the refresh lifetime for good. -->
      <div v-if="staySupported" class="checkbox-row">
        <Checkbox v-model="stay" input-id="join-stay" binary :disabled="settling" />
        <label for="join-stay">
          {{ t('login.rememberMe') }}
          <small class="field-hint">{{ t('pairing.joinStayHint') }}</small>
        </label>
      </div>

      <p class="join-status">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        <span>{{ step === 'finishing' ? t('pairing.joinFinishing') : t('pairing.joinWaiting') }}</span>
      </p>

      <Button
        type="button"
        :label="t('common.cancel')"
        severity="secondary"
        text
        :disabled="settling"
        @click="emit('cancel')"
      />
    </template>

    <template v-else>
      <!-- `gone` is E1's single message. It is also what an adopt `401` reaches, because the grant is spent by
           then — but "start again" there means a NEW ceremony on the other device, which the copy says. -->
      <Message v-if="step === 'gone'" severity="warn" :closable="false" class="form-message">
        {{ t('pairing.gone') }}
      </Message>
      <!-- Not folded into the message above, because the remedy is different: this browser is already signed in
           somewhere, and starting the ceremony over would not change that. -->
      <Message v-else-if="step === 'occupied'" severity="warn" :closable="false" class="form-message">
        {{ t('pairing.joinOccupied') }}
      </Message>
      <Message v-else severity="error" :closable="false" class="form-message">{{ error }}</Message>

      <p v-if="step === 'gone'" class="field-hint">{{ t('pairing.joinGoneHint') }}</p>

      <Button type="button" :label="t('pairing.joinBack')" icon="pi pi-arrow-left" @click="emit('cancel')" />
    </template>
  </div>
</template>

<style scoped>
.join-number {
  margin: 0.5rem 0 0.25rem;
  text-align: center;
  font-size: 2rem;
  letter-spacing: 0.35rem;
  font-variant-numeric: tabular-nums;
}
.join-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0.5rem 0 0;
  color: var(--app-muted);
}
</style>
