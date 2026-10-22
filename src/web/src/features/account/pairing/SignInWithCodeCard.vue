<script setup lang="ts">
// uc.account.onboard-device § Main flow — sign in with a QR code, steps 2–7 (web, ADR 0059). The half that runs
// on the device that wants in: mint the channel's secret, open the channel, display the secret under THIS
// direction's marker, take the code the trusted device shows, send key and proof as the channel's one answer.
// Waiting for the grant — with the transport key, the stay-signed-in question and the shared tail — is
// `useGrantReceiver`'s, the same as the other direction's.
//
// **It has no route, for the same reason `JoinDeviceCard` has none.** It renders inside the sign-in card,
// replacing the form: this device has no session and no vault, so every shell route is closed to it, and a route
// that needed neither would be the first session-gated-but-not-unlock-gated route in the router
// (`scripts/validate_shell_route_gate.py`). Nothing is added to `router.ts` by this screen.
//
// **The displayed code carries the channel's secret, never the transport key** (D1). A serialized bundle is 1256
// bytes — a QR version 34 nobody scans off a laptop screen, past the decoder bound `src/web/CLAUDE.md`'s jsQR
// exception rests on. Nothing is lost: the key crosses the relay MACed under the channel key the secret derives,
// bound to the transcribed code, so neither the relay nor a phisher who authored the QR can swap it
// (ADR 0059 §5, §11).
//
// **There is exactly one send, and no retry to offer.** The answer slot is write-once on the server and the
// channel carries no status, so nothing can tell this device its proof was refused — a wrong code is learned
// only as the channel going quiet and then expiring. That is why the transcription stays editable until it is
// sent and why the terminal copy says *start again* rather than *keep waiting* (uc.account.onboard-device E2).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import GrantReceiverOutcome from './GrantReceiverOutcome.vue'
import PairingCode from './PairingCode.vue'
import { mintPairingSecret, channelKeys, signInPayload, signInAnswer, type PairingChannelKeys } from './pairing'
import { openPairingChannel, answerPairingChannel } from './pairingChannel'
import { useGrantReceiver } from './useGrantReceiver'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const emit = defineEmits<{
  /** The tail completed: this device holds its own session and an open vault. The caller routes into the shell. */
  (e: 'done'): void
  /** Back to the sign-in form — nothing was granted, or nothing more can be done here. */
  (e: 'cancel'): void
}>()

const { t } = useI18n()
// `opening` and `transcribing` are this direction's own. A REFUSED proof lands in the shared `gone`: the trusted
// device seals nothing, the row expires, and the poll's 404 is the only thing this side ever learns. It is
// deliberately the same message as every other terminal state (E1).
const { step, error, transportKey, staySupported, stay, settling, isDisposed, fail, onAnswerFailed, awaitGrant } =
  useGrantReceiver<'opening' | 'transcribing'>('opening')

const secret = ref('')
let channel: PairingChannelKeys | null = null
const code = ref('')

const payload = computed(() => signInPayload(secret.value))
/** Exactly the six digits the trusted device drew — no partial send, and nothing to trim server-side. */
const canSend = computed(() => /^\d{6}$/.test(code.value))
const showTranscription = computed(() => step.value === 'transcribing')

const { run: runOpen } = useAsyncAction(
  async () => {
    const fresh = mintPairingSecret()
    const keys = channelKeys(fresh)
    await openPairingChannel(keys.handle)
    if (isDisposed()) return
    secret.value = fresh
    channel = keys
    step.value = 'transcribing'
  },
  { onError: () => { if (!isDisposed()) fail(t('pairing.signInOpenFailed')) } },
)

/**
 * The one send. `sent` is set SYNCHRONOUSLY, before the await, because the guard has to hold against a second
 * click that lands while the first POST is still in flight — the server's answer slot is write-once, so a race
 * that got two posts through would burn the ceremony on the loser and leave both screens looking correct.
 */
let sent = false

const { run: runSend, pending: sending } = useAsyncAction(
  async () => {
    if (sent || !canSend.value || !channel) return
    sent = true
    const keys = channel
    try {
      // Key AND proof as the channel's single relayed value (D2): `seal` takes no code and never may (§6), so
      // this is the only slot the proof can travel in.
      await answerPairingChannel(keys.handle, signInAnswer(keys.key, transportKey.pub, code.value))
    } catch (e) {
      // A refused answer is a channel already spent, expired or superseded. There is nothing to re-send: the
      // slot is write-once, so this is terminal even though nothing was proven.
      return onAnswerFailed(e, t('pairing.signInAnswerFailed'))
    }
    if (isDisposed()) return
    awaitGrant(keys, () => emit('done'))
  },
)

async function send() {
  error.value = ''
  await runSend()
}

void runOpen()
</script>

<template>
  <div class="veyq-form sign-in-code">
    <template v-if="step === 'opening'">
      <p class="join-status">
        <VeyqIcon name="spinner" spin />
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
          <VeyqInput
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
          <VeyqCheckbox v-model="stay" input-id="sign-in-stay" />
          <label for="sign-in-stay">
            {{ t('login.rememberMe') }}
            <small class="field-hint">{{ t('pairing.joinStayHint') }}</small>
          </label>
        </div>

        <VeyqButton
          type="button"
          :label="t('pairing.signInSend')"
          icon="check"
          :disabled="!canSend || sending"
          :loading="sending"
          @click="send"
        />
      </template>

      <p v-else class="join-status">
        <VeyqIcon name="spinner" spin />
        <span>{{ step === 'finishing' ? t('pairing.joinFinishing') : t('pairing.signInWaiting') }}</span>
      </p>

      <VeyqButton
        type="button"
        :label="t('common.cancel')"
        variant="secondary"
        appearance="text"
        :disabled="settling"
        @click="emit('cancel')"
      />
    </template>

    <GrantReceiverOutcome v-else :step="step" :error="error" :gone-hint="t('pairing.signInGoneHint')" @back="emit('cancel')" />
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
