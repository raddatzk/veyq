<script setup lang="ts">
// uc.spaces.verify-identity A/E1/E2 (cr-0099) — review a CHANGED key. The directory key differs from the pin
// (a legitimate rotation, E1, or an operator substitution, E2 — identical on the wire). Shows the expected
// (pinned) vs current (directory) safety-numbers side by side; the caller compares out-of-band and either
// accepts → `acceptChangedKey` (one scope-rotation worker pass, then re-pin as pinned; the member's profile then offers
// the compare again, the only way back to verified) or refuses → leave it blocked (the wrap/delivery stays gated).
// A VERIFIED contact's change is said louder (ADR 0028 Decision 3): the caller compared that key in person before, so a
// different one is the case the compare exists for. Client-only, operator-blind.
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { safetyNumber, getPin } from '../account/identityPins'
import { acceptChangedKey } from './acceptChangedKey'
import { TrustStateRefused, refusalMessageKey } from '../account/trustMarks'
import TrustResetLink from '../account/trustReset/TrustResetLink.vue'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

const props = defineProps<{ member: { userId: string; username: string; identityPublicKey: string } }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ accepted: [] }>()
const { t } = useI18n()

const expected = ref('') // the pinned key's safety-number (what the caller trusted before)
const current = ref('') // the directory key's safety-number (what is shown now)
const error = ref('')
// inbox #246: a key this device cannot decode has no safety number. An unreadable served (current) key disables
// the accept — there is nothing to compare, and pinning bytes the identity codec refuses would only fail later; an
// unreadable pinned (expected) key only loses its reference number, since the out-of-band compare is against the
// current one.
const currentUnreadable = ref(false)
const expectedUnreadable = ref(false)
/** The accept failed on a refused pin store — the error then links to the settings hub's reset. */
const trustRefused = ref(false)
/** The pin being replaced was `verified` — a key the caller compared out-of-band before (ADR 0028 Decision 3). */
const wasVerified = ref(false)

watch(
  visible,
  async (open) => {
    if (!open) return
    error.value = ''
    const pin = await getPin(props.member.userId).catch(() => null)
    wasVerified.value = pin?.state === 'verified'
    const expectedNumber = pin ? numberOf(pin.key) : ''
    expected.value = expectedNumber ?? ''
    expectedUnreadable.value = expectedNumber === null
    const currentNumber = numberOf(props.member.identityPublicKey)
    current.value = currentNumber ?? ''
    currentUnreadable.value = currentNumber === null
  },
  { immediate: true },
)

/** The key's safety number, or `null` when the identity codec refuses its bytes. */
function numberOf(keyB64: string): string | null {
  try {
    return safetyNumber(keyB64)
  } catch {
    return null
  }
}

const { run: runAccept, pending: busy } = useAsyncAction(
  async () => {
    await acceptChangedKey(props.member.userId, props.member.identityPublicKey)
    emit('accepted')
    visible.value = false
  },
  {
    onError: (e) => {
      trustRefused.value = e instanceof TrustStateRefused
      error.value = e instanceof TrustStateRefused ? t(refusalMessageKey(e)) : t('verify.acceptFailed')
    },
  },
)

async function accept() {
  error.value = ''
  await runAccept()
}
</script>

<template>
  <Dialog v-model:visible="visible" :header="t('verify.reviewTitle', { name: '@' + member.username })" modal class="tenant-dialog" :closable="!busy" :close-on-escape="!busy">
    <div class="veyq-form review-body">
      <Message v-if="wasVerified" severity="error" :closable="false" data-testid="review-verified-changed">
        {{ t('safety.reviewVerifiedIntro', { name: '@' + member.username }) }}
      </Message>
      <Message v-else severity="warn" :closable="false">{{ t('verify.reviewIntro') }}</Message>
      <p class="settings-muted">{{ t('verify.reviewPendingRekey') }}</p>

      <div class="review-grid">
        <section class="sn-block">
          <span class="sn-label">{{ t('verify.expectedLabel') }}</span>
          <code class="sn-digits expected">{{ expected }}</code>
        </section>
        <section class="sn-block">
          <span class="sn-label">{{ t('verify.currentLabel') }}</span>
          <code class="sn-digits current">{{ current }}</code>
        </section>
      </div>

      <Message v-if="currentUnreadable" severity="error" :closable="false">{{ t('verify.currentUnreadable') }}</Message>
      <Message v-else-if="expectedUnreadable" severity="warn" :closable="false">{{ t('verify.expectedUnreadable') }}</Message>

      <Message v-if="error" severity="error" :closable="false">
        {{ error }}
        <TrustResetLink v-if="trustRefused" />
      </Message>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('verify.refuse')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="visible = false" />
        <Button type="button" :label="t('verify.accept')" icon="pi pi-shield" severity="warn" :loading="busy" :disabled="currentUnreadable" @click="accept" />
      </div>
    </div>
  </Dialog>
</template>

<style scoped>
.review-body {
  gap: 1rem;
}
.review-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
}
@media (max-width: 480px) {
  .review-grid {
    grid-template-columns: 1fr;
  }
}
.sn-block {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.sn-label {
  font-weight: 700;
  font-size: 0.85rem;
}
.sn-digits {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 1rem;
  letter-spacing: 0.03em;
  line-height: 1.5;
  word-break: break-word;
}
.sn-digits.expected {
  color: var(--app-muted);
}
.sn-digits.current {
  color: var(--app-warning-text);
}
</style>
