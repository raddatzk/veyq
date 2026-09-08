<script setup lang="ts">
// uc.account.onboard-device § Main flow — sign in with a QR code, steps 2–7 (web, ADR 0059). The half that runs
// on the device that wants in: mint a single-use transport key, open a channel, display its handle under THIS
// direction's marker, take the code the trusted device shows, send key and proof as the channel's one answer,
// then run the shared tail.
//
// **It has no route, for the same reason `JoinDeviceCard` has none.** It renders inside the sign-in card,
// replacing the form: this device has no session and no vault, so every shell route is closed to it, and a route
// that needed neither would be the first session-gated-but-not-unlock-gated route in the router
// (`scripts/validate_shell_route_gate.py`). Nothing is added to `router.ts` by this screen.
//
// **The displayed code carries the handle, never the transport key** (D1). A serialized bundle is 1260 bytes —
// a QR version 28 nobody scans off a laptop screen, past the decoder bound `web/CLAUDE.md`'s jsQR exception
// rests on. Nothing is lost: in this direction the scan authenticates nothing anyway (the attacker authors the
// QR, therefore any key in it — ADR 0059 §5). The transcribed code is what authenticates the key.
//
// **There is exactly one send, and no retry to offer.** The answer slot is write-once on the server and the
// channel carries no status, so nothing can tell this device its proof was refused — a wrong code is learned
// only as the channel going quiet and then expiring. That is why the transcription stays editable until it is
// sent and why the terminal copy says *start again* rather than *keep waiting* (uc.account.onboard-device E2).
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import PairingCode from './PairingCode.vue'
import { mintHandle, mintTransportKey, signInPayload, signInAnswer, openGrant } from './pairing'
import { openPairingChannel, answerPairingChannel } from './pairingChannel'
import { startGrantEnvelopePolling, stopGrantEnvelopePolling } from './grantEnvelopePolling'
import { runOnboardTail, OnboardTailError } from './onboardTail'
import { isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { ACCOUNT_DEVICE_TRUST } from '../../platform/capabilities'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import type * as v1 from '@veyq/crypto/v1'

// `gone`, `occupied` and `failed` are terminal and none offers a way back into this ceremony. `gone` is also
// where a REFUSED proof lands: the trusted device seals nothing, the row expires, and the poll's 404 is the only
// thing this side ever learns. It is deliberately the same message as every other terminal state (E1).
type Step = 'opening' | 'transcribing' | 'waiting' | 'finishing' | 'gone' | 'occupied' | 'failed'

const emit = defineEmits<{
  /** The tail completed: this device holds its own session and an open vault. The caller routes into the shell. */
  (e: 'done'): void
  /** Back to the sign-in form — nothing was granted, or nothing more can be done here. */
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const { hasCapability } = useCapabilities()

const step = ref<Step>('opening')
const handle = ref('')
const code = ref('')
const error = ref('')

/** The single-use transport keypair. Minted once, here: its private half never leaves this page load, and the
 *  grant is unreadable without it. The public half goes on the wire bound to the code, never in the QR. */
const transportKey: v1.Identity = mintTransportKey()

const payload = computed(() => signInPayload(handle.value))
/** Exactly the six digits the trusted device drew — no partial send, and nothing to trim server-side. */
const canSend = computed(() => /^\d{6}$/.test(code.value))
const showTranscription = computed(() => step.value === 'transcribing')
const settling = computed(() => step.value === 'finishing')

// The same gate the sign-in form puts on its own "remember me" (ADR 0032): without IndexedDB or with device
// trust switched off instance-wide there is nowhere to enrol a wrap, so the question is not asked — and the
// declined arm is then the only reachable tier (ADR 0005 §22).
const staySupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
const stay = ref(false)
/** ADR 0059 §2 / ADR 0004 § web refresh cookie lifetimes: answered HERE, before the session exists, because
 *  adoption fixes the lifetime and no later refresh can move it. It is also ADR 0005 §22's tier choice. */
const staySignedIn = computed(() => staySupported && stay.value)

/** Every terminal server state answers `404`, deliberately indistinguishably (E1) — and a refused proof is
 *  reached the same way, because there is no refusal to relay. One message, never a retry. */
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

/** Set on unmount. The open/answer POSTs are in flight when the user cancels, so without this the loop would be
 *  started *after* the card is gone — and an orphaned loop does not merely leak a timer here: its next tick
 *  collects the envelope and spends the channel, leaving the real attempt with nothing. */
let disposed = false

const { run: runOpen } = useAsyncAction(
  async () => {
    const fresh = mintHandle()
    await openPairingChannel(fresh)
    if (disposed) return
    handle.value = fresh
    step.value = 'transcribing'
  },
  { onError: () => { if (!disposed) { error.value = t('pairing.signInOpenFailed'); step.value = 'failed' } } },
)

/**
 * The one send. `sent` is set SYNCHRONOUSLY, before the await, because the guard has to hold against a second
 * click that lands while the first POST is still in flight — the server's answer slot is write-once, so a race
 * that got two posts through would burn the ceremony on the loser and leave both screens looking correct.
 */
let sent = false

const { run: runSend, pending: sending } = useAsyncAction(
  async () => {
    if (sent || !canSend.value) return
    sent = true
    try {
      // Key AND proof as the channel's single relayed value (D2): `seal` takes no code and never may (§6), so
      // this is the only slot the commitment can travel in.
      await answerPairingChannel(handle.value, signInAnswer(transportKey.pub, code.value))
    } catch (e) {
      if (disposed) return
      // A refused answer is a channel already spent, expired or superseded. There is nothing to re-send: the
      // slot is write-once, so this is terminal even though nothing was proven.
      if (isHttpStatus(e, HttpStatus.notFound)) return onGone()
      error.value = t('pairing.signInAnswerFailed')
      step.value = 'failed'
      return
    }
    if (disposed) return
    step.value = 'waiting'
    startGrantEnvelopePolling(handle.value, (envelope) => void onEnvelope(envelope), onGone)
  },
)

async function send() {
  error.value = ''
  await runSend()
}

void runOpen()

onUnmounted(() => {
  disposed = true
  stopGrantEnvelopePolling()
})
</script>

<template>
  <div class="veyq-form sign-in-code">
    <template v-if="step === 'opening'">
      <p class="join-status">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        <span>{{ t('pairing.signInOpening') }}</span>
      </p>
    </template>

    <template v-else-if="showTranscription || step === 'waiting' || step === 'finishing'">
      <template v-if="showTranscription">
        <p>{{ t('pairing.signInIntro') }}</p>
        <PairingCode :payload="payload" />
        <p class="field-hint">{{ t('pairing.signInScanHint') }}</p>

        <!-- Editable until it is sent, which is where every typo is caught for free — there is exactly one send
             and no way for the other device to say "wrong, try again" (ADR 0059 §6). -->
        <label class="sign-in-code-field">
          {{ t('pairing.signInCodeField') }}
          <InputText
            v-model="code"
            id="sign-in-code"
            inputmode="numeric"
            autocomplete="one-time-code"
            maxlength="6"
            :spellcheck="false"
            fluid
          />
          <small class="field-hint">{{ t('pairing.signInCodeHint') }}</small>
        </label>

        <!-- Answered before the first call, not at the end: adoption fixes the refresh lifetime for good. -->
        <div v-if="staySupported" class="checkbox-row">
          <Checkbox v-model="stay" input-id="sign-in-stay" binary />
          <label for="sign-in-stay">
            {{ t('login.rememberMe') }}
            <small class="field-hint">{{ t('pairing.joinStayHint') }}</small>
          </label>
        </div>

        <Button
          type="button"
          :label="t('pairing.signInSend')"
          icon="pi pi-check"
          :disabled="!canSend || sending"
          :loading="sending"
          @click="send"
        />
      </template>

      <p v-else class="join-status">
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        <span>{{ step === 'finishing' ? t('pairing.joinFinishing') : t('pairing.signInWaiting') }}</span>
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
      <!-- E1's single message, and also where a REFUSED code lands: this device cannot be told it was wrong, so
           the copy has to send the person back to the start rather than leave them waiting. -->
      <Message v-if="step === 'gone'" severity="warn" :closable="false" class="form-message">
        {{ t('pairing.gone') }}
      </Message>
      <!-- Not folded into the message above, because the remedy is different: this browser is already signed in
           somewhere, and starting the ceremony over would not change that. -->
      <Message v-else-if="step === 'occupied'" severity="warn" :closable="false" class="form-message">
        {{ t('pairing.joinOccupied') }}
      </Message>
      <Message v-else severity="error" :closable="false" class="form-message">{{ error }}</Message>

      <p v-if="step === 'gone'" class="field-hint">{{ t('pairing.signInGoneHint') }}</p>

      <Button type="button" :label="t('pairing.joinBack')" icon="pi pi-arrow-left" @click="emit('cancel')" />
    </template>
  </div>
</template>

<style scoped>
.sign-in-code-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.sign-in-code-field :deep(input) {
  font-size: 1.5rem;
  letter-spacing: 0.35rem;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
.join-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0.5rem 0 0;
  color: var(--app-muted);
}
</style>
