// The gates every public link passes and the guest behind them — one state machine, two hosts (ADR 0121 decision 6):
// the share page (`views/ShareView.vue`, `/s/<id>`, this origin's link) and a bookmark's page that shows its link in place
// (`core/bookmark/BookmarkEmbed.vue`, a link of this or any veyq instance). Extracted from the share page unchanged, so its
// order and its rules are the share page's own:
//
//   password -> the share's tenant's terms -> the note's acceptance -> content,
//
// each decided on a full resolve (`load`), BEFORE the state flips to `ready`, so no content flashes ahead of a gate; a
// live poll (`poll`) never raises a gate mid-visit, it only swaps the head when the subtree moved, flips to `unavailable`
// when the link is gone and to the password form when its password changed. Behind the gates the guest's `access`
// (`./guestAccess.ts`) carries the page's ONE naming flow, `requestGuest`, answered by core's `GuestNaming` dialog that
// `./ShareGates.vue` mounts.
//
// **Where the share lives** is `at` (`./shareAt.ts`): a bare id on the share page, exactly the calls it always made; an id
// with another instance's API base on a bookmark's page, so the resolve, the poll, the terms, the naming and every read
// and write of the views below go there, and what this device keeps — the acceptance of the terms and of a note, the
// guest's identity — is that link's alone.
//
// **The accountless terms gate** (uc.sharing.share-accept, ADR 0035 §3): acceptance is stored CLIENT-SIDE only
// (localStorage, keyed per tenant — of the share's instance), the server records nothing. An OPERATIONAL GATE, NOT
// ENFORCEMENT: a localStorage flag is bypassable, so it records and surfaces consent. It FAILS OPEN: a transient terms
// error is "no terms" (revision 0), so a network blip never locks a visitor out. The terms come from the share
// (`getShareTerms`): a public share is Host-independent, so the tenant cannot come from the Host. After the space's text
// comes the fixed operator section (ADR 0035 decision 6); what is accepted is the PAIR of revisions.
//
// **The note's acceptance gate** (uc.sharing.share-note, ADR 0036 point 2): sealed inside the note, so the head carries
// it only after the fragment key opened it (`noteAcceptance`, the digest of the opened plaintext); remembered on this
// device (`./shareNote.ts`) and for this visit in memory, so a device whose storage refuses still lets the visitor
// through once. Operational, not enforcement: the content opens with the fragment key alone.
import { computed, ref, shallowRef, type Ref } from 'vue'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { ShareRole } from '../../api/generated/sharing'
import { markdownHtml } from '../../shared/security/trustedTypes'
import { pairKey, pairOf, type OperatorSection } from '../terms/operatorSection'
import ShareTreePage from './ShareTreePage.vue'
import { guestAccess } from './guestAccess'
import { forgetGuestSession, nameShareGuest, useGuestSession, type GuestIdentity } from './guestIdentity'
import { shareTreeFingerprint } from './shareTreeFingerprint'
import { isNoteAccepted, recordNoteAccepted } from './shareNote'
import { apiBaseOf, type ShareAt } from './shareAt'
import {
  acceptedTermsKeys,
  derivePasswordToken,
  fetchShareTerms,
  pickLocaleText,
  pickShareTerms,
  pollPublicShare,
  recordTermsAccepted,
  resolvePublicShare,
  SharePasswordIncorrectError,
  SharePasswordRequiredError,
  SHARE_UNREACHABLE,
  ShareUnavailableError,
  type PublicShareHead,
} from './resolvePublicShare'

export type ShareGateStatus = 'loading' | 'ready' | 'unavailable' | 'locked'

/** What the gates are run with. */
export interface ShareGateInput {
  /** The share, where it lives (`./shareAt.ts`): the share page's bare id, a bookmark's id with its instance's base. */
  at: ShareAt
  /** The URL fragment's key (`#k=`, key material), read on each resolve. */
  fragmentKey: () => string
  /** A password handed to this page, entered once when the link asks for one (a bookmark's, ADR 0120 decision 5, ADR
   *  0121 decision 6): taken, it is gone; a wrong one leaves the password form. */
  handedPassword: () => string | null
  /** The viewer's language, for the terms and the note. */
  locale: Ref<string>
}

/** What renders a share behind the gates, or null (the explicit unavailable state): a `WRITE_ONLY` link's drop page
 *  claimed for its root kind, else core's page — for a root kind the app can render as a member would (a folder, or a
 *  kind with a `detail`). */
export function shareBodyFor(h: PublicShareHead) {
  const kind = h.rootKind
  if (!kind) return null
  if (h.role === ShareRole.WriteOnly) return registry.shareBodyFor(kind)
  const info = registry.kind(kind)
  return kind === FOLDER_KIND || (info.supported && info.detail) ? ShareTreePage : null
}

/** Run the gates of the share `input.at` (see the header). Call in a component's setup; nothing is read until `load`. */
export function useShareGates(input: ShareGateInput) {
  const { at, locale } = input
  const apiBase = apiBaseOf(at)

  const status = ref<ShareGateStatus>('loading')
  /** The last unavailable answer was no answer at all (the network, or another instance that does not answer this
   *  origin): a bookmark's page falls back to opening the link, the share page shows its unavailable state either way. */
  const unreachable = ref(false)
  // cr-0118: a password-protected share resolves to a LOCKED shell — the viewer enters the password, the Argon2id token is
  // derived client-side, and the share re-resolved with it. The token is then held + presented on every gated read.
  const pwSalt = ref('')
  const pwInput = ref('')
  const pwToken = ref<string | undefined>(undefined)
  const pwError = ref(false) // last attempt was the wrong password
  const pwBusy = ref(false) // deriving Argon2id + re-resolving
  // The platform half of the resolve. A shallow ref: it holds `K_share`, which must never be wrapped in a reactive proxy.
  const head = shallowRef<PublicShareHead | null>(null)

  // --- the accountless terms gate -------------------------------------------------------------------------------------
  const termsTenantId = ref('')
  const termsRevision = ref(0) // 0 = no gate (also the fail-open value on a fetch error)
  const operatorSection = ref<OperatorSection | null>(null)
  const acceptedKeys = ref<string[]>([]) // the locally-recorded accepted pairs; extended in-memory on accept so the gate clears
  const termsMarkdown = ref('')
  const renderedTerms = computed(() => (termsMarkdown.value ? markdownHtml(termsMarkdown.value) : null))
  const currentKey = computed(() => (operatorSection.value ? pairKey(pairOf(termsRevision.value, operatorSection.value)) : ''))
  const needsTerms = computed(
    () => status.value === 'ready' && termsRevision.value > 0 && !acceptedKeys.value.includes(currentKey.value),
  )

  /** Fetch the owning tenant's terms + read the locally-stored accepted pairs. FAILS OPEN (see the header). */
  async function loadTerms() {
    try {
      const terms = await fetchShareTerms(at)
      termsTenantId.value = terms.tenantId
      termsRevision.value = terms.termsRevision
      operatorSection.value = terms.operatorSection
      termsMarkdown.value = pickShareTerms(terms, locale.value)
      acceptedKeys.value = acceptedTermsKeys(terms.tenantId, apiBase)
    } catch {
      termsRevision.value = 0
      operatorSection.value = null
      acceptedKeys.value = []
    }
  }

  /** Record acceptance client-side (per tenant of the share's instance) + clear the gate in-memory. No server call. */
  function acceptTerms() {
    const section = operatorSection.value
    if (!section) return
    const pair = pairOf(termsRevision.value, section)
    recordTermsAccepted(termsTenantId.value, pair, apiBase)
    acceptedKeys.value = [...acceptedKeys.value, pairKey(pair)]
  }

  // --- the note's acceptance gate -------------------------------------------------------------------------------------
  /** The digests this visit accepted (a re-resolve after a guest's write must not ask again where storage refused). */
  const acceptedNotes = new Set<string>()
  /** The digest of the note this visitor has still to accept, or null (no note asks, or it was accepted). */
  const noteGate = ref<string | null>(null)
  const needsNote = computed(() => status.value === 'ready' && noteGate.value !== null)
  const renderedNote = computed(() => {
    const text = needsNote.value && head.value ? pickLocaleText(head.value.noteByLocale, locale.value) : ''
    return text ? markdownHtml(text) : null
  })
  /** The gate a resolved head asks for: its note's digest unless this device (or this visit) accepted that very text. */
  function noteGateOf(h: PublicShareHead): string | null {
    const digest = h.noteAcceptance?.digest
    if (!digest || acceptedNotes.has(digest) || isNoteAccepted(at, digest)) return null
    return digest
  }
  /** Accept the note: remembered on this device (if it can) and for this visit; no server call. */
  function acceptNote() {
    const digest = noteGate.value
    if (!digest) return
    recordNoteAccepted(at, digest)
    acceptedNotes.add(digest)
    noteGate.value = null
  }
  /** The head the page below the gates is handed: a note that asked to be accepted is not repeated as its banner — the
   *  visitor got past its gate, so they have read it. */
  const bodyHead = computed(() => (head.value?.noteAcceptance ? { ...head.value, noteByLocale: {} } : head.value))
  const body = computed(() => (head.value ? shareBodyFor(head.value) : null))

  // --- the guest: its access, and the page's one naming flow ----------------------------------------------------------
  const session = useGuestSession(at)
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
    await nameShareGuest(h, { name, fragmentKey: input.fragmentKey(), pwToken: pwToken.value })
  }
  function onNamingClose() {
    naming.value = false
    const all = waiting
    waiting = []
    for (const w of all) w(session.value)
  }
  /** "Not you?": forget this device's session of the link. */
  function forgetGuest() {
    forgetGuestSession(at)
  }
  const access = computed(() =>
    head.value
      ? guestAccess({ head: head.value, pwToken: pwToken.value, fragmentKey: input.fragmentKey(), requestGuest, reload: load, poll })
      : null,
  )
  /** The line that says whose session this is: a READ_WRITE link's (a drop page shows its own). */
  const showsIdentity = computed(() => head.value?.role === ShareRole.ReadWrite && !!session.value)

  /** The level view's live refresh (inbox #408, ADR 0081): re-resolve quietly and CONDITIONALLY — the last response's
   *  tag goes as `If-None-Match`, and a `304` is no work at all — and swap `head` only when the subtree differs (the
   *  fingerprint stays the fallback for a server that sends no tag). Resolves whether the link's state moved.
   *
   *  A link that is GONE is noticed here: the server's non-enumerable `404` flips to the unavailable state, as a reload
   *  would, and a changed password to the password gate. A failure that says nothing about the link (the network, a 5xx,
   *  a 429) is thrown to the poller (it backs off) and never reaches the state. A resolve that began before a `load` (the
   *  guest's own write) ran is dropped, as it may predate the write — and the write's own re-resolve starts the tag over. */
  let resolves = 0
  let shareTag: string | null = null
  async function poll(signal: AbortSignal): Promise<boolean> {
    const current = head.value
    if (!current || status.value !== 'ready') return false
    const mine = resolves
    let polled
    try {
      polled = await pollPublicShare(at, input.fragmentKey(), pwToken.value, shareTag)
    } catch (e) {
      if (signal.aborted || mine !== resolves || head.value !== current) return false
      if (!failWith(e)) throw e
      return false
    }
    if (signal.aborted || mine !== resolves || head.value !== current) return false
    if (!polled) return false // 304: the link serves what is shown
    shareTag = polled.etag
    if (!shareBodyFor(polled.head)) {
      status.value = 'unavailable' // the root is gone from under the link (trashed): nothing renders it any more
      return false
    }
    if (shareTreeFingerprint(polled.head.share) !== shareTreeFingerprint(current.share)) head.value = polled.head
    return true
  }

  /** The state a failed resolve means, or false for a failure that is none of the link's (the caller rethrows). */
  function failWith(e: unknown): boolean {
    if (e instanceof SharePasswordRequiredError) {
      pwSalt.value = e.pwSalt
      status.value = 'locked'
      // A handed password (a bookmark's): entered once, as the reader would.
      const handed = input.handedPassword()
      if (handed) {
        pwInput.value = handed
        void submitPassword()
      }
    } else if (e instanceof SharePasswordIncorrectError) {
      pwError.value = true
      status.value = 'locked'
    } else if (e instanceof ShareUnavailableError) {
      unreachable.value = e.message === SHARE_UNREACHABLE
      status.value = 'unavailable'
    } else {
      return false
    }
    return true
  }

  // --- resolve (on mount, after the password, and after a contribution) ---------------------------------------------
  async function load() {
    resolves++
    shareTag = null // what this resolve serves carries no tag the poll holds: its first read is a full one
    try {
      const resolved = await resolvePublicShare(at, input.fragmentKey(), pwToken.value)
      if (!shareBodyFor(resolved)) {
        // No application renders this kind of share: say so, rather than mounting nothing.
        status.value = 'unavailable'
        return
      }
      // Decide the terms gate BEFORE flipping to 'ready' so an ungated content flash can't slip in between resolve and
      // the gate (loadTerms fails open, so it never throws here). The gate then sits ahead of the content.
      await loadTerms()
      noteGate.value = noteGateOf(resolved) // the note's gate too, decided before 'ready' (it follows the terms gate)
      head.value = resolved
      status.value = 'ready'
    } catch (e) {
      // cr-0118: a password-protected share -> prompt (or re-prompt on a wrong password); otherwise the honest
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

  return {
    at,
    status,
    unreachable,
    pwInput,
    pwToken,
    pwError,
    pwBusy,
    head,
    bodyHead,
    body,
    operatorSection,
    renderedTerms,
    needsTerms,
    renderedNote,
    needsNote,
    session,
    naming,
    showsIdentity,
    access,
    fragmentKey: input.fragmentKey,
    load,
    poll,
    submitPassword,
    acceptTerms,
    acceptNote,
    requestGuest,
    nameGuest,
    onNamingClose,
    forgetGuest,
  }
}

/** The gates of one share, as {@link useShareGates} runs them. */
export type ShareGates = ReturnType<typeof useShareGates>
