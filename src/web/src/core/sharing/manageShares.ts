// The platform's share management (uc.sharing.share-public-viewer curator side, cr-0100/0104/0115/0116/0118/0119, ADR
// 0029/0036/0037/0072/0075/0076; the platform's, not an application's, ADR 0079). A member turns ONE node of their
// scope into an anonymous, revocable link `/s/{shareId}#k=<K_share>`: the share adds an INDEPENDENT second wrap of that
// node's key — sealed under `K_share` (ADR 0012) — whose secret `K_share` rides the URL **fragment** (ADR 0015) and
// never reaches the server; the node's name is re-sealed under `K_share` so a viewer with only the fragment key can
// show the title without `K_SC`.
//
// Everything here is kind-independent: mint (READ / READ_WRITE, with the mandatory recovery copy), list, revoke,
// re-activate, delete, re-issue, edit, reading a managed share's fields back through its recovery copy, and re-sealing
// every share's root name after a member renamed the root (ADR 0080 decision 14). What is not:
// a WRITE_ONLY link carries no wrap but the node's drop public key (ADR 0031 point 7), and the drop key is the
// application's that registered the drop flow — so a WRITE_ONLY mint or re-issue is only reachable through a
// [WriteOnlyMint] that application hands in (today Memories' `createShare` / `reissueShare` in features/memories/share.ts).
import { shallowReactive } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { sharesPwTokenAsync } from '@veyq/crypto/argonWorker'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { sharesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { SHARE_MEMORY_NAME, SHARE_NOTE, openShareNote } from './resolvePublicShare'
import { NO_NOTE, encodeShareNote, hasLocaleText, type ShareNote } from './shareNote'
import { SHARE_KEY } from './shareTree'
import { zeroScopeKey, type ScopeKey } from '../../features/spaces/epochs'
import { openValue, sealValue } from '../../features/spaces/valueKey'
import { openScopeById } from '../../features/spaces/scopes'
import { writeAtNodeEpoch } from '../../shared/rekeyRetry'
import { fetchTreeRows, listTree } from '../node/tree'
import { dropKeysForShare } from './dropKey'
import { nodeKeyForWrite } from '../node/rekey/floor'
import { assertActiveScopeFloor } from '../node/rereadNode'
import { pullRekeyPath, rekeyPath } from '../node/rekey/rekeyScope'
import { ShareRole, type ShareGuestStop } from '../../api/generated/sharing'

// The seals under `K_share` name their purpose only (ADR 0072 Decision 2): `K_share` is unique to one share, so the
// key is the binding. `share-key` (the second wrap, `./shareTree.ts`) and `share-memory-name` / `share-note`
// (`./resolvePublicShare.ts`) are the ones a guest opens; `share-name`/`share-password` are the curator's editable
// fields (cr-0115/0118), opened by the manage UI only.
const SHARE_NAME = { purpose: 'share-name' } as const
const SHARE_PASSWORD = { purpose: 'share-password' } as const
/** Where a share lives: the node it shares and that node's scope, plus the share's own id (ADR 0072). */
export type ShareAddress = { scopeId: string; nodeId: string; shareId: string }
/** The recoverable copy of `K_share`, under a value key `K_v` of its own whose wrap the scope key `K_SC` holds (ADR 0115)
 *  — shared across the scope, so it binds the share it belongs to and the epoch it was written at, the link's minted-at
 *  epoch (ADR 0072). A rotation re-wraps `K_v` and leaves the copy: `K_share` is itself a key, so a wrap a rotator
 *  substituted opens no `K_share` at all, never one of its choosing. Also the address the wrap is bound to (`value-key`). */
export const recoveryCopyContext = (a: ShareAddress, epoch: number) =>
  ({ purpose: 'share-key-recovery', scopeId: a.scopeId, nodeId: a.nodeId, shareId: a.shareId, epoch }) as const
/** A share's recovery copy as the manage list serves it: `K_share` sealed under its own value key at `sealedShareKeyEpoch`,
 *  and that key's wrap under `K_SC`. All null on a share minted without one (a legacy row) or a tombstone (ADR 0080
 *  Decision 12). */
export type RecoveryCopy = { sealedShareKey: string | null; wrappedRecoveryValueKey: string | null; sealedShareKeyEpoch: number | null }
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
  return hasLocaleText(note.texts) ? b64std(v1.sealField(SHARE_NOTE, kShare, v1.utf8(encodeShareNote(note)))) : undefined
}
/** Seal a share's optional private name under `K_share` (opaque base64). */
function sealShareName(kShare: Uint8Array, name: string): string {
  return b64std(v1.sealField(SHARE_NAME, kShare, v1.utf8(name)))
}
/** Seal the shared node's own name under `K_share` (`share-memory-name`, the title a guest reads with only the
 *  fragment key): at a mint, a re-issue and when a member renames the share's root ({@link resealShareNames}). */
function sealRootName(kShare: Uint8Array, rootName: string): string {
  return b64std(v1.sealField(SHARE_MEMORY_NAME, kShare, v1.utf8(rootName)))
}
/** Open a share name sealed under `K_share`. */
function openShareName(kShare: Uint8Array, sealed: string): string {
  return noteDecoder.decode(v1.openField(SHARE_NAME, kShare, fromB64std(sealed)))
}
/**
 * Recover `K_share` from its recovery copy: the copy's value key from its wrap under the scope key `K_SC` — the scope's
 * one key, since every rotation re-wraps it (ADR 0115) — then `K_share` under that value key. `K_SC` is a key every scope
 * member holds and **no share link does** (ADR 0036 point 4): a READ or READ_WRITE link carries the node's key, so a
 * copy under that key would open every sibling share to that link plus a dump. Bound to the share it is listed as and
 * to its epoch (ADR 0072), so a copy the server moved onto another share's row, or relabelled, does not open — nor does a
 * wrap moved there, or one a rotator could not open and staged as one nobody opens. Throws `OpenRefused`, or a plain
 * error when the share carries no copy.
 */
export function recoverShareKey(epochs: ScopeKey, share: ShareAddress, copy: RecoveryCopy): Uint8Array {
  const { sealedShareKey, sealedShareKeyEpoch: epoch } = copy
  if (sealedShareKey == null || epoch == null) throw new Error('share_key_not_recoverable')
  // The wrap is under the one key the caller holds; the copy's epoch is in both AADs, so a copy served under an epoch
  // label it was not written at — or a wrap still under a key a rotation replaced — opens under no key.
  const context = recoveryCopyContext(share, epoch)
  return openValue(epochs, context, copy.wrappedRecoveryValueKey, (valueKey) => v1.open(context, valueKey, fromB64std(sealedShareKey)))
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
  epochs: ScopeKey, share: ShareAddress, copy: RecoveryCopy, sealedPassword: string | null,
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
  epochs: ScopeKey, share: ShareAddress, copy: RecoveryCopy, sealedNote: string | null,
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
  epochs: ScopeKey, share: ShareAddress, copy: RecoveryCopy, sealedShareName: string | null,
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
  /** The drop key fields under `kShare`; a new drop private key is sealed under `rootKey`, the shared node's key as the
   *  mint holds it now (a re-read after an epoch refusal hands the re-keyed one, ADR 0109 point 6). */
  dropFields(kShare: Uint8Array, rootKey: Uint8Array): Promise<{ sealedDropPublicKey: string; sealedDropPrivateKey?: string }>
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
 * `root.key` (ADR 0036 point 4). `root` is the shared node's key with the epoch it was minted at, which the request
 * states (`nodeEpoch`, ADR 0109 point 6), behind the device floor (`rekey/floor.ts`). A rotation that moved the scope's
 * epoch, or a re-key that moved the node's, under the caller is `409 stale_epoch`: it re-opens the scope key, re-reads
 * the tree for the node's key and epoch now, and retries once (ADR 0024 Decision 6); a node not re-keyed yet
 * (`node_rekey_pending`, or the floor) is retried after a wait the same way (`writeAtNodeEpoch`).
 */
export async function mintShare(
  scopeId: string,
  nodeId: string,
  root: ShareRootKey,
  epochs: ScopeKey,
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
  let at = root
  const post = async () => {
    // The device floor inside the attempt, so a node below it is pulled forward and re-read like a server refusal.
    assertActiveScopeFloor(scopeId, { id: nodeId, epoch: at.epoch })
    // Only a READ or READ_WRITE link holds the node's key; a WRITE_ONLY link's guest never does.
    const wrap = writeOnly ? undefined : b64std(v1.seal(SHARE_KEY, kShare, at.key))
    const drop = writeOnly ? await writeOnly.dropFields(kShare, at.key) : undefined
    await withRefresh(() =>
      sharesApi().createShare({
        createShareRequest: {
          nodeEpoch: at.epoch, shareId, nodeId, role, wrap, sealedName, expiresAt: expiresAt ?? undefined, sealedNote, sealedShareName, ...recovery,
          writeVerifier: writeVerifierOf(kShare, shareId),
          passwordRequired: pw?.passwordRequired, pwSalt: pw?.pwSalt, pwVerifier: pw?.pwVerifier, sealedPassword: pw?.sealedPassword,
          sealedDropPublicKey: drop?.sealedDropPublicKey, sealedDropPrivateKey: drop?.sealedDropPrivateKey,
        },
      }),
    )
  }
  // Each refusal is retried once: a nothing-written conflict the caller can answer by re-reading what moved. A member's
  // device pulls the shared node's path forward first (ADR 0111 decision 11).
  const pull = pullRekeyPath(scopeId, [nodeId])
  let retriedDrop = false
  for (;;) {
    try {
      // A rotation moved the scope's epoch, or a re-key the node's, under us: re-open the scope key, re-read the node's
      // key and epoch, and re-seal the copy and the wrap at them (ADR 0109 point 6).
      await writeAtNodeEpoch(async (reread) => {
        if (reread) ({ root: at, recovery } = await rereadShareRoot(scopeId, nodeId, recoveryCopy))
        await post()
      }, { pull })
      return { shareId, kShare }
    } catch (e) {
      // The application's own drop-key conflict (another member set the node's drop key first): re-read and re-seal.
      if (writeOnly?.retryOnce && !retriedDrop && (await writeOnly.retryOnce(e))) {
        retriedDrop = true
        continue
      }
      throw e
    }
  }
}

/** The key of the node a share is rooted on, with the epoch it was minted at (`TreeNode.epoch`): what a mint or re-issue
 *  wraps and states (ADR 0109 point 6). */
export type ShareRootKey = { key: Uint8Array; epoch: number }

/** What the share stores of its write proof (ADR 0076): the hash of the token a guest derives from the link's key and
 *  presents when naming themself. Every role carries it — a READ link can become a READ_WRITE one, and its key
 *  never changes. */
function writeVerifierOf(kShare: Uint8Array, shareId: string): string {
  return b64std(v1.sha256Digest(v1.shareWriteToken(kShare, shareId)))
}

/** The recovery copy of [kShare] for the share at [address] (ADR 0036 point 4): sealed under a fresh value key, bound to
 *  that share id and the scope key's epoch (ADR 0072), and that value key wrapped under the scope key — what a mint and a
 *  re-issue write. A rotation re-wraps the value key and never this copy (`features/spaces/scopeRekey.ts`, ADR 0115). */
export function sealRecoveryCopy(
  address: ShareAddress, ek: ScopeKey, kShare: Uint8Array,
): { sealedShareKey: string; wrappedRecoveryValueKey: string; sealedShareKeyEpoch: number } {
  const context = recoveryCopyContext(address, ek.epoch)
  const copy = sealValue(ek, context, (valueKey) => v1.seal(context, valueKey, kShare))
  return { sealedShareKey: copy.sealed, wrappedRecoveryValueKey: copy.wrapped, sealedShareKeyEpoch: ek.epoch }
}

function recoveryCopyOf(address: ShareAddress, kShare: Uint8Array) {
  return (ek: ScopeKey) => sealRecoveryCopy(address, ek, kShare)
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
export function carriedSettings(epochs: ScopeKey, old: ReissuableShare): ReissueSettings {
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
 * — one request, one server transaction — after which this device starts the root's subtree re-key in the background
 * ({@link rekeyAfterClose}, ADR 0114 decision 9). This is what cuts a departed member off a link they could copy: the old id
 * 404s and nothing of the new one is derivable from the old `K_share`. The note, label and password are re-sealed
 * under the new key (the password with a fresh salt), the recovery copy is sealed at the scope's current epoch, and
 * the role is the old share's (the server takes it from the old row). A READ/READ_WRITE link wraps the node's key
 * (`root`, whose epoch it states); a WRITE_ONLY link takes its drop public key from [writeOnly] (which that role
 * requires). `stale_epoch` re-opens the scope key, re-reads the node's key and retries once, and `node_rekey_pending`
 * after a wait, as {@link mintShare} does. A share is re-issued at most once: `409 share_deleted`
 * ({@link isShareDeleted}) means another renew — a concurrent one or an earlier one — or a delete got there first; it
 * is never retried, and the caller re-reads the list. The caller shows the returned link once, as after a create.
 */
export async function remintShare(
  old: ReissuableShare,
  root: ShareRootKey,
  epochs: ScopeKey,
  rootName: string,
  settings: ReissueSettings = carriedSettings(epochs, old),
  writeOnly?: WriteOnlyMint,
): Promise<MintedShare> {
  requireWriteOnlyMatch(old.role, writeOnly)
  const { scopeId, nodeId } = old
  const kShare = v1.randomBytes(32) // never the old one: the old K_share is what the departed member may hold
  const shareId = newShareId()
  const sealedName = sealRootName(kShare, rootName)
  const sealedNote = sealNote(kShare, settings.note)
  const sealedShareName = settings.name.trim() ? sealShareName(kShare, settings.name) : undefined
  const pw = settings.password.trim() ? await buildPasswordFields(kShare, settings.password) : undefined
  const recoveryCopy = recoveryCopyOf({ scopeId, nodeId, shareId }, kShare)
  let recovery = recoveryCopy(epochs)
  let at = root
  const post = async () => {
    assertActiveScopeFloor(scopeId, { id: nodeId, epoch: at.epoch }) // inside the attempt: pulled and re-read as a refusal
    const wrap = writeOnly ? undefined : b64std(v1.seal(SHARE_KEY, kShare, at.key))
    const sealedDropPublicKey = writeOnly ? (await writeOnly.dropFields(kShare, at.key)).sealedDropPublicKey : undefined
    await withRefresh(() =>
      sharesApi().reissueShare({
        shareId: old.shareId,
        reissueShareRequest: {
          nodeEpoch: at.epoch, shareId, wrap, sealedDropPublicKey, sealedName, sealedNote, sealedShareName,
          expiresAt: settings.expiresAt ?? undefined, ...recovery, writeVerifier: writeVerifierOf(kShare, shareId),
          passwordRequired: pw?.passwordRequired, pwSalt: pw?.pwSalt, pwVerifier: pw?.pwVerifier, sealedPassword: pw?.sealedPassword,
        },
      }),
    )
  }
  await writeAtNodeEpoch(async (reread) => {
    if (reread) ({ root: at, recovery } = await rereadShareRoot(scopeId, nodeId, recoveryCopy))
    await post()
  }, { pull: pullRekeyPath(scopeId, [nodeId]) })
  // The old `K_share` is closed like any other (ADR 0114 decision 1); the new link follows the new key (decision 5).
  void rekeyAfterClose(old)
  return { shareId, kShare }
}

/** After an epoch refusal of a mint or re-issue: the scope re-opened (its current epoch for the recovery copy) and the
 *  tree re-read for the shared node's key and epoch now, through the device floor (`nodeKeyForWrite`, which throws
 *  `NodeKeyBelowFloorError` for a node still not re-keyed — retried as pending). */
async function rereadShareRoot<T>(
  scopeId: string, nodeId: string, seal: (ek: ScopeKey) => T,
): Promise<{ root: ShareRootKey; recovery: T }> {
  const opened = await openScopeById(scopeId)
  if (!opened) throw new Error('cannot open scope to re-seal the share key')
  try {
    const root = nodeKeyForWrite(scopeId, opened.epochs, await listTree(scopeId, opened.epochs), nodeId)
    if (!root) throw new Error('share_root_unavailable')
    return { root, recovery: seal(opened.epochs) }
  } finally {
    zeroScopeKey(opened.epochs)
  }
}

/** The edited link as the manage list serves it: its recovery copy, and what an edit needs to tell whether it reopens
 *  or closes the link (ADR 0114 decisions 6-7) — its role, its switch and its end date as stored. */
export type EditedShare = RecoveryCopy & Pick<ManagedShare, 'role' | 'enabled' | 'expiresAt'>

/** The shared node an edit that reopens a link seals its key and name from ({@link reopenFields}): its key with its own
 *  epoch, and its name. */
export type ShareRoot = { root: ShareRootKey; rootName: string }

/**
 * Edit a share in place (cr-0115/cr-0116/cr-0119/cr-0118, ADR 0036/0037): recover `K_share` from its recovery copy
 * (through its value key's wrap under `K_SC`), re-seal note + name (or clear a blank one) and PATCH the full pair — the same
 * link keeps working. The copy itself is never re-sealed here, so an edit needs no current epoch. An
 * optional `role` changes the grant in place (READ ↔ READ_WRITE; the server 422s WRITE_ONLY). `passwordChange` is
 * the tri-state gate: `{ set }` sets/changes it, `{ remove: true }` removes it, `undefined` leaves it untouched.
 * Owner-gated server-side; the link/`K_share` are untouched throughout.
 *
 * **An end date is a close, and moving it back a reopen** (ADR 0114 decisions 6-7). An edit of a switched-on link whose
 * end date has passed that moves it into the future or clears it carries what a re-activation carries
 * ({@link reopenFields}, sealed from [root]) — at the root's own epoch, re-read after `stale_epoch` and pulled forward on
 * `node_rekey_pending` like every member write. A clock behind the server's is answered `422 link_key_required` and
 * sent once more as a reopen. An end date set into the past on an open link closes it, and this device then starts the
 * root's subtree re-key ({@link rekeyAfterClose}).
 */
export async function updateShare(
  share: ShareAddress, epochs: ScopeKey, copy: EditedShare, note: ShareNote, name: string,
  expiresAt: Date | null, role?: ShareRole,
  passwordChange?: { set: string } | { remove: true },
  root?: ShareRoot,
): Promise<void> {
  const { shareId } = share
  const now = new Date()
  const reopens = reopensByDate(copy, expiresAt, now)
  if (reopens && !root) throw new Error('share_reopen_root_missing')
  const closes = isOpenLink(copy, now) && expiresAt !== null && expiresAt <= now
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
  const send = (reopen: ReopenFields | Record<string, never> = {}) =>
    withRefresh(() =>
      sharesApi().updateShare({
        // expiresAt is a full replace (like sealedNote/sealedShareName): send the current value to preserve it,
        // null to clear. The edit dialog always passes the pre-filled expiry, so an unrelated edit keeps it.
        shareId, updateShareRequest: { sealedNote, sealedShareName, expiresAt: expiresAt ?? null, role, ...passwordPatch, ...reopen },
      }),
    )
  const reopen = () => reopenAtRootEpoch({ ...share, ...copy }, root!.root, epochs, root!.rootName, send)
  if (reopens) return reopen()
  try {
    await send()
  } catch (e) {
    // The server's clock says the end date has passed where this one did not yet: the edit is a reopen after all.
    if (root && (await isCodedError(e, HttpStatus.unprocessable, 'link_key_required'))) return reopen()
    throw e
  }
  if (closes) void rekeyAfterClose({ ...share, role: copy.role })
}

/** Whether a link is **open** (ADR 0114 decision 1): switched on and its end date not passed. A managed row is never a
 *  tombstone. */
export function isOpenLink(share: Pick<ManagedShare, 'enabled' | 'expiresAt'>, now: Date = new Date()): boolean {
  return share.enabled && (share.expiresAt === null || share.expiresAt > now)
}

/** Whether an edit setting [expiresAt] reopens [share] by its end date (ADR 0114 decision 7) — the server's own rule
 *  (`SharingStore.reopensByDate`): switched on, its end date passed, the new one in the future or none. */
function reopensByDate(share: Pick<ManagedShare, 'enabled' | 'expiresAt'>, expiresAt: Date | null, now: Date): boolean {
  return share.enabled && share.expiresAt !== null && share.expiresAt <= now && (expiresAt === null || expiresAt > now)
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
  /** The note (per-locale) + name ciphertext (under `K_share`) + the recoverable `K_share` (under its own value key, wrapped under `K_SC`, at
   *  `sealedShareKeyEpoch`, ADR 0036 point 4). The dialog reads them via `readShareNote`/`readShareName` and edits
   *  them via `updateShare`. */
  sealedNote: string | null
  sealedShareName: string | null
  sealedShareKey: string | null
  /** The recovery copy's value key, wrapped under the scope key of its current epoch (ADR 0115): what opens `sealedShareKey`. */
  wrappedRecoveryValueKey: string | null
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
  /** Why a guest is not served this link now — empty exactly when one is (inbox #457). The guest gate's own answer
   *  (`ShareGuestGate.guestStops`), worded by `guestStops.ts` and drawn by `ShareGuestStops.vue` on every share row. */
  guestStops: ShareGuestStop[]
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
      wrappedRecoveryValueKey: r.wrappedRecoveryValueKey ?? null,
      sealedShareKeyEpoch: r.sealedShareKeyEpoch ?? null,
      passwordRequired: r.passwordRequired ?? false,
      sealedPassword: r.sealedPassword ?? null,
      reissueRequired: r.reissueRequired,
      deletable: r.deletable,
      newGuestsRefused: r.newGuestsRefused,
      writeVerifierMissing: r.writeVerifierMissing,
      guestStops: r.guestStops,
    }))
    .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
}

/** What a re-seal of the share names did, by share id: stored, closed (switched off or past its end date: nothing new
 *  is sealed under its `K_share`, ADR 0114 decision 5 — its reopen carries the current name), skipped (no recovery
 *  copy: a tombstone or a legacy row minted before ADR 0080 decision 12, so its title stays the old name) and failed
 *  (the copy did not open, or the write was refused). */
export type ResealReport = { resealed: string[]; closed: string[]; skipped: string[]; failed: string[] }

/**
 * After a member renamed [nodeId] to [newName], re-seal the name of every OPEN share rooted at it — the caller's own and
 * every other member's — so no link shows a stale title (ADR 0080 decision 14). A closed one is left as it is: a re-key
 * seals nothing new under a closed link (ADR 0114 decision 5), and its reopen seals the root's name then. Each
 * share's `K_share` is opened through its recovery copy under the scope key (decision 12), the new name sealed under
 * it as a mint does, and stored with `setShareSealedName`, which any member with write access may call (not the
 * owner-gated `updateShare`). Each share is its own write: one that fails is collected and the next is still tried;
 * nothing is rolled back and the rename itself is never undone (the name is last-writer-wins, the next rename
 * re-seals again). A node without shares costs one list request and writes nothing. Throws only when the list itself
 * cannot be read — then nothing was written.
 */
export async function resealShareNames(nodeId: string, newName: string, epochs: ScopeKey): Promise<ResealReport> {
  const report: ResealReport = { resealed: [], closed: [], skipped: [], failed: [] }
  const now = new Date()
  for (const share of await listSharesFor(nodeId)) {
    if (!isOpenLink(share, now)) {
      report.closed.push(share.shareId)
      continue
    }
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
    } catch (e) {
      // a link that closed since the list was read: the server refuses the re-seal (`422 link_closed`), as this loop
      // skips a closed one — nothing failed
      if (await isCodedError(e, HttpStatus.unprocessable, 'link_closed')) report.closed.push(share.shareId)
      else report.failed.push(share.shareId)
    }
  }
  return report
}

/** A link a close names: where it lives and its role, which says whether its close re-keys anything. */
export type ClosedLink = ShareAddress & { role: ShareRole }

/** The links whose root's subtree this device is re-keying after closing them, by share id (ADR 0114 decision 9) —
 *  what the share dialog says on such a link's row while it runs (`share.closedRekeying`). */
export const rekeyingClosedLinks = shallowReactive(new Set<string>())

/**
 * After the server answered a close of [link] — a revoke, a delete of an open link, a re-issue, an end date set into
 * the past — this device starts the re-key of the link root's subtree in the background (ADR 0114 decision 9): the
 * path re-key with its subtree (`rekeyPath(…, { subtree: true })`), never awaited by the caller, so the dialog does not
 * wait. Any failure — a device whose grant is behind, an owner-only node a member may not re-key, a closed tab — leaves
 * the due marks for the members' workers, which resume any scope with a due node. A `WRITE_ONLY` link's close re-keys
 * nothing (decision 2) and starts nothing. Answers the run, which never rejects (for a caller that waits on it: a test).
 */
export function rekeyAfterClose(link: ClosedLink): Promise<void> {
  if (link.role === ShareRole.WriteOnly) return Promise.resolve()
  rekeyingClosedLinks.add(link.shareId)
  return rekeyPath(link.scopeId, [link.nodeId], { subtree: true })
    .then(() => undefined, () => undefined)
    .finally(() => rekeyingClosedLinks.delete(link.shareId))
}

/** Revoke a share (soft — the next guest request 404s). A close: the server marks the root's subtree due and this
 *  device starts its re-key ({@link rekeyAfterClose}). */
export async function revokeShare(link: ClosedLink): Promise<void> {
  await withRefresh(() => sharesApi().revokeShare({ shareId: link.shareId }))
  void rekeyAfterClose(link)
}

/** Delete a share link for good (ADR 0029 §7): the server keeps only its id, so it can never be switched back on,
 *  renewed or minted again; what its guests added stays in the shared node. A delete of an OPEN link is a close
 *  ({@link rekeyAfterClose}); one already closed was marked at its close. */
export async function deleteShare(link: ClosedLink & Pick<ManagedShare, 'enabled' | 'expiresAt'>): Promise<void> {
  const open = isOpenLink(link)
  await withRefresh(() => sharesApi().deleteShare({ shareId: link.shareId }))
  if (open) void rekeyAfterClose(link)
}

/** A link to switch back on, as the manage list serves it: its address, its role and its recovery copy. */
export type ReopenedShare = ShareAddress & RecoveryCopy & { role: ShareRole }

/** The fields a reopen carries ({@link reopenFields}): the root's own epoch, its name and its key for the link's role. */
export type ReopenFields = { nodeEpoch: number; sealedName: string; wrap?: string; sealedDropPublicKey?: string }

/** What a reopen carries (ADR 0114 decision 7): what the close cleared — the root's current key under the link's
 *  `K_share` (a READ or READ_WRITE link's `wrap`, a WRITE_ONLY link's drop public key) — and the root's current name
 *  under `K_share`, with the root's own epoch as read. `K_share` comes from the recovery copy (ADR 0036 point 4): a link
 *  with none is reopened only by a re-issue. `sealedDropPrivateKey` is the root's, for a WRITE_ONLY link. */
export function reopenFields(
  share: ReopenedShare, root: ShareRootKey, epochs: ScopeKey, rootName: string, sealedDropPrivateKey?: string | null,
): ReopenFields {
  const kShare = recoverShareKey(epochs, share, share)
  try {
    const sealedName = sealRootName(kShare, rootName)
    if (share.role !== ShareRole.WriteOnly) return { nodeEpoch: root.epoch, sealedName, wrap: b64std(v1.seal(SHARE_KEY, kShare, root.key)) }
    if (!sealedDropPrivateKey) throw new Error('share_drop_key_missing')
    const { sealedDropPublicKey } = dropKeysForShare(share.scopeId, share.nodeId, root.key, kShare, sealedDropPrivateKey)
    return { nodeEpoch: root.epoch, sealedName, sealedDropPublicKey }
  } finally {
    kShare.fill(0)
  }
}

/** Re-activate a soft-revoked share and adopt its ownership (cr-0104) — any current member may take over an
 *  orphaned (disabled) share on a shared node; the guest link then resolves again, now owned by the caller. A switch-off
 *  is a close: the server cleared the link's key and the subtree was re-keyed (ADR 0114), so the reopen carries the
 *  root's current key and name under `K_share` ({@link reopenFields}) — `root` the shared node's key with its own epoch,
 *  `rootName` its name. The server refuses `409 reissue_required` for a link a departed member may hold (ADR 0075) — see
 *  {@link isReissueRequired}; such a share comes back only through a re-issue ({@link remintShare}). A WRITE_ONLY link's
 *  drop key is read from the root's row. */
export async function reactivateShare(share: ReopenedShare, root: ShareRootKey, epochs: ScopeKey, rootName: string): Promise<void> {
  await reopenAtRootEpoch(share, root, epochs, rootName, (reactivateShareRequest) =>
    withRefresh(() => sharesApi().reactivateShare({ shareId: share.shareId, reactivateShareRequest })),
  )
}

/**
 * Send a reopen's fields ({@link reopenFields}) through [send] at the root's own epoch, as every member write that seals
 * under a node's key (`writeAtNodeEpoch`): first from [root] as the caller holds it, behind the device floor; after
 * `stale_epoch` — the close's re-key replaced the root's key since the caller read it — or `node_rekey_pending` — the
 * re-key has not reached the root yet, so this device pulls its path forward first — once more from the scope and the
 * tree re-read, with the link's recovery copy as listed now (a rotation re-wraps its value key). A WRITE_ONLY link's drop key is
 * read from the root's row on every attempt, since a re-key replaces it.
 */
async function reopenAtRootEpoch(
  share: ReopenedShare, root: ShareRootKey, epochs: ScopeKey, rootName: string, send: (fields: ReopenFields) => Promise<unknown>,
): Promise<void> {
  const { scopeId, nodeId } = share
  const dropKey = () => (share.role === ShareRole.WriteOnly ? rootDropKey(scopeId, nodeId) : Promise.resolve(null))
  await writeAtNodeEpoch(async (reread) => {
    if (!reread) {
      assertActiveScopeFloor(scopeId, { id: nodeId, epoch: root.epoch })
      await send(reopenFields(share, root, epochs, rootName, await dropKey()))
      return
    }
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open scope to reopen the share')
    try {
      const now = nodeKeyForWrite(scopeId, opened.epochs, await listTree(scopeId, opened.epochs), nodeId)
      if (!now) throw new Error('share_root_unavailable')
      const listed = (await listSharesFor(nodeId)).find((s) => s.shareId === share.shareId)
      const copy = listed
        ? { sealedShareKey: listed.sealedShareKey, wrappedRecoveryValueKey: listed.wrappedRecoveryValueKey, sealedShareKeyEpoch: listed.sealedShareKeyEpoch }
        : share
      await send(reopenFields({ ...share, ...copy }, now, opened.epochs, rootName, await dropKey()))
    } finally {
      zeroScopeKey(opened.epochs)
    }
  }, { pull: pullRekeyPath(scopeId, [nodeId]) })
}

/** The root's drop private key as its row holds it (sealed under the root's key), or null. */
async function rootDropKey(scopeId: string, nodeId: string): Promise<string | null> {
  return (await fetchTreeRows(scopeId)).find((n) => n.id === nodeId)?.sealedDropPrivateKey ?? null
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
