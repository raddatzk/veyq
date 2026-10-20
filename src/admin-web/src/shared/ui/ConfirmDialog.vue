<script setup lang="ts">
// The console's confirmation dialog for a destructive action (cr-admin-web-own-confirm-dialog) — mounted
// **once** in App.vue beside the toasts, and driven by `shared/state/useConfirm`. A **copy** of
// `src/web/src/shared/ui/ConfirmDialog.vue`, not an import: ADR 0065 gives this package no shared UI, and
// `shared/state/useToast.ts` beside it is the byte-identical precedent for exactly that.
//
// Its footer is the styleguide's shape (§ Buttons & dialog actions: one `.veyq-dialog-action-row` — already
// present verbatim in this package's `styles/ui.css` — cancel secondary-outlined on the left, the destructive
// primary a filled `severity="danger"` with an explicit label on the right), which is the whole reason this is
// an own component rather than PrimeVue's `ConfirmationService`: that service would have to be re-templated
// through its `#container` slot to reach this same shape anyway.
//
// It renders and answers — it never runs the action. The busy state belongs to the control that opened it, so
// there is no `:loading` here and nothing to disable: the dialog is gone before the mutation starts.
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { useI18n } from 'vue-i18n'
import { confirmRequest, settleConfirm } from '../state/useConfirm'

const { t } = useI18n()
</script>

<template>
  <Dialog
    v-if="confirmRequest"
    :visible="true"
    :header="confirmRequest.header"
    modal
    :draggable="false"
    class="veyq-confirm-dialog"
    @update:visible="settleConfirm(false)"
  >
    <p class="dialog-copy">{{ confirmRequest.message }}</p>
    <div class="veyq-dialog-action-row">
      <Button
        type="button"
        :label="t('common.cancel')"
        icon="pi pi-times"
        severity="secondary"
        outlined
        @click="settleConfirm(false)"
      />
      <Button
        type="button"
        :label="confirmRequest.acceptLabel"
        :icon="confirmRequest.acceptIcon"
        severity="danger"
        @click="settleConfirm(true)"
      />
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
  /* The consequence sentences this console owns are long on purpose — taking a tenant offline has to spell out
     that nothing inside it is changed or deleted — so they get a reading width instead of stretching to the
     dialog's own max. */
  max-width: 34rem;
}
</style>
