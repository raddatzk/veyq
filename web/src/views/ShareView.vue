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
//  - for a `WRITE_ONLY` link, the drop page its root kind's application claimed (`registry.shareBody`): its guest holds
//    no key, so there is nothing to render the member's way (Memories' photo drop, Depot's drop box).
// Anything else — a root kind nobody can render, or none named — is the explicit unavailable state, never a blank page.
// A footer links to veyq's security docs (the client-integrity trust tier, ADR 0026).
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
import { ref, shallowRef, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  resolvePublicShare,
  pollPublicShare,
  derivePasswordToken,
  fetchShareTerms,
  pickShareTerms,
  acceptedTermsRevision,
  recordTermsAccepted,
  pickLocaleText,
  SharePasswordRequiredError,
  SharePasswordIncorrectError,
  ShareUnavailableError,
  type PublicShareHead,
} from '../core/sharing/resolvePublicShare'
import { registry } from '../core/applications/registry'
import { FOLDER_KIND } from '../core/node/kind'
import ShareTreePage from '../core/sharing/ShareTreePage.vue'
import { guestAccess } from '../core/sharing/guestAccess'
import { forgetGuestSession, nameShareGuest, useGuestSession, type GuestIdentity } from '../core/sharing/guestIdentity'
import { ShareRole } from '../api/generated/sharing'
import GuestNaming from '../core/sharing/GuestNaming.vue'
import { renderMarkdown } from '../shared/markdown'
import { setPreferenceAudience } from '../shared/state/scopedPreference'
import { shareTreeFingerprint } from '../core/sharing/shareTreeFingerprint'
import { isNoteAccepted, recordNoteAccepted } from '../core/sharing/shareNote'

const route = useRoute()
const { t, locale } = useI18n()

const shareId = String(route.params.shareId ?? '')
// The fragment key rides `#k=<base64url>` (ADR 0015) — read on device, never sent. Router history strips it from
// route.hash into `#k=...`; parse it here (empty if absent → an inert link, handled as unavailable).
const fragmentKey = computed(() => {
  const h = route.hash.startsWith('#') ? route.hash.slice(1) : route.hash
  const m = /(?:^|&)k=([^&]+)/.exec(h)
  return m ? m[1] : ''
})

const status = ref<'loading' | 'ready' | 'unavailable' | 'locked'>('loading')
// cr-0118: a password-protected share resolves to a LOCKED shell — the viewer enters the password, we derive the
// Argon2id token client-side, and re-resolve with it. The token is then held + presented on every gated blob GET.
const pwSalt = ref('')
const pwInput = ref('')
const pwToken = ref<string | undefined>(undefined)
const pwError = ref(false) // last attempt was the wrong password
const pwBusy = ref(false) // deriving Argon2id + re-resolving
// The platform half of the resolve. A shallow ref: it holds `K_share`, which must never be wrapped in a reactive proxy.
const head = shallowRef<PublicShareHead | null>(null)

// --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ----------
// Before viewing/contributing, the visitor accepts the operator's tenant terms. Acceptance is stored CLIENT-SIDE
// only (localStorage, keyed per tenant) — the server records nothing about the accountless visitor. This is an
// OPERATIONAL GATE, NOT ENFORCEMENT: a localStorage flag is bypassable, so it records + surfaces consent, it does
// not police access (the terms' content + the decryptable-on-report path are the operator's real remedy). It also
// FAILS OPEN: a transient terms-fetch error is treated as "no terms" (revision 0) so a network blip never locks a
// visitor out. The terms come from the share (GET /api/shares/{shareId}/terms) — a public share is Host-independent,
// so the tenant can't come from the Host, and the member terms endpoint is auth+membership-gated.
const termsTenantId = ref('')
const termsRevision = ref(0) // 0 = no gate (also the fail-open value on a fetch error)
const acceptedRevision = ref(0) // the locally-recorded accepted revision; bumped in-memory on accept so the gate clears
const termsMarkdown = ref('')
const renderedTerms = computed(() => (termsMarkdown.value ? renderMarkdown(termsMarkdown.value) : ''))
// The gate fires once the share is resolved (post-password), the tenant has published terms, and the locally-stored
// accepted revision is below current. Sits BETWEEN the password shell and the display-name pre-screen in the template.
const needsTerms = computed(() => status.value === 'ready' && termsRevision.value > 0 && acceptedRevision.value < termsRevision.value)

/** Fetch the owning tenant's terms + read the locally-stored accepted revision. FAILS OPEN (uc.sharing.share-accept):
 *  a transient error is treated as "no terms" (revision 0) so the visitor is never locked out by a network blip. */
async function loadTerms() {
  try {
    const terms = await fetchShareTerms(shareId)
    termsTenantId.value = terms.tenantId
    termsRevision.value = terms.termsRevision
    termsMarkdown.value = pickShareTerms(terms, locale.value)
    acceptedRevision.value = acceptedTermsRevision(terms.tenantId)
  } catch {
    termsRevision.value = 0
    acceptedRevision.value = 0
  }
}

/** Record acceptance client-side (localStorage, per tenant) + clear the gate in-memory. No server call — the
 *  accountless visitor's acceptance is client-only, so the server keeps no record (anonymity preserved). */
function acceptTerms() {
  recordTermsAccepted(termsTenantId.value, termsRevision.value)
  acceptedRevision.value = termsRevision.value
}

// --- the note's acceptance gate (uc.sharing.share-note, ADR 0036 point 2) ----------------------------------------
// A curator may ask that their note be accepted before the share opens: the switch is sealed inside the note, so the
// head carries it only after the fragment key opened it (`noteAcceptance`, the digest of the opened plaintext). The gate
// sits after the password and the terms and before anything of the share is drawn, for every role, and is the shell's
// like the terms gate: decided on each full resolve (`load`), BEFORE the page flips to `ready`, so no content flashes
// ahead of it; a live poll never raises it mid-visit (an edited note asks at the next load). Accepting is remembered on
// this device only (`core/sharing/shareNote.ts`, keyed by share and digest — an edited note asks again), and for this
// visit in memory, so a device whose storage refuses still lets the visitor through once and asks again on the next
// load. Nothing reaches the server. Operational, not enforcement: the content opens with the fragment key alone.
/** The digests this visit accepted (a re-resolve after a guest's write must not ask again where storage refused). */
const acceptedNotes = new Set<string>()
/** The digest of the note this visitor has still to accept, or null (no note asks, or it was accepted). */
const noteGate = ref<string | null>(null)
const needsNote = computed(() => status.value === 'ready' && noteGate.value !== null)
const renderedNote = computed(() => {
  const text = needsNote.value && head.value ? pickLocaleText(head.value.noteByLocale, locale.value) : ''
  return text ? renderMarkdown(text) : ''
})
/** The gate a resolved head asks for: its note's digest unless this device (or this visit) accepted that very text. */
function noteGateOf(h: PublicShareHead): string | null {
  const digest = h.noteAcceptance?.digest
  if (!digest || acceptedNotes.has(digest) || isNoteAccepted(shareId, digest)) return null
  return digest
}
/** Accept the note: remembered on this device (if it can) and for this visit; no server call. */
function acceptNote() {
  const digest = noteGate.value
  if (!digest) return
  recordNoteAccepted(shareId, digest)
  acceptedNotes.add(digest)
  noteGate.value = null
}
/** The head the page below the gates is handed: a note that asked to be accepted is not repeated as its banner — the
 *  visitor got past its gate, so they have read it. */
const bodyHead = computed(() => (head.value?.noteAcceptance ? { ...head.value, noteByLocale: {} } : head.value))

// The security-doc footer link (ADR 0026 trust-tier honesty) — the published docs site (ADR 0087), locale-aware: the
// German page sits under /de/ at the English page's path.
const securityDocUrl = computed(() =>
  locale.value === 'de'
    ? 'https://raddatzk.github.io/veyq/de/privacy/is-my-data-safe/'
    : 'https://raddatzk.github.io/veyq/privacy/is-my-data-safe/',
)

/** What renders this share behind the gates, or null (the explicit unavailable state): a `WRITE_ONLY` link's drop page
 *  claimed for its root kind, else core's page — for a root kind the app can render as a member would (see the header). */
function bodyFor(h: PublicShareHead) {
  const kind = h.rootKind
  if (!kind) return null
  if (h.role === ShareRole.WriteOnly) return registry.shareBody(kind)
  const info = registry.kind(kind)
  return kind === FOLDER_KIND || (info.supported && info.detail) ? ShareTreePage : null
}
const body = computed(() => (head.value ? bodyFor(head.value) : null))

// --- the guest: its access, and the page's one naming flow ------------------------------------------------------------
const session = useGuestSession(shareId)
const naming = ref(false)
// The views waiting for the guest's identity: resolved once it is named, or with null when the dialog closes unnamed.
let waiting: ((g: GuestIdentity | null) => void)[] = []
/** The guest's identity for this link: the device's session, else the visitor names itself first (null: it closed the
 *  dialog without a name). */
function requestGuest(): Promise<GuestIdentity | null> {
  if (session.value) return Promise.resolve(session.value)
  naming.value = true
  return new Promise((resolve) => waiting.push(resolve))
}
async function nameGuest(name: string) {
  const h = head.value
  if (!h) throw new Error('share_not_ready')
  await nameShareGuest(h, { name, fragmentKey: fragmentKey.value, pwToken: pwToken.value })
}
function onNamingClose() {
  naming.value = false
  const all = waiting
  waiting = []
  for (const w of all) w(session.value)
}
const access = computed(() =>
  head.value ? guestAccess({ head: head.value, pwToken: pwToken.value, fragmentKey: fragmentKey.value, requestGuest, reload: load, poll }) : null,
)
// This document's pages are a guest's: its stored view mode and tab are the guest's own, never an account's
// (`shared/state/scopedPreference`, inbox #408).
setPreferenceAudience('guest')
onBeforeUnmount(() => setPreferenceAudience('account'))

/** The level view's live refresh (inbox #408, ADR 0081): re-resolve quietly and CONDITIONALLY — the last response's tag
 *  goes as `If-None-Match`, and a `304` is no work at all — and swap `head` only when the subtree differs (the
 *  fingerprint stays the fallback for a server that sends no tag). Resolves whether the link's state moved, the cue for
 *  the row sources to look again at what lives beside the tree.
 *
 *  A link that is GONE (revoked, expired, deleted, its space going, its root trashed) is noticed here: the server's
 *  non-enumerable `404` flips the page to the unavailable state, as a reload would, and a changed password to the
 *  password gate. A failure that says nothing about the link (the network, a 5xx, a 429) is thrown to the poller (it
 *  backs off) and never reaches the page's state. A resolve that began before a `load` (the guest's own write) ran is
 *  dropped, as it may predate the write — and the write's own re-resolve starts the tag over. */
let resolves = 0
let shareTag: string | null = null
async function poll(signal: AbortSignal): Promise<boolean> {
  const current = head.value
  if (!current || status.value !== 'ready') return false
  const mine = resolves
  let polled
  try {
    polled = await pollPublicShare(shareId, fragmentKey.value, pwToken.value, shareTag)
  } catch (e) {
    if (signal.aborted || mine !== resolves || head.value !== current) return false
    if (!failWith(e)) throw e
    return false
  }
  if (signal.aborted || mine !== resolves || head.value !== current) return false
  if (!polled) return false // 304: the link serves what is shown
  shareTag = polled.etag
  if (!bodyFor(polled.head)) {
    status.value = 'unavailable' // the root is gone from under the link (trashed): nothing renders it any more
    return false
  }
  if (shareTreeFingerprint(polled.head.share) !== shareTreeFingerprint(current.share)) head.value = polled.head
  return true
}

/** The page state a failed resolve means, or false for a failure that is none of the link's (the caller rethrows). */
function failWith(e: unknown): boolean {
  if (e instanceof SharePasswordRequiredError) {
    pwSalt.value = e.pwSalt
    status.value = 'locked'
  } else if (e instanceof SharePasswordIncorrectError) {
    pwError.value = true
    status.value = 'locked'
  } else if (e instanceof ShareUnavailableError) {
    status.value = 'unavailable'
  } else {
    return false
  }
  return true
}

/** The line that says whose session this is: a READ_WRITE link's (a drop page shows its own). */
const showsIdentity = computed(() => head.value?.role === ShareRole.ReadWrite && !!session.value)

// --- resolve (on mount, after the password, and after a contribution) --------
async function load() {
  resolves++
  shareTag = null // what this resolve serves carries no tag the poll holds: its first read is a full one
  try {
    const resolved = await resolvePublicShare(shareId, fragmentKey.value, pwToken.value)
    if (!bodyFor(resolved)) {
      // No application renders this kind of share: say so, rather than mounting nothing.
      status.value = 'unavailable'
      return
    }
    // Decide the terms gate BEFORE flipping to 'ready' so an ungated content flash can't slip in between resolve
    // and the gate (loadTerms fails open, so it never throws here). The gate then sits ahead of the content.
    await loadTerms()
    noteGate.value = noteGateOf(resolved) // the note's gate too, decided before 'ready' (it follows the terms gate)
    head.value = resolved
    status.value = 'ready'
  } catch (e) {
    // cr-0118: a password-protected share → prompt (or re-prompt on a wrong password); otherwise the honest
    // "unavailable" state (bad key / 404 / unexpected).
    if (!failWith(e)) status.value = 'unavailable'
  }
}

/** The viewer submitted the password: derive the Argon2id token client-side, then re-resolve with it. */
async function submitPassword() {
  if (!pwInput.value || pwBusy.value) return
  pwBusy.value = true
  pwError.value = false
  try {
    pwToken.value = await derivePasswordToken(pwInput.value, pwSalt.value)
    status.value = 'loading'
    await load()
  } finally {
    pwBusy.value = false
  }
}
onMounted(() => {
  void load()
})
</script>

<template>
  <div class="share-view">
    <div v-if="status === 'loading'" class="share-view-state">
      <span class="pi pi-spin pi-spinner" />
      <span>{{ t('share.loading') }}</span>
    </div>

    <div v-else-if="status === 'unavailable'" class="share-view-state share-view-unavailable">
      <span class="pi pi-lock" />
      <h1>{{ t('share.unavailable') }}</h1>
      <p>{{ t('share.unavailableHint') }}</p>
    </div>

    <!-- cr-0118: a password-protected share resolves to a LOCKED shell (nothing else crossed the wire) — prompt for
         the password, derive the Argon2id token on device, and re-resolve with it. -->
    <div v-else-if="status === 'locked'" class="share-view-state share-name-gate">
      <form class="veyq-surface share-name-card" @submit.prevent="submitPassword">
        <span class="pi pi-lock share-password-icon" />
        <h1>{{ t('share.passwordTitle') }}</h1>
        <p class="share-name-intro">{{ t('share.passwordIntro') }}</p>
        <label for="share-password-input">{{ t('share.passwordLabel') }}</label>
        <input
          id="share-password-input"
          v-model="pwInput"
          type="password"
          name="share-password"
          autocomplete="current-password"
          :placeholder="t('share.passwordPlaceholder')"
          :disabled="pwBusy"
        />
        <button class="share-name-submit" type="submit" :disabled="pwBusy || !pwInput">
          {{ pwBusy ? t('share.passwordChecking') : t('share.passwordUnlock') }}
        </button>
        <p v-if="pwError" class="share-error">{{ t('share.passwordWrong') }}</p>
      </form>
    </div>

    <!-- The accountless terms gate (uc.sharing.share-accept, ADR 0035 §3): the operator's terms + an Accept button,
         BETWEEN the password shell and the display-name pre-screen (password → terms → name → content). Acceptance
         is client-only (localStorage) — an operational gate, not enforcement: it records + surfaces consent, it does
         not police access. renderMarkdown escapes first (safe subset), so v-html is safe. -->
    <div v-else-if="needsTerms" class="share-view-state share-name-gate">
      <div class="veyq-surface share-name-card share-terms-card">
        <span class="pi pi-file-check share-terms-icon" />
        <h1>{{ t('share.termsTitle') }}</h1>
        <p class="share-name-intro">{{ t('share.termsIntro') }}</p>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="share-terms-body markdown-body" v-html="renderedTerms"></div>
        <button class="share-name-submit" type="button" @click="acceptTerms">{{ t('share.termsAccept') }}</button>
      </div>
    </div>

    <!-- The note's acceptance gate (uc.sharing.share-note): the curator's note + Accept, AFTER the terms and before
         anything of the share (every role). No decline: closing the tab is the decline. renderMarkdown escapes first
         (safe subset), so v-html is safe. -->
    <div v-else-if="needsNote" class="share-view-state share-name-gate">
      <div class="veyq-surface share-name-card share-terms-card" data-testid="share-note-gate">
        <span class="pi pi-envelope share-terms-icon" />
        <h1>{{ t('share.noteGateTitle') }}</h1>
        <p class="share-name-intro">{{ t('share.noteGateIntro') }}</p>
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div class="share-terms-body markdown-body" v-html="renderedNote"></div>
        <button class="share-name-submit" type="button" @click="acceptNote">{{ t('share.noteGateAccept') }}</button>
      </div>
    </div>

    <template v-else-if="head && bodyHead && body && access">
      <GuestNaming v-if="showsIdentity" class="share-view-identity" :current="session" :forget="() => forgetGuestSession(shareId)" />
      <!-- Core's page of the link's tree (the member's views), or a WRITE_ONLY link's drop page (its naming too). -->
      <component
        :is="body"
        :share-id="shareId"
        :fragment-key="fragmentKey"
        :pw-token="pwToken"
        :head="bodyHead"
        :reload="load"
        :access="access"
        @unavailable="status = 'unavailable'"
      />
      <GuestNaming
        v-if="naming"
        dialog
        :title="t('share.guest.nameTitle')"
        :hint="t('share.guest.nameHint')"
        :action="nameGuest"
        @close="onNamingClose"
      />
    </template>

    <footer class="share-view-footer">
      <a :href="securityDocUrl" target="_blank" rel="noopener noreferrer">{{ t('share.securityFooter') }}</a>
    </footer>
  </div>
</template>

<style scoped src="../shared/ui/shareGate.css"></style>

<style scoped>
/* The share page fills the full viewport (rendered outside the app shell, in the 'plain' layout). The page below the
   gates brings its own look (the member's views and their GLOBAL design-source stylesheets); the state cards (loading,
   unavailable, password, terms) come from the shared gate sheet above. */
.share-view {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  width: min(1200px, 100%);
  margin: 0 auto;
  padding: 1.5rem 1.25rem 2rem;
  box-sizing: border-box;
}
.share-view-identity {
  margin-bottom: 0.75rem;
}
.share-view-unavailable .pi {
  font-size: 2rem;
}
.share-view-unavailable h1 {
  margin: 0.25rem 0 0;
  font-size: 1.4rem;
}
.share-view-unavailable p {
  margin: 0;
  max-width: 30rem;
}
/* The terms gate reuses the centered card; its body is the safe-markdown terms, scrollable when long so the
   Accept button stays reachable. Wider than the name card since terms are prose. */
.share-terms-card {
  width: min(38rem, 100%);
}
.share-terms-icon {
  font-size: 1.5rem;
  color: var(--app-accent);
}
.share-terms-body {
  max-height: min(50vh, 26rem);
  overflow-y: auto;
  margin: 0.2rem 0 0.4rem;
  padding: 0.85rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-size: 0.92rem;
  line-height: 1.55;
}
.share-terms-body :deep(h1) {
  font-size: 1.15rem;
  margin: 0 0 0.5rem;
}
.share-terms-body :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.share-terms-body :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
}
.share-terms-body :deep(p) {
  margin: 0 0 0.55rem;
}
.share-terms-body :deep(ul),
.share-terms-body :deep(ol) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
.share-terms-body :deep(a) {
  color: var(--app-accent);
}
.share-view-footer {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid var(--app-line);
  text-align: center;
}
.share-view-footer a {
  color: var(--app-muted);
  font-size: 0.82rem;
}
</style>
