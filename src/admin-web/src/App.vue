<script setup lang="ts">
// Root. Two shells, picked by `route.meta.layout` the way web's `AppShell.vue` picks between four: a card
// screen is centred in the public shell, the console takes the full viewport and centres itself. The card
// shell is the fallback, so a new sign-in-shaped screen needs no decision; `router.ts` owns the vocabulary.
//
// The unreachable state is a card too, and it PRE-EMPTS the route rather than sitting above it as web's banner
// does — with no reachable backend there is no console to render underneath. The toaster is the same transient
// acknowledgement surface the moved panels already expect (ADR 0039).
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import VeyqMessage from './shared/ui/VeyqMessage.vue'
import VeyqToaster from './shared/ui/VeyqToaster.vue'
import { useI18n } from 'vue-i18n'
import ConfirmDialog from './shared/ui/ConfirmDialog.vue'
import { serverState } from './shared/state/serverState'

const { t } = useI18n()
const route = useRoute()
const cardLayout = computed(() => serverState.unreachable || route.meta.layout !== 'plain')
</script>

<template>
  <main v-if="cardLayout" class="veyq-shell">
    <div class="veyq-shell-inner">
      <VeyqMessage v-if="serverState.unreachable" tone="error">
        {{ t('admin.unreachable') }}
      </VeyqMessage>
      <RouterView v-else />
    </div>
  </main>
  <main v-else class="admin-root">
    <RouterView />
  </main>
  <!-- The console's confirmation dialog for a destructive action, mounted once beside the toast and driven by
       `useConfirm` — the one that retired `window.confirm` here (cr-admin-web-own-confirm-dialog). It sits
       outside both shells because either may ask. -->
  <ConfirmDialog />
  <VeyqToaster />
</template>

<style scoped>
/* The console's page: it centres its own content (`.admin-shell`, max-width 68rem), so the root owes it only
   height and a background — the *subtle* pattern, which is what web's authenticated shell uses
   (`src/web/src/styles/layout.css` § .app-shell) rather than the stronger one the card screens sit on. Text colour
   comes from `body` (`styles/base.css`), as it does in web's shells. */
.admin-root {
  min-height: 100dvh;
  background: var(--app-background-pattern-subtle);
}
</style>
