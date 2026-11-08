<script setup lang="ts">
// A modal dialog (ADR 0107), on Reka's Dialog: focus moves in and stays in, Escape and the close button close it, and
// focus returns to what had it before. A click outside closes it only while it holds no field (owner 2026-11-08): a
// menu, a confirmation or a notice closes as Cancel would, a form never, so a stray tap loses nothing typed into it,
// and neither closes while `closable` is false or `keepOnOutside` is set (a result shown once, a running operation). `closable: false` hides the close button and
// `closeOnEscape: false` ignores Escape, so a busy dialog cannot be closed mid-write. `position="bottom"` is the mobile
// sheet. A dialog does not move: there is no dragging (ADR 0107 § Behaviour changed on purpose). The caller's classes,
// style and attributes land on the dialog box; sheets reach the parts by `veyq-dialog__header`, `__title`,
// `__header-actions`, `__close` and `__content` (styled in `layer.css`). On opening, focus goes to the field marked
// `autofocus`, else to the first focusable part (the close button), as the dialog before the layer did; `show` fires
// once it is open and focused.
//
// The portal mounts only while the dialog is open, so a dialog that opens later is also later in `<body>` (inbox #545).
// Every dialog layer shares one z-index (`layer.css`), so their paint order is their document order, and Reka orders its
// focus, Escape and pointer layers by when each opened: a dialog opened from inside an open one — core's report or
// confirmation dialog, mounted once in App.vue long before — must therefore enter the document then, not when it was
// mounted, or it opens beneath the other's mask with the focus trapped in it.
import { nextTick, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'

defineOptions({ inheritAttrs: false })
const visible = defineModel<boolean>('visible', { required: true })
const props = withDefaults(
  defineProps<{
    header?: string
    closable?: boolean
    closeOnEscape?: boolean
    position?: 'center' | 'bottom'
    keepOnOutside?: boolean
  }>(),
  { closable: true, closeOnEscape: true, position: 'center', keepOnOutside: false },
)
const emit = defineEmits<{ show: [] }>()
const { t } = useI18n()

function onEscape(event: KeyboardEvent) {
  if (!props.closeOnEscape) event.preventDefault()
}
// What makes a dialog a form: a field to type into, pick or tick - a native one or a layer component by its role.
const FIELDS = [
  'input:not([type="hidden"])', 'textarea', 'select', '[contenteditable=""]', '[contenteditable="true"]',
  '[role="textbox"]', '[role="combobox"]', '[role="checkbox"]', '[role="radio"]', '[role="switch"]', '[role="slider"]',
  '[role="spinbutton"]', '[role="listbox"]', '[aria-pressed]',
].join(', ')
const body = ref<HTMLElement | null>(null)
// A press or a focus outside the box: Reka closes unless prevented. A dialog that holds a field stays, whether or not
// anything was entered yet, and so does one that may not be closed now, or that says so (`keepOnOutside`: a result shown
// once, a running operation a close would abort or lose, a choice made by browsing); every other dialog closes, as
// Cancel would.
function onOutside(event: Event) {
  if (!props.closable || props.keepOnOutside || body.value?.querySelector(FIELDS)) event.preventDefault()
}
function onOpenFocus(event: Event) {
  const marked = (event.target as HTMLElement | null)?.querySelector<HTMLElement>('[autofocus]')
  if (marked) {
    event.preventDefault()
    marked.focus()
  }
  void nextTick(() => emit('show'))
}
</script>

<template>
  <DialogRoot :open="visible" @update:open="(open) => (visible = open)">
    <DialogPortal v-if="visible">
      <DialogOverlay class="veyq-dialog__mask" />
      <div :class="['veyq-dialog__frame', `veyq-dialog__frame--${position}`]">
        <DialogContent
          v-bind="$attrs"
          :class="['veyq-dialog', `veyq-dialog--${position}`]"
          :aria-describedby="undefined"
          aria-modal="true"
          @escape-key-down="onEscape"
          @pointer-down-outside="onOutside"
          @interact-outside="onOutside"
          @open-auto-focus="onOpenFocus"
        >
          <div class="veyq-dialog__header">
            <DialogTitle class="veyq-dialog__title">{{ header }}</DialogTitle>
            <div class="veyq-dialog__header-actions">
              <DialogClose v-if="closable" class="veyq-dialog__close" :aria-label="t('common.close')">
                <VeyqIcon name="times" />
              </DialogClose>
            </div>
          </div>
          <div ref="body" class="veyq-dialog__content"><slot /></div>
        </DialogContent>
      </div>
    </DialogPortal>
  </DialogRoot>
</template>
