<script setup lang="ts">
// Top-level layout switch (cr-0030). Markup + classes are the design source's AppShell verbatim (the
// .app-shell grid: .app-compact-header places the sidebar + topbar, .app-workspace > main.content holds the
// route) so layout.css styles it identically. Ours only differs in the gate: routes flagged
// `meta.layout === 'app'` render the workspace shell; everything else keeps the centered .public-shell. The
// router already gates app routes on session + unlocked, so this only decides layout. Below the workspace sits the
// operator footer (uc.admin.operator-details): who runs the instance and its terms, reachable from every page of the
// signed-in app as § 5 DDG asks of a provider identification and DSA Art. 14(1) of the terms.
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useServerState } from '../../shared/state/useServerState'
import { useCapabilities } from '../../shared/state/useCapabilities'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'
import IncompatibleScreen from './IncompatibleScreen.vue'
import FinishProfilePrompt from '../../features/account/profile/FinishProfilePrompt.vue'
import OperatorIdentification from '../../core/operator/OperatorIdentification.vue'

const route = useRoute()
const { serverState } = useServerState()
const { incompatibility } = useCapabilities()
const { t } = useI18n()
// A `major` mismatch is a hard stop that supersedes every layout — the client and server are incompatible, so
// nothing else (not even the anonymous share viewer) may render until one is updated (ADR 0032).
const incompatible = computed(() => incompatibility() !== null)
const appLayout = computed(() => route.meta.layout === 'app')
// The anonymous public share viewer (cr-0100) wants the full viewport width for its gallery, not the narrow
// centered card the login/setup public shell uses.
const plainLayout = computed(() => route.meta.layout === 'plain')
</script>

<template>
  <main v-if="incompatible" class="public-shell">
    <div class="veyq-shell-inner">
      <IncompatibleScreen />
    </div>
  </main>
  <div v-else-if="appLayout" class="app-shell">
    <div class="app-compact-header">
      <AppSidebar />
      <AppTopbar />
    </div>
    <div class="app-workspace">
      <main class="content">
        <FinishProfilePrompt />
        <RouterView />
      </main>
      <footer class="app-operator-footer">
        <OperatorIdentification />
      </footer>
    </div>
  </div>
  <main v-else-if="plainLayout" class="plain-shell">
    <RouterView />
  </main>
  <main v-else class="public-shell">
    <div class="veyq-shell-inner">
      <p v-if="serverState.unreachable" class="veyq-banner" role="alert">{{ t('common.unreachable') }}</p>
      <RouterView />
    </div>
  </main>
</template>

<style scoped>
.app-operator-footer {
  display: flex;
  justify-content: center;
  padding: 0 1.25rem 1.25rem;
  text-align: center;
}
.veyq-shell-inner {
  width: min(460px, 100%);
}
</style>
