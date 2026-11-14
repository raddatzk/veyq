<script setup lang="ts">
// Top-level layout switch (cr-0030). Markup + classes are the design source's AppShell verbatim (the
// .app-shell grid: .app-compact-header places the sidebar + topbar, .app-workspace > main.content holds the
// route) so layout.css styles it identically. Ours only differs in the gate: routes flagged
// `meta.layout === 'app'` render the workspace shell; everything else keeps the centered .public-shell. The
// router already gates app routes on session + unlocked, so this decides layout — and opens a scope for an app route
// that is not the home (below). Below the workspace sits the
// operator footer (uc.admin.operator-details): who runs the instance and its terms, reachable from every page of the
// signed-in app as § 5 DDG asks of a provider identification and DSA Art. 14(1) of the terms. Above the route sits the
// donation banner as a member's (uc.platform.support-the-project, ADR 0124) — on the signed-in app only, never on the
// public or plain layouts' login, invite, join and recovery pages — and above it, while the account's address is
// unconfirmed, the banner that says so and cannot be dismissed (ADR 0129 decision 5), on the same pages only. While it
// stands — and until its read has answered — the donation banner and the finish-your-profile prompt wait (owner
// 2026-11-13): one notice at a time, the one that asks the member to act; they appear as before once the address is
// confirmed or cannot be read. The banner's read is the only one (`unconfirmedAddress.ts`). Below the footer sits core's
// audio bar (`core/audio/AudioBar.vue`, ADR 0135 decision 8), mounted here once so the music outlives every change of page,
// and only while the session is signed in and unlocked: a lock unmounts it, which stops the player and releases every
// source it opened (a sign-out or a tenant switch loads a new document).
import { computed, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useServerState } from '../../shared/state/useServerState'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { activeSpace, enterScope } from '../../app/activeSpace'
import { session } from '../../app/session'
import { vaultState } from '../../app/vault'
import { TENANT_SCOPE } from '../../core/node/location'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'
import IncompatibleScreen from './IncompatibleScreen.vue'
import FinishProfilePrompt from '../../features/account/profile/FinishProfilePrompt.vue'
import OperatorIdentification from '../../core/operator/OperatorIdentification.vue'
import DonationBanner from '../../core/project/DonationBanner.vue'
import UnconfirmedEmailBanner from '../../features/account/email/UnconfirmedEmailBanner.vue'
import { unconfirmedAddress } from '../../features/account/email/unconfirmedAddress'
import ShortcutsDialog from '../../core/shortcuts/ShortcutsDialog.vue'
import AudioBar from '../../core/audio/AudioBar.vue'

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
// A page of running text in the public shell — the terms and the privacy notice (`meta.reading`) — reads at a text
// measure, not in the sign-in card's 460 px column, which wrapped it every six words.
const readingLayout = computed(() => route.meta.reading === true)
// The audio bar lives as long as the signed-in, unlocked session: never on a page a locked or signed-out browser shows.
const audioBarOn = computed(() => session.accessToken !== null && vaultState.unlocked)
// The other notices wait for the banner's answer and show only on a confirmed (or unreadable) address.
const otherNoticesOn = computed(() => unconfirmedAddress.value === null)

// Only the home enters a scope (from its #fragment), so an app page loaded straight — a reload on /settings — would run
// with none: no tenant sections, no terms gate, no tenant capabilities. Such a page gets the token-less tenant home,
// what `/` opens without a fragment. The home is left to itself (its fragment names the scope), and a suspended tenant
// to the offline notice.
const needsScope = computed(
  () =>
    appLayout.value &&
    route.name !== 'home' &&
    session.accessToken !== null &&
    vaultState.unlocked &&
    !serverState.suspended &&
    activeSpace.status === 'idle',
)
watch(needsScope, (needs) => { if (needs) void enterScope(TENANT_SCOPE) }, { immediate: true })
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
        <UnconfirmedEmailBanner />
        <DonationBanner v-if="otherNoticesOn" member />
        <FinishProfilePrompt v-if="otherNoticesOn" />
        <RouterView />
      </main>
      <footer class="app-operator-footer">
        <OperatorIdentification />
      </footer>
      <AudioBar v-if="audioBarOn" />
    </div>
    <!-- The keyboard shortcut overview (uc.platform.keyboard-shortcuts): `?` and the account menu open it; the member's
         shell only, as a share page has none. -->
    <ShortcutsDialog />
  </div>
  <main v-else-if="plainLayout" class="plain-shell">
    <RouterView />
  </main>
  <main v-else class="public-shell">
    <div class="veyq-shell-inner" :class="{ 'veyq-shell-inner--reading': readingLayout }">
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
.veyq-shell-inner--reading {
  width: min(46rem, 100%);
}
</style>
