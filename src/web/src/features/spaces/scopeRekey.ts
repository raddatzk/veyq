// A rotation's scope-level re-seal (ADR 0115 decision 2): a scope's key is REPLACED, never chained, so before its commit
// the rotating client — the only one that holds both the old key and the new one (decision 7) — opens every row sealed
// under the old scope key, re-seals it under the new one at the new epoch with the binding its purpose needs, and stages
// it under the rotation's re-key id. The commit then swaps every staged value in, or nothing: a row without a staged value
// is `409 rekey_parts_missing`, one whose ciphertext changed since it was read `409 rekey_parts_stale` — each staged value
// names the SHA-256 of the ciphertext TEXT it was opened from (`replacedDigest`), so a write between the read and the
// commit is never overwritten by a re-seal of what it replaced. What moves, all staged here but an application's rows:
//   - each scope-root node's entry (live and trashed): its own key re-wrapped, its name re-sealed, its slug token
//     recomputed, bound to its `scopeEpoch` and `placementRevision + 1` — the node's own key is replaced afterwards by
//     its node re-key (`core/node/rekey/rekeyScope.ts`; every node is due from the commit on);
//   - a group's name and a space's icon, re-sealed at the new epoch;
//   - each pending group invitee's name key — the NEW `K_name` sealed to the invitee's identity, only to one the rotation
//     anchored (the key it wraps the grant to, ADR 0028 Decision 2); an invitee it holds as an anomaly, or anchors no key
//     for, gets its name key CLEARED instead — an omission, allowed and visible (ADR 0111 decision 9): the proposal
//     stands, shown to the invitee without the group's name;
//   - each link's recovery copy, at the new epoch (the server keeps the epoch the link was minted at beside it);
//   - a tenant's member profiles (name, picture, join record — each field as it is, the picture's constant pad kept) and
//     verifications (the signed statement inside unchanged, the seal at the new epoch);
//   - every application's rows, through its scope re-key participant (`registry.scopeRekeyParticipants()`: Memories'
//     item marks) — core never names an application's tables (ADR 0079 decision 3).
// One rule for every kind (decision 2): a value that does not open under the old key is staged as a seal nobody opens
// ({@link unopenable}) — the purpose's own seal at the new epoch, of the replaced value's length, under a random key
// dropped at once — with the digest of what it replaces. Every reader shows it as it showed the value before: no name,
// the letter, an unnamed group, an ignored verification, a tree that refuses the row, a link whose key is lost (its
// root's node re-key then closes it, ADR 0114). One unreadable value must not stop every rotation of the scope, and
// nothing readable is lost.
// Operator-blind: every value is opened and sealed on this device; the server learns no key, and nothing it would not
// learn from the commit (decision 9).
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { shallowReactive } from 'vue'
import { rekeyApi } from '../../api/client'
import type {
  ScopeRekeyInviteeSeal, ScopeRekeyMember, ScopeRekeyMemberSeal, ScopeRekeyParts, ScopeRekeyRootEntry, ScopeRekeyRootNode,
  ScopeRekeyStage, ScopeRekeyVerification, ScopeRekeyVerificationSeal,
} from '../../api/generated/content'
import { withRefresh } from '../../app/sessionRefresh'
import { registry } from '../../core/applications/registry'
import type { ScopeRekeyContext } from '../../core/node/rekey/hooks'
import { fetchTreeRows, openContentKey, openName, sealName, slugTokenFor, wrapContentKey } from '../../core/node/tree'
import { recoverShareKey, sealRecoveryCopy } from '../../core/sharing/manageShares'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { resealMemberAvatar } from '../account/profile/memberAvatar'
import { resealMemberName } from '../account/profile/memberName'
import type { ScopeKey } from './epochs'
import { recipientIdentityDigest } from './grant'
import { deriveGroupNameKey, openGroupNameWith, sealGroupName, wrapNameKeyToInvitee } from './inviteName'
import { deriveSpaceIconKey, openIcon, sealIcon } from './spaceIcon'

/** The stage's caps per call (`ScopeRekeyStage`): every list 256 but a member's profile, whose picture is 16 KiB. */
export const SCOPE_STAGE_BATCH = 256
export const MEMBER_STAGE_BATCH = 128

/** How often a commit refused `rekey_parts_stale` / `rekey_parts_missing` is staged again and retried — a row written
 *  while the rotation staged. A bound, never a loop: a scope whose rows keep moving gets this many, then the refusal. */
export const REKEY_RESTAGES = 2

/** The scopes whose scope-level rows this device is re-sealing right now — what a panel shows the rotating person while
 *  it runs (`spaceLock.resealing`): with many members it takes a moment, and the page must stay open. */
export const resealingScopes = shallowReactive(new Set<string>())

/** Whether a refused stage or commit is answered by staging again: a row written since it was read
 *  (`rekey_parts_stale`), or one that appeared since (`rekey_parts_missing`). */
export async function isRestageable(e: unknown): Promise<boolean> {
  return (await isCodedError(e, HttpStatus.conflict, 'rekey_parts_stale'))
    || isCodedError(e, HttpStatus.conflict, 'rekey_parts_missing')
}

/** SHA-256 of a served ciphertext's text, base64 — every staged value's `replacedDigest` (the contract's definition). */
export function replacedDigestOf(ciphertext: string): string {
  return b64std(v1.sha256Digest(v1.utf8(ciphertext)))
}

/** What one staging run needs: the scope, its kind, the re-key id, the old key and the one minted, and the identity key
 *  each member the rotation anchored is granted under — a pending invitee's name key goes to that key only. */
export type ScopeLevelRekey = {
  scopeId: string
  rekeyId: string
  oldKey: ScopeKey
  newKey: ScopeKey
  /** userId → the identity bundle (base64) the rotation wraps that member's grant to; a member it declared pending is absent. */
  anchoredKeys: ReadonlyMap<string, string>
}

/**
 * Stage every scope-level row under [run.rekeyId] (see the file header): core's, page by page from
 * `listScopeRekeyParts`, then each application's through its participant. Staging twice replaces what the first run
 * staged, so a refused commit is answered by running this again. A value that does not open under the old key is staged
 * as one nobody opens ({@link unopenable}), never a reason to stop.
 */
export async function stageScopeLevelRekey(run: ScopeLevelRekey): Promise<void> {
  resealingScopes.add(run.scopeId)
  try {
    const kindOf = await rootKinds(run.scopeId)
    let since: string | undefined
    for (let first = true; ; first = false) {
      const page = await withRefresh(() => rekeyApi().listScopeRekeyParts({ scopeId: run.scopeId, since, limit: SCOPE_STAGE_BATCH }))
      for (const stage of await stagesOf(run, page, kindOf, first)) {
        await withRefresh(() => rekeyApi().stageScopeRekey({ scopeId: run.scopeId, rekeyId: run.rekeyId, scopeRekeyStage: stage }))
      }
      if (!page.more || !page.cursor) break
      since = page.cursor
    }
    const ctx: ScopeRekeyContext = {
      scopeId: run.scopeId, rekeyId: run.rekeyId, oldKey: run.oldKey, newKey: run.newKey, digestOf: replacedDigestOf,
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

/** One listed page re-sealed into the stages that carry it, each within the caps. */
async function stagesOf(
  run: ScopeLevelRekey, page: ScopeRekeyParts, kindOf: (nodeId: string) => Promise<string>, first: boolean,
): Promise<ScopeRekeyStage[]> {
  const { scopeId, oldKey, newKey } = run
  const rootNodes: ScopeRekeyRootEntry[] = []
  for (const n of page.rootNodes) rootNodes.push(resealRootEntry(scopeId, oldKey, newKey, n, n.trashed ? null : await kindOf(n.nodeId)))
  const groupInvitees = page.groupInvitees.map((i): ScopeRekeyInviteeSeal => {
    const to = run.anchoredKeys.get(i.userId)
    const replacedDigest = replacedDigestOf(i.sealedNameKey)
    // A name key goes only to the key the rotation anchored and grants to (ADR 0028 Decision 2). An invitee it declared
    // pending — its key an anomaly here, or none anchored — gets none: its name key is cleared (both nulls), never sealed
    // to an anomaly and never left unstaged, which the commit would refuse (`rekey_parts_missing`) for every rotation
    // of the group while the proposal stood.
    if (!to) return { userId: i.userId, sealedNameKey: null, recipientIdentityDigest: null, replacedDigest }
    const nameKey = deriveGroupNameKey(newKey.key)
    try {
      return {
        userId: i.userId, sealedNameKey: wrapNameKeyToInvitee(nameKey, scopeId, newKey.epoch, to),
        recipientIdentityDigest: recipientIdentityDigest(to), replacedDigest,
      }
    } finally {
      nameKey.fill(0)
    }
  })
  const shares = page.shares.map((s) => {
    const address = { scopeId, nodeId: s.nodeId, shareId: s.shareId }
    const replacedDigest = replacedDigestOf(s.sealedShareKey)
    let kShare: Uint8Array
    try {
      kShare = recoverShareKey(oldKey, address, s)
    } catch {
      // A copy nobody opens: the link's key is lost to every member, and its root's node re-key closes it as such
      // (ADR 0114) — never random bytes under the new key, which would open as a K_share the link does not hold.
      const sealedShareKey = unopenable(s.sealedShareKey, (key, length) =>
        sealRecoveryCopy(address, { epoch: newKey.epoch, key }, v1.randomBytes(length)).sealedShareKey)
      return { shareId: s.shareId, sealedShareKey, replacedDigest }
    }
    try {
      return { shareId: s.shareId, sealedShareKey: sealRecoveryCopy(address, newKey, kShare).sealedShareKey, replacedDigest }
    } finally {
      kShare.fill(0)
    }
  })
  const members = page.members.map((m) => resealMember(scopeId, oldKey, newKey, m))
  const verifications = page.verifications.map((v) => resealVerification(scopeId, oldKey, newKey, v))
  const stages: ScopeRekeyStage[] = []
  const batches = Math.max(
    1,
    ...[rootNodes, groupInvitees, shares, verifications].map((l) => Math.ceil(l.length / SCOPE_STAGE_BATCH)),
    Math.ceil(members.length / MEMBER_STAGE_BATCH),
  )
  for (let i = 0; i < batches; i++) {
    const slice = <T>(l: T[], cap = SCOPE_STAGE_BATCH) => l.slice(i * cap, (i + 1) * cap)
    const stage: ScopeRekeyStage = {
      fromEpoch: oldKey.epoch,
      groupName: first && i === 0 && page.groupName ? resealGroupName(scopeId, oldKey, newKey, page.groupName) : null,
      icon: first && i === 0 && page.icon ? resealIcon(scopeId, oldKey, newKey, page.icon) : null,
      rootNodes: slice(rootNodes), groupInvitees: slice(groupInvitees), shares: slice(shares),
      members: slice(members, MEMBER_STAGE_BATCH), verifications: slice(verifications),
    }
    const empty = !stage.groupName && !stage.icon && ![stage.rootNodes, stage.groupInvitees, stage.shares, stage.members, stage.verifications]
      .some((l) => l && l.length)
    if (!empty) stages.push(stage)
  }
  return stages
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
      replacedDigest: replacedDigestOf(n.wrappedContentKey),
    }
  } finally {
    nodeKey?.fill(0)
  }
}

/** The group's name at the new epoch; one that does not open is staged as one nobody opens — the unnamed group. */
function resealGroupName(scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, g: { encryptedName: string; nameEpoch: number }) {
  const oldName = deriveGroupNameKey(oldKey.key)
  const newName = deriveGroupNameKey(newKey.key)
  try {
    const name = orNull(() => openGroupNameWith(oldName, scopeId, g.nameEpoch, g.encryptedName))
    const encryptedName = name !== null
      ? sealGroupName(newName, scopeId, newKey.epoch, name)
      : unopenable(g.encryptedName, (key, length) => sealGroupName(key, scopeId, newKey.epoch, 'x'.repeat(length)))
    return { encryptedName, replacedDigest: replacedDigestOf(g.encryptedName) }
  } finally {
    oldName.fill(0)
    newName.fill(0)
  }
}

/** The space's icon at the new epoch; one that does not open is staged as one nobody opens — the letter. Every icon is
 *  padded to one length, so the stand-in is too. */
function resealIcon(scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, i: { sealedIcon: string; iconEpoch: number }) {
  const oldIcon = deriveSpaceIconKey(oldKey.key)
  const newIcon = deriveSpaceIconKey(newKey.key)
  const at = { scopeId, iconEpoch: newKey.epoch }
  try {
    const bytes = openIcon(oldIcon, { scopeId, iconEpoch: i.iconEpoch }, i.sealedIcon)
    const sealedIcon = bytes ? sealIcon(newIcon, at, bytes) : unopenable('', (key) => sealIcon(key, at, new Uint8Array(0)))
    return { sealedIcon, replacedDigest: replacedDigestOf(i.sealedIcon) }
  } finally {
    oldIcon.fill(0)
    newIcon.fill(0)
  }
}

/**
 * A member's profile fields under the new tenant key, each exactly where the row holds one, with the digest it was
 * opened from. A field that does not open under the old key — what every reader shows as no name, the letter, no join
 * record — is staged as one nobody opens ({@link unopenable}), which reads exactly the same; the picture keeps its
 * constant length that way (the contract's `maxLength`, ADR 0084).
 */
function resealMember(tenantId: string, oldKey: ScopeKey, newKey: ScopeKey, m: ScopeRekeyMember): ScopeRekeyMemberSeal {
  const address = { scopeId: tenantId, userId: m.userId }
  const unreadable = (purpose: 'member-name' | 'member-avatar' | 'member-join-record', sealed: string) => () =>
    unopenable(sealed, (key, length) => b64std(v1.sealField({ purpose, ...address }, key, v1.randomBytes(length))))
  const out: ScopeRekeyMemberSeal = { userId: m.userId }
  if (m.sealedName) {
    const sealed = m.sealedName
    out.sealedName = orUnreadable(() => resealMemberName(oldKey.key, newKey.key, address, sealed), unreadable('member-name', sealed))
    out.replacedNameDigest = replacedDigestOf(sealed)
  }
  if (m.sealedAvatar) {
    const sealed = m.sealedAvatar
    out.sealedAvatar = orUnreadable(() => resealMemberAvatar(oldKey.key, newKey.key, address, sealed), unreadable('member-avatar', sealed))
    out.replacedAvatarDigest = replacedDigestOf(sealed)
  }
  if (m.sealedJoinRecord) {
    const sealed = m.sealedJoinRecord
    const joinAddress = { tenantId, userId: m.userId }
    out.sealedJoinRecord = orUnreadable(
      () => b64std(invite.sealJoinRecord(newKey.key, joinAddress, invite.openJoinRecord(oldKey.key, joinAddress, fromB64std(sealed)))),
      unreadable('member-join-record', sealed),
    )
    out.replacedJoinRecordDigest = replacedDigestOf(sealed)
  }
  return out
}

/** A verification under the new tenant key at the new epoch, the signed statement inside unchanged; one that does not
 *  open — which every reader ignores — staged as one nobody opens (the rule of every kind). */
function resealVerification(tenantId: string, oldKey: ScopeKey, newKey: ScopeKey, v: ScopeRekeyVerification): ScopeRekeyVerificationSeal {
  const address = { tenantId, verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId }
  const sealedStatement = orUnreadable(
    () => b64std(verification.sealVerification(newKey.key, { ...address, epoch: newKey.epoch },
      verification.openVerification(oldKey.key, { ...address, epoch: v.epoch }, fromB64std(v.sealedStatement)))),
    () => unopenable(v.sealedStatement, (key, length) => b64std(v1.sealField({
      purpose: 'member-verification', scopeId: tenantId, userId: v.verifierUserId, subjectUserId: v.subjectUserId, epoch: newKey.epoch,
    }, key, v1.randomBytes(length)))),
  )
  return { verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId, sealedStatement, replacedDigest: replacedDigestOf(v.sealedStatement) }
}

/**
 * The stand-in for a value the rotator cannot open (ADR 0115 decision 2): [seal] — the purpose's own seal, its binding
 * at the new epoch — under a fresh random key dropped at once, so it opens under no key anyone holds, of the length of
 * [replaced] (its plaintext the replaced ciphertext's length less the seal's overhead). Never random bytes under the new
 * key: those would open, as a name of garbage, or for a recovery copy or a root entry's wrap as a key that is no one's.
 * The server cannot tell the stand-in from a re-seal.
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

function orUnreadable(reseal: () => string, unreadable: () => string): string {
  try {
    return reseal()
  } catch {
    return unreadable()
  }
}
