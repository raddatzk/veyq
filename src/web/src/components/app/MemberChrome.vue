<script setup lang="ts">
// The member's chrome (inbox #1310): the signed-in app's workspace shell around the route, drawn by `AppShell` for
// `meta.layout === 'app'` and loaded apart from the entry (`memberChrome.ts`), so a link guest's first load does not
// carry it. Markup + classes are the design source's AppShell verbatim (the .app-shell grid: .app-compact-header places
// the sidebar + topbar, .app-workspace > main.content holds the route) so layout.css styles it identically. Below the
// workspace sits the operator footer (uc.admin.operator-details): who runs the instance and its terms, reachable from every page of the
// signed-in app as § 5 DDG asks of a provider identification and DSA Art. 14(1) of the terms. Above the route sits the
// donation banner as a member's (uc.platform.support-the-project, ADR 0124) — on the signed-in app only, never on the
// public or plain layouts' login, invite, join and recovery pages — and above it, while the account's address is
// unconfirmed, the banner that says so and cannot be dismissed (ADR 0129 decision 5), on the same pages only. While it
// stands — and until its read has answered — the donation banner, the finish-your-profile prompt and the clearer-kit
// notice (ADR 0157 decision 5) wait (owner
// 2026-11-13): one notice at a time, the one that asks the member to act; they appear as before once the address is
// confirmed or cannot be read. The banner's read is the only one (`unconfirmedAddress.ts`). Below the footer sits core's
// audio bar (`core/audio/AudioBar.vue`, ADR 0135 decision 8), mounted here once so the music outlives every change of page,
// and only while the session is signed in and unlocked: a lock unmounts it, which stops the player and releases every
// source it opened (a sign-out or a tenant switch loads a new document). The *Open in the app* banner
// (`core/openInApp/`, ADR 0150 § O2) heads the signed-in app's pages (waiting, like the other notices, while the
// address banner stands) and the public card's; the share page (the plain layout) draws its own.
import { RouterView } from 'vue-router'
import AppSidebar from './AppSidebar.vue'
import AppTopbar from './AppTopbar.vue'
import FinishProfilePrompt from '../../features/account/profile/FinishProfilePrompt.vue'
import KitRenewalPrompt from '../../features/account/emergencyKit/KitRenewalPrompt.vue'
import OperatorIdentification from '../../core/operator/OperatorIdentification.vue'
import DonationBanner from '../../core/project/DonationBanner.vue'
import UnconfirmedEmailBanner from '../../features/account/email/UnconfirmedEmailBanner.vue'
import ShortcutsDialog from '../../core/shortcuts/ShortcutsDialog.vue'
import AudioBar from '../../core/audio/AudioBar.vue'
import OpenInAppBanner from '../../core/openInApp/OpenInAppBanner.vue'
// `AppShell` holds the two conditions — the audio bar's signed-in, unlocked session, and the other notices waiting for the
// address banner's answer — and hands them here.
defineProps<{ otherNoticesOn: boolean; audioBarOn: boolean }>()
</script>
<template>
  <div class="app-shell">
    <div class="app-compact-header">
      <AppSidebar />
      <AppTopbar />
    </div>
    <div class="app-workspace">
      <main class="content">
        <UnconfirmedEmailBanner />
        <OpenInAppBanner v-if="otherNoticesOn" />
        <DonationBanner v-if="otherNoticesOn" member />
        <FinishProfilePrompt v-if="otherNoticesOn" />
        <KitRenewalPrompt v-if="otherNoticesOn" />
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
</template>

<style scoped>
.app-operator-footer {
  display: flex;
  justify-content: center;
  padding: 0 1.25rem 1.25rem;
  text-align: center;
}
</style>
