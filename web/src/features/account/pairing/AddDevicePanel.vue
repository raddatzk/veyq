<script setup lang="ts">
// uc.account.onboard-device (web, ADR 0059). The trusted, unlocked device's half of BOTH directions — ADR 0059
// §1 requires each device to offer *show a code* and *scan a code*, so whichever device has a camera is the one
// that scans and neither direction is the "camera" one.
//
//   - **show a code** (*add a device*): show a code, wait for the answer, compare a number, take a deliberate
//     confirmation, then delegate and seal.
//   - **scan a code** (*sign in with a QR code*): scan, state what is being granted and display a six-digit
//     pairing code, wait for the answer, VERIFY the proof, and only then take the name and the confirmation.
//
// It lives in the settings hub because that is where the premise holds — `/settings/:section?` is already
// `requiresUnlock`, and this screen is meaningless without an open vault. Neither arm adds a route.
//
// **The deliberate gesture is the LAST step before the irreversible one, in both arms** (D9). In the scanned
// direction that means the slide comes AFTER the proof holds, never before: a gesture spent before the evidence
// exists cannot be about the evidence, and a wrong code would otherwise cost the user the whole ceremony and
// hand back nothing — which reads as *"it glitched, try again"*, the exact reflex ADR 0059 §7 is written
// against. What does NOT move is the information: what is being granted is on screen from the moment the code
// is displayed.
//
// **The order delegate → seal is the server's, not a preference**: the grant is sealed here, so its plaintext
// (the delegated refresh token) has to be in this device's hand first.
//
// **Declining is not a retry.** E2 abandons: no delegate, no seal, the handle is discarded and the row is left
// to expire. Starting over mints a NEW handle, because a mismatch is the one signal that something other than
// the intended device answered, and offering "try again" on the same channel invites granting past it.
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import PairingCode from './PairingCode.vue'
import KitScanner from '../emergencyKit/KitScanner.vue'
import SlideToConfirm from '../../../shared/ui/SlideToConfirm.vue'
import {
  mintHandle,
  pairingPayload,
  parseSignInPayload,
  mintPairingCode,
  readTransportAnswer,
  readSignInAnswer,
  codeProofHolds,
  checkNumber,
  sealGrant,
} from './pairing'
import {
  openPairingChannel,
  delegatePairingSession,
  sealPairingChannel,
} from './pairingChannel'
import { startPairingAnswerPolling, stopPairingAnswerPolling } from './pairingAnswerPolling'
import { account } from '../../../app/account'
import { masterKey } from '../../../app/vault'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import type * as v1 from '@veyq/crypto/v1'

// `sealed` is terminal on this device and says so: the collect that hands the grant over spends the channel
// whoever calls it, so this side cannot look again to see whether the other device arrived (see the poll
// module). `gone` is every terminal server state at once — they are indistinguishable on purpose (E1).
//
// `awaiting`/`comparing` are the shown-code arm; `transcribing`/`proven`/`refused` the scanned one. `refused` is
// terminal and deliberately offers no retry: the answer slot is write-once, so there is no second proof to
// accept, and offering "try again" in place is the invitation to grant past the one signal there is (E2).
type Step =
  | 'idle'
  | 'awaiting'
  | 'comparing'
  | 'transcribing'
  | 'proven'
  | 'refused'
  | 'sealed'
  | 'gone'

const { t } = useI18n()

const step = ref<Step>('idle')
const handle = ref('')
const label = ref('')
const error = ref('')
/** The answering device's public bundle, kept from the moment the answer lands: the number on screen and the
 *  key the grant is sealed to must be the same one, or the comparison guarantees nothing. */
const answeringKey = ref<v1.Identity['pub'] | null>(null)
/** The six digits this device drew and displays, for the account holder to type into the new one. Freshly
 *  drawn and NOT derived from any key: in this direction the attacker authors the QR, so anything derivable
 *  from it is already theirs (ADR 0059 §5). It is also why this arm shows no comparison number at all. */
const pairingCode = ref('')
const scanOpen = ref(false)

const code = computed(() => pairingPayload(handle.value))
const number = computed(() => (answeringKey.value ? checkNumber(answeringKey.value) : ''))
const canConfirm = computed(() => label.value.trim().length > 0)
/** Both arms end on the same screen: name it, then slide. `proven` is only ever reached once the proof held. */
const confirming = computed(() => step.value === 'comparing' || step.value === 'proven')

function reset() {
  stopPairingAnswerPolling()
  step.value = 'idle'
  handle.value = ''
  label.value = ''
  answeringKey.value = null
  pairingCode.value = ''
  scanOpen.value = false
  error.value = ''
}

function onGone() {
  stopPairingAnswerPolling()
  answeringKey.value = null
  step.value = 'gone'
}

const { run: runStart, pending: starting } = useAsyncAction(
  async () => {
    const fresh = mintHandle()
    await openPairingChannel(fresh)
    handle.value = fresh
    step.value = 'awaiting'
    startPairingAnswerPolling(
      fresh,
      (answer) => {
        try {
          answeringKey.value = readTransportAnswer(answer)
          step.value = 'comparing'
        } catch {
          // An answer this device cannot read is not a channel to keep waiting on: nothing can be sealed to it.
          onGone()
        }
      },
      onGone,
    )
  },
  { onError: () => { error.value = t('pairing.startFailed') } },
)

async function start() {
  reset()
  await runStart()
}

// --- The scanned arm: sign in with a QR code (uc.account.onboard-device § Main flow, steps 3-6) ---

/** Which decoded strings this scanner stops on. It must accept ONLY this direction's marker: a `veyqpair:` code
 *  is one THIS device showed, and treating it as a scan would have the panel pair with itself. Keeping it a
 *  predicate is what lets the camera scan past a foreign code instead of reporting it as a failure. */
const acceptsSignIn = (text: string): boolean => parseSignInPayload(text) !== null

function openScan() {
  reset()
  scanOpen.value = true
}

/**
 * The scan landed. Draw the pairing code and start watching for the answer.
 *
 * No channel is opened here — the other device already opened it; this side only learned its handle. And
 * nothing is delegated or sealed yet: what goes on screen now is the CODE and what is being granted, which is
 * D9's "information early, commitment last".
 */
function onScanned(text: string) {
  scanOpen.value = false
  const scanned = parseSignInPayload(text)
  if (!scanned) return
  handle.value = scanned
  pairingCode.value = mintPairingCode()
  step.value = 'transcribing'
  startPairingAnswerPolling(scanned, onSignInAnswer, onGone)
}

/**
 * The other device answered with its key and a proof of the code. **This is the gate** (ADR 0059 §6): the proof
 * is checked HERE, against the code this device drew, and a failure reaches neither `delegate` nor `seal` — and
 * does not so much as offer the slide.
 *
 * `answeringKey` is cleared on refusal rather than merely left unused. `runGrant` reads it and throws without
 * one, so the key nothing may be sealed to is not sitting in a ref for a later code path to find.
 */
function onSignInAnswer(answer: string) {
  stopPairingAnswerPolling()
  try {
    const { pub, commitment } = readSignInAnswer(answer)
    if (!codeProofHolds(pub, pairingCode.value, commitment)) {
      answeringKey.value = null
      step.value = 'refused'
      return
    }
    answeringKey.value = pub
    step.value = 'proven'
  } catch {
    // An answer this device cannot read is not a channel to keep waiting on: nothing can be sealed to it.
    onGone()
  }
}

/**
 * The confirmation ran. Delegate, then seal — and only from here, so that a declined or unreached comparison
 * reaches neither call.
 */
const { run: runGrant, pending: granting } = useAsyncAction(
  async () => {
    const pub = answeringKey.value
    const mk = masterKey()
    if (!pub || !mk) throw new Error('pairing_not_ready')
    const refreshToken = await delegatePairingSession(handle.value)
    const envelope = sealGrant(pub, {
      email: account.email,
      masterKey: mk,
      refreshToken,
      label: label.value.trim(),
    })
    await sealPairingChannel(handle.value, envelope)
    step.value = 'sealed'
  },
  { onError: () => { error.value = t('pairing.grantFailed') } },
)

async function onConfirmed() {
  error.value = ''
  await runGrant()
}

/** E2 — the numbers do not match. Nothing is delegated, nothing is sealed, and the channel is left to expire.
 *  The screen goes back to its start, where the next attempt mints a fresh handle. */
function decline() {
  reset()
}

onUnmounted(stopPairingAnswerPolling)
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('pairing.title') }}</h2>
          <p>{{ t('pairing.sub') }}</p>
        </div>
      </div>

      <!-- ADR 0059 §1: both halves, on every device. Whichever device has a camera is the one that scans, so
           neither direction is the "phone" one — and offering only one half here would make it so. -->
      <template v-if="step === 'idle'">
        <p class="pairing-copy">{{ t('pairing.intro') }}</p>
        <p class="pairing-copy">{{ t('pairing.scanIntro') }}</p>
        <div class="pairing-actions">
          <Button
            type="button"
            :label="t('pairing.start')"
            icon="pi pi-qrcode"
            :loading="starting"
            @click="start"
          />
          <Button
            type="button"
            :label="t('pairing.scanStart')"
            icon="pi pi-camera"
            severity="secondary"
            outlined
            @click="openScan"
          />
        </div>
      </template>

      <template v-else-if="step === 'awaiting'">
        <p class="pairing-copy">{{ t('pairing.scanHint') }}</p>
        <PairingCode :payload="code" />
        <p class="pairing-waiting">
          <i class="pi pi-spin pi-spinner" aria-hidden="true" />
          <span>{{ t('pairing.waiting') }}</span>
        </p>
        <div class="pairing-actions">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="reset" />
        </div>
      </template>

      <!-- The scanned direction, before the proof. What is being granted is stated HERE, with the code — the
           information comes early; only the gesture is deferred until the proof holds (D9). No comparison
           number is shown on either screen: the attacker authored one of them, so comparing is theatre (§5). -->
      <template v-else-if="step === 'transcribing'">
        <p class="pairing-copy">{{ t('pairing.scanCodePrompt') }}</p>
        <p class="pairing-number" data-testid="pairing-code">{{ pairingCode }}</p>
        <p class="pairing-consequence">{{ t('pairing.consequence') }}</p>
        <p class="pairing-waiting">
          <i class="pi pi-spin pi-spinner" aria-hidden="true" />
          <span>{{ t('pairing.scanWaiting') }}</span>
        </p>
        <div class="pairing-actions">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="reset" />
        </div>
      </template>

      <!-- E2 in this direction, and it is terminal. The answer slot is write-once, so there is no second proof
           to accept — and the refusal arrives BEFORE the user has committed anything, which is the whole point
           of taking the gesture last. -->
      <template v-else-if="step === 'refused'">
        <Message severity="warn" :closable="false">{{ t('pairing.scanRefused') }}</Message>
        <p class="pairing-copy">{{ t('pairing.scanRefusedHint') }}</p>
        <div class="pairing-actions">
          <Button type="button" :label="t('pairing.again')" icon="pi pi-camera" @click="openScan" />
        </div>
      </template>

      <template v-else-if="confirming">
        <p class="pairing-copy">{{ step === 'proven' ? t('pairing.scanProven') : t('pairing.comparePrompt') }}</p>
        <p v-if="step === 'comparing'" class="pairing-number" data-testid="check-number">{{ number }}</p>
        <label class="pairing-label-field">
          {{ t('pairing.labelField') }}
          <InputText v-model="label" :placeholder="t('sessions.renamePlaceholder')" fluid />
          <small class="field-hint">{{ t('pairing.labelHint') }}</small>
        </label>
        <!-- The consequence is in the gesture's own sentence, not in a paragraph above it: signing this device
             out later cuts it off the server and does NOT take the key back (ADR 0058). -->
        <SlideToConfirm
          :label="t('pairing.slide', { device: label.trim() || t('pairing.slideNoName') })"
          :done-label="t('pairing.slideDone')"
          :disabled="!canConfirm || granting"
          @confirmed="onConfirmed"
        />
        <p class="pairing-consequence">{{ t('pairing.consequence') }}</p>
        <div class="pairing-actions">
          <Button
            type="button"
            :label="step === 'proven' ? t('common.cancel') : t('pairing.mismatch')"
            :icon="step === 'proven' ? undefined : 'pi pi-times'"
            :severity="step === 'proven' ? 'secondary' : 'danger'"
            :outlined="step !== 'proven'"
            :text="step === 'proven'"
            :disabled="granting"
            @click="decline"
          />
        </div>
      </template>

      <template v-else-if="step === 'sealed'">
        <Message severity="success" :closable="false">{{ t('pairing.sealed') }}</Message>
        <p class="pairing-copy">{{ t('pairing.sealedHint') }}</p>
        <div class="pairing-actions">
          <Button type="button" :label="t('pairing.again')" severity="secondary" outlined @click="reset" />
        </div>
      </template>

      <template v-else>
        <Message severity="warn" :closable="false">{{ t('pairing.gone') }}</Message>
        <div class="pairing-actions">
          <Button type="button" :label="t('pairing.again')" icon="pi pi-qrcode" @click="start" />
        </div>
      </template>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    </section>
  </section>
  <!-- Reused as-is: it is decoder-agnostic and emits the raw string, so `accepts` is what lets this scanner
       refuse the other direction's marker while the camera keeps looking. -->
  <KitScanner v-if="scanOpen" :accepts="acceptsSignIn" @found="onScanned" @close="scanOpen = false" />
</template>

<style scoped>
.pairing-copy {
  margin: 0.5rem 0 1rem;
}
.pairing-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 1rem;
}
.pairing-waiting {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 1rem 0 0;
  color: var(--app-muted);
}
.pairing-number {
  margin: 0.5rem 0 1rem;
  text-align: center;
  font-size: 2rem;
  letter-spacing: 0.35rem;
  font-variant-numeric: tabular-nums;
}
.pairing-label-field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 1rem;
}
.pairing-consequence {
  margin: 0.75rem 0 0;
  color: var(--app-muted);
  font-size: 0.85rem;
}
</style>
