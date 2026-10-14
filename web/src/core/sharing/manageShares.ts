// The platform's share management (uc.sharing.share-public-viewer curator side, cr-0100/0104/0115/0116/0118/0119, ADR
// 0029/0036/0037/0072/0075/0076; lifted out of the Memories application in cr-node-key-hierarchy 5b.2). A member turns
// ONE node of their scope into an anonymous, revocable link `/s/{shareId}#k=<K_share>`: the share adds an INDEPENDENT
// second wrap of that node's key — sealed under `K_share` (ADR 0012) — whose secret `K_share` rides the URL **fragment**
// (ADR 0015) and never reaches the server; the node's name is re-sealed under `K_share` so a viewer with only the
// fragment key can show the title without `K_SC`.
//
// Everything here is kind-independent: mint (READ / READ_WRITE, with the mandatory recovery copy), list, revoke,
// re-activate, delete, re-issue, edit, reading a managed share's fields back through its recovery copy, and re-sealing
// every share's root name after a member renamed the root (ADR 0080 decision 14). What is not:
// a WRITE_ONLY link carries no wrap but the node's drop public key (ADR 0031 point 7), and the drop key is the
// application's that registered the drop flow — so a WRITE_ONLY mint or re-issue is only reachable through a
// [WriteOnlyMint] that application hands in (today Memories' `createShare` / `reissueShare` in features/memory/share.ts).
import * as v1 from '@veyq/crypto/v1'
import { sharesPwTokenAsync } from '@veyq/crypto/argonWorker'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { sharesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { SHARE_MEMORY_NAME, SHARE_NOTE, openShareNote } from './resolvePublicShare'
import { NO_NOTE, encodeShareNote, hasLocaleText, type ShareNote } from './shareNote'
import { SHARE_KEY } from './shareTree'
import { keyAt, writeKey, zeroEpochKeys, type EpochKeys } from '../../features/spaces/epochs'
import { isStaleEpoch, openScopeById } from '../../features/spaces/scopes'
import { ShareRole } from '../../api/generated/sharing'

// The seals under `K_share` name their purpose only (ADR 0072 Decision 2): `K_share` is unique to one share, so the
// key is the binding. `share-key` (the second wrap, `./shareTree.ts`) and `share-memory-name` / `share-note`
// (`./resolvePublicShare.ts`) are the ones a guest opens; `share-name`/`share-password` are the curator's editable
// fields (cr-0115/0118), opened by the manage UI only.
const SHARE_NAME = { purpose: 'share-name' } as const
const SHARE_PASSWORD = { purpose: 'share-password' } as const
/** Where a share lives: the node it shares and that node's scope, plus the share's own id (ADR 0072). */
export type ShareAddress = { scopeId: string; nodeId: string; shareId: string }
/** The recoverable copy of `K_share` under the scope key `K_SC` at `epoch` — shared across the scope, so it binds the
 *  share it belongs to and the epoch it is stored at (ADR 0072). */
const shareKeyRecoveryContext = (a: ShareAddress, epoch: number) =>
  ({ purpose: 'share-key-recovery', scopeId: a.scopeId, nodeId: a.nodeId, shareId: a.shareId, epoch }) as const
/** A share's recovery copy as the manage list serves it: `K_share` sealed under `K_SC` at `sealedShareKeyEpoch`.
 *  Both null on a share minted without one (a legacy row) or a tombstone (ADR 0080 Decision 12). */
export type RecoveryCopy = { sealedShareKey: string | null; sealedShareKeyEpoch: number | null }
const noteDecoder = new TextDecoder()

/** A fresh share id: 256 random bits, base64url — the unguessable half of the capability, minted by the client so
 *  the recovery wrap can be sealed bound to it (ADR 0072). */
export function newShareId(): string {
  return b64url(v1.randomBytes(32))
}

/** A per-locale text map, e.g. `{ de: '…', en: '…' }` (cr-0116). Defined in `shared/ui/localeMap.ts` since the
 *  editor was lifted there — re-exported so the share management's callers keep reading it from here. */
export type { LocaleMap } from '../../shared/ui/localeMap'

/** A curator note (its texts and its acceptance switch) and the helpers around it, from `./shareNote.ts` — re-exported
 *  so the share management's callers keep reading them from here. */
export { NO_NOTE, hasLocaleText, type ShareNote } from './shareNote'

/** Seal a curator note under `K_share`, or nothing when it has no text (`./shareNote.ts` owns the plaintext's shape:
 *  the switch rides inside the seal, so the operator cannot tell a note that asks to be accepted from one that does not). */
function sealNote(kShare: Uint8Array, note: ShareNote): string | undefined {
  return hasLocaleText(note.texts) ? b64std(v1.seal(SHARE_NOTE, kShare, v1.utf8(encodeShareNote(note)))) : undefined
}
/** Seal a share's optional private name under `K_share` (opaque base64). */
function sealShareName(kShare: Uint8Array, name: string): string {
  return b64std(v1.seal(SHARE_NAME, kShare, v1.utf8(name)))
}
/** Seal the shared node's own name under `K_share` (`share-memory-name`, the title a guest reads with only the
 *  fragment key): at a mint, a re-issue and when a member renames the share's root ({@link resealShareNames}). */
function sealRootName(kShare: Uint8Array, rootName: string): string {
  return b64std(v1.seal(SHARE_MEMORY_NAME, kShare, v1.utf8(rootName)))
}
/** Open a share name sealed under `K_share`. */
function openShareName(kShare: Uint8Array, sealed: string): string {
  return noteDecoder.decode(v1.open(SHARE_NAME, kShare, fromB64std(sealed)))
}
/**
 * Recover `K_share` from its recovery copy, sealed under the scope key `K_SC` at the copy's epoch — a key every scope
 * member holds and **no share link does** (ADR 0036 point 4): a READ or READ_WRITE link carries the node's key, so a
 * copy under that key would open every sibling share to that link plus a dump. Bound to the share it is listed as and
 * to its epoch (ADR 0072), so a copy the server moved onto another share's row, or relabelled, does not open. Throws
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

/** A managed share's note, decrypted (recover `K_share` through the epoch keys, then open it): its texts and its
 *  acceptance switch. {@link NO_NOTE} when there is none, or the ciphertext or its plaintext does not open. */
export function readShareNote(
  epochs: EpochKeys, share: ShareAddress, copy: RecoveryCopy, sealedNote: string | null,
): ShareNote {
  if (!copy.sealedShareKey || !sealedNote) return NO_NOTE
  try {
    return openShareNote(recoverShareKey(epochs, share, copy), sealedNote)?.note ?? NO_NOTE
  } catch {
    return NO_NOTE
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
 *  `./shareLink.ts` owns that, and its header says why this layer hands back the key rather than a URL. */
export type MintedShare = { shareId: string; kShare: Uint8Array }

/**
 * The WRITE_ONLY half of a mint or re-issue, which the application that owns the node's drop key hands in (ADR 0031
 * point 7): a WRITE_ONLY link carries no wrap, only the node's drop public key under `K_share`.
 *  - `dropFields` seals the drop key for the request — run before each attempt of a mint, once for a re-issue.
 *  - `retryOnce` names a mint refusal the application answers by re-reading what moved (another member set the
 *    node's first drop key); such a refusal is retried once. A re-issue does not ask it.
 */
export type WriteOnlyMint = {
  dropFields(kShare: Uint8Array): Promise<{ sealedDropPublicKey: string; sealedDropPrivateKey?: string }>
  retryOnce?(e: unknown): Promise<boolean>
}

/** A WRITE_ONLY role and a [WriteOnlyMint] go together: a WRITE_ONLY link with a wrap would hand its guest the key it
 *  exists to keep from them, and one without its drop key is a link nobody can upload through. */
function requireWriteOnlyMatch(role: ShareRole, writeOnly: WriteOnlyMint | undefined): void {
  if ((role === ShareRole.WriteOnly) !== (writeOnly !== undefined)) throw new Error('share_write_only_mint_mismatch')
}

/**
 * Mint a public viewer share of one node (curator side). Generates a fresh 32-byte `K_share`, wraps the node's key
 * under it, re-seals the node's name under `K_share`, and POSTs the opaque record. Returns the `shareId` + `K_share`;
 * the caller turns that pair into a link with `buildShareLink`. The server never sees `K_share` — it leaves this
 * function only towards the UI, and reaches a visitor only in the link's fragment.
 *
 * A WRITE_ONLY share carries no wrap (ADR 0031 point 7): its drop key comes from [writeOnly], which a WRITE_ONLY role
 * requires and every other role refuses.
 *
 * The recovery copy of `K_share` is sealed under the scope's **write key** (`epochs`' current epoch), never under
 * `rootKey` (ADR 0036 point 4). A rotation that moved the epoch under the caller is `409 stale_epoch`: it re-opens
 * the scope key and retries once, as a group rename does (ADR 0024 Decision 6).
 */
export async function mintShare(
  scopeId: string,
  nodeId: string,
  rootKey: Uint8Array,
  epochs: EpochKeys,
  rootName: string,
  role: ShareRole = ShareRole.Read,
  expiresAt: Date | null = null,
  note: ShareNote = NO_NOTE,
  name = '',
  password = '',
  writeOnly?: WriteOnlyMint,
): Promise<MintedShare> {
  requireWriteOnlyMatch(role, writeOnly)
  const kShare = v1.randomBytes(32)
  const shareId = newShareId() // client-minted: the recovery copy below is sealed bound to it (ADR 0072)
  // Only a READ or READ_WRITE link holds the node's key; a WRITE_ONLY link's guest never does.
  const wrap = writeOnly ? undefined : b64std(v1.seal(SHARE_KEY, kShare, rootKey))
  const sealedName = sealRootName(kShare, rootName) // the node's name re-sealed under K_share
  // ADR 0036: a recoverable copy of K_share under K_SC(epoch) (so every scope member can reveal, edit and re-copy it
  // later — and no link can, point 4), and the optional per-locale note + private name under K_share. The copy is on
  // EVERY share, so a note/name can be added later even to one minted without them.
  const recoveryCopy = recoveryCopyOf({ scopeId, nodeId, shareId }, kShare)
  const sealedNote = sealNote(kShare, note)
  const sealedShareName = name.trim() ? sealShareName(kShare, name) : undefined
  // cr-0118 (ADR 0037): an optional operator-blind password gate (fields derived client-side; the raw password never leaves).
  const pw = password.trim() ? await buildPasswordFields(kShare, password) : undefined
  let recovery = recoveryCopy(epochs)
  const post = async () => {
    const drop = writeOnly ? await writeOnly.dropFields(kShare) : undefined
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
      // The application's own drop-key conflict (another member set the node's drop key first): re-read and re-seal.
      if (writeOnly?.retryOnce && !retriedDrop && (await writeOnly.retryOnce(e))) {
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
export type ReissueSettings = { note: ShareNote; name: string; password: string; expiresAt: Date | null }

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
    note: readShareNote(epochs, old, old, old.sealedNote),
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
 * the role is the old share's (the server takes it from the old row). A READ/READ_WRITE link wraps the node's key; a
 * WRITE_ONLY link takes its drop public key from [writeOnly] (which that role requires). `stale_epoch` re-opens the
 * scope key and retries once, as {@link mintShare} does. A share is re-issued at most once: `409 share_deleted`
 * ({@link isShareDeleted}) means another renew — a concurrent one or an earlier one — or a delete got there first; it
 * is never retried, and the caller re-reads the list. The caller shows the returned link once, as after a create.
 */
export async function remintShare(
  old: ReissuableShare,
  rootKey: Uint8Array,
  epochs: EpochKeys,
  rootName: string,
  settings: ReissueSettings = carriedSettings(epochs, old),
  writeOnly?: WriteOnlyMint,
): Promise<MintedShare> {
  requireWriteOnlyMatch(old.role, writeOnly)
  const { scopeId, nodeId } = old
  const kShare = v1.randomBytes(32) // never the old one: the old K_share is what the departed member may hold
  const shareId = newShareId()
  const wrap = writeOnly ? undefined : b64std(v1.seal(SHARE_KEY, kShare, rootKey))
  const sealedName = sealRootName(kShare, rootName)
  const sealedNote = sealNote(kShare, settings.note)
  const sealedShareName = settings.name.trim() ? sealShareName(kShare, settings.name) : undefined
  const pw = settings.password.trim() ? await buildPasswordFields(kShare, settings.password) : undefined
  const recoveryCopy = recoveryCopyOf({ scopeId, nodeId, shareId }, kShare)
  let recovery = recoveryCopy(epochs)
  const sealedDropPublicKey = writeOnly ? (await writeOnly.dropFields(kShare)).sealedDropPublicKey : undefined
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

/**
 * Edit a share in place (cr-0115/cr-0116/cr-0119/cr-0118, ADR 0036/0037): recover `K_share` from its recovery copy
 * (under `K_SC` at the copy's epoch), re-seal note + name (or clear a blank one) and PATCH the full pair — the same
 * link keeps working. The copy itself is never re-sealed here, so an edit needs no current epoch. An
 * optional `role` changes the grant in place (READ ↔ READ_WRITE; the server 422s WRITE_ONLY). `passwordChange` is
 * the tri-state gate: `{ set }` sets/changes it, `{ remove: true }` removes it, `undefined` leaves it untouched.
 * Owner-gated server-side; the link/`K_share` are untouched throughout.
 */
export async function updateShare(
  share: ShareAddress, epochs: EpochKeys, copy: RecoveryCopy, note: ShareNote, name: string,
  expiresAt: Date | null, role?: ShareRole,
  passwordChange?: { set: string } | { remove: true },
): Promise<void> {
  const { shareId } = share
  const kShare = recoverShareKey(epochs, share, copy)
  const sealedNote = sealNote(kShare, note) ?? null
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

/** A curator's management row for one of their shares (opaque; the sealed name isn't shown — the node is known). */
export type ManagedShare = {
  shareId: string
  /** The shared node's scope — the recovery copy of `K_share` is bound to (scope, node, share) (ADR 0072). */
  scopeId: string
  nodeId: string
  role: ShareRole
  enabled: boolean
  /** True when the caller owns this share (so may revoke it). A disabled share by any member is adoptable (cr-0104). */
  mine: boolean
  createdAt: Date
  expiresAt: Date | null
  /** The note (per-locale) + name ciphertext (under `K_share`) + the recoverable `K_share` (under `K_SC` at
   *  `sealedShareKeyEpoch`, ADR 0036 point 4). The dialog reads them via `readShareNote`/`readShareName` and edits
   *  them via `updateShare`. */
  sealedNote: string | null
  sealedShareName: string | null
  sealedShareKey: string | null
  sealedShareKeyEpoch: number | null
  /** cr-0118: whether the share is password-protected (a lock badge) + the `K_share`-sealed reveal copy of the
   *  plaintext password (management-only), read via `readSharePassword`. */
  passwordRequired: boolean
  sealedPassword: string | null
  /** True when a departed member may hold this link, so it comes back only by a re-issue ({@link remintShare}): a
   *  bulk switch-off turned it off, its owner left, or its copy predates the scope's last key change or member removal.
   *  The server computes it with the predicate its re-activation refusal runs (ADR 0075 point 4) — the client has
   *  no removal count to compare, so it reads this rather than deriving it. */
  reissueRequired: boolean
  /** True when the caller may delete this share ({@link deleteShare}): it is theirs, or its owner has left the
   *  scope. The server's own gate answer (ADR 0029 §7) — the client cannot tell whether a co-member is still here. */
  deletable: boolean
  /** True when this guest link holds no write verifier (minted before ADR 0076), so it takes no new guest until it is
   *  re-issued ({@link remintShare}). The server's answer: the verifier is never on the wire. */
  newGuestsRefused: boolean
  /** True when the link holds no write verifier, whatever its role — so as a READ_WRITE it would refuse every new
   *  guest, and an edit cannot fix that (ADR 0076 point 2): the role edit warns and offers the renew (#303). */
  writeVerifierMissing: boolean
}

/** List every share on one node visible to a scope member (cr-0104) — the manage-shares panel. Scoped by
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

/** What a re-seal of the share names did, by share id: stored, skipped (no recovery copy: a tombstone or a legacy row
 *  minted before ADR 0080 decision 12, so its title stays the old name) and failed (the copy did not open, or the
 *  write was refused). */
export type ResealReport = { resealed: string[]; skipped: string[]; failed: string[] }

/**
 * After a member renamed [nodeId] to [newName], re-seal the name of EVERY share rooted at it — the caller's own and
 * every other member's, switched off ones included — so no link shows a stale title (ADR 0080 decision 14). Each
 * share's `K_share` is opened through its recovery copy under the scope key (decision 12), the new name sealed under
 * it as a mint does, and stored with `setShareSealedName`, which any member with write access may call (not the
 * owner-gated `updateShare`). Each share is its own write: one that fails is collected and the next is still tried;
 * nothing is rolled back and the rename itself is never undone (the name is last-writer-wins, the next rename
 * re-seals again). A node without shares costs one list request and writes nothing. Throws only when the list itself
 * cannot be read — then nothing was written.
 */
export async function resealShareNames(nodeId: string, newName: string, epochs: EpochKeys): Promise<ResealReport> {
  const report: ResealReport = { resealed: [], skipped: [], failed: [] }
  for (const share of await listSharesFor(nodeId)) {
    if (share.sealedShareKey == null || share.sealedShareKeyEpoch == null) {
      report.skipped.push(share.shareId) // share_key_not_recoverable: nothing to seal the new name under
      continue
    }
    try {
      const kShare = recoverShareKey(epochs, share, share)
      const sealedName = sealRootName(kShare, newName)
      kShare.fill(0)
      await withRefresh(() => sharesApi().setShareSealedName({ shareId: share.shareId, setShareSealedNameRequest: { sealedName } }))
      report.resealed.push(share.shareId)
    } catch {
      report.failed.push(share.shareId)
    }
  }
  return report
}

/** Revoke a share (soft — the next guest request 404s). */
export async function revokeShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().revokeShare({ shareId }))
}

/** Delete a share link for good (ADR 0029 §7): the server keeps only its id, so it can never be switched back on,
 *  renewed or minted again; what its guests added stays in the shared node. */
export async function deleteShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().deleteShare({ shareId }))
}

/** Re-activate a soft-revoked share and adopt its ownership (cr-0104) — any current member may take over an
 *  orphaned (disabled) share on a shared node; the guest link then resolves again, now owned by the caller. The
 *  server refuses `409 reissue_required` for a link a departed member may hold (ADR 0075) — see
 *  {@link isReissueRequired}; such a share comes back only through a re-issue ({@link remintShare}). */
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
