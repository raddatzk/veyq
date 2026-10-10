<script setup lang="ts">
// The anonymous public share page (uc.sharing.share-public-viewer, cr-0100, ADR 0029) — a SHELL. Mounted at the
// no-session route `/s/:shareId`; the share key `K_share` rides the URL **#fragment** (`#k=<base64url>`) and is read
// on device — it never reaches the server. The shell resolves the kind-independent half of the share
// (`resolvePublicShare`), runs the gates every share has — password, then the accountless terms — and renders the body
// the registry holds for the share's ROOT node kind, which the server names for every role (a kind nobody claimed, or
// none, is the explicit unavailable state, never a blank page). What a body is —
// the name pre-screen, the content, the lightbox — belongs to the application (Memories: `MemoryShareBody`). A footer
// links to veyq's security docs (the client-integrity trust tier, ADR 0026).
//
// The order is password -> terms -> name -> content: the first two are here, "name" is the body's first screen.
// So a share whose application half cannot be opened (a server that served no wrap) is discovered when the body
// mounts — after the terms gate, not before it as when this page was one component — and the body answers with
// `unavailable`. The password and unlock flow, and the "no body registered" check, still run before any gate.
//
// **Who looks (inbox #401 S2).** Behind the gates the shell builds the guest's `access` (`core/sharing/guestAccess.ts`:
// the link's role and head, the password token) and hands it to the body, which hands it on to every view it mounts —
// core's level view at a folder, a section, an embedded body. The access carries the page's ONE naming flow,
// `requestGuest`: a READ_WRITE guest names itself in the shell's dialog (core's `GuestNaming`, under the share ROOT's
// key, `nameShareGuest`) whichever view first writes, and the shell shows whose session this device holds with its
// "Not you?" (`forgetGuestSession`, asked first). A WRITE_ONLY drop page names its guest on the page itself (its own
// identity key, the application's), so the shell's line is a READ_WRITE link's.
import { ref, shallowRef, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  resolvePublicShare,
  derivePasswordToken,
  fetchShareTerms,
  pickShareTerms,
  acceptedTermsVersion,
  recordTermsAccepted,
  SharePasswordRequiredError,
  SharePasswordIncorrectError,
  type PublicShareHead,
} from '../core/sharing/resolvePublicShare'
import { registry } from '../core/applications/registry'
import { guestAccess } from '../core/sharing/guestAccess'
import { forgetGuestSession, nameShareGuest, useGuestSession, type GuestIdentity } from '../core/sharing/guestIdentity'
import { ShareRole } from '../api/generated/sharing'
import GuestNaming from '../core/sharing/GuestNaming.vue'
import { renderMarkdown } from '../shared/markdown'

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
// FAILS OPEN: a transient terms-fetch error is treated as "no terms" (version 0) so a network blip never locks a
// visitor out. The terms come from the share (GET /api/shares/{shareId}/terms) — a public share is Host-independent,
// so the tenant can't come from the Host, and the member terms endpoint is auth+membership-gated.
const termsTenantId = ref('')
const termsVersion = ref(0) // 0 = no gate (also the fail-open value on a fetch error)
const acceptedVersion = ref(0) // the locally-recorded accepted version; bumped in-memory on accept so the gate clears
const termsMarkdown = ref('')
const renderedTerms = computed(() => (termsMarkdown.value ? renderMarkdown(termsMarkdown.value) : ''))
// The gate fires once the share is resolved (post-password), the tenant has published terms, and the locally-stored
// accepted version is below current. Sits BETWEEN the password shell and the display-name pre-screen in the template.
const needsTerms = computed(() => status.value === 'ready' && termsVersion.value > 0 && acceptedVersion.value < termsVersion.value)

/** Fetch the owning tenant's terms + read the locally-stored accepted version. FAILS OPEN (uc.sharing.share-accept):
 *  a transient error is treated as "no terms" (version 0) so the visitor is never locked out by a network blip. */
async function loadTerms() {
  try {
    const terms = await fetchShareTerms(shareId)
    termsTenantId.value = terms.tenantId
    termsVersion.value = terms.termsVersion
    termsMarkdown.value = pickShareTerms(terms, locale.value)
    acceptedVersion.value = acceptedTermsVersion(terms.tenantId)
  } catch {
    termsVersion.value = 0
    acceptedVersion.value = 0
  }
}

/** Record acceptance client-side (localStorage, per tenant) + clear the gate in-memory. No server call — the
 *  accountless visitor's acceptance is client-only, so the server keeps no record (anonymity preserved). */
function acceptTerms() {
  recordTermsAccepted(termsTenantId.value, termsVersion.value)
  acceptedVersion.value = termsVersion.value
}

// The security-doc footer link (ADR 0026 trust-tier honesty) — the published wiki, locale-aware.
const securityDocUrl = computed(() =>
  locale.value === 'de'
    ? 'https://github.com/raddatzk/veyq/wiki/sind-meine-daten-sicher'
    : 'https://github.com/raddatzk/veyq/wiki/is-my-data-safe',
)

/** The body the registry holds for this share's root kind, whatever its role, or null (the explicit unavailable
 *  state) when nobody claimed the kind or the server named none. */
function bodyFor(h: PublicShareHead) {
  return h.rootKind ? registry.shareBody(h.rootKind) : null
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
  head.value ? guestAccess({ head: head.value, pwToken: pwToken.value, fragmentKey: fragmentKey.value, requestGuest, reload: load }) : null,
)
/** The line that says whose session this is: a READ_WRITE link's (a drop page shows its own). */
const showsIdentity = computed(() => head.value?.role === ShareRole.ReadWrite && !!session.value)

// --- resolve (on mount, after the password, and after a contribution) --------
async function load() {
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
    head.value = resolved
    status.value = 'ready'
  } catch (e) {
    // cr-0118: a password-protected share → prompt (or re-prompt on a wrong password); otherwise the honest
    // "unavailable" state (bad key / 404 / unexpected).
    if (e instanceof SharePasswordRequiredError) {
      pwSalt.value = e.pwSalt
      status.value = 'locked'
    } else if (e instanceof SharePasswordIncorrectError) {
      pwError.value = true
      status.value = 'locked'
    } else {
      status.value = 'unavailable'
    }
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

    <template v-else-if="head && body && access">
      <GuestNaming v-if="showsIdentity" class="share-view-identity" :current="session" :forget="() => forgetGuestSession(shareId)" />
      <!-- The application's body for this share: its content and lightbox (a drop page's naming too). -->
      <component
        :is="body"
        :share-id="shareId"
        :fragment-key="fragmentKey"
        :pw-token="pwToken"
        :head="head"
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
/* The share page fills the full viewport (rendered outside the app shell, in the 'plain' layout). The application's
   body brings its own look (gallery grid + lightbox skin come from the GLOBAL design-source stylesheets); the state
   cards (loading, unavailable, password, terms) come from the shared gate sheet above. */
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
