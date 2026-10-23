<script setup lang="ts">
// The console's toast region (ADR 0039, ADR 0107), mounted once in App.vue, on Reka's Toast: top-right, for the
// success acknowledgements `shared/state/useToast` sends. It is a live region a screen reader reads; a toast pauses
// while the pointer is on it. What to show comes through `toast.ts`. The app surface has its own copy with an undo
// region (`src/web/src/shared/ui/VeyqToaster.vue`) - no shared UI between the packages (ADR 0065).
import { useI18n } from 'vue-i18n'
import { ToastClose, ToastProvider, ToastRoot, ToastTitle, ToastViewport } from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'
import { dropToast, toastQueue, type QueuedToast } from './toast'
import type { VeyqIconName } from './icons'

const { t } = useI18n()

const ICON: Record<QueuedToast['severity'], VeyqIconName> = { success: 'check', error: 'times-circle', info: 'info-circle' }

function onOpen(toast: QueuedToast, open: boolean) {
  if (!open) dropToast(toast.id)
}
</script>

<template>
  <ToastProvider :label="t('ui.notifications')" swipe-direction="right">
    <ToastRoot
      v-for="toast in toastQueue"
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
        <ToastClose class="veyq-toast__close" :aria-label="t('ui.close')"><VeyqIcon name="times" /></ToastClose>
      </div>
    </ToastRoot>
    <ToastViewport class="veyq-toaster" :label="t('ui.notifications')" />
  </ToastProvider>
</template>
