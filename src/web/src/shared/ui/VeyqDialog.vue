<script setup lang="ts">
// A modal dialog (ADR 0107): a title, the content, and a close button unless `closable` is false. Escape closes it
// unless `closeOnEscape` is false; a busy dialog sets both false so nothing closes it mid-write. `position="bottom"`
// is the mobile sheet. A dialog does not move: there is no dragging (cr-own-ui-components-on-reka decision 5).
// The caller's classes land on the dialog box; sheets reach the parts by `veyq-dialog__header`, `__title`,
// `__header-actions`, `__close` and `__content`.
import Dialog from 'primevue/dialog'

const visible = defineModel<boolean>('visible', { required: true })
withDefaults(
  defineProps<{
    header?: string
    closable?: boolean
    closeOnEscape?: boolean
    position?: 'center' | 'bottom'
  }>(),
  { closable: true, closeOnEscape: true, position: 'center' },
)
const emit = defineEmits<{ show: [] }>()

const PT = {
  header: { class: 'veyq-dialog__header' },
  title: { class: 'veyq-dialog__title' },
  headerActions: { class: 'veyq-dialog__header-actions' },
  pcCloseButton: { root: { class: 'veyq-dialog__close' } },
  content: { class: 'veyq-dialog__content' },
  mask: { class: 'veyq-dialog__mask' },
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    class="veyq-dialog"
    modal
    :draggable="false"
    :header="header"
    :closable="closable"
    :close-on-escape="closeOnEscape"
    :position="position"
    :pt="PT"
    @show="emit('show')"
  >
    <slot />
  </Dialog>
</template>
