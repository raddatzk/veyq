<script setup lang="ts">
// A modal dialog (ADR 0107), on Reka's Dialog: focus moves in and stays in, Escape and the close button close it, and
// focus returns to what had it before. A click outside closes it only with `closeOnOutside` — a menu such as the mobile
// navigation sheet, never a dialog that holds input a stray tap would lose. `closable: false` hides the close button and
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
import { nextTick } from 'vue'
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
    closeOnOutside?: boolean
  }>(),
  { closable: true, closeOnEscape: true, position: 'center', closeOnOutside: false },
)
const emit = defineEmits<{ show: [] }>()
const { t } = useI18n()

function onEscape(event: KeyboardEvent) {
  if (!props.closeOnEscape) event.preventDefault()
}
// A press or a focus outside the box: Reka closes unless prevented, so only a dialog that opted in lets it through.
function onOutside(event: Event) {
  if (!props.closeOnOutside) event.preventDefault()
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
          <div class="veyq-dialog__content"><slot /></div>
        </DialogContent>
      </div>
    </DialogPortal>
  </DialogRoot>
</template>
