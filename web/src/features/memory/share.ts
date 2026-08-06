// uc.sharing.share-public-viewer (web) — the public viewer share data layer (cr-0100, ADR 0029). Operator-blind:
// a curator turns ONE personal-scope Memory into a read-only, anonymous, revocable link `/s/{shareId}#k=<K_share>`.
// The share adds an INDEPENDENT second wrap of that Memory's `K_mem` — `symWrap(K_share, K_mem)` (ADR 0012) —
// whose secret `K_share` rides the URL **fragment** (ADR 0015) and never reaches the server. The Memory name is
// re-sealed under `K_share` so a viewer with only the fragment key can show the title without `K_SC`.
//
// Two sides live here:
//  - **curator** (authed): mint / list / revoke a share.
//  - **viewer** (anonymous, no session): resolve a share, unwrap `K_mem` from the fragment key, derive each
//    chapter's `K_mem` down the subtree, and open blobs via the gated share-scoped presign endpoint.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std, fromB64url } from '../../shared/base64'
import { sharesApi, publicSharesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { isHttpStatus, HttpStatus } from '../../shared/httpError'
import { openName, sealName } from './tree'
import {
  gridRef,
  lightboxRef,
  downloadRef,
  openBlobFromUrls,
  makeChunkProvider,
  uploadMediaVia,
  createBrowserDeriver,
  type BlobRef,
  type ChunkProvider,
  type MediaItem,
  type MediaUploadTransport,
  type AddMediaSource,
  type AddMediaOptions,
  type MediaDeriver,
} from './media'
import { ShareRole, type PublicShare, type ShareMedia, type ShareNode, type ShareTerms } from '../../api/generated/sharing'
import type { MediaDerivativeKind } from '../../api/generated/media'

// The AAD context binding `symWrap(K_share, K_mem)` — a dedicated domain tag for the share second-wrap, distinct
// from the tree's `content-key` (parent → K_mem) wrap. Sealed by the curator, opened by the viewer; both here.
const SHARE_CTX = 'share-key'
// The AAD context for a contributor's display name sealed under K_mem (cr-0101) — the operator never sees the name.
const SHARE_IDENTITY_NAME_CTX = 'share-identity-name'
// cr-0115 (ADR 0036): the AAD contexts for the editable curator note. `share-note` binds the note text under
// `K_share`; `share-key-recovery` binds the recoverable copy of `K_share` under `K_mem`. Distinct tags, never reused.
const SHARE_NOTE_CTX = 'share-note'
const SHARE_KEY_RECOVERY_CTX = 'share-key-recovery'
// cr-0116: the AAD context for a share's optional private name sealed under `K_share`.
const SHARE_NAME_CTX = 'share-name'
const SHARE_PASSWORD_CTX = 'share-password' // cr-0118: the reveal copy of the plaintext password, under K_share
const noteDecoder = new TextDecoder()

/** A per-locale text map, e.g. `{ de: '…', en: '…' }` (cr-0116). Empty when there is no text. */
export type LocaleMap = Record<string, string>

/** Whether a per-locale map has any non-blank text. */
export function hasLocaleText(map: LocaleMap): boolean {
  return Object.values(map).some((t) => t.trim().length > 0)
}

/** Seal a per-locale curator-note map under `K_share` — the plaintext is a JSON `{locale: markdown}` map (cr-0116). */
function sealNoteMap(kShare: Uint8Array, note: LocaleMap): string {
  return b64std(v1.symWrap(kShare, v1.utf8(JSON.stringify(note)), SHARE_NOTE_CTX, v1.randomBytes(12)))
}
/** Open a per-locale note map sealed under `K_share`; `{}` when it can't be opened or isn't a JSON object. */
function openNoteMap(kShare: Uint8Array, sealed: string): LocaleMap {
  try {
    const parsed = JSON.parse(noteDecoder.decode(v1.symUnwrap(kShare, fromB64std(sealed), SHARE_NOTE_CTX)))
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as LocaleMap) : {}
  } catch {
    return {}
  }
}
/** Seal a share's optional private name under `K_share` (opaque base64). */
function sealShareName(kShare: Uint8Array, name: string): string {
  return b64std(v1.symWrap(kShare, v1.utf8(name), SHARE_NAME_CTX, v1.randomBytes(12)))
}
/** Open a share name sealed under `K_share`. */
function openShareName(kShare: Uint8Array, sealed: string): string {
  return noteDecoder.decode(v1.symUnwrap(kShare, fromB64std(sealed), SHARE_NAME_CTX))
}
/** Recover `K_share` from its `K_mem`-sealed copy `symWrap(K_mem, K_share)` — the curator always holds `K_mem`. */
export function recoverShareKey(memoryKey: Uint8Array, sealedShareKey: string): Uint8Array {
  return v1.symUnwrap(memoryKey, fromB64std(sealedShareKey), SHARE_KEY_RECOVERY_CTX)
}

// --- password protection (cr-0118, ADR 0037): an operator-blind ACCESS-CONTROL gate ------------------------
//
// The password gates what the SERVER serves; the content stays E2EE under K_share. The client derives a token
// `pwToken = Argon2id(password, pwSalt)` (the guest bearer credential) and stores only its `SHA-256` verifier +
// salt + a K_share-sealed reveal copy — never the raw password.

/** The password fields for a create/change: a fresh salt, the SHA-256 verifier, and the reveal copy under K_share. */
function buildPasswordFields(kShare: Uint8Array, password: string): {
  passwordRequired: true; pwSalt: string; pwVerifier: string; sealedPassword: string
} {
  const salt = v1.randomBytes(16)
  const pwToken = v1.sharesPwToken(password, salt)
  return {
    passwordRequired: true,
    pwSalt: b64std(salt),
    pwVerifier: b64std(v1.sha256Digest(pwToken)),
    sealedPassword: b64std(v1.symWrap(kShare, v1.utf8(password), SHARE_PASSWORD_CTX, v1.randomBytes(12))),
  }
}

/** The base64 guest token `Argon2id(password, pwSalt)` a viewer presents in `X-Share-Password` (the salt comes from
 *  the locked resolve). Heavy (Argon2id) but a one-off per unlock. */
export function derivePasswordToken(password: string, pwSalt: string): string {
  return b64std(v1.sharesPwToken(password, fromB64std(pwSalt)))
}

/** Reveal a managed share's password (curator only): recover `K_share` via `K_mem`, open the sealed copy. '' when
 *  none / unreadable (or a share minted before cr-0118). */
export function readSharePassword(memoryKey: Uint8Array, sealedShareKey: string | null, sealedPassword: string | null): string {
  if (!sealedShareKey || !sealedPassword) return ''
  try {
    return noteDecoder.decode(v1.symUnwrap(recoverShareKey(memoryKey, sealedShareKey), fromB64std(sealedPassword), SHARE_PASSWORD_CTX))
  } catch {
    return ''
  }
}

/** A managed share's per-locale note, decrypted (recover `K_share` via `K_mem`, then open the map). `{}` when there
 *  is none or the ciphertext can't be opened (a share minted before cr-0115 carries no recoverable key). */
export function readShareNoteMap(memoryKey: Uint8Array, sealedShareKey: string | null, sealedNote: string | null): LocaleMap {
  if (!sealedShareKey || !sealedNote) return {}
  try {
    return openNoteMap(recoverShareKey(memoryKey, sealedShareKey), sealedNote)
  } catch {
    return {}
  }
}
/** A managed share's private name, decrypted. '' when none / unreadable. */
export function readShareName(memoryKey: Uint8Array, sealedShareKey: string | null, sealedShareName: string | null): string {
  if (!sealedShareKey || !sealedShareName) return ''
  try {
    return openShareName(recoverShareKey(memoryKey, sealedShareKey), sealedShareName)
  } catch {
    return ''
  }
}

/** Pick the best per-locale text for a viewer's locale (exact → base language → en → first present). '' if empty. */
export function pickLocaleText(map: LocaleMap, locale: string): string {
  const entries = Object.entries(map).filter(([, t]) => t && t.trim())
  if (!entries.length) return ''
  const req = locale.toLowerCase()
  const base = req.split('-')[0]
  const hit =
    entries.find(([l]) => l.toLowerCase() === req) ??
    entries.find(([l]) => l.toLowerCase() === base) ??
    entries.find(([l]) => l.toLowerCase() === 'en') ??
    entries[0]
  return hit[1]
}

// --- curator (authenticated) -----------------------------------------------

/** A newly minted share: the opaque id + `K_share` itself. The copyable link is assembled by the caller —
 *  `shareLink.ts` owns that, and its header says why this layer hands back the key rather than a URL. */
export type MintedShare = { shareId: string; kShare: Uint8Array }

/**
 * Mint a public viewer share of one Memory (curator side). Generates a fresh 32-byte `K_share`, wraps the
 * Memory's `K_mem` under it, re-seals the Memory name under `K_share`, and POSTs the opaque record. Returns the
 * `shareId` + `K_share`; the caller turns that pair into a link with [buildShareLink]. The server never sees
 * `K_share` — it leaves this function only towards the UI, and reaches a visitor only in the link's fragment.
 */
export async function createShare(
  nodeId: string,
  memoryKey: Uint8Array,
  memoryName: string,
  role: ShareRole = ShareRole.Viewer,
  expiresAt: Date | null = null,
  note: LocaleMap = {},
  name = '',
  password = '',
): Promise<MintedShare> {
  const kShare = v1.randomBytes(32)
  const wrap = b64std(v1.symWrap(kShare, memoryKey, SHARE_CTX, v1.randomBytes(12)))
  const sealedName = sealName(kShare, memoryName) // the Memory name re-sealed under K_share (same node-name construction)
  // cr-0115/cr-0116 (ADR 0036): store a recoverable copy of K_share under K_mem (so the curator can edit note + name
  // later) and seal the optional per-locale note + private name under K_share. sealedShareKey is on EVERY share, so
  // a note/name can be added later even to one minted without them.
  const sealedShareKey = b64std(v1.symWrap(memoryKey, kShare, SHARE_KEY_RECOVERY_CTX, v1.randomBytes(12)))
  const sealedNote = hasLocaleText(note) ? sealNoteMap(kShare, note) : undefined
  const sealedShareName = name.trim() ? sealShareName(kShare, name) : undefined
  // cr-0118 (ADR 0037): an optional operator-blind password gate (fields derived client-side; the raw password never leaves).
  const pw = password.trim() ? buildPasswordFields(kShare, password) : undefined
  const { shareId } = await withRefresh(() =>
    sharesApi().createShare({
      createShareRequest: {
        nodeId, role, wrap, sealedName, expiresAt: expiresAt ?? undefined, sealedNote, sealedShareKey, sealedShareName,
        passwordRequired: pw?.passwordRequired, pwSalt: pw?.pwSalt, pwVerifier: pw?.pwVerifier, sealedPassword: pw?.sealedPassword,
      },
    }),
  )
  return { shareId, kShare }
}

/**
 * Edit a share in place (cr-0115/cr-0116/cr-0119/cr-0118, ADR 0036/0037): recover `K_share` from its `K_mem`-sealed
 * copy, re-seal note + name (or clear a blank one) and PATCH the full pair — the same link keeps working. An
 * optional `role` changes the grant in place (VIEWER ↔ CONTRIBUTOR; the server 422s UPLOADER). `passwordChange` is
 * the tri-state gate: `{ set }` sets/changes it, `{ remove: true }` removes it, `undefined` leaves it untouched.
 * Owner-gated server-side; the link/`K_share` are untouched throughout.
 */
export async function updateShare(
  shareId: string, memoryKey: Uint8Array, sealedShareKey: string, note: LocaleMap, name: string,
  expiresAt: Date | null, role?: ShareRole,
  passwordChange?: { set: string } | { remove: true },
): Promise<void> {
  const kShare = recoverShareKey(memoryKey, sealedShareKey)
  const sealedNote = hasLocaleText(note) ? sealNoteMap(kShare, note) : null
  const sealedShareName = name.trim() ? sealShareName(kShare, name) : null
  // cr-0118: the password tri-state. A `remove` sends only `passwordRequired: false` — the server clears the fields.
  const passwordPatch =
    passwordChange && 'set' in passwordChange
      ? buildPasswordFields(kShare, passwordChange.set)
      : passwordChange && 'remove' in passwordChange
        ? { passwordRequired: false }
        : {}
  await withRefresh(() =>
    sharesApi().updateShare({
      // expiresAt is a full replace (like sealedNote/sealedShareName): send the current value to preserve it,
      // null to clear. The edit dialog always passes the pre-filled expiry, so an unrelated edit keeps it.
      shareId, updateShareRequest: { sealedNote, sealedShareName, expiresAt: expiresAt ?? null, role, ...passwordPatch },
    }),
  )
}

/** A curator's management row for one of their shares (opaque; the sealed name isn't shown — the Memory is known). */
export type ManagedShare = {
  shareId: string
  nodeId: string
  role: ShareRole
  enabled: boolean
  /** True when the caller owns this share (so may revoke it). A disabled share by any member is adoptable (cr-0104). */
  mine: boolean
  createdAt: Date
  expiresAt: Date | null
  /** cr-0115/cr-0116: the note (per-locale) + name ciphertext (under `K_share`) + the recoverable `K_share` (under
   *  `K_mem`). The dialog reads them via `readShareNoteMap`/`readShareName` and edits them via `updateShare`. Null
   *  on a pre-cr-0115 share. */
  sealedNote: string | null
  sealedShareName: string | null
  sealedShareKey: string | null
  /** cr-0118: whether the share is password-protected (a lock badge) + the `K_share`-sealed reveal copy of the
   *  plaintext password (management-only), read via `readSharePassword`. */
  passwordRequired: boolean
  sealedPassword: string | null
}

/** List every share on one Memory visible to a scope member (cr-0104) — the manage-shares panel. Scoped by
 *  `nodeId` server-side, so it includes co-members' shares (each tagged `mine`), not only the caller's own. */
export async function listSharesFor(nodeId: string): Promise<ManagedShare[]> {
  const rows = await withRefresh(() => sharesApi().listShares({ nodeId }))
  return rows
    .map((r) => ({
      shareId: r.shareId,
      nodeId: r.nodeId,
      role: r.role,
      enabled: r.enabled,
      mine: r.mine,
      createdAt: new Date(r.createdAt),
      expiresAt: r.expiresAt ? new Date(r.expiresAt) : null,
      sealedNote: r.sealedNote ?? null,
      sealedShareName: r.sealedShareName ?? null,
      sealedShareKey: r.sealedShareKey ?? null,
      passwordRequired: r.passwordRequired ?? false,
      sealedPassword: r.sealedPassword ?? null,
    }))
    .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
}

/** Revoke a share (soft — the next guest request 404s). */
export async function revokeShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().revokeShare({ shareId }))
}

/** Re-activate a soft-revoked share and adopt its ownership (cr-0104) — any current member may take over an
 *  orphaned (disabled) share on a shared Memory; the guest link then resolves again, now owned by the caller. */
export async function reactivateShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().reactivateShare({ shareId }))
}

// --- contributor (anonymous, no session; cr-0101) --------------------------

/** A named contributor session on a CONTRIBUTOR share: the anonymous identity id + its capability token. */
export type ContributorSession = { identityId: string; contributorToken: string }

/**
 * Name yourself on a CONTRIBUTOR share (operator-blind): seal the display name under `K_mem` and create an
 * anonymous share identity. Returns the identity id + a capability token that authorizes this session's uploads.
 * The server never sees the name (only the ciphertext) or the person.
 */
export async function createContributorIdentity(
  shareId: string,
  memoryKey: Uint8Array,
  displayName: string,
  pwToken?: string,
): Promise<ContributorSession> {
  const sealedDisplayName = b64std(v1.symWrap(memoryKey, v1.utf8(displayName), SHARE_IDENTITY_NAME_CTX, v1.randomBytes(12)))
  const { identityId, contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    createShareIdentityRequest: { sealedDisplayName },
    xSharePassword: pwToken, // cr-0164: the password gate fronts the write path too
  })
  return { identityId, contributorToken }
}

/** The share-scoped upload transport (no auth) — reserve via `startShareBlobUploads`, complete via `completeShareMedia`. */
function shareUploadTransport(shareId: string, contributorToken: string, pwToken?: string): MediaUploadTransport {
  return {
    async reserve(declares) {
      const started = await publicSharesApi().startShareBlobUploads({
        shareId,
        shareBlobUploadsRequest: { contributorToken, items: declares },
        xSharePassword: pwToken, // cr-0164
      })
      if (started.results.length !== declares.length) throw new Error('upload_failed')
      return started.results.map((r) => {
        if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
        if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
        return { uploadId: r.uploadId, putUrls: [...r.presignedPuts].sort((a, b) => a.index - b.index).map((u) => u.url) }
      })
    },
    async complete(media) {
      const done = await publicSharesApi().completeShareMedia({
        shareId,
        shareMediaBatchRequest: {
          contributorToken,
          items: [{
            originalUploadId: media.originalUploadId,
            derivatives: media.derivatives.map((d) => ({ kind: d.kind, uploadId: d.uploadId })),
            wrappedMeta: media.wrappedMeta,
            wrappedExif: media.wrappedExif,
          }],
        },
        xSharePassword: pwToken, // cr-0164
      })
      const m = done.results[0]
      if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
      return { blobId: m.blobId, mediaId: m.mediaId }
    },
  }
}

/**
 * Add one photo to a CONTRIBUTOR share (anonymous): seal it under `K_mem` (which the contributor holds) with its
 * derivatives + metadata and upload it via the share-scoped endpoints — the exact same operator-blind pipeline as
 * the curator's `addMedia`, only the transport is share-gated. Returns the new blob + media ids.
 */
export async function addSharePhoto(
  session: ContributorSession & { shareId: string; memoryKey: Uint8Array; pwToken?: string },
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(session.memoryKey, source, deriver, shareUploadTransport(session.shareId, session.contributorToken, session.pwToken), opts)
}

/**
 * Delete one of your own contributions on a CONTRIBUTOR/UPLOADER share (anonymous, token-authorized). The server
 * authorizes it by a single-row ownership check (the media's `share_identity_id` equals the token's identity) and
 * reclaims its blobs — a foreign/unknown media is a 404. Used by the uploader drop-box's session-only delete-own.
 */
export async function deleteSharePhoto(shareId: string, contributorToken: string, mediaId: string, pwToken?: string): Promise<void> {
  await publicSharesApi().deleteShareMedia({ shareId, mediaId, xContributorToken: contributorToken, xSharePassword: pwToken }) // cr-0164
}

// --- viewer (anonymous, no session) ----------------------------------------

/** One shared media resolved for the read-only viewer: the media + the exact `K_mem` its blobs are sealed under. */
export type ShareItem = { item: MediaItem; memoryKey: Uint8Array }

/**
 * The decrypted shared Memory, ready to render: its name, the grant `role` (VIEWER / CONTRIBUTOR), the **root**
 * `K_mem` (for a contributor to seal their name + uploads under), and every media (each with its own `K_mem`).
 */
export type ResolvedShare = { memoryName: string; noteByLocale: LocaleMap; role: ShareRole; rootMemoryKey: Uint8Array; items: ShareItem[] }

/** The fragment key is missing/malformed, or the share is unknown/revoked/expired (a non-enumerable 404). */
export class ShareUnavailableError extends Error {}

/** cr-0118: the share is password-protected and no (valid) token was given — the caller prompts with `pwSalt`. */
export class SharePasswordRequiredError extends Error {
  constructor(public pwSalt: string) {
    super('password_required')
  }
}
/** cr-0118: the presented password token was wrong (403) — the caller re-prompts. */
export class SharePasswordIncorrectError extends Error {}

// --- accountless terms gate (uc.sharing.share-accept, ADR 0035 §3) ----------
// The accountless half of the two-tier terms gate. The terms are the operator's PUBLIC policy (never E2EE); a
// visitor accepts them before viewing/contributing, and acceptance is stored **client-side only** (localStorage
// keyed per tenant) so the server records NOTHING about the accountless visitor. This is an **operational gate,
// not enforcement**: a localStorage flag is bypassable by an accountless visitor (devtools / a fresh profile /
// resolving the share directly), so it records + surfaces consent — it does not *prevent* access. The
// legally-meaningful protection is the terms' content + the decryptable-on-report path, not this prompt.
const TERMS_ACCEPTED_KEY_PREFIX = 'veyq:terms-accepted:'

/**
 * The owning tenant's public terms for a share (a guest read keyed on the non-enumerable `shareId`, so unknown/
 * disabled/expired is the same non-enumerable 404 as `resolveShare` — no `tenantId` existence oracle). Returns
 * `{ tenantId, termsVersion, terms }`; `termsVersion` 0 means the operator published no terms (the client never
 * gates). No auth, no password — terms are public.
 */
export async function fetchShareTerms(shareId: string): Promise<ShareTerms> {
  return publicSharesApi().getShareTerms({ shareId })
}

/** Pick the viewer's-locale terms markdown out of a [ShareTerms] (exact → base → en → first), '' when none. */
export function pickShareTerms(terms: ShareTerms, locale: string): string {
  const map: LocaleMap = {}
  if (terms.terms?.de) map.de = terms.terms.de
  if (terms.terms?.en) map.en = terms.terms.en
  return pickLocaleText(map, locale)
}

/** The locally-recorded accepted terms version for a tenant (0 if never accepted or the store is unreadable). */
export function acceptedTermsVersion(tenantId: string): number {
  try {
    const raw = localStorage.getItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`)
    const n = raw ? Number.parseInt(raw, 10) : 0
    return Number.isFinite(n) ? n : 0
  } catch {
    return 0
  }
}

/** Record acceptance of [version] for [tenantId] — client-only (localStorage), keyed per tenant so accepting on
 *  one share of a tenant clears the gate on **every** share of that tenant. A storage failure is swallowed: the
 *  gate is operational, not enforcement, so it must never throw and block a visitor from viewing. */
export function recordTermsAccepted(tenantId: string, version: number): void {
  try {
    localStorage.setItem(`${TERMS_ACCEPTED_KEY_PREFIX}${tenantId}`, String(version))
  } catch {
    /* private-mode / quota — an unrecordable acceptance still clears the in-memory gate for this session */
  }
}

function shareMediaToItem(m: ShareMedia): MediaItem {
  return {
    mediaId: m.mediaId,
    original: { blobId: m.blobId, chunkCount: m.chunkCount, byteSize: m.byteSize, wrappedBlobKey: m.wrappedBlobKey },
    derivatives: (m.derivatives ?? []).map((d) => ({
      kind: d.kind as MediaDerivativeKind,
      ref: { blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey },
    })),
    wrappedMeta: m.wrappedMeta ?? undefined,
  }
}

/**
 * Build the per-node `K_mem` map for a shared subtree: the root Memory's `K_mem` comes from the share `wrap`
 * (unwrapped with the fragment key), each chapter's from its `wrappedContentKey` under its parent's `K_mem`
 * (recursive `K_mem`, ADR 0012). Nodes whose chain can't be opened are dropped.
 */
function deriveSubtreeKeys(nodes: ShareNode[], rootKmem: Uint8Array): Map<string, Uint8Array> {
  const keys = new Map<string, Uint8Array>()
  const byParent = new Map<string, ShareNode[]>()
  let root: ShareNode | undefined
  for (const n of nodes) {
    if (n.parentId == null) root = n
    else byParent.set(n.parentId, [...(byParent.get(n.parentId) ?? []), n])
  }
  if (!root) return keys
  keys.set(root.id, rootKmem)
  // BFS from the root, deriving each child's K_mem under its parent's (already-known) K_mem.
  const queue: string[] = [root.id]
  while (queue.length) {
    const parentId = queue.shift()!
    const parentKey = keys.get(parentId)
    if (!parentKey) continue
    for (const child of byParent.get(parentId) ?? []) {
      if (!child.wrappedContentKey) continue
      try {
        keys.set(child.id, v1.symUnwrap(parentKey, fromB64std(child.wrappedContentKey), 'content-key'))
        queue.push(child.id)
      } catch {
        /* a chapter whose K_mem won't open is skipped (its media then simply won't render) */
      }
    }
  }
  return keys
}

/**
 * Resolve + decrypt a public share for the anonymous viewer. Fetches the sealed subtree (no auth), unwraps the
 * Memory's `K_mem` with the fragment key, decrypts the Memory name, derives every chapter's `K_mem`, and returns
 * a flat, read-only list of media (each tagged with the exact `K_mem` its blobs open under). Throws
 * [ShareUnavailableError] on a missing/bad fragment key or a 404 (unknown/revoked/expired — indistinguishable).
 */
export async function resolveShare(shareId: string, fragmentKey: string, pwToken?: string): Promise<ResolvedShare> {
  let kShare: Uint8Array
  try {
    kShare = fromB64url(fragmentKey)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
  if (kShare.length !== 32) throw new ShareUnavailableError('bad_fragment_key')

  let share: PublicShare
  try {
    share = await publicSharesApi().resolveShare({ shareId, xSharePassword: pwToken }) // cr-0118: the guest token gates it
  } catch (e) {
    if (isHttpStatus(e, HttpStatus.forbidden)) throw new SharePasswordIncorrectError() // wrong password
    throw new ShareUnavailableError('not_found') // 404 (revoked/expired/unknown) or a network error
  }
  // cr-0118: a locked share (password required, no/valid-less token) serves only the challenge — prompt the viewer.
  if (share.passwordRequired && !share.wrap) throw new SharePasswordRequiredError(share.pwSalt ?? '')

  let rootKmem: Uint8Array
  let memoryName: string
  try {
    rootKmem = v1.symUnwrap(kShare, fromB64std(share.wrap!), SHARE_CTX)
    memoryName = openName(kShare, share.sealedName!)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key') // the id was valid but the fragment key doesn't match
  }
  // cr-0116: the optional per-locale curator note, opened with the same fragment key (a bad/absent one leaves `{}`).
  // The share page picks the viewer's locale via `pickLocaleText`.
  const noteByLocale = share.sealedNote ? openNoteMap(kShare, share.sealedNote) : {}

  const keys = deriveSubtreeKeys(share.nodes, rootKmem)
  const items: ShareItem[] = []
  for (const m of share.media) {
    const memoryKey = keys.get(m.nodeId)
    if (!memoryKey) continue // a media under a chapter whose K_mem didn't open — skip
    items.push({ item: shareMediaToItem(m), memoryKey })
  }
  return { memoryName, noteByLocale, role: share.role, rootMemoryKey: rootKmem, items }
}

/** Open a shared blob via the gated, share-scoped presign endpoint (no auth), then decrypt it under its `K_mem`.
 *  A password-protected share needs the same guest token (cr-0118) as the resolve. */
async function openShareBlob(shareId: string, memoryKey: Uint8Array, ref: BlobRef, pwToken?: string): Promise<Uint8Array> {
  const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
  const getUrls = [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  return openBlobFromUrls(memoryKey, ref, getUrls)
}

/**
 * A {@link ChunkProvider} for the anonymous share viewer — the Slice-2 SW range seam over the gated share-scoped
 * presign (`resolveShareBlob`: `shareId` + guest `pwToken`, whole-blob only). Same `openFrame` core as the authed
 * `chunkProvider`; only the presign differs, so the SW never learns which route served the bytes (ADR 0047).
 */
export function shareChunkProvider(shareId: string, memoryKey: Uint8Array, ref: BlobRef, pwToken?: string): ChunkProvider {
  return makeChunkProvider(memoryKey, ref, async () => {
    const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
    return [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  })
}

/** Open a shared media's grid (thumbnail) tier. */
export const openShareGrid = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si.memoryKey, gridRef(si.item), pwToken)
/** Open a shared media's lightbox (preview) tier. */
export const openShareLightbox = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si.memoryKey, lightboxRef(si.item), pwToken)
/** Open a shared media's pristine original (download). */
export const openShareOriginal = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si.memoryKey, downloadRef(si.item), pwToken)
