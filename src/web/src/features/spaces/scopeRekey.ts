// A rotation's scope-level move (ADR 0115 decision 2): a scope's key is REPLACED, never chained, so before its commit the
// rotating client — the only one that holds both the old key and the new one (decision 7) — moves everything under the
// old scope key to the new one and stages it under the rotation's re-key id. **A rotation re-wraps keys and never
// rewrites a value** (owner 2026-11-02): every scope-level value that has an author is sealed under a value key `K_v` of
// its own (`./valueKey.ts`), so what moves is only `K_v`'s wrap — the value's ciphertext stays byte-identical, and a
// rotator, owner or member alike, can make a value unreadable but never rewrite another member's. The server refuses a
// stage that writes a value (`422 rekey_writes_value`). The commit swaps every staged wrap in, or nothing: a wrap without
// a staged replacement is `409 rekey_parts_missing`, one that changed since it was read `409 rekey_parts_stale` — each
// staged wrap names the wrap it replaces as it was read (`replacedValueKey`), a root entry its placement revision; every
// write of a value mints a fresh `K_v` and so a fresh wrap, so a write between the read and the commit is never
// overwritten by a re-wrap of the key it replaced. What moves, all staged here but an application's:
//   - each scope-root node's entry (live and trashed) — the one thing re-sealed rather than re-wrapped, since any member
//     may write a root entry (ADR 0111 decision 11) and its slug token is a keyed MAC under the scope key: its own key
//     re-wrapped, its name re-sealed, its slug token recomputed, bound to its `scopeEpoch` and `placementRevision + 1` —
//     the node's own key is replaced afterwards by its node re-key (`core/node/rekey/rekeyScope.ts`);
//   - a group's name's and a space's icon's value keys;
//   - each link's recovery copy's value key (the copy, and the epoch it was written at, stay);
//   - a tenant's member profiles' value keys (name, picture, join record — each where the row holds one) and each
//     verification's;
//   - every application's value keys, through its scope re-key participant (`registry.scopeRekeyParticipants()`:
//     Memories' item marks), handed only the re-wrap — core never names an application's tables (ADR 0079 decision 3).
// A pending group invitee's name key is sealed to the invitee, not under the scope key: a rotation leaves it, and a
// rename hands the invitees the new name's key (`scopes.ts` `renameGroup`).
// One rule for every kind (decision 2): what does not open under the old key is staged as one nobody opens — a wrap under
// a key dropped at once (`rewrapValueKey`), a root entry's seal under one ({@link unopenable}) — beside what it
// replaces. Every reader shows it as it showed the value before: no name, the letter, an unnamed group, an ignored
// verification, a tree that refuses the row, a link whose key is lost (its root's node re-key then closes it, ADR 0114).
// One unreadable value must not stop every rotation of the scope, and nothing readable is lost.
// Operator-blind: every key is opened and wrapped on this device; the server learns no key, and nothing it would not
// learn from the commit (decision 9).
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { fromB64std } from '@veyq/crypto/base64'
import { shallowReactive } from 'vue'
import { rekeyApi } from '../../api/client'
import type {
  ScopeRekeyMember, ScopeRekeyMemberWrap, ScopeRekeyParts, ScopeRekeyRewrap, ScopeRekeyRootEntry, ScopeRekeyRootNode,
  ScopeRekeyStage, ScopeRekeyVerification, ScopeRekeyVerificationWrap,
} from '../../api/generated/content'
import { withRefresh } from '../../app/sessionRefresh'
import { registry } from '../../core/applications/registry'
import type { ScopeRekeyContext } from '../../core/node/rekey/hooks'
import { fetchTreeRows, openContentKey, openName, sealName, slugTokenFor, wrapContentKey } from '../../core/node/tree'
import { recoveryCopyContext } from '../../core/sharing/manageShares'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { memberAvatarContext } from '../account/profile/memberAvatar'
import { memberNameContext } from '../account/profile/memberName'
import type { ScopeKey } from './epochs'
import { groupNameContext } from './inviteName'
import { iconContext } from './spaceIcon'
import { rewrapValueKey } from './valueKey'

/** The listing's page (`listScopeRekeyParts`' `limit`, every kind together) and so each stage's: the stage caps every
 *  kind at 256 too, so one page is one stage. */
export const SCOPE_STAGE_BATCH = 256

/** How often a commit refused `rekey_parts_stale` / `rekey_parts_missing` is staged again and retried — a row written
 *  while the rotation staged. A bound, never a loop: a scope whose rows keep moving gets this many, then the refusal. */
export const REKEY_RESTAGES = 2

/** The scopes whose scope-level keys this device is moving right now — what a panel shows the rotating person while it
 *  runs (`spaceLock.resealing`): with many members it takes a moment, and the page must stay open. */
export const resealingScopes = shallowReactive(new Set<string>())

/** Whether a refused stage or commit is answered by staging again: a row written since it was read
 *  (`rekey_parts_stale`), or one that appeared since (`rekey_parts_missing`). */
export async function isRestageable(e: unknown): Promise<boolean> {
  return (await isCodedError(e, HttpStatus.conflict, 'rekey_parts_stale'))
    || isCodedError(e, HttpStatus.conflict, 'rekey_parts_missing')
}

/** What one staging run needs: the scope, the re-key id, and the old key and the one minted. */
export type ScopeLevelRekey = {
  scopeId: string
  rekeyId: string
  oldKey: ScopeKey
  newKey: ScopeKey
}

/**
 * Stage every scope-level move under [run.rekeyId] (see the file header): core's, page by page from
 * `listScopeRekeyParts`, then each application's through its participant. Staging twice replaces what the first run
 * staged, so a refused commit is answered by running this again. A wrap or a root entry that does not open under the old
 * key is staged as one nobody opens, never a reason to stop.
 */
export async function stageScopeLevelRekey(run: ScopeLevelRekey): Promise<void> {
  resealingScopes.add(run.scopeId)
  try {
    const kindOf = await rootKinds(run.scopeId)
    let since: string | undefined
    for (let first = true; ; first = false) {
      const page = await withRefresh(() => rekeyApi().listScopeRekeyParts({ scopeId: run.scopeId, since, limit: SCOPE_STAGE_BATCH }))
      const stage = await stageOf(run, page, kindOf, first)
      if (stage) await withRefresh(() => rekeyApi().stageScopeRekey({ scopeId: run.scopeId, rekeyId: run.rekeyId, scopeRekeyStage: stage }))
      if (!page.more || !page.cursor) break
      since = page.cursor
    }
    const ctx: ScopeRekeyContext = {
      scopeId: run.scopeId,
      rekeyId: run.rekeyId,
      fromEpoch: run.oldKey.epoch,
      rewrap: (value, wrapped) => rewrapValueKey(run.oldKey, run.newKey, value, wrapped),
    }
    for (const participant of registry.scopeRekeyParticipants()) await participant(ctx)
  } finally {
    resealingScopes.delete(run.scopeId)
  }
}

/** The kind of every live scope-root node (its slug token is the kind's, `slugTokenFor`): the parts listing serves none,
 *  so it is read from the tree, once per run and once more for a root created since. */
async function rootKinds(scopeId: string): Promise<(nodeId: string) => Promise<string>> {
  let kinds: Map<string, string> | null = null
  let reread = false
  const read = async () => new Map((await fetchTreeRows(scopeId)).filter((r) => r.parentId === null).map((r) => [r.id, r.kind]))
  return async (nodeId) => {
    kinds ??= await read()
    if (!kinds.has(nodeId) && !reread) {
      reread = true
      kinds = await read()
    }
    const kind = kinds.get(nodeId)
    if (kind === undefined) throw new Error(`scope rekey: the root node ${nodeId} is not in the tree`)
    return kind
  }
}

/** One listed page moved into the stage that carries it, or null for a page with nothing to move. */
async function stageOf(
  run: ScopeLevelRekey, page: ScopeRekeyParts, kindOf: (nodeId: string) => Promise<string>, first: boolean,
): Promise<ScopeRekeyStage | null> {
  const { scopeId, oldKey, newKey } = run
  const rewrap = (value: v1.ValueContext, wrapped: string): ScopeRekeyRewrap =>
    ({ wrappedValueKey: rewrapValueKey(oldKey, newKey, value, wrapped), replacedValueKey: wrapped })
  const rootNodes: ScopeRekeyRootEntry[] = []
  for (const n of page.rootNodes) rootNodes.push(resealRootEntry(scopeId, oldKey, newKey, n, n.trashed ? null : await kindOf(n.nodeId)))
  // A wrap nobody opens loses the link its key: its root's node re-key closes it as such (ADR 0114) — never a wrap under
  // the new key of a key that is no one's, which would open as a K_share the link does not hold.
  const shares = page.shares.map((s) => ({
    shareId: s.shareId,
    ...rewrap(recoveryCopyContext({ scopeId, nodeId: s.nodeId, shareId: s.shareId }, s.sealedShareKeyEpoch), s.wrappedRecoveryValueKey),
  }))
  const members = page.members.map((m) => rewrapMember(scopeId, rewrap, m))
  const verifications = page.verifications.map((v) => rewrapVerification(scopeId, rewrap, v))
  // The name and the icon ride on the first page only.
  const groupName = first && page.groupName
    ? rewrap(groupNameContext(scopeId, page.groupName.nameEpoch), page.groupName.wrappedNameValueKey) : null
  const icon = first && page.icon ? rewrap(iconContext({ scopeId, iconEpoch: page.icon.iconEpoch }), page.icon.wrappedIconValueKey) : null
  if (!groupName && !icon && ![rootNodes, shares, members, verifications].some((l) => l.length)) return null
  return { fromEpoch: oldKey.epoch, groupName, icon, rootNodes, shares, members, verifications }
}

/** A scope-root entry under the new key: the node's unchanged key re-wrapped, its name re-sealed and — for a live node —
 *  its slug token recomputed, bound to its `scopeEpoch` and the next placement revision. A name or a key wrap that does
 *  not open under the old key is staged as one nobody opens, a name's slug token as one under no held key: the tree
 *  refuses the row as it did before the rotation, and no member can re-key the node, as before. */
function resealRootEntry(
  scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, n: ScopeRekeyRootNode, kind: string | null,
): ScopeRekeyRootEntry {
  const now = { scopeId, nodeId: n.nodeId, scopeEpoch: n.scopeEpoch, placementRevision: n.placementRevision }
  const at = { ...now, placementRevision: n.placementRevision + 1 }
  const name = orNull(() => openName(oldKey.key, now, n.encryptedName))
  const nodeKey = orNull(() => openContentKey(scopeId, oldKey.key, { id: n.nodeId, ...now, wrappedContentKey: n.wrappedContentKey }))
  try {
    return {
      nodeId: n.nodeId, placementRevision: n.placementRevision, scopeEpoch: n.scopeEpoch,
      encryptedName: name !== null
        ? sealName(newKey.key, at, name)
        : unopenable(n.encryptedName, (key, length) => sealName(key, at, 'x'.repeat(length))),
      slugToken: kind === null ? null : name !== null
        ? slugTokenFor(newKey.key, scopeId, kind, name)
        : unopenable('', (key) => slugTokenFor(key, scopeId, kind, n.nodeId)),
      wrappedContentKey: nodeKey
        ? wrapContentKey(newKey.key, at, nodeKey)
        : unopenable(n.wrappedContentKey, (key, length) => wrapContentKey(key, at, v1.randomBytes(length))),
    }
  } finally {
    nodeKey?.fill(0)
  }
}

type Rewrap = (value: v1.ValueContext, wrapped: string) => ScopeRekeyRewrap

/**
 * A member's profile fields' value keys under the new tenant key, each exactly where the row holds one, beside the wrap
 * it replaces. The name, the picture and the join record themselves stay byte-identical — the picture keeps its constant
 * length (ADR 0084) because it is not touched at all. A wrap that does not open under the old key is staged as one
 * nobody opens, which every reader shows as before: no name, the letter, no join record.
 */
function rewrapMember(tenantId: string, rewrap: Rewrap, m: ScopeRekeyMember): ScopeRekeyMemberWrap {
  const address = { scopeId: tenantId, userId: m.userId }
  const out: ScopeRekeyMemberWrap = { userId: m.userId }
  if (m.wrappedNameValueKey) out.name = rewrap(memberNameContext(address), m.wrappedNameValueKey)
  if (m.wrappedAvatarValueKey) out.avatar = rewrap(memberAvatarContext(address), m.wrappedAvatarValueKey)
  if (m.wrappedJoinRecordValueKey) {
    out.joinRecord = rewrap(invite.joinRecordContext({ tenantId, userId: m.userId }), m.wrappedJoinRecordValueKey)
  }
  return out
}

/** A verification's value key under the new tenant key; the row — bound to the epoch it was written at — stays
 *  byte-identical, the signed statement inside unchanged. A wrap that does not open is staged as one nobody opens, and
 *  every reader ignores the row as before. */
function rewrapVerification(tenantId: string, rewrap: Rewrap, v: ScopeRekeyVerification): ScopeRekeyVerificationWrap {
  const slot = { tenantId, verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId, epoch: v.epoch }
  return { verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId, ...rewrap(verification.verificationContext(slot), v.wrappedValueKey) }
}

/**
 * The stand-in for a scope-root entry's seal the rotator cannot open (ADR 0115 decision 2): [seal] — the purpose's own
 * seal, its binding at the new epoch — under a fresh random key dropped at once, so it opens under no key anyone holds, of
 * the length of [replaced] (its plaintext the replaced ciphertext's length less the seal's overhead). Never random bytes
 * under the new key: those would open, as a name of garbage, or for a root entry's wrap as a key that is no one's. The
 * server cannot tell the stand-in from a re-seal. A value key's wrap has its own, `valueKey.ts` `rewrapValueKey`.
 */
export function unopenable(replaced: string, seal: (key: Uint8Array, length: number) => string): string {
  const key = v1.randomBytes(32)
  try {
    const overhead = byteLength(seal(key, 0))
    return seal(key, Math.max(0, byteLength(replaced) - overhead))
  } finally {
    key.fill(0)
  }
}

function byteLength(b64: string): number {
  try {
    return fromB64std(b64).length
  } catch {
    return 0
  }
}

function orNull<T>(open: () => T): T | null {
  try {
    return open()
  } catch {
    return null
  }
}
