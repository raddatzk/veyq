<script setup lang="ts">
// The one door to starting a refused trust document over (ADR 0028 Decision 7's way back, inbox #192) — and the
// ONLY file allowed to import the reset (`eslint.config.js`, `veyq/trust-reset-one-door`). The reset re-opens
// exactly what Decision 7 closed, an empty store, so the whole defence is who can cause it: the server can cause
// the refusal, never this write. Its one call is in `confirm()`, reached only from the confirm button, which stays
// disabled until the person ticks that they understand the cost.
//
// **No password and no Secret Key, on purpose.** A credential defends against neither adversary here: the operator
// cannot click, and a client the operator serves needs no function to write an empty store (ADR 0026). What the
// friction is for is that the person knows what they are giving up — so the dialog names the document's own cost.
//
// Markup mirrors KeyRotationPanel's confirm Dialog (dialog copy, Message, veyq-dialog-action-row) and the
// acknowledgement mirrors EmergencyKitSave's Checkbox row — the built screens are the design source.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import { resetAccountDocument, type ResetOutcome } from './trustReset'
import type { AccountDocument } from '../trustMarks'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const props = defineProps<{ document: AccountDocument }>()
const visible = defineModel<boolean>('visible', { default: false })
const emit = defineEmits<{ done: [outcome: ResetOutcome] }>()

const { t } = useI18n()
const acknowledged = ref(false)
const error = ref('')

// Every opening starts unticked: a consent given for one document, or one earlier attempt, is not a consent now.
watch(visible, (open) => {
  if (open) {
    acknowledged.value = false
    error.value = ''
  }
})

const { run, pending: busy } = useAsyncAction(() => resetAccountDocument(props.document), {
  onError: () => {
    error.value = t('trustReset.failed')
  },
})

const canConfirm = computed(() => acknowledged.value && !busy.value)

async function confirm() {
  if (!acknowledged.value) return
  error.value = ''
  const outcome = await run()
  if (outcome === undefined) return
  visible.value = false
  emit('done', outcome)
}
</script>

<template>
  <VeyqDialog
    v-model:visible="visible"
    :header="t(`trustReset.confirmTitle.${props.document}`)"
    :closable="!busy"
    :close-on-escape="!busy"
  >
    <p class="dialog-copy" data-test="trust-reset-cost">{{ t(`trustReset.cost.${props.document}`) }}</p>
    <p class="dialog-copy">{{ t('trustReset.serverCanWait') }}</p>
    <label class="trust-reset-ack">
      <VeyqCheckbox v-model="acknowledged" :disabled="busy" input-id="trust-reset-ack" data-test="trust-reset-ack" />
      <span>{{ t('trustReset.acknowledge') }}</span>
    </label>
    <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
    <div class="veyq-dialog-action-row">
      <VeyqButton
        type="button"
        :label="t('common.cancel')"
        icon="times"
        variant="secondary"
        appearance="outlined"
        :disabled="busy"
        data-test="trust-reset-cancel"
        @click="visible = false"
      />
      <VeyqButton
        type="button"
        :label="t('trustReset.confirm')"
        icon="refresh"
        variant="danger"
        :loading="busy"
        :disabled="!canConfirm"
        data-test="trust-reset-confirm"
        @click="confirm"
      />
    </div>
  </VeyqDialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
.trust-reset-ack {
  display: flex;
  align-items: start;
  gap: 0.5rem;
  margin: 0.5rem 0 1rem;
}
</style>
