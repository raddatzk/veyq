<script setup lang="ts">
// Root: delegates layout to AppShell, which picks the authenticated Memory shell (sidebar + topbar) or the
// centered public card per the active route (cr-0030). It also mounts the two global transient-feedback
// surfaces (cr-0157, ADR 0039): a default <Toast> for success acknowledgements, and a templated undo-group
// <Toast> whose container renders the undo-toast text + a "Rückgängig" action (PrimeVue needs a custom
// template to carry an action button). The timeout/restore live in useUndo/useToast at each call site.
import Toast from 'primevue/toast'
import { useI18n } from 'vue-i18n'
import AppShell from './components/app/AppShell.vue'
import ConfirmDialog from './shared/ui/ConfirmDialog.vue'
import ReportDialog from './core/report/ReportDialog.vue'
import { UNDO_GROUP } from './shared/state/useToast'

const { t } = useI18n()

/** The extra field `useToast().showUndo(...)` rides on the message so this template can wire the action. */
interface UndoMessage {
  summary?: string
  onUndo?: () => void
}
</script>

<template>
  <AppShell />
  <!-- The app's confirmation dialog for a destructive action, mounted once beside the toasts and driven by
       `useConfirm` — the one that retired `window.confirm` (cr-web-own-confirm-dialog). It is here rather than
       in the authenticated shell because the locked sign-out asks from the public sign-in card. -->
  <ConfirmDialog />
  <!-- The one report dialog (uc.moderation.report), driven by `core/report/report.ts`: opened from a level, a header, the
       lightbox or an upload link's page, for a member and a guest alike. -->
  <ReportDialog />
  <!-- Success acknowledgements: auto-dismissing, top-right (retires the inline `notice` refs). -->
  <Toast />
  <!-- Undo-toasts: bottom-center dark pill carrying a "Rückgängig" action (retires the two hand-rolled bars). -->
  <Toast :group="UNDO_GROUP" position="bottom-center">
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
          <span class="pi pi-times" aria-hidden="true" />
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
