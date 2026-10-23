<script setup lang="ts">
// The app's two toast regions (ADR 0039, ADR 0107), mounted once in App.vue: the default one, top-right, for success
// acknowledgements and errors, and the undo one, bottom-centre, whose card carries a "Rückgängig" action. What to show
// comes through `toast.ts`; the timeout/restore live in useUndo/useToast at each call site.
import Toast from 'primevue/toast'
import { useI18n } from 'vue-i18n'
import VeyqIcon from './VeyqIcon.vue'
import { UNDO_GROUP } from './toast'

const { t } = useI18n()

/** The extra field the undo entry rides on the message so this template can wire the action. */
interface UndoMessage {
  summary?: string
  onUndo?: () => void
}

const PT = {
  message: { class: 'veyq-toast' },
  messageText: { class: 'veyq-toast__text' },
  closeButton: { class: 'veyq-toast__close' },
}
</script>

<template>
  <Toast class="veyq-toaster" :pt="PT" />
  <Toast :group="UNDO_GROUP" position="bottom-center" class="veyq-toaster veyq-toaster--undo">
    <template #container="{ message, closeCallback }">
      <div class="veyq-undo-toast" role="status">
        <span class="veyq-undo-toast__text">{{ message.summary }}</span>
        <button
          type="button"
          class="veyq-undo-toast__action"
          @click="() => { (message as UndoMessage).onUndo?.(); closeCallback() }"
        >
          {{ t('common.undo') }}
        </button>
        <button
          type="button"
          class="veyq-undo-toast__close"
          :aria-label="t('common.cancel')"
          @click="closeCallback"
        >
          <VeyqIcon name="times" />
        </button>
      </div>
    </template>
  </Toast>
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
