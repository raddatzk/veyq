<script setup lang="ts">
// Root: delegates layout to AppShell, which picks the authenticated Memory shell (sidebar + topbar) or the
// centered public card per the active route (cr-0030). It also mounts the two global transient-feedback
// regions (cr-0157, ADR 0039) through the layer's `VeyqToaster`: success acknowledgements, and the undo toasts with
// their "Rückgängig" action. The timeout/restore live in useUndo/useToast at each call site.
import AppShell from './components/app/AppShell.vue'
import ConfirmDialog from './shared/ui/ConfirmDialog.vue'
import VeyqToaster from './shared/ui/VeyqToaster.vue'
import ReportDialog from './core/report/ReportDialog.vue'
import BookmarkOpenDialog from './core/bookmark/BookmarkOpenDialog.vue'
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
  <!-- The one dialog a bookmark of another instance with a password opens before its tab (ADR 0120 decision 5). -->
  <BookmarkOpenDialog />
  <VeyqToaster />
</template>
