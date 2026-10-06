// uc.sharing.share-public-viewer (web) — the public viewer share data layer (cr-0100, ADR 0029). Operator-blind:
// a member turns ONE Memory of their scope into an anonymous, revocable link `/s/{shareId}#k=<K_share>`.
// The share adds an INDEPENDENT second wrap of that Memory's `K_mem` — sealed under `K_share` (ADR 0012) —
// whose secret `K_share` rides the URL **fragment** (ADR 0015) and never reaches the server. The Memory name is
// re-sealed under `K_share` so a viewer with only the fragment key can show the title without `K_SC`.
//
// Two sides live here:
//  - **curator** (authed): mint / list / revoke a share.
//  - **viewer** (anonymous, no session): resolve a share, unwrap `K_mem` from the fragment key, derive each
//    chapter's `K_mem` down the subtree and open each chapter's title under its parent's key (ADR 0080 Decision 2 —
//    a guest of a Memory reads its chapter titles), and open blobs via the gated share-scoped presign endpoint.
//
// A WRITE_ONLY share is the exception to the second wrap (ADR 0031 point 7): its guest is the one party the link
// exists to keep out, so it gets no `K_mem` at all — only the Memory's drop PUBLIC key under `K_share`. The guest
// seals under an identity key of its own, wrapped to that drop key; `./mediaKey.ts` owns that chain.
import * as v1 from '@veyq/crypto/v1'
import { sharesPwTokenAsync } from '@veyq/crypto/argonWorker'
import { b64std, b64url, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { contentApi, sharesApi, publicSharesApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import {
  SHARE_MEMORY_NAME,
  SHARE_NOTE,
  openNoteMap,
  resolvePublicShare,
  ShareUnavailableError,
  type PublicShareHead,
} from '../../core/sharing/resolvePublicShare'
import { dropKeysForShare, mediaKey, mintUploaderKey, openDropPublicKey, uploaderOriginOf } from './mediaKey'
import { openName } from '../../core/node/tree'
import { keyAt, writeKey, zeroEpochKeys, type EpochKeys } from '../spaces/epochs'
import { isStaleEpoch, openScopeById } from '../spaces/scopes'
import type { LocaleMap } from '../../shared/ui/localeMap'
import {
  gridRef,
  lightboxRef,
  downloadRef,
  openBlobFromUrls,
  makeChunkProvider,
  uploadMediaVia,
  windowPuts,
  createBrowserDeriver,
  type BlobRef,
  type MediaItem,
  type MediaUploadTransport,
  type AddMediaSource,
  type AddMediaOptions,
  type MediaDeriver,
} from './media'
import type { ChunkProvider } from '../../sw/protocol'
import {
  ShareRole,
  type ShareNode,
  type ShareBlobUploadRequestItemRoleEnum,
} from '../../api/generated/sharing'
import type { ShareMedia, ShareMediaListing } from '../../api/generated/share-media'
import type { MediaDerivativeKind } from '../../api/generated/media'

// The seals under `K_share` name their purpose only (ADR 0072 Decision 2): `K_share` is unique to one share, so the
// key is the binding. `share-key` is the share's second wrap of `K_mem` (distinct from the tree's `content-key`),
// `share-memory-name` the Memory name re-sealed for the viewer, `share-note`/`share-name`/`share-password` the
// curator's editable fields (cr-0115/0116/0118). Sealed by the curator, opened by the viewer or the manage UI.
const SHARE_KEY = { purpose: 'share-key' } as const
const SHARE_NAME = { purpose: 'share-name' } as const
const SHARE_PASSWORD = { purpose: 'share-password' } as const
/** Where a share lives: the Memory it shares and that Memory's scope, plus the share's own id (ADR 0072). */
export type ShareAddress = { scopeId: string; nodeId: string; shareId: string }
/** The recoverable copy of `K_share` under the scope key `K_SC` at `epoch` — shared across the scope, so it binds the
 *  share it belongs to and the epoch it is stored at (ADR 0072). */
const shareKeyRecoveryContext = (a: ShareAddress, epoch: number) =>
  ({ purpose: 'share-key-recovery', scopeId: a.scopeId, nodeId: a.nodeId, shareId: a.shareId, epoch }) as const
/** A share's recovery copy as the manage list serves it: `K_share` sealed under `K_SC` at `sealedShareKeyEpoch`.
 *  Both null on a share minted without one. */
export type RecoveryCopy = { sealedShareKey: string | null; sealedShareKeyEpoch: number | null }
/** A contributor's display name under `K_mem` (cr-0101), bound to (scope, share, identity). */
const identityNameContext = (scopeId: string, shareId: string, identityId: string) =>
  ({ purpose: 'share-identity-name', scopeId, shareId, identityId }) as const
const noteDecoder = new TextDecoder()

/** A fresh share id: 256 random bits, base64url — the unguessable half of the capability, minted by the client so
 *  the recovery wrap can be sealed bound to it (ADR 0072). */
export function newShareId(): string {
  return b64url(v1.randomBytes(32))
}

/** A per-locale text map, e.g. `{ de: '…', en: '…' }` (cr-0116). Defined in `shared/ui/localeMap.ts` since the
 *  editor was lifted there — re-exported so this feature's callers keep reading it from `share.ts`. */
export type { LocaleMap } from '../../shared/ui/localeMap'

/** Whether a per-locale map has any non-blank text. */
export function hasLocaleText(map: LocaleMap): boolean {
  return Object.values(map).some((t) => t.trim().length > 0)
}

/** Seal a per-locale curator-note map under `K_share` — the plaintext is a JSON `{locale: markdown}` map (cr-0116). */
function sealNoteMap(kShare: Uint8Array, note: LocaleMap): string {
  return b64std(v1.seal(SHARE_NOTE, kShare, v1.utf8(JSON.stringify(note))))
}
/** Seal a share's optional private name under `K_share` (opaque base64). */
function sealShareName(kShare: Uint8Array, name: string): string {
  return b64std(v1.seal(SHARE_NAME, kShare, v1.utf8(name)))
}
/** Open a share name sealed under `K_share`. */
function openShareName(kShare: Uint8Array, sealed: string): string {
  return noteDecoder.decode(v1.open(SHARE_NAME, kShare, fromB64std(sealed)))
}
/**
 * Recover `K_share` from its recovery copy, sealed under the scope key `K_SC` at the copy's epoch — a key every scope
 * member holds and **no share link does** (ADR 0036 point 4): a READ or READ_WRITE link carries `K_mem`, so a copy
 * under `K_mem` would open every sibling share to that link plus a dump. Bound to the share it is listed as and to
 * its epoch (ADR 0072), so a copy the server moved onto another share's row, or relabelled, does not open. Throws
 * `OpenRefused`, or a plain error when the share carries no copy.
 */
export function recoverShareKey(epochs: EpochKeys, share: ShareAddress, copy: RecoveryCopy): Uint8Array {
  const { sealedShareKey, sealedShareKeyEpoch: epoch } = copy
  if (sealedShareKey == null || epoch == null) throw new Error('share_key_not_recoverable')
  // `keyAt` answers an epoch the caller does not hold with the WRITE key rather than refusing. That stays harmless
  // only because the epoch is in the AAD: a copy served under an epoch label it was not sealed at opens under no key.
  return v1.open(shareKeyRecoveryContext(share, epoch), keyAt(epochs, epoch), fromB64std(sealedShareKey))
}

// --- password protection (cr-0118, ADR 0037): an operator-blind ACCESS-CONTROL gate ------------------------
//
// The password gates what the SERVER serves; the content stays E2EE under K_share. The client derives a token
// `pwToken = Argon2id(password, pwSalt)` (the guest bearer credential) and stores only its `SHA-256` verifier +
// salt + a K_share-sealed reveal copy — never the raw password.

/** The password fields for a create/change: a fresh salt, the SHA-256 verifier, and the reveal copy under K_share. */
async function buildPasswordFields(kShare: Uint8Array, password: string): Promise<{
  passwordRequired: true; pwSalt: string; pwVerifier: string; sealedPassword: string
}> {
  const salt = v1.randomBytes(16)
  const pwToken = await sharesPwTokenAsync(password, salt)
  return {
    passwordRequired: true,
    pwSalt: b64std(salt),
    pwVerifier: b64std(v1.sha256Digest(pwToken)),
    sealedPassword: b64std(v1.seal(SHARE_PASSWORD, kShare, v1.utf8(password))),
  }
}

/** Reveal a managed share's password (scope members only): recover `K_share` through the epoch keys, open the sealed
 *  copy. '' when none / unreadable — a copy that does not open degrades, it does not refuse (ADR 0036 point 4). */
export function readSharePassword(
  epochs: EpochKeys, share: ShareAddress, copy: RecoveryCopy, sealedPassword: string | null,
): string {
  if (!copy.sealedShareKey || !sealedPassword) return ''
  try {
    return noteDecoder.decode(v1.open(SHARE_PASSWORD, recoverShareKey(epochs, share, copy), fromB64std(sealedPassword)))
  } catch {
    return ''
  }
}

/** A managed share's per-locale note, decrypted (recover `K_share` through the epoch keys, then open the map). `{}`
 *  when there is none or the ciphertext can't be opened. */
export function readShareNoteMap(
  epochs: EpochKeys, share: ShareAddress, copy: RecoveryCopy, sealedNote: string | null,
): LocaleMap {
  if (!copy.sealedShareKey || !sealedNote) return {}
  try {
    return openNoteMap(recoverShareKey(epochs, share, copy), sealedNote)
  } catch {
    return {}
  }
}
/** A managed share's private name, decrypted. '' when none / unreadable. */
export function readShareName(
  epochs: EpochKeys, share: ShareAddress, copy: RecoveryCopy, sealedShareName: string | null,
): string {
  if (!copy.sealedShareKey || !sealedShareName) return ''
  try {
    return openShareName(recoverShareKey(epochs, share, copy), sealedShareName)
  } catch {
    return ''
  }
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
 *
 * A WRITE_ONLY share carries no wrap (ADR 0031 point 7): it carries the Memory's drop public key under `K_share`
 * instead — the node's existing drop key, read fresh from the server because every upload so far is sealed to it,
 * or a new one whose private half the server then sets on the Memory. Two owners minting the Memory's first one at
 * once: the server keeps one and refuses the other `drop_key_exists`, which re-reads the node and retries once.
 *
 * The recovery copy of `K_share` is sealed under the scope's **write key** (`epochs`' current epoch), never under
 * `memoryKey` (ADR 0036 point 4). A rotation that moved the epoch under the caller is `409 stale_epoch`: it re-opens
 * the scope key and retries once, as a group rename does (ADR 0024 Decision 6).
 */
export async function createShare(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  epochs: EpochKeys,
  memoryName: string,
  role: ShareRole = ShareRole.Read,
  expiresAt: Date | null = null,
  note: LocaleMap = {},
  name = '',
  password = '',
): Promise<MintedShare> {
  const kShare = v1.randomBytes(32)
  const shareId = newShareId() // client-minted: the recovery copy below is sealed bound to it (ADR 0072)
  const uploader = role === ShareRole.WriteOnly
  // Only a READ or READ_WRITE link holds K_mem; a WRITE_ONLY link's guest never does.
  const wrap = uploader ? undefined : b64std(v1.seal(SHARE_KEY, kShare, memoryKey))
  const sealedName = b64std(v1.seal(SHARE_MEMORY_NAME, kShare, v1.utf8(memoryName))) // the Memory name re-sealed under K_share
  // ADR 0036: a recoverable copy of K_share under K_SC(epoch) (so every scope member can reveal, edit and re-copy it
  // later — and no link can, point 4), and the optional per-locale note + private name under K_share. The copy is on
  // EVERY share, so a note/name can be added later even to one minted without them.
  const recoveryCopy = recoveryCopyOf({ scopeId, nodeId, shareId }, kShare)
  const sealedNote = hasLocaleText(note) ? sealNoteMap(kShare, note) : undefined
  const sealedShareName = name.trim() ? sealShareName(kShare, name) : undefined
  // cr-0118 (ADR 0037): an optional operator-blind password gate (fields derived client-side; the raw password never leaves).
  const pw = password.trim() ? await buildPasswordFields(kShare, password) : undefined
  let recovery = recoveryCopy(epochs)
  const post = async () => {
    const drop = uploader
      ? dropKeysForShare(scopeId, nodeId, memoryKey, kShare, await currentSealedDropKey(scopeId, nodeId))
      : undefined
    await withRefresh(() =>
      sharesApi().createShare({
        createShareRequest: {
          shareId, nodeId, role, wrap, sealedName, expiresAt: expiresAt ?? undefined, sealedNote, sealedShareName, ...recovery,
          writeVerifier: writeVerifierOf(kShare, shareId),
          passwordRequired: pw?.passwordRequired, pwSalt: pw?.pwSalt, pwVerifier: pw?.pwVerifier, sealedPassword: pw?.sealedPassword,
          sealedDropPublicKey: drop?.sealedDropPublicKey, sealedDropPrivateKey: drop?.sealedDropPrivateKey,
        },
      }),
    )
  }
  // Each refusal is retried once: a nothing-written conflict the caller can answer by re-reading what moved.
  let retriedDrop = false
  let retriedEpoch = false
  for (;;) {
    try {
      await post()
      return { shareId, kShare }
    } catch (e) {
      // Another owner set the Memory's drop key first: seal to theirs, which every upload so far is sealed to.
      if (uploader && !retriedDrop && (await isCodedError(e, HttpStatus.conflict, 'drop_key_exists'))) {
        retriedDrop = true
        continue
      }
      // A rotation moved the scope's epoch under us: re-open the scope key and re-seal the copy at the current one.
      if (!retriedEpoch && (await isStaleEpoch(e))) {
        retriedEpoch = true
        recovery = await freshRecoveryCopy(scopeId, recoveryCopy)
        continue
      }
      throw e
    }
  }
}

/** What the share stores of its write proof (ADR 0076): the hash of the token a guest derives from the link's key and
 *  presents when naming themself. Every role carries it — a READ link can become a READ_WRITE one, and its key
 *  never changes. */
function writeVerifierOf(kShare: Uint8Array, shareId: string): string {
  return b64std(v1.sha256Digest(v1.shareWriteToken(kShare, shareId)))
}

/** The recovery copy of [kShare] for the share at [address], sealed under the scope's write key at its current epoch
 *  (ADR 0036 point 4) and bound to that share id and epoch (ADR 0072). */
function recoveryCopyOf(address: ShareAddress, kShare: Uint8Array) {
  return (ek: EpochKeys) => ({
    sealedShareKey: b64std(v1.seal(shareKeyRecoveryContext(address, ek.current), writeKey(ek), kShare)),
    sealedShareKeyEpoch: ek.current,
  })
}

/** What a re-issue gives the new share — the settings the curator confirmed in the pre-filled dialog. */
export type ReissueSettings = { note: LocaleMap; name: string; password: string; expiresAt: Date | null }

/** The old share a re-issue starts from, as the manage list serves it. */
export type ReissuableShare = ShareAddress & RecoveryCopy &
  Pick<ManagedShare, 'role' | 'expiresAt' | 'sealedNote' | 'sealedShareName' | 'passwordRequired' | 'sealedPassword'>

/**
 * The old share's settings, read through its recovery copy — what a re-issue carries unless the curator changes it
 * (ADR 0075 Decision 2). A password-protected share whose password cannot be read is refused rather than carried
 * without one: a re-issue must never quietly drop a link's protection.
 */
export function carriedSettings(epochs: EpochKeys, old: ReissuableShare): ReissueSettings {
  const password = readSharePassword(epochs, old, old, old.sealedPassword)
  if (old.passwordRequired && !password) throw new Error('share_password_not_recoverable')
  return {
    note: readShareNoteMap(epochs, old, old, old.sealedNote),
    name: readShareName(epochs, old, old, old.sealedShareName),
    password,
    expiresAt: old.expiresAt,
  }
}

/**
 * Re-issue a share (ADR 0075): a new share id and a fresh `K_share`, carrying [settings], and the old link turned off
 * — one request, one server transaction. This is what cuts a departed member off a link they could copy: the old id
 * 404s and nothing of the new one is derivable from the old `K_share`. The note, label and password are re-sealed
 * under the new key (the password with a fresh salt), the recovery copy is sealed at the scope's current epoch, and
 * the role is the old share's (the server takes it from the old row). A READ/READ_WRITE link wraps `K_mem`; a
 * WRITE_ONLY link seals the Memory's **existing** drop public key — every upload so far is sealed to it (ADR 0031
 * point 7) — and is refused here rather than minting a new one. `stale_epoch` re-opens the scope key and retries
 * once, as {@link createShare} does. A share is re-issued at most once: `409 share_deleted` ({@link isShareDeleted})
 * means another renew — a concurrent one or an earlier one — or a delete got there first; it is never retried, and
 * the caller re-reads the list. The caller shows the returned link once, as after a create.
 */
export async function reissueShare(
  old: ReissuableShare,
  memoryKey: Uint8Array,
  epochs: EpochKeys,
  memoryName: string,
  settings: ReissueSettings = carriedSettings(epochs, old),
): Promise<MintedShare> {
  const { scopeId, nodeId } = old
  const kShare = v1.randomBytes(32) // never the old one: the old K_share is what the departed member may hold
  const shareId = newShareId()
  const uploader = old.role === ShareRole.WriteOnly
  const wrap = uploader ? undefined : b64std(v1.seal(SHARE_KEY, kShare, memoryKey))
  const sealedName = b64std(v1.seal(SHARE_MEMORY_NAME, kShare, v1.utf8(memoryName)))
  const sealedNote = hasLocaleText(settings.note) ? sealNoteMap(kShare, settings.note) : undefined
  const sealedShareName = settings.name.trim() ? sealShareName(kShare, settings.name) : undefined
  const pw = settings.password.trim() ? await buildPasswordFields(kShare, settings.password) : undefined
  const recoveryCopy = recoveryCopyOf({ scopeId, nodeId, shareId }, kShare)
  let recovery = recoveryCopy(epochs)
  let sealedDropPublicKey: string | undefined
  if (uploader) {
    const existing = await currentSealedDropKey(scopeId, nodeId)
    if (!existing) throw new Error('drop_key_missing') // a WRITE_ONLY share proves the Memory has one
    sealedDropPublicKey = dropKeysForShare(scopeId, nodeId, memoryKey, kShare, existing).sealedDropPublicKey
  }
  const post = () =>
    withRefresh(() =>
      sharesApi().reissueShare({
        shareId: old.shareId,
        reissueShareRequest: {
          shareId, wrap, sealedDropPublicKey, sealedName, sealedNote, sealedShareName,
          expiresAt: settings.expiresAt ?? undefined, ...recovery, writeVerifier: writeVerifierOf(kShare, shareId),
          passwordRequired: pw?.passwordRequired, pwSalt: pw?.pwSalt, pwVerifier: pw?.pwVerifier, sealedPassword: pw?.sealedPassword,
        },
      }),
    )
  try {
    await post()
  } catch (e) {
    if (!(await isStaleEpoch(e))) throw e
    recovery = await freshRecoveryCopy(scopeId, recoveryCopy)
    await post()
  }
  return { shareId, kShare }
}

/** Re-open the scope's key after a `stale_epoch` and seal the recovery copy at its current epoch; the re-opened
 *  epoch keys are wiped straight after. Throws when the scope no longer opens. */
async function freshRecoveryCopy<T>(scopeId: string, seal: (ek: EpochKeys) => T): Promise<T> {
  const opened = await openScopeById(scopeId)
  if (!opened) throw new Error('cannot open scope to re-seal the share key')
  try {
    return seal(opened.epochs)
  } finally {
    zeroEpochKeys(opened.epochs)
  }
}

/** The Memory's sealed drop private key as the server holds it now — null until its first WRITE_ONLY share. */
async function currentSealedDropKey(scopeId: string, nodeId: string): Promise<string | null> {
  const nodes = await withRefresh(() => contentApi().listNodes({ scopeId }))
  return nodes.find((n) => n.id === nodeId)?.sealedDropPrivateKey ?? null
}

/**
 * Edit a share in place (cr-0115/cr-0116/cr-0119/cr-0118, ADR 0036/0037): recover `K_share` from its recovery copy
 * (under `K_SC` at the copy's epoch), re-seal note + name (or clear a blank one) and PATCH the full pair — the same
 * link keeps working. The copy itself is never re-sealed here, so an edit needs no current epoch. An
 * optional `role` changes the grant in place (READ ↔ READ_WRITE; the server 422s WRITE_ONLY). `passwordChange` is
 * the tri-state gate: `{ set }` sets/changes it, `{ remove: true }` removes it, `undefined` leaves it untouched.
 * Owner-gated server-side; the link/`K_share` are untouched throughout.
 */
export async function updateShare(
  share: ShareAddress, epochs: EpochKeys, copy: RecoveryCopy, note: LocaleMap, name: string,
  expiresAt: Date | null, role?: ShareRole,
  passwordChange?: { set: string } | { remove: true },
): Promise<void> {
  const { shareId } = share
  const kShare = recoverShareKey(epochs, share, copy)
  const sealedNote = hasLocaleText(note) ? sealNoteMap(kShare, note) : null
  const sealedShareName = name.trim() ? sealShareName(kShare, name) : null
  // cr-0118: the password tri-state. A `remove` sends only `passwordRequired: false` — the server clears the fields.
  const passwordPatch =
    passwordChange && 'set' in passwordChange
      ? await buildPasswordFields(kShare, passwordChange.set)
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
  /** The shared Memory's scope — the recovery copy of `K_share` is bound to (scope, node, share) (ADR 0072). */
  scopeId: string
  nodeId: string
  role: ShareRole
  enabled: boolean
  /** True when the caller owns this share (so may revoke it). A disabled share by any member is adoptable (cr-0104). */
  mine: boolean
  createdAt: Date
  expiresAt: Date | null
  /** The note (per-locale) + name ciphertext (under `K_share`) + the recoverable `K_share` (under `K_SC` at
   *  `sealedShareKeyEpoch`, ADR 0036 point 4). The dialog reads them via `readShareNoteMap`/`readShareName` and edits
   *  them via `updateShare`. */
  sealedNote: string | null
  sealedShareName: string | null
  sealedShareKey: string | null
  sealedShareKeyEpoch: number | null
  /** cr-0118: whether the share is password-protected (a lock badge) + the `K_share`-sealed reveal copy of the
   *  plaintext password (management-only), read via `readSharePassword`. */
  passwordRequired: boolean
  sealedPassword: string | null
  /** True when a departed member may hold this link, so it comes back only by {@link reissueShare}: a bulk
   *  switch-off turned it off, its owner left, or its copy predates the scope's last key change or member removal.
   *  The server computes it with the predicate its re-activation refusal runs (ADR 0075 point 4) — the client has
   *  no removal count to compare, so it reads this rather than deriving it. */
  reissueRequired: boolean
  /** True when the caller may delete this share ({@link deleteShare}): it is theirs, or its owner has left the
   *  scope. The server's own gate answer (ADR 0029 §7) — the client cannot tell whether a co-member is still here. */
  deletable: boolean
  /** True when this guest link holds no write verifier (minted before ADR 0076), so it takes no new guest until it is
   *  re-issued ({@link reissueShare}). The server's answer: the verifier is never on the wire. */
  newGuestsRefused: boolean
  /** True when the link holds no write verifier, whatever its role — so as a READ_WRITE it would refuse every new
   *  guest, and an edit cannot fix that (ADR 0076 point 2): the role edit warns and offers the renew (#303). */
  writeVerifierMissing: boolean
}

/** List every share on one Memory visible to a scope member (cr-0104) — the manage-shares panel. Scoped by
 *  `nodeId` server-side, so it includes co-members' shares (each tagged `mine`), not only the caller's own. */
export async function listSharesFor(nodeId: string): Promise<ManagedShare[]> {
  const rows = await withRefresh(() => sharesApi().listShares({ nodeId }))
  return rows
    .map((r) => ({
      shareId: r.shareId,
      scopeId: r.scopeId,
      nodeId: r.nodeId,
      role: r.role,
      enabled: r.enabled,
      mine: r.mine,
      createdAt: new Date(r.createdAt),
      expiresAt: r.expiresAt ? new Date(r.expiresAt) : null,
      sealedNote: r.sealedNote ?? null,
      sealedShareName: r.sealedShareName ?? null,
      sealedShareKey: r.sealedShareKey ?? null,
      sealedShareKeyEpoch: r.sealedShareKeyEpoch ?? null,
      passwordRequired: r.passwordRequired ?? false,
      sealedPassword: r.sealedPassword ?? null,
      reissueRequired: r.reissueRequired,
      deletable: r.deletable,
      newGuestsRefused: r.newGuestsRefused,
      writeVerifierMissing: r.writeVerifierMissing,
    }))
    .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
}

/** Revoke a share (soft — the next guest request 404s). */
export async function revokeShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().revokeShare({ shareId }))
}

/** Delete a share link for good (ADR 0029 §7): the server keeps only its id, so it can never be switched back on,
 *  renewed or minted again; the photos its guests added stay in the Memory. */
export async function deleteShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().deleteShare({ shareId }))
}

/** Re-activate a soft-revoked share and adopt its ownership (cr-0104) — any current member may take over an
 *  orphaned (disabled) share on a shared Memory; the guest link then resolves again, now owned by the caller. The
 *  server refuses `409 reissue_required` for a link a departed member may hold (ADR 0075) — see
 *  {@link isReissueRequired}; such a share comes back only through {@link reissueShare}. */
export async function reactivateShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().reactivateShare({ shareId }))
}

/** Whether a re-issue was refused because the share is already deleted — renewed once already (a concurrent renew
 *  won, or an earlier one ran) or deleted (ADR 0075 point 1). Nothing was minted; the one new link, if any, is in
 *  the list, so the caller re-reads it. */
export async function isShareDeleted(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'share_deleted')
}

/** Whether a re-activation was refused because a departed member may hold the link (ADR 0075). */
export async function isReissueRequired(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'reissue_required')
}

// --- contributor / uploader (anonymous, no session; cr-0101, cr-0102) --------

/**
 * A named session on a READ_WRITE or WRITE_ONLY share: the anonymous identity id + its capability token and — a
 * WRITE_ONLY identity only — its own key `K_up` (standard base64), which everything it uploads is sealed under
 * (ADR 0031 point 7). Persisted on the guest's device only; it opens nothing but what this identity sealed.
 */
export type ContributorSession = { identityId: string; contributorToken: string; uploaderKey?: string }

/**
 * Name yourself on a READ_WRITE or WRITE_ONLY share (operator-blind) and create an anonymous share identity.
 * A READ_WRITE guest seals the display name under `K_mem`. A WRITE_ONLY guest, which never holds `K_mem`, mints its own key
 * `K_up`, seals the name under it and hands `K_up` over wrapped to the Memory's drop public key. Returns the
 * identity id + a capability token that authorizes this session's uploads (+ `K_up`). The server never sees the
 * name (only the ciphertext), the key, or the person.
 *
 * The request proves the guest holds the whole link, not only its id (ADR 0076): `X-Share-Write` carries the token
 * derived from [fragmentKey] (the link's `#k=`), whose hash the share stores.
 */
export async function createContributorIdentity(
  shareId: string,
  scopeId: string,
  access: ShareAccess,
  displayName: string,
  fragmentKey: string,
  pwToken?: string,
): Promise<ContributorSession> {
  const identityId = crypto.randomUUID() // client-minted: the name and the key wrap are sealed bound to it (ADR 0072)
  const minted = access.kind === 'drop' ? mintUploaderKey(scopeId, access.nodeId, identityId, access.dropPublicKey) : undefined
  const nameKey = minted ? minted.uploaderKey : access.kind === 'read' ? access.memoryKey : null
  if (!nameKey) throw new Error('share_not_contributable')
  const sealedDisplayName = b64std(v1.seal(identityNameContext(scopeId, shareId, identityId), nameKey, v1.utf8(displayName)))
  const { contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    createShareIdentityRequest: { identityId, sealedDisplayName, wrappedIdentityKey: minted?.wrappedIdentityKey },
    xSharePassword: pwToken, // cr-0164: the password gate fronts the write path too
    xShareWrite: b64std(v1.shareWriteToken(fromB64url(fragmentKey), shareId)),
  })
  return minted
    ? { identityId, contributorToken, uploaderKey: b64std(minted.uploaderKey) }
    : { identityId, contributorToken }
}

/**
 * The key a named session's uploads are sealed under: a WRITE_ONLY identity's own `K_up`, a READ_WRITE identity's `K_mem`.
 * Null when the session cannot seal for this share — a WRITE_ONLY session saved without its key, which must name
 * itself again rather than fall back to a key it does not hold.
 */
export function sessionSealKey(session: ContributorSession, access: ShareAccess): Uint8Array | null {
  if (access.kind === 'drop') return session.uploaderKey ? fromB64std(session.uploaderKey) : null
  return access.memoryKey
}

/** The share-scoped upload transport (no auth) — reserve via `startShareBlobUploads`, sign each next window via
 *  `presignShareBlobUploadWindows` (the link's gate again, so a revoked link stops the upload there), complete via
 *  `completeShareMedia`. */
function shareUploadTransport(shareId: string, contributorToken: string, pwToken?: string): MediaUploadTransport {
  return {
    async reserve(declares) {
      const started = await publicSharesApi().startShareBlobUploads({
        shareId,
        shareBlobUploadsRequest: {
          contributorToken,
          // A contributor uploads media only — never a cover — so ORIGINAL/THUMBNAIL/PREVIEW is the whole role set.
          items: declares.map((d) => ({ ...d, role: d.role as ShareBlobUploadRequestItemRoleEnum })),
        },
        xSharePassword: pwToken, // cr-0164
      })
      if (started.results.length !== declares.length) throw new Error('upload_failed')
      return started.results.map((r) => {
        if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
        if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
        return { uploadId: r.uploadId, puts: r.presignedPuts }
      })
    },
    async window(uploadId, fromChunk) {
      const signed = await publicSharesApi().presignShareBlobUploadWindows({
        shareId,
        shareBlobUploadWindowsRequest: { contributorToken, items: [{ uploadId, fromChunk }] },
        xSharePassword: pwToken, // cr-0164
      })
      return windowPuts(signed.results[0])
    },
    async complete(media) {
      const done = await publicShareMediaApi().completeShareMedia({
        shareId,
        shareMediaBatchRequest: {
          contributorToken,
          items: [{
            mediaId: media.mediaId,
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
 * Add one photo to a READ_WRITE or WRITE_ONLY share (anonymous): seal it under the session's key ([sessionSealKey]:
 * `K_mem` for a contributor, the guest's own `K_up` for an uploader) with its derivatives + metadata and upload it
 * via the share-scoped endpoints — the exact same operator-blind pipeline as the curator's `addMedia`, only the
 * transport is share-gated. Returns the new blob + media ids.
 */
export async function addSharePhoto(
  session: ContributorSession & { shareId: string; scopeId: string; sealKey: Uint8Array; pwToken?: string },
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(
    session.scopeId, session.sealKey, source, deriver, shareUploadTransport(session.shareId, session.contributorToken, session.pwToken), opts,
  )
}

/**
 * Delete one of your own contributions on a READ_WRITE/WRITE_ONLY share (anonymous, token-authorized). The server
 * authorizes it by a single-row ownership check (the media's `share_identity_id` equals the token's identity) and
 * reclaims its blobs — a foreign/unknown media is a 404. Used by the uploader drop-box's session-only delete-own.
 */
export async function deleteSharePhoto(shareId: string, contributorToken: string, mediaId: string, pwToken?: string): Promise<void> {
  await publicShareMediaApi().deleteShareMedia({ shareId, mediaId, xContributorToken: contributorToken, xSharePassword: pwToken }) // cr-0164
}

// --- viewer (anonymous, no session) ----------------------------------------

/** One shared media resolved for the read-only viewer: the media + the `K_mem` of the node it sits on + the shared
 *  Memory's scope, which every one of its seals is bound to (ADR 0072). A media a WRITE_ONLY guest added carries its
 *  origin, so [mediaKey] opens it through that node's drop key instead (ADR 0031 point 7). `chapterName` is the title
 *  of the chapter it sits in (ADR 0080 Decision 2), null for a media on the shared root itself. */
export type ShareItem = { item: MediaItem; memoryKey: Uint8Array; scopeId: string; chapterName: string | null }

/**
 * What a resolved link lets its guest do with keys (ADR 0031 point 7):
 *  - `read` (READ, READ_WRITE): the shared Memory's root `K_mem` — to open its media, and for a contributor to
 *    seal their name + uploads under;
 *  - `drop` (WRITE_ONLY): the Memory's drop PUBLIC key and the node it belongs to — enough to wrap an identity key
 *    of its own to, nothing to open anything with.
 */
export type ShareAccess =
  | { kind: 'read'; memoryKey: Uint8Array }
  | { kind: 'drop'; nodeId: string; dropPublicKey: v1.HybridPublic }

/** The decrypted shared Memory, ready to render: its name, the grant `role`, what its [access] holds, and every
 *  media (each with its own node's `K_mem` — none for a WRITE_ONLY link, which is served no media). */
export type ResolvedShare = {
  memoryName: string
  noteByLocale: LocaleMap
  role: ShareRole
  /** The shared Memory's scope — a contributor seals their name and uploads bound to it (ADR 0072). */
  scopeId: string
  access: ShareAccess
  items: ShareItem[]
}

function shareMediaToItem(m: ShareMedia, uploader: MediaItem['uploader']): MediaItem {
  // Each ref is owned by THIS row's media, in the role the row lists it in (ADR 0072), exactly as the authed list.
  return {
    mediaId: m.mediaId,
    original: {
      blobId: m.blobId, chunkCount: m.chunkCount, byteSize: m.byteSize, wrappedBlobKey: m.wrappedBlobKey,
      ownerId: m.mediaId, role: 'ORIGINAL',
    },
    derivatives: (m.derivatives ?? []).map((d) => ({
      kind: d.kind as MediaDerivativeKind,
      ref: {
        blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey,
        ownerId: m.mediaId, role: d.kind as MediaDerivativeKind,
      },
    })),
    wrappedMeta: m.wrappedMeta ?? undefined,
    uploader,
  }
}

/**
 * Walk a shared subtree top-down from the root's `K_mem` — unwrapped with the fragment key from the share `wrap` — (ADR
 * 0012 recursive `K_mem`, ADR 0080 Decisions 2 and 9): each descendant's key unwraps under its parent's, and its name — its directory entry, sealed under the same parent key — opens with it. The root
 * has no entry here (its title is the share's `sealedName`). A node whose key or name does not open is skipped, and
 * so is everything below it: its media simply won't render, its title won't show.
 */
function walkSubtree(
  scopeId: string, nodes: ShareNode[], rootKmem: Uint8Array,
): { keys: Map<string, Uint8Array>; names: Map<string, string> } {
  const keys = new Map<string, Uint8Array>()
  const names = new Map<string, string>()
  const byParent = new Map<string, ShareNode[]>()
  let root: ShareNode | undefined
  for (const n of nodes) {
    if (n.parentId == null) root = n
    else byParent.set(n.parentId, [...(byParent.get(n.parentId) ?? []), n])
  }
  if (!root) return { keys, names }
  keys.set(root.id, rootKmem)
  // BFS from the root, deriving each child's K_mem under its parent's (already-known) K_mem.
  const queue: string[] = [root.id]
  while (queue.length) {
    const parentId = queue.shift()!
    const parentKey = keys.get(parentId)
    if (!parentKey) continue
    for (const child of byParent.get(parentId) ?? []) {
      if (!child.wrappedContentKey || !child.encryptedName) continue
      try {
        // Both bound to (scope, this chapter, its epoch) — the node they are listed for (ADR 0072) — and both under
        // the parent's key: the chapter's directory entry (ADR 0080 Decision 2).
        const node = { scopeId, nodeId: child.id, epoch: child.epoch }
        const key = v1.open({ purpose: 'content-key', ...node }, parentKey, fromB64std(child.wrappedContentKey))
        const name = openName(parentKey, node, child.encryptedName)
        keys.set(child.id, key)
        names.set(child.id, name)
        queue.push(child.id)
      } catch {
        /* a chapter whose K_mem or title won't open is skipped (its media then simply won't render) */
      }
    }
  }
  return { keys, names }
}

/** The Memories half of a resolved share: what its link lets the guest do with keys, every media (each with its
 *  own node's `K_mem` — none for a WRITE_ONLY link, which is served no media), and the title of every chapter below
 *  the shared root it could open (ADR 0080 Decision 2; empty for a WRITE_ONLY link, which holds no key to open one). */
export type OpenedMemory = { access: ShareAccess; items: ShareItem[]; chapterNames: Map<string, string> }

/** No media and no uploader key: what a WRITE_ONLY link is served, and the listing before it is read. */
export const NO_SHARE_MEDIA: ShareMediaListing = { media: [], identities: [] }

/**
 * Read what a share serves of the Memory's media — Memories' own guest read (`listShareMedia`, no bearer; the guest's
 * password token as for the resolve): the sealed rows under the shared subtree and the wrapped keys of the WRITE_ONLY
 * identities whose rows those are. A WRITE_ONLY link is served nothing (ADR 0031), so it is not asked. Throws
 * [ShareUnavailableError] when the read is refused (a link revoked since its resolve answers 404 here too).
 */
export async function listShareMedia(shareId: string, role: ShareRole, pwToken?: string): Promise<ShareMediaListing> {
  if (role === ShareRole.WriteOnly) return NO_SHARE_MEDIA
  try {
    return await publicShareMediaApi().listShareMedia({ shareId, xSharePassword: pwToken })
  } catch {
    throw new ShareUnavailableError('not_found')
  }
}

/**
 * Open the Memories half of a public share: unwrap the Memory's `K_mem` with the fragment key, derive every chapter's
 * `K_mem`, and return a flat, read-only list of the [listing]'s media (each tagged with the exact `K_mem` its blobs open
 * under). The platform half (fetch, password, name, note, root kind) is `resolvePublicShare` (core/sharing); the shell
 * calls it and hands this its head, and the media are Memories' own read ([listShareMedia]). Without a listing it opens
 * the keys alone — what the body needs before the listing lands (a contributor's name gate reads the access). Throws
 * [ShareUnavailableError] when the response cannot be opened (a server that served a share without the wrap or drop key
 * the role needs, or one that does not open under the fragment key).
 */
export function openSharedMemory(head: PublicShareHead, listing: ShareMediaListing = NO_SHARE_MEDIA): OpenedMemory {
  const { share, kShare, scopeId } = head
  // ADR 0031 point 7: a WRITE_ONLY link opens the drop public key and nothing else. It never unwraps `K_mem`, even
  // from a row that still carries a wrap, and it is served no media to key.
  if (share.role === ShareRole.WriteOnly) {
    if (!share.sealedDropPublicKey || !share.nodeId) throw new ShareUnavailableError('not_found')
    let dropPublicKey: v1.HybridPublic
    try {
      dropPublicKey = openDropPublicKey(kShare, share.sealedDropPublicKey)
    } catch {
      throw new ShareUnavailableError('bad_fragment_key')
    }
    return { access: { kind: 'drop', nodeId: share.nodeId, dropPublicKey }, items: [], chapterNames: new Map() }
  }

  if (!share.wrap) throw new ShareUnavailableError('not_found')
  let rootKmem: Uint8Array
  try {
    rootKmem = v1.open(SHARE_KEY, kShare, fromB64std(share.wrap)) // READ / READ_WRITE only (guarded above)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
  const { keys, names } = walkSubtree(scopeId, share.nodes, rootKmem)
  // A media a WRITE_ONLY guest added opens through its node's drop key and its identity's wrapped key: the resolve's
  // node carries the one, the listing's identities the other (ADR 0031 point 7). That list is all a guest is told
  // about any identity — never a name, which is for the Memory's members (ADR 0030 point 8).
  const dropKeyOf = new Map(share.nodes.map((n) => [n.id, n.sealedDropPrivateKey]))
  const wrapped = new Map(listing.identities.map((i) => [i.identityId, i.wrappedIdentityKey]))
  const items: ShareItem[] = []
  for (const m of listing.media) {
    const memoryKey = keys.get(m.nodeId)
    if (!memoryKey) continue // a media under a chapter whose K_mem didn't open — skip
    const owner = m.identityId ? `share:${m.identityId}` : undefined
    const uploader = uploaderOriginOf(owner, m.nodeId, dropKeyOf.get(m.nodeId), wrapped)
    items.push({ item: shareMediaToItem(m, uploader), memoryKey, scopeId, chapterName: names.get(m.nodeId) ?? null })
  }
  return { access: { kind: 'read', memoryKey: rootKmem }, items, chapterNames: names }
}

/**
 * Resolve + decrypt a public share in one call: the platform half (`resolvePublicShare`), then the Memories half — its
 * media read ([listShareMedia]) and `openSharedMemory`. The share page does NOT use this — its shell resolves the head,
 * gates on password and terms, and only then lets the Memories body open the rest. It is the headless entry the unit
 * and e2e suites drive. The media read follows the resolve rather than racing it: it needs the role (a WRITE_ONLY link
 * is not asked) and a locked link would refuse it.
 */
export async function resolveShare(shareId: string, fragmentKey: string, pwToken?: string): Promise<ResolvedShare> {
  const head = await resolvePublicShare(shareId, fragmentKey, pwToken)
  const { access, items } = openSharedMemory(head, await listShareMedia(shareId, head.role, pwToken))
  return { memoryName: head.name, noteByLocale: head.noteByLocale, role: head.role, scopeId: head.scopeId, access, items }
}

/** Open a shared blob via the gated, share-scoped presign endpoint (no auth), then decrypt it under its `K_mem`.
 *  A password-protected share needs the same guest token (cr-0118) as the resolve. */
async function openShareBlob(shareId: string, si: ShareItem, ref: BlobRef, pwToken?: string): Promise<Uint8Array> {
  const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
  const getUrls = [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  return openBlobFromUrls(si.scopeId, mediaKey(si.scopeId, si.memoryKey, si.item), ref, getUrls)
}

/**
 * A {@link ChunkProvider} for the anonymous share viewer — the Slice-2 SW range seam over the gated share-scoped
 * presign (`resolveShareBlob`: `shareId` + guest `pwToken`, whole-blob only). Same `openFrame` core as the authed
 * `chunkProvider`; only the presign differs, so the SW never learns which route served the bytes (ADR 0047).
 */
export function shareChunkProvider(shareId: string, si: ShareItem, ref: BlobRef, pwToken?: string): ChunkProvider {
  return makeChunkProvider(si.scopeId, mediaKey(si.scopeId, si.memoryKey, si.item), ref, async () => {
    const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId, xSharePassword: pwToken })
    return [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  })
}

/** Open a shared media's grid (thumbnail) tier. */
export const openShareGrid = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, gridRef(si.item), pwToken)
/** Open a shared media's lightbox (preview) tier. */
export const openShareLightbox = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, lightboxRef(si.item), pwToken)
/** Open a shared media's pristine original (download). */
export const openShareOriginal = (shareId: string, si: ShareItem, pwToken?: string) => openShareBlob(shareId, si, downloadRef(si.item), pwToken)
