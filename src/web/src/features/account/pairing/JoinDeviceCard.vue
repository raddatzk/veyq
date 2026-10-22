<script setup lang="ts">
// uc.account.onboard-device § Main flow — add a new device, steps 2–7 (web, ADR 0059). The half that runs on
// the device being let in: derive the channel from the scanned secret, show the number to compare, answer the
// channel, then wait for the grant — which, with the transport key, the stay-signed-in question and the shared
// tail, is `useGrantReceiver`'s, the same as the other direction's.
//
// **It has no route, and that is the point.** It renders inside the sign-in card, replacing the form the way
// `LockedSignOut` does — this device has no session and no vault, so every shell route is closed to it, and a
// route that needed neither would be the first session-gated-but-not-unlock-gated route in the router
// (`scripts/validate_shell_route_gate.py`). Nothing is added to `router.ts` by this screen.
//
// **The number is derived at mint time, from this device's own public bundle** — never from anything the server
// sent back. So it is on screen before the answer is posted, and it is still the right number to compare when
// the answer was refused. The relay, which carries the answer, can see the number too; the answer's MAC under
// the channel key is what keeps it from answering with a bundle ground to match (ADR 0059 §11).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import GrantReceiverOutcome from './GrantReceiverOutcome.vue'
import { channelKeys, transportAnswer, checkNumber } from './pairing'
import { answerPairingChannel } from './pairingChannel'
import { useGrantReceiver } from './useGrantReceiver'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const props = defineProps<{ secret: string }>()

const emit = defineEmits<{
  /** The tail completed: this device holds its own session and an open vault. The caller routes into the shell. */
  (e: 'done'): void
  /** Back to the sign-in form — nothing was granted, or nothing more can be done here. */
  (e: 'cancel'): void
}>()

const { t } = useI18n()
const { step, error, transportKey, staySupported, stay, settling, isDisposed, onAnswerFailed, awaitGrant } =
  useGrantReceiver('answering')

const channel = channelKeys(props.secret)
const number = checkNumber(transportKey.pub)

const showNumber = computed(() => step.value === 'answering' || step.value === 'waiting' || step.value === 'finishing')

async function begin() {
  try {
    await answerPairingChannel(channel.handle, transportAnswer(channel.key, transportKey.pub))
  } catch (e) {
    // A refused answer is a channel that is already spent, expired or superseded — the number on screen is
    // still this device's own, so the person can see it does not match whatever their other device shows.
    return onAnswerFailed(e, t('pairing.joinAnswerFailed'))
  }
  if (isDisposed()) return
  awaitGrant(channel, () => emit('done'))
}

void begin()
</script>

<template>
  <div class="veyq-form join-device">
    <template v-if="showNumber">
      <p>{{ t('pairing.joinIntro') }}</p>
      <p class="join-number" data-testid="join-check-number">{{ number }}</p>
      <p class="field-hint">{{ t('pairing.joinCompare') }}</p>

      <!-- Answered before the first call, not at the end: adoption fixes the refresh lifetime for good. -->
      <div v-if="staySupported" class="checkbox-row">
        <VeyqCheckbox v-model="stay" input-id="join-stay" :disabled="settling" />
        <label for="join-stay">
          {{ t('login.rememberMe') }}
          <small class="field-hint">{{ t('pairing.joinStayHint') }}</small>
        </label>
      </div>

      <p class="join-status">
        <VeyqIcon name="spinner" spin />
        <span>{{ step === 'finishing' ? t('pairing.joinFinishing') : t('pairing.joinWaiting') }}</span>
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

    <GrantReceiverOutcome v-else :step="step" :error="error" :gone-hint="t('pairing.joinGoneHint')" @back="emit('cancel')" />
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
