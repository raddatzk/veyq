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
//     anchored (the key it wraps the grant to, ADR 0028 Decision 2);
//   - each link's recovery copy, at the new epoch (the server keeps the epoch the link was minted at beside it);
//   - a tenant's member profiles (name, picture, join record — each field as it is, the picture's constant pad kept) and
//     verifications (the signed statement inside unchanged, the seal at the new epoch);
//   - every application's rows, through its scope re-key participant (`registry.scopeRekeyParticipants()`: Memories'
//     item marks) — core never names an application's tables (ADR 0079 decision 3).
// Operator-blind: every value is opened and sealed on this device; the server learns no key, and nothing it would not
// learn from the commit (decision 9).
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { shallowReactive } from 'vue'
import { rekeyApi } from '../../api/client'
import type {
  ScopeRekeyMember, ScopeRekeyMemberSeal, ScopeRekeyParts, ScopeRekeyRootEntry, ScopeRekeyRootNode, ScopeRekeyStage,
  ScopeRekeyVerification, ScopeRekeyVerificationSeal,
} from '../../api/generated/content'
import { withRefresh } from '../../app/sessionRefresh'
import { registry } from '../../core/applications/registry'
import type { ScopeRekeyContext } from '../../core/node/rekey/hooks'
import { fetchTreeRows, openContentKey, openName, sealName, slugTokenFor, wrapContentKey } from '../../core/node/tree'
import { recoverShareKey, sealRecoveryCopy } from '../../core/sharing/manageShares'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { AVATAR_PADDED_LEN, resealMemberAvatar } from '../account/profile/memberAvatar'
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
 * staged, so a refused commit is answered by running this again. Throws on a row that does not open under the old key
 * (the scope then keeps its key; the server would refuse the commit for the missing row anyway).
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
  const groupInvitees = page.groupInvitees.map((i) => {
    const to = run.anchoredKeys.get(i.userId)
    // A name key goes only to the key the rotation anchored and grants to (ADR 0028 Decision 2): an invitee it declared
    // pending gets none, and the commit refuses the rotation for it (`rekey_parts_missing`) rather than seal to an anomaly.
    if (!to) throw new Error(`scope rekey: the pending invitee ${i.userId} is not anchored on this device`)
    const nameKey = deriveGroupNameKey(newKey.key)
    try {
      return {
        userId: i.userId, sealedNameKey: wrapNameKeyToInvitee(nameKey, scopeId, newKey.epoch, to),
        recipientIdentityDigest: recipientIdentityDigest(to), replacedDigest: replacedDigestOf(i.sealedNameKey),
      }
    } finally {
      nameKey.fill(0)
    }
  })
  const shares = page.shares.map((s) => {
    const address = { scopeId, nodeId: s.nodeId, shareId: s.shareId }
    const kShare = recoverShareKey(oldKey, address, s)
    try {
      return { shareId: s.shareId, sealedShareKey: sealRecoveryCopy(address, newKey, kShare).sealedShareKey, replacedDigest: replacedDigestOf(s.sealedShareKey) }
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
 *  its slug token recomputed, bound to its `scopeEpoch` and the next placement revision. */
function resealRootEntry(
  scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, n: ScopeRekeyRootNode, kind: string | null,
): ScopeRekeyRootEntry {
  const now = { scopeId, nodeId: n.nodeId, scopeEpoch: n.scopeEpoch, placementRevision: n.placementRevision }
  const name = openName(oldKey.key, now, n.encryptedName)
  const nodeKey = openContentKey(scopeId, oldKey.key, { id: n.nodeId, ...now, wrappedContentKey: n.wrappedContentKey })
  try {
    const at = { ...now, placementRevision: n.placementRevision + 1 }
    return {
      nodeId: n.nodeId, placementRevision: n.placementRevision, scopeEpoch: n.scopeEpoch,
      encryptedName: sealName(newKey.key, at, name),
      slugToken: kind === null ? null : slugTokenFor(newKey.key, scopeId, kind, name),
      wrappedContentKey: wrapContentKey(newKey.key, at, nodeKey),
      replacedDigest: replacedDigestOf(n.wrappedContentKey),
    }
  } finally {
    nodeKey.fill(0)
  }
}

function resealGroupName(scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, g: { encryptedName: string; nameEpoch: number }) {
  const oldName = deriveGroupNameKey(oldKey.key)
  const newName = deriveGroupNameKey(newKey.key)
  try {
    const name = openGroupNameWith(oldName, scopeId, g.nameEpoch, g.encryptedName)
    return { encryptedName: sealGroupName(newName, scopeId, newKey.epoch, name), replacedDigest: replacedDigestOf(g.encryptedName) }
  } finally {
    oldName.fill(0)
    newName.fill(0)
  }
}

function resealIcon(scopeId: string, oldKey: ScopeKey, newKey: ScopeKey, i: { sealedIcon: string; iconEpoch: number }) {
  const oldIcon = deriveSpaceIconKey(oldKey.key)
  const newIcon = deriveSpaceIconKey(newKey.key)
  try {
    const bytes = openIcon(oldIcon, { scopeId, iconEpoch: i.iconEpoch }, i.sealedIcon)
    if (!bytes) throw new v1.OpenRefused('space-icon')
    return { sealedIcon: sealIcon(newIcon, { scopeId, iconEpoch: newKey.epoch }, bytes), replacedDigest: replacedDigestOf(i.sealedIcon) }
  } finally {
    oldIcon.fill(0)
    newIcon.fill(0)
  }
}

/**
 * A member's profile fields under the new tenant key, each exactly where the row holds one, with the digest it was
 * opened from. A field that does not open under the old key — what every reader shows as no name, the letter, no join
 * record — is staged as a seal of random bytes that reads exactly the same: one member's unreadable field must not stop
 * every rotation of the tenant, and nothing readable is lost (the server cannot tell either seal from the other).
 */
function resealMember(tenantId: string, oldKey: ScopeKey, newKey: ScopeKey, m: ScopeRekeyMember): ScopeRekeyMemberSeal {
  const address = { scopeId: tenantId, userId: m.userId }
  const unreadable = (purpose: 'member-name' | 'member-avatar' | 'member-join-record', length: number) =>
    b64std(v1.sealField({ purpose, ...address }, newKey.key, v1.randomBytes(length)))
  const out: ScopeRekeyMemberSeal = { userId: m.userId }
  if (m.sealedName) {
    const sealed = m.sealedName
    out.sealedName = orUnreadable(() => resealMemberName(oldKey.key, newKey.key, address, sealed), () => unreadable('member-name', 32))
    out.replacedNameDigest = replacedDigestOf(sealed)
  }
  if (m.sealedAvatar) {
    const sealed = m.sealedAvatar
    // the picture's constant length holds for the unreadable one too (the contract's `maxLength`, ADR 0084)
    out.sealedAvatar = orUnreadable(() => resealMemberAvatar(oldKey.key, newKey.key, address, sealed), () => unreadable('member-avatar', AVATAR_PADDED_LEN))
    out.replacedAvatarDigest = replacedDigestOf(sealed)
  }
  if (m.sealedJoinRecord) {
    const sealed = m.sealedJoinRecord
    const joinAddress = { tenantId, userId: m.userId }
    out.sealedJoinRecord = orUnreadable(
      () => b64std(invite.sealJoinRecord(newKey.key, joinAddress, invite.openJoinRecord(oldKey.key, joinAddress, fromB64std(sealed)))),
      () => unreadable('member-join-record', 32),
    )
    out.replacedJoinRecordDigest = replacedDigestOf(sealed)
  }
  return out
}

/** A verification under the new tenant key at the new epoch, the signed statement inside unchanged; one that does not
 *  open — which every reader ignores — re-sealed as random bytes that read the same (the profile fields' rule). */
function resealVerification(tenantId: string, oldKey: ScopeKey, newKey: ScopeKey, v: ScopeRekeyVerification): ScopeRekeyVerificationSeal {
  const address = { tenantId, verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId }
  const sealedStatement = orUnreadable(
    () => b64std(verification.sealVerification(newKey.key, { ...address, epoch: newKey.epoch },
      verification.openVerification(oldKey.key, { ...address, epoch: v.epoch }, fromB64std(v.sealedStatement)))),
    () => b64std(v1.sealField({
      purpose: 'member-verification', scopeId: tenantId, userId: v.verifierUserId, subjectUserId: v.subjectUserId, epoch: newKey.epoch,
    }, newKey.key, v1.randomBytes(32))),
  )
  return { verifierUserId: v.verifierUserId, subjectUserId: v.subjectUserId, sealedStatement, replacedDigest: replacedDigestOf(v.sealedStatement) }
}

function orUnreadable(reseal: () => string, unreadable: () => string): string {
  try {
    return reseal()
  } catch {
    return unreadable()
  }
}
