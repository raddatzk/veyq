<script setup lang="ts">
// Top-level layout switch (cr-0030). Routes flagged `meta.layout === 'app'` render the workspace shell; everything else
// keeps the centered .public-shell. The router already gates app routes on session + unlocked, so this decides layout —
// and opens a scope for an app route that is not the home (below). The workspace shell itself — the sidebar, the top bar,
// the notices above the route, the operator footer, the audio bar and the shortcut overview — is the member's chrome
// (`MemberChrome.vue`), a chunk of its own that a link guest's first load does not fetch (inbox #1310); its header says
// what each part is and when it shows. This file keeps the two conditions it is handed (below). The *Open in the app*
// banner (`core/openInApp/`, ADR 0150 § O2) heads the public card's pages as it heads the signed-in app's; the share page
// (the plain layout) draws its own.
import { computed, defineAsyncComponent, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useServerState } from '../../shared/state/useServerState'
import { useCapabilities } from '../../shared/state/useCapabilities'
import { activeSpace, enterScope } from '../../app/activeSpace'
import { session } from '../../app/session'
import { vaultState } from '../../app/vault'
import { TENANT_SCOPE } from '../../core/node/location'
import IncompatibleScreen from './IncompatibleScreen.vue'
import { unconfirmedAddress } from '../../features/account/email/unconfirmedAddress'
import OpenInAppBanner from '../../core/openInApp/OpenInAppBanner.vue'
import { loadMemberChrome } from './memberChrome'

const MemberChrome = defineAsyncComponent(loadMemberChrome)

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
  <MemberChrome v-else-if="appLayout" :other-notices-on="otherNoticesOn" :audio-bar-on="audioBarOn" />
  <main v-else-if="plainLayout" class="plain-shell">
    <RouterView />
  </main>
  <main v-else class="public-shell">
    <div class="veyq-shell-inner" :class="{ 'veyq-shell-inner--reading': readingLayout }">
      <p v-if="serverState.unreachable" class="veyq-banner" role="alert">{{ t('common.unreachable') }}</p>
      <OpenInAppBanner />
      <RouterView />
    </div>
  </main>
</template>

<style scoped>
.veyq-shell-inner {
  width: min(460px, 100%);
}
.veyq-shell-inner--reading {
  width: min(46rem, 100%);
}
</style>
