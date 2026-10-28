<script setup lang="ts">
// The app's confirmation dialog for a destructive action (cr-web-own-confirm-dialog) — mounted **once** in
// App.vue beside the toasts, and driven by `shared/state/useConfirm`. It is `DeleteNodeDialog.vue`'s footer
// verbatim (styleguide § Buttons & dialog actions: one `.veyq-dialog-action-row`, cancel secondary-outlined on
// the left, the destructive primary a filled `variant="danger"` with an explicit label on the right), which is
// the whole reason this is an own component rather than a library's confirmation service: one would have to be
// re-templated through its slots to reach this same shape anyway.
//
// It renders and answers — it never runs the action. The busy state belongs to the row button that opened it
// (Decision 5), so there is no `:loading` here and nothing to disable: the dialog is gone before the mutation
// starts.
import VeyqButton from './VeyqButton.vue'
import VeyqCheckbox from './VeyqCheckbox.vue'
import VeyqDialog from './VeyqDialog.vue'
import { useI18n } from 'vue-i18n'
import { confirmOptions, confirmRequest, settleConfirm } from '../state/useConfirm'

const { t } = useI18n()
</script>

<template>
  <VeyqDialog
    v-if="confirmRequest"
    :visible="true"
    :header="confirmRequest.header"
    class="veyq-confirm-dialog"
    @update:visible="settleConfirm(false)"
  >
    <p class="dialog-copy">{{ confirmRequest.message }}</p>
    <!-- The request's opt-ins (useConfirm's askWithOptions): unchecked each time it opens, read back only on accept.
         Each laid out as MemoryNodeDialog's opt-in — the label, then its consequence. -->
    <div v-for="(option, i) in confirmRequest.options ?? []" :key="i" class="confirm-option">
      <VeyqCheckbox v-model="confirmOptions[i]" :input-id="`confirm-option-${i}`" />
      <label :for="`confirm-option-${i}`" class="confirm-option-text">
        <span>{{ option.label }}</span>
        <span v-if="option.hint" class="confirm-option-hint">{{ option.hint }}</span>
      </label>
    </div>
    <div class="veyq-dialog-action-row">
      <VeyqButton
        :label="t('common.cancel')"
        icon="times"
        variant="secondary"
        appearance="outlined"
        @click="settleConfirm(false)"
      />
      <VeyqButton
        :label="confirmRequest.acceptLabel"
        :icon="confirmRequest.acceptIcon"
        variant="danger"
        @click="settleConfirm(true)"
      />
    </div>
  </VeyqDialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
  /* The longest sentence the app owns lives in this dialog (the saved-key escalation), so it gets a reading
     width instead of stretching to the dialog's own max. */
  max-width: 34rem;
}
.confirm-option {
  display: flex;
  align-items: flex-start;
  gap: 0.55rem;
  margin: 0 0 1rem;
  max-width: 34rem;
}
.confirm-option-text {
  display: grid;
  gap: 0.12rem;
  font-size: 0.92rem;
  line-height: 1.35;
}
.confirm-option-hint {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
