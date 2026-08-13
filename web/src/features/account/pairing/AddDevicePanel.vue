<script setup lang="ts">
// uc.account.onboard-device § Main flow — add a new device (web, ADR 0059). The trusted, unlocked device's
// half: show a code, wait for the answer, compare a number, take a deliberate confirmation, then delegate and
// seal. It lives in the settings hub because that is where the premise holds — `/settings/:section?` is already
// `requiresUnlock`, and this screen is meaningless without an open vault.
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
import SlideToConfirm from '../../../shared/ui/SlideToConfirm.vue'
import {
  mintHandle,
  pairingPayload,
  readTransportAnswer,
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
import type * as v1 from '../../../shared/crypto/v1'

// `sealed` is terminal on this device and says so: the collect that hands the grant over spends the channel
// whoever calls it, so this side cannot look again to see whether the other device arrived (see the poll
// module). `gone` is every terminal server state at once — they are indistinguishable on purpose (E1).
type Step = 'idle' | 'awaiting' | 'comparing' | 'sealed' | 'gone'

const { t } = useI18n()

const step = ref<Step>('idle')
const handle = ref('')
const label = ref('')
const error = ref('')
/** The answering device's public bundle, kept from the moment the answer lands: the number on screen and the
 *  key the grant is sealed to must be the same one, or the comparison guarantees nothing. */
const answeringKey = ref<v1.Identity['pub'] | null>(null)

const code = computed(() => pairingPayload(handle.value))
const number = computed(() => (answeringKey.value ? checkNumber(answeringKey.value) : ''))
const canConfirm = computed(() => label.value.trim().length > 0)

function reset() {
  stopPairingAnswerPolling()
  step.value = 'idle'
  handle.value = ''
  label.value = ''
  answeringKey.value = null
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

      <template v-if="step === 'idle'">
        <p class="pairing-copy">{{ t('pairing.intro') }}</p>
        <div class="pairing-actions">
          <Button
            type="button"
            :label="t('pairing.start')"
            icon="pi pi-qrcode"
            :loading="starting"
            @click="start"
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

      <template v-else-if="step === 'comparing'">
        <p class="pairing-copy">{{ t('pairing.comparePrompt') }}</p>
        <p class="pairing-number" data-testid="check-number">{{ number }}</p>
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
            :label="t('pairing.mismatch')"
            icon="pi pi-times"
            severity="danger"
            outlined
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
