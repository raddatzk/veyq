<script setup lang="ts">
// The app's two toast regions (ADR 0039, ADR 0107), mounted once in App.vue, on Reka's Toast: the default one,
// top-right, for success acknowledgements and errors, and the undo one, bottom-centre, whose card carries a
// "Rückgängig" action. Each region is a live region a screen reader reads; a toast pauses while the pointer is on it.
// What to show comes through `toast.ts`; the timeout/restore live in useUndo/useToast at each call site.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ToastClose, ToastProvider, ToastRoot, ToastTitle, ToastViewport } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'
import { dropToast, toastQueue, type QueuedToast } from './toast'
import type { VeyqIconName } from './icons'

const { t } = useI18n()

const plain = computed(() => toastQueue.filter((q) => !q.onUndo))
const undo = computed(() => toastQueue.filter((q) => q.onUndo))

const ICON: Record<QueuedToast['severity'], VeyqIconName> = { success: 'check', error: 'times-circle', info: 'info-circle' }

function onOpen(toast: QueuedToast, open: boolean) {
  if (!open) dropToast(toast.id)
}
function runUndo(toast: QueuedToast) {
  toast.onUndo?.()
  dropToast(toast.id)
}
</script>

<template>
  <ToastProvider :label="t('ui.notifications')" swipe-direction="right">
    <ToastRoot
      v-for="toast in plain"
      :key="toast.id"
      :duration="toast.life ?? Infinity"
      :class="['veyq-toast', `veyq-toast--${toast.severity}`]"
      @update:open="(open) => onOpen(toast, open)"
    >
      <div class="veyq-toast__content">
        <VeyqIcon :name="ICON[toast.severity]" class="veyq-toast__icon" />
        <div class="veyq-toast__text">
          <ToastTitle class="veyq-toast__summary">{{ toast.summary }}</ToastTitle>
        </div>
        <ToastClose class="veyq-toast__close" :aria-label="t('common.close')"><VeyqIcon name="times" /></ToastClose>
      </div>
    </ToastRoot>
    <ToastViewport class="veyq-toaster" :label="t('ui.notifications')" />
  </ToastProvider>
  <ToastProvider :label="t('ui.notifications')" swipe-direction="down">
    <ToastRoot
      v-for="toast in undo"
      :key="toast.id"
      :duration="toast.life ?? Infinity"
      class="veyq-undo-toast-root"
      @update:open="(open) => onOpen(toast, open)"
    >
      <div class="veyq-undo-toast">
        <ToastTitle class="veyq-undo-toast__text">{{ toast.summary }}</ToastTitle>
        <button type="button" class="veyq-undo-toast__action" @click="runUndo(toast)">
          {{ t('common.undo') }}
        </button>
        <ToastClose class="veyq-undo-toast__close" :aria-label="t('common.cancel')"><VeyqIcon name="times" /></ToastClose>
      </div>
    </ToastRoot>
    <ToastViewport class="veyq-toaster veyq-toaster--undo" :label="t('ui.notifications')" />
  </ToastProvider>
</template>

<style scoped>
/* The undo-toast is styled as an app surface card (theme-aware light/dark) so it reads as the same family as the
   success toast + the rest of the UI — not the old dark snackbar pill. */
.veyq-undo-toast {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.55rem 0.6rem 0.55rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-surface);
  color: var(--app-text);
  box-shadow: var(--app-shadow-md);
}
.veyq-undo-toast__text {
  font-size: 0.92rem;
}
/* The action reads in the app accent (like a text button), not underlined white. */
.veyq-undo-toast__action {
  margin-left: 0.25rem;
  padding: 0.3rem 0.65rem;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--app-accent);
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}
.veyq-undo-toast__action:hover {
  background: var(--app-accent-soft);
}
.veyq-undo-toast__close {
  display: inline-flex;
  padding: 0.3rem;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
}
.veyq-undo-toast__close:hover {
  color: var(--app-text);
  background: var(--app-hover);
}
</style>
