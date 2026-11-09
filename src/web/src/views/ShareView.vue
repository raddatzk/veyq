<script setup lang="ts">
// The anonymous public share page (uc.sharing.share-public-viewer, cr-0100, ADR 0029) — a SHELL. Mounted at the
// no-session route `/s/:shareId`; the share key `K_share` rides the URL **#fragment** (`#k=<base64url>`) and is read
// on device — it never reaches the server. The shell resolves the kind-independent half of the share
// (`resolvePublicShare`), runs the gates every share has — password, then the accountless terms — and then renders:
//  - for a `READ` or `READ_WRITE` link, core's page (`core/sharing/ShareTreePage.vue`): the MEMBER's views handed the
//    guest's access and tree — core's level view at a folder, the kind's registered `detail` at any other node, the
//    link's root included — so a share looks exactly like the member view and the role only removes functions (inbox
//    #401, the owner principle of 2026-10-09). The root kind must be one the app can render (FOLDER, or a kind with a
//    `detail`);
//  - for a `WRITE_ONLY` link, the drop page its root kind's application claimed (`registry.shareBodyFor`): its guest holds
//    no key, so there is nothing to render the member's way (Memories' photo drop, Depot's drop box).
// Anything else — a root kind nobody can render, or none named — is the explicit unavailable state, never a blank page.
// Above the gates, the donation banner as a guest's (uc.platform.support-the-project: its dismissal stays in this browser);
// the footer is the instance's, with veyq's own "Why is veyq secure?" and "Donate" in it (`OperatorIdentification`).
//
// The gates and what lies behind them are core's (`core/sharing/shareGates.ts`, drawn by `core/sharing/ShareGates.vue`),
// which a bookmark's page runs too for a link it shows in place (ADR 0121); this page is their host for this origin's link,
// with what only it has: the moderation mode, a bookmark's report hand-off, the footer, the guest audience of its stored
// preferences and the guest uploader's notice.
//
// The order is password -> terms -> the note's acceptance -> content. A share whose keys cannot be opened (a server that
// served no wrap) is discovered when the page mounts — after the gates — and the page answers with `unavailable`.
//
// **Who looks (inbox #401 S2).** Behind the gates the shell builds the guest's `access` (`core/sharing/guestAccess.ts`:
// the link's role and head, the password token) and hands it to the page, which hands it on to every view it mounts.
// The access carries the page's ONE naming flow, `requestGuest`: a READ_WRITE guest names itself in the shell's dialog
// (core's `GuestNaming`, under the share ROOT's key, `nameShareGuest`) whichever view first writes, and the shell shows
// whose session this device holds with its "Not you?" (`forgetGuestSession`, asked first). A WRITE_ONLY drop page names
// its guest on the page itself (its own identity key, the application's), so the shell's line is a READ_WRITE link's.
//
// **Moderation mode** (`/s/<caseId>#moderation`, uc.moderation.review-a-case, ADR 0093 § 3, ADR 0099 § 6). The fragment
// `#moderation` turns this page into a moderator's review of a case: the route's id is then a CASE id, no link is
// resolved — none of the shell's public reads (`resolvePublicShare`, the terms, the poll) runs — and the review renders
// behind the moderation gate (`features/moderation/review/ShareReview.vue`), the held snapshot drawn by the same guest
// views over the review's own key-proved data source. The fragment carries no key.
//
// **A bookmark's hand-off** (ADR 0120 decisions 5 and 6). A bookmark of this instance opens this page in the same tab and
// may hand it the link's password in memory (`core/sharing/handedPassword.ts`): it is entered once, when the link asks
// for one, and a wrong one leaves the password form. A fragment parameter `report` (beside `k=`, from a bookmark here or on
// another instance) opens this page's own report dialog once the gates are passed - for what the link shows, as its guest
// would report it: the root of a `READ` / `READ_WRITE` link, the link itself for an upload link - and is dropped from the
// URL as it is read, so a reload or a copied address does not ask again.
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import ShareGates from '../core/sharing/ShareGates.vue'
import { useShareGates } from '../core/sharing/shareGates'
import { guestTreeSource } from '../core/sharing/guestAccess'
import { takeHandedSharePassword } from '../core/sharing/handedPassword'
import { asksForReport, withoutReport } from '../core/bookmark/bookmarkLink'
import { reportNodes } from '../core/report/reportActions'
import { ShareRole } from '../api/generated/sharing'
import { setPreferenceAudience } from '../shared/state/scopedPreference'
import OperatorIdentification from '../core/operator/OperatorIdentification.vue'
import DonationBanner from '../core/project/DonationBanner.vue'
import { closeGuestPage, openGuestPage } from '../core/sharing/guestNotice'
import { openReport, useReportOffered } from '../core/report/report'
import { linkReportKeys } from '../core/report/reportKeys'
import ShareReview from '../features/moderation/review/ShareReview.vue'

const route = useRoute()
const { t, locale } = useI18n()

const shareId = String(route.params.shareId ?? '')
/** The moderation mode: read once — a review never becomes a link's page, nor a link's page a review. */
const moderation = route.hash === '#moderation'
/** A bookmark's report hand-off (ADR 0120 decision 6): read once, then dropped from the URL. */
const reportAsked = !moderation && asksForReport(route.hash)
if (reportAsked) void useRouter().replace({ query: route.query, hash: withoutReport(route.hash) })
// The fragment key rides `#k=<base64url>` (ADR 0015) — read on device, never sent. Router history strips it from
// route.hash into `#k=...`; parse it here (empty if absent → an inert link, handled as unavailable).
const fragmentKey = computed(() => {
  const h = route.hash.startsWith('#') ? route.hash.slice(1) : route.hash
  const m = /(?:^|&)k=([^&]+)/.exec(h)
  return m ? m[1] : ''
})

// The gates and the guest behind them (`core/sharing/shareGates.ts`, drawn by `ShareGates.vue`): this origin's link, its
// id bare — every call exactly as this page always made it. A bookmark of this instance may have handed the link's
// password (ADR 0120 decision 5): entered once, as the reader would.
const gates = useShareGates({
  at: shareId,
  fragmentKey: () => fragmentKey.value,
  handedPassword: () => takeHandedSharePassword(shareId),
  locale,
})
const { status, head, access, needsTerms, needsNote } = gates

// An upload link's guest sees no node, so it reports the link itself (uc.moderation.report, ADR 0093 § 1): its name and
// note, with `K_share`, which opens no content. A viewing guest reports what it sees from the views themselves.
const reportOn = useReportOffered()
const reportsLink = computed(() => reportOn.value && status.value === 'ready' && head.value?.role === ShareRole.WriteOnly)
function reportLink() {
  const a = access.value
  if (a) openReport({ access: a, scopeId: a.head.scopeId, targets: [{ kind: 'LINK', keys: () => linkReportKeys(a) }] })
}
// This document's pages are a guest's: its stored view mode and tab are the guest's own, never an account's
// (`shared/state/scopedPreference`, inbox #408).
setPreferenceAudience('guest')
onBeforeUnmount(() => setPreferenceAudience('account'))
// What the link tells this device's guest uploader of a measure on its upload (ADR 0093 §§ 7, 12) is read for this page
// — by the § 12 notice and by a blocked placeholder, only where this device holds the guest's session; never in a review,
// whose id is a case's and which calls no guest endpoint.
if (!moderation) {
  openGuestPage(shareId)
  onBeforeUnmount(closeGuestPage)
}

onMounted(() => {
  if (!moderation) void gates.load()
})

// The report a bookmark asked for, opened once the page shows the link's content (every gate passed) and this instance
// takes reports: what the link shows, as its guest reports it - never before, and never twice.
let reported = false
watch(
  () => reportAsked && !reported && reportOn.value && status.value === 'ready' && !needsTerms.value && !needsNote.value && !!access.value,
  (ready) => {
    const a = access.value
    if (!ready || !a) return
    reported = true
    if (a.head.role === ShareRole.WriteOnly) {
      reportLink()
      return
    }
    const tree = guestTreeSource(a.head, route)
    const root = tree.node(tree.rootId)
    if (root) reportNodes(a, tree, [root], null)
  },
  { immediate: true },
)
</script>

<template>
  <ShareReview v-if="moderation" :case-id="shareId" />
  <div v-else class="share-view">
    <DonationBanner />
    <ShareGates :gates="gates" />

    <footer class="share-view-footer">
      <button v-if="reportsLink" type="button" class="share-view-report" @click="reportLink">{{ t('report.reportLink') }}</button>
      <OperatorIdentification />
    </footer>
  </div>
</template>

<style scoped>
/* The share page fills the full viewport (rendered outside the app shell, in the 'plain' layout). The page below the
   gates brings its own look (the member's views and their GLOBAL design-source stylesheets); the gates' cards are
   `ShareGates.vue`'s. */
.share-view {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  width: min(1200px, 100%);
  margin: 0 auto;
  padding: 1.5rem 1.25rem 2rem;
  box-sizing: border-box;
}
.share-view-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: baseline;
  gap: 0.25rem 1rem;
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid var(--app-line);
  text-align: center;
}
.share-view-report {
  color: var(--app-muted);
  font-size: 0.82rem;
  padding: 0;
  border: none;
  background: none;
  text-decoration: underline;
  cursor: pointer;
}
</style>
