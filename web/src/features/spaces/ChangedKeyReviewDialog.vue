<script setup lang="ts">
// uc.spaces.verify-identity A/E1/E2 (cr-0099) — review a CHANGED key. The directory key differs from the pin
// (a legitimate rotation, E1, or an operator substitution, E2 — identical on the wire). Shows the expected
// (pinned) vs current (directory) safety-numbers side by side; the caller compares out-of-band and either
// accepts → `acceptChangedKey` (one scope-rotation worker pass, then re-pin as pinned; they may then run Verify to
// reach verified) or refuses → leave it blocked (the wrap/delivery stays gated). Client-only, operator-blind.
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
/** The accept failed on a refused pin store — the error then links to the settings hub's reset. */
const trustRefused = ref(false)

watch(
  visible,
  async (open) => {
    if (!open) return
    error.value = ''
    const pin = await getPin(props.member.userId).catch(() => null)
    expected.value = pin ? safetyNumber(pin.key) : ''
    current.value = safetyNumber(props.member.identityPublicKey)
  },
  { immediate: true },
)

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
      <Message severity="warn" :closable="false">{{ t('verify.reviewIntro') }}</Message>
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

      <Message v-if="error" severity="error" :closable="false">
        {{ error }}
        <TrustResetLink v-if="trustRefused" />
      </Message>

      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('verify.refuse')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="visible = false" />
        <Button type="button" :label="t('verify.accept')" icon="pi pi-shield" severity="warn" :loading="busy" @click="accept" />
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
