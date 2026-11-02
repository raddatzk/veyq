// A rotation's scope-level move (ADR 0115 decision 2, `scopeRekey.ts`): a rotation re-wraps keys and never rewrites a
// value. Every authored value the listing names is listed as its value key's wrap only; the stage carries that same key
// wrapped under the new scope key at the new epoch, bound to the value's address, with the digest of the wrap text it
// replaces — and the value itself is neither read nor sent, so it stays byte-identical and opens through the new wrap.
// Each against a control that the old key no longer opens the moved wrap. A scope-root entry is the one thing re-sealed.
// An application's values go through its registered participant, handed only the re-wrap.
import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import * as verification from '@veyq/crypto/verification'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { fakeApi } from '../../test/apiMock'

vi.mock('../../api/client', () => ({ rekeyApi: vi.fn(), contentApi: vi.fn() }))

import { rekeyApi, contentApi } from '../../api/client'
import type { ContentApi, RekeyApi, ScopeRekeyParts, ScopeRekeyStage } from '../../api/generated/content'
import { createRegistry } from '../../core/applications/registry'
import * as registryModule from '../../core/applications/registry'
import type { ScopeRekeyParticipant } from '../../core/node/rekey/hooks'
import { openContentKey, openName, sealName, slugTokenFor, wrapContentKey } from '../../core/node/tree'
import { FOLDER_KIND } from '../../core/node/kind'
import { recoverShareKey, recoveryCopyContext, sealRecoveryCopy } from '../../core/sharing/manageShares'
import { memberNameContext, openMemberName, sealName as sealMemberName } from '../account/profile/memberName'
import { memberAvatarContext, openMemberAvatar, sealAvatar } from '../account/profile/memberAvatar'
import { groupNameContext, openGroupNameWith, sealGroupName } from './inviteName'
import { iconContext, openIcon, sealIcon } from './spaceIcon'
import { openValue, openValueKey, sealValue } from './valueKey'
import { SCOPE_STAGE_BATCH, replacedDigestOf, resealingScopes, stageScopeLevelRekey } from './scopeRekey'
import type { ScopeKey } from './epochs'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f1'
const ROOT = '5c09e000-0000-4000-8000-0000000000a1'
const TRASHED = '5c09e000-0000-4000-8000-0000000000a2'
const MEMBER = '5c09e000-0000-4000-8000-0000000000b2'
const SUBJECT = '5c09e000-0000-4000-8000-0000000000b3'
const SHARE = 'S'.repeat(43)

const OLD: ScopeKey = { epoch: 3, key: v1.randomBytes(32) }
const NEW: ScopeKey = { epoch: 4, key: v1.randomBytes(32) }
/** The epoch the group's name was written at — an earlier rotation already moved its wrap to OLD and left the name. */
const NAME_EPOCH = 2

/** A root entry as the scope key seals it: its own key wrapped, its name sealed, both bound to (scope, node, its scope
 *  epoch, its placement revision). */
function rootEntry(nodeId: string, nodeKey: Uint8Array, name: string, trashed: boolean) {
  const at = { scopeId: SCOPE, nodeId, scopeEpoch: 1, placementRevision: 5 }
  return {
    nodeId, trashed, placementRevision: 5, scopeEpoch: 1,
    encryptedName: sealName(OLD.key, at, name), wrappedContentKey: wrapContentKey(OLD.key, at, nodeKey),
  }
}

const rootKey = v1.randomBytes(32)
const trashedKey = v1.randomBytes(32)
const kShare = v1.randomBytes(32)
const iconBytes = new Uint8Array([1, 2, 3, 4])
const joinRecordKey = invite.inviteKeyFromSeed(new Uint8Array(32).fill(5))
const record: invite.JoinRecord = {
  identityPublicKey: v1.serializeIdentityPublic(v1.generateIdentity().pub), secretKeyProofPublicKey: new Uint8Array(32).fill(2),
  inviteId: '5c09e000-0000-4000-8000-0000000000c1', invitePublicKey: v1.serializeIdentityPublic(joinRecordKey.pub),
  inviteKeySignature: new Uint8Array(64).fill(3), inviteExpiresAt: 1_900_000_000, signerUserId: SUBJECT,
  signerIdentityPublicKey: v1.serializeIdentityPublic(v1.generateIdentity().pub),
  inviteSignature: new Uint8Array(64).fill(4), identitySignature: new Uint8Array(64).fill(6),
}
const statement: verification.Verification = { subjectHead: new Uint8Array(32).fill(7), verifierHead: new Uint8Array(32).fill(8), signature: new Uint8Array(64).fill(9) }

// Each value as its author wrote it — under a value key of its own, its wrap under OLD — and as the server keeps it: the
// rotation never reads these ciphertexts, so they are the bytes every reader opens after it.
const memberAddress = { scopeId: SCOPE, userId: MEMBER }
const joinAddress = { tenantId: SCOPE, userId: MEMBER }
const slot = { tenantId: SCOPE, verifierUserId: MEMBER, subjectUserId: SUBJECT, epoch: OLD.epoch }
const shareAddress = { scopeId: SCOPE, nodeId: ROOT, shareId: SHARE }
const groupName = sealValue(OLD, groupNameContext(SCOPE, NAME_EPOCH), (k) => fromB64std(sealGroupName(k, SCOPE, NAME_EPOCH, 'Familie')))
const icon = sealValue(OLD, iconContext({ scopeId: SCOPE, iconEpoch: OLD.epoch }), (k) => fromB64std(sealIcon(k, { scopeId: SCOPE, iconEpoch: OLD.epoch }, iconBytes)))
const copy = sealRecoveryCopy(shareAddress, OLD, kShare)
const name = sealMemberName(OLD, memberAddress, { firstName: 'Ada', lastName: 'L' })
const avatar = sealAvatar(OLD, memberAddress, iconBytes)
const joinRecord = sealValue(OLD, invite.joinRecordContext(joinAddress), (k) => invite.sealJoinRecord(k, joinAddress, record))
const verified = sealValue(OLD, verification.verificationContext(slot), (k) => verification.sealVerification(k, slot, statement))
/** Every value ciphertext the scope holds — none of which a stage may carry. */
const VALUES = [groupName.sealed, icon.sealed, copy.sealedShareKey, name.sealedName, avatar.sealedAvatar, joinRecord.sealed, verified.sealed]

/** The listing as the server serves it: each value's wrap with its address, never the value; each root entry whole. */
function served(): ScopeRekeyParts {
  return {
    currentEpoch: OLD.epoch,
    groupName: { nameEpoch: NAME_EPOCH, wrappedNameValueKey: groupName.wrapped },
    icon: { iconEpoch: OLD.epoch, wrappedIconValueKey: icon.wrapped },
    rootNodes: [rootEntry(ROOT, rootKey, 'Reisen', false), rootEntry(TRASHED, trashedKey, 'Alt', true)],
    shares: [{ shareId: SHARE, nodeId: ROOT, sealedShareKeyEpoch: copy.sealedShareKeyEpoch, wrappedRecoveryValueKey: copy.wrappedRecoveryValueKey }],
    members: [{
      userId: MEMBER, wrappedNameValueKey: name.wrappedNameValueKey, wrappedAvatarValueKey: avatar.wrappedAvatarValueKey,
      wrappedJoinRecordValueKey: joinRecord.wrapped,
    }],
    verifications: [{ verifierUserId: MEMBER, subjectUserId: SUBJECT, epoch: OLD.epoch, wrappedValueKey: verified.wrapped }],
    more: false,
    cursor: 'c1',
  }
}

let staged: ScopeRekeyStage[]
let participant: Mock<ScopeRekeyParticipant>

beforeEach(() => {
  staged = []
  participant = vi.fn<ScopeRekeyParticipant>(async () => {})
  const reg = createRegistry()
  vi.spyOn(registryModule.registry, 'scopeRekeyParticipants').mockReturnValue([participant])
  vi.spyOn(registryModule.registry, 'tokenInputFor').mockImplementation(reg.tokenInputFor)
  fakeApi(contentApi, {
    listNodes: vi.fn<ContentApi['listNodes']>().mockResolvedValue([{
      id: ROOT, kind: FOLDER_KIND, parentId: null, epoch: 0, scopeEpoch: 1, encryptedName: 'x', slugToken: 'x', orderIndex: 0,
      wrappedContentKey: 'x', metaRevision: 0, placementRevision: 5, createdAt: new Date(0), blockedByOperator: false,
    }]),
  })
})

function api(pages: ScopeRekeyParts[]) {
  return fakeApi(rekeyApi, {
    listScopeRekeyParts: vi.fn<RekeyApi['listScopeRekeyParts']>(async ({ since }) => pages[since ? Number(since.slice(1)) : 0]),
    stageScopeRekey: vi.fn<RekeyApi['stageScopeRekey']>(async ({ scopeRekeyStage }) => { staged.push(scopeRekeyStage) }),
  })
}

const run = () => stageScopeLevelRekey({ scopeId: SCOPE, rekeyId: 'r-1', oldKey: OLD, newKey: NEW })
const len = (b64: string) => fromB64std(b64).length

describe('stageScopeLevelRekey — every value key re-wrapped under the new key, no value rewritten (ADR 0115 decision 2)', () => {
  it('stages each kind once — wraps only, the same key under the new one at the new epoch, naming the digest it replaces', async () => {
    const page = served()
    const mocks = api([page])
    await run()
    expect(mocks.stageScopeRekey).toHaveBeenCalledWith(expect.objectContaining({ scopeId: SCOPE, rekeyId: 'r-1' }))
    expect(staged).toHaveLength(1)
    const [s] = staged
    expect(s.fromEpoch).toBe(OLD.epoch)
    // a pending invitee's name key is sealed to the invitee, not under the scope key: there is no invitee stage
    expect(s).not.toHaveProperty('groupInvitees')

    // the group name's key: under the new key, still bound to the epoch the name was written at; the name unchanged
    const nameCtx = groupNameContext(SCOPE, NAME_EPOCH)
    expect(Object.keys(s.groupName!).sort()).toEqual(['replacedDigest', 'wrappedNameValueKey'])
    expect(openValue(NEW, nameCtx, s.groupName!.wrappedNameValueKey, (k) => openGroupNameWith(k, SCOPE, NAME_EPOCH, groupName.sealed))).toBe('Familie')
    expect(() => openValueKey(OLD, nameCtx, s.groupName!.wrappedNameValueKey)).toThrow(v1.OpenRefused)
    expect(() => openValueKey(NEW, nameCtx, page.groupName!.wrappedNameValueKey)).toThrow(v1.OpenRefused) // the old wrap is shut under NEW
    expect(s.groupName!.replacedDigest).toBe(replacedDigestOf(page.groupName!.wrappedNameValueKey))

    // the icon's key, the icon unchanged
    const iconAt = { scopeId: SCOPE, iconEpoch: OLD.epoch }
    expect(Object.keys(s.icon!).sort()).toEqual(['replacedDigest', 'wrappedIconValueKey'])
    expect(openValue(NEW, iconContext(iconAt), s.icon!.wrappedIconValueKey, (k) => openIcon(k, iconAt, icon.sealed))).toEqual(iconBytes)
    expect(s.icon!.replacedDigest).toBe(replacedDigestOf(page.icon!.wrappedIconValueKey))

    // each root entry — the one thing re-sealed: the node's own key re-wrapped and its name re-sealed at its scope epoch
    // and the next revision
    const [live, trashed] = s.rootNodes!
    const at = { scopeId: SCOPE, nodeId: ROOT, scopeEpoch: 1, placementRevision: 6 }
    expect(openContentKey(SCOPE, NEW.key, { id: ROOT, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: live.wrappedContentKey })).toEqual(rootKey)
    expect(openName(NEW.key, at, live.encryptedName)).toBe('Reisen')
    expect(() => openContentKey(SCOPE, OLD.key, { id: ROOT, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: live.wrappedContentKey })).toThrow()
    expect(live.slugToken).toBe(slugTokenFor(NEW.key, SCOPE, FOLDER_KIND, 'Reisen'))
    expect(live).toMatchObject({ placementRevision: 5, scopeEpoch: 1, replacedDigest: replacedDigestOf(page.rootNodes[0].wrappedContentKey) })
    expect(trashed.slugToken).toBeNull() // a trashed node's slot holds its own id
    expect(openContentKey(SCOPE, NEW.key, { id: TRASHED, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: trashed.wrappedContentKey })).toEqual(trashedKey)

    // the recovery copy's key: K_share recovers through it under the new key, from the copy as it always was
    const [share] = s.shares!
    expect(Object.keys(share).sort()).toEqual(['replacedDigest', 'shareId', 'wrappedRecoveryValueKey'])
    const moved = { ...copy, wrappedRecoveryValueKey: share.wrappedRecoveryValueKey }
    expect(recoverShareKey(NEW, shareAddress, moved)).toEqual(kShare)
    expect(() => recoverShareKey(OLD, shareAddress, moved)).toThrow(v1.OpenRefused)
    expect(share.replacedDigest).toBe(replacedDigestOf(page.shares[0].wrappedRecoveryValueKey))

    // the member's profile fields' keys, each with the digest of the wrap it replaces; the fields themselves unchanged
    const [m] = s.members!
    expect(Object.keys(m).sort()).toEqual([
      'replacedAvatarDigest', 'replacedJoinRecordDigest', 'replacedNameDigest', 'userId',
      'wrappedAvatarValueKey', 'wrappedJoinRecordValueKey', 'wrappedNameValueKey',
    ])
    expect(openMemberName(NEW, memberAddress, name.sealedName, m.wrappedNameValueKey)).toEqual({ firstName: 'Ada', lastName: 'L' })
    expect(openMemberName(OLD, memberAddress, name.sealedName, m.wrappedNameValueKey)).toBeNull()
    expect(openMemberName(NEW, memberAddress, name.sealedName, name.wrappedNameValueKey)).toBeNull() // the replaced wrap
    expect(openMemberAvatar(NEW, memberAddress, avatar.sealedAvatar, m.wrappedAvatarValueKey)).toEqual(iconBytes)
    expect(openValue(NEW, invite.joinRecordContext(joinAddress), m.wrappedJoinRecordValueKey, (k) =>
      invite.openJoinRecord(k, joinAddress, fromB64std(joinRecord.sealed)))).toEqual(record)
    expect(m).toMatchObject({
      replacedNameDigest: replacedDigestOf(name.wrappedNameValueKey),
      replacedAvatarDigest: replacedDigestOf(avatar.wrappedAvatarValueKey),
      replacedJoinRecordDigest: replacedDigestOf(joinRecord.wrapped),
    })

    // the verification's key, the row at the epoch it was written at, the signed statement inside unchanged
    const [v] = s.verifications!
    expect(openValue(NEW, verification.verificationContext(slot), v.wrappedValueKey, (k) =>
      verification.openVerification(k, slot, fromB64std(verified.sealed)))).toEqual(statement)
    expect(v.replacedDigest).toBe(replacedDigestOf(verified.wrapped))
  })

  it('carries no value: not one ciphertext the scope holds is in any stage, so every value stays byte-identical', async () => {
    api([served()])
    await run()
    const body = JSON.stringify(staged)
    for (const value of VALUES) expect(body.includes(value)).toBe(false)
    // control: what the stage does carry is in it — the root entry's name, which a rotation re-seals
    expect(body.includes(staged[0].rootNodes![0].encryptedName)).toBe(true)
    // every staged wrap is a wrap: of one 32-byte value key, the length a write of the value sent
    expect(len(staged[0].members![0].wrappedAvatarValueKey!)).toBe(len(avatar.wrappedAvatarValueKey))
  })

  it('hands every application only the re-wrap — no scope key — with the same re-key id, after core staged', async () => {
    api([served()])
    await run()
    expect(participant).toHaveBeenCalledTimes(1)
    const ctx = participant.mock.calls[0][0]
    expect(ctx).toMatchObject({ scopeId: SCOPE, rekeyId: 'r-1', fromEpoch: OLD.epoch })
    expect(ctx).not.toHaveProperty('oldKey')
    expect(ctx).not.toHaveProperty('newKey')
    // the re-wrap moves an application's value key from OLD to NEW at its address …
    const marks = { purpose: 'item-favorites', scopeId: SCOPE, nodeId: ROOT } as const
    const k = v1.generateValueKey()
    const wrapped = b64std(v1.wrapValueKey(OLD.key, { scopeId: SCOPE, epoch: OLD.epoch, value: marks }, k))
    expect(openValueKey(NEW, marks, ctx.rewrap(marks, wrapped))).toEqual(k)
    // … and one that does not open under OLD becomes one nobody opens, at the same slot
    const foreign = b64std(v1.wrapValueKey(v1.randomBytes(32), { scopeId: SCOPE, epoch: OLD.epoch, value: marks }, k))
    expect(() => openValueKey(NEW, marks, ctx.rewrap(marks, foreign))).toThrow(v1.OpenRefused)
    expect(ctx.digestOf('abc')).toBe(b64std(v1.sha256Digest(v1.utf8('abc'))))
    expect(staged).toHaveLength(1) // control: core's stage came first
  })

  it('pages through the listing with its cursor and keeps the cap per stage', async () => {
    const many = served()
    many.rootNodes = []
    many.shares = []
    many.verifications = []
    const member = many.members[0]
    many.members = Array.from({ length: SCOPE_STAGE_BATCH + 1 }, () => ({ ...member, wrappedAvatarValueKey: null, wrappedJoinRecordValueKey: null }))
    many.more = true
    many.cursor = 'p1'
    // a later page that (wrongly) serves the name again is not staged twice: the name is the first page's
    const second = { ...served(), more: false, cursor: null }
    const mocks = api([many, second])
    await run()
    expect(mocks.listScopeRekeyParts.mock.calls.map(([r]) => r.since)).toEqual([undefined, 'p1'])
    expect(staged.map((s) => s.members!.length)).toEqual([SCOPE_STAGE_BATCH, 1, 1])
    expect(staged.filter((s) => s.groupName).length).toBe(1) // the name rides on the first page only
    expect(staged[0].groupName).toBeTruthy()
    expect(staged.filter((s) => s.icon).length).toBe(1)
  })

  // One rule for every kind (ADR 0115 decision 2): a wrap the rotator cannot open is staged as one nobody opens — at the
  // same slot, of the same length, with its digest — so one unreadable value stops no rotation and reads as before.
  it('stages a member\'s wrap that does not open as the stand-in, so one member cannot stop every rotation', async () => {
    const page = served()
    // the server (or a member) put a wrap of some other key at this slot, under no key the rotator holds
    page.members[0].wrappedNameValueKey = b64std(v1.wrapValueKey(v1.randomBytes(32),
      { scopeId: SCOPE, epoch: OLD.epoch, value: memberNameContext(memberAddress) }, v1.generateValueKey()))
    api([page])
    await run()
    const [m] = staged[0].members!
    expect(openMemberName(NEW, memberAddress, name.sealedName, m.wrappedNameValueKey)).toBeNull()
    for (const key of [NEW, OLD]) {
      expect(() => openValueKey(key, memberNameContext(memberAddress), m.wrappedNameValueKey!), 'under no key').toThrow(v1.OpenRefused)
    }
    expect(len(m.wrappedNameValueKey!)).toBe(len(page.members[0].wrappedNameValueKey!))
    expect(m.replacedNameDigest).toBe(replacedDigestOf(page.members[0].wrappedNameValueKey!))
    // control: the readable field beside it re-wrapped as ever
    expect(openMemberAvatar(NEW, memberAddress, avatar.sealedAvatar, m.wrappedAvatarValueKey)).toEqual(iconBytes)
  })

  it('a wrap moved onto another value\'s slot is staged as the stand-in — that value reads as unreadable, never as the other', async () => {
    const page = served()
    // the picture's wrap served as the name's: it opens under OLD at the picture's slot only
    page.members[0].wrappedNameValueKey = avatar.wrappedAvatarValueKey
    api([page])
    await run()
    const [m] = staged[0].members!
    expect(openMemberName(NEW, memberAddress, name.sealedName, m.wrappedNameValueKey)).toBeNull()
    expect(() => openValueKey(NEW, memberNameContext(memberAddress), m.wrappedNameValueKey!)).toThrow(v1.OpenRefused)
    expect(() => openValueKey(NEW, memberAvatarContext(memberAddress), m.wrappedNameValueKey!)).toThrow(v1.OpenRefused)
    expect(openMemberAvatar(NEW, memberAddress, avatar.sealedAvatar, m.wrappedAvatarValueKey)).toEqual(iconBytes) // control
  })

  it('stages a root entry whose name or key wrap does not open as one nobody opens, and the readable field beside it as ever', async () => {
    const page = served()
    const was = { scopeId: SCOPE, scopeEpoch: 1, placementRevision: 5 }
    page.rootNodes[0].encryptedName = sealName(v1.randomBytes(32), { ...was, nodeId: ROOT }, 'Reisen') // the live root's name
    page.rootNodes[1].wrappedContentKey = wrapContentKey(v1.randomBytes(32), { ...was, nodeId: TRASHED }, trashedKey) // the trashed one's key
    api([page])
    await run()
    expect(staged).toHaveLength(1)
    const [live, trashed] = staged[0].rootNodes!
    const at = (nodeId: string) => ({ scopeId: SCOPE, nodeId, scopeEpoch: 1, placementRevision: 6 })

    // the live root: its name opens under no key, its slug token is under none either; its key re-wrapped (the control)
    for (const key of [NEW.key, OLD.key]) expect(() => openName(key, at(ROOT), live.encryptedName)).toThrow(v1.OpenRefused)
    expect(len(live.encryptedName)).toBe(len(page.rootNodes[0].encryptedName))
    expect(live.slugToken).toHaveLength(slugTokenFor(NEW.key, SCOPE, FOLDER_KIND, 'x').length)
    expect(openContentKey(SCOPE, NEW.key, { id: ROOT, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: live.wrappedContentKey })).toEqual(rootKey)
    expect(live.replacedDigest).toBe(replacedDigestOf(page.rootNodes[0].wrappedContentKey))

    // the trashed root: its key wrap opens under no key, its name re-sealed (the control)
    const wrapAt = { id: TRASHED, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: trashed.wrappedContentKey }
    for (const key of [NEW.key, OLD.key]) expect(() => openContentKey(SCOPE, key, wrapAt)).toThrow(v1.OpenRefused)
    expect(len(trashed.wrappedContentKey)).toBe(len(page.rootNodes[1].wrappedContentKey))
    expect(openName(NEW.key, at(TRASHED), trashed.encryptedName)).toBe('Alt')
    expect(trashed.replacedDigest).toBe(replacedDigestOf(page.rootNodes[1].wrappedContentKey))
  })

  it('stages a group name\'s, an icon\'s and a recovery copy\'s wrap that do not open as ones nobody opens, each with its digest', async () => {
    const page = served()
    const elsewhere = { epoch: OLD.epoch, key: v1.randomBytes(32) } // a key the rotator does not hold
    const strayName = sealValue(elsewhere, groupNameContext(SCOPE, NAME_EPOCH), (k) => fromB64std(sealGroupName(k, SCOPE, NAME_EPOCH, 'Familie')))
    const strayIcon = sealValue(elsewhere, iconContext({ scopeId: SCOPE, iconEpoch: OLD.epoch }), (k) => fromB64std(sealIcon(k, { scopeId: SCOPE, iconEpoch: OLD.epoch }, iconBytes)))
    const strayCopy = sealRecoveryCopy(shareAddress, elsewhere, kShare)
    page.groupName = { nameEpoch: NAME_EPOCH, wrappedNameValueKey: strayName.wrapped }
    page.icon = { iconEpoch: OLD.epoch, wrappedIconValueKey: strayIcon.wrapped }
    page.shares = [{ ...page.shares[0], wrappedRecoveryValueKey: strayCopy.wrappedRecoveryValueKey }]
    api([page])
    await run()
    expect(staged).toHaveLength(1)
    const [s] = staged

    // the group name: its key under no key — the unnamed group — as long as the wrap it replaces
    expect(() => openValue(NEW, groupNameContext(SCOPE, NAME_EPOCH), s.groupName!.wrappedNameValueKey, (k) =>
      openGroupNameWith(k, SCOPE, NAME_EPOCH, strayName.sealed))).toThrow(v1.OpenRefused)
    expect(len(s.groupName!.wrappedNameValueKey)).toBe(len(strayName.wrapped))
    expect(s.groupName!.replacedDigest).toBe(replacedDigestOf(strayName.wrapped))

    // the icon: the letter
    expect(() => openValueKey(NEW, iconContext({ scopeId: SCOPE, iconEpoch: OLD.epoch }), s.icon!.wrappedIconValueKey)).toThrow(v1.OpenRefused)
    expect(s.icon!.replacedDigest).toBe(replacedDigestOf(strayIcon.wrapped))

    // the recovery copy: no K_share at all, never one of random bytes under the new key — the link's key is lost, and
    // its root's node re-key closes it
    const [share] = s.shares!
    expect(() => recoverShareKey(NEW, shareAddress, { ...strayCopy, wrappedRecoveryValueKey: share.wrappedRecoveryValueKey })).toThrow(v1.OpenRefused)
    expect(() => openValueKey(NEW, recoveryCopyContext(shareAddress, OLD.epoch), share.wrappedRecoveryValueKey)).toThrow(v1.OpenRefused)
    expect(len(share.wrappedRecoveryValueKey)).toBe(len(strayCopy.wrappedRecoveryValueKey))
    expect(share.replacedDigest).toBe(replacedDigestOf(strayCopy.wrappedRecoveryValueKey))

    // control on the same page: the root entries re-sealed and the member's keys re-wrapped as ever
    expect(openName(NEW.key, { scopeId: SCOPE, nodeId: ROOT, scopeEpoch: 1, placementRevision: 6 }, s.rootNodes![0].encryptedName)).toBe('Reisen')
    expect(openMemberName(NEW, memberAddress, name.sealedName, s.members![0].wrappedNameValueKey)).toEqual({ firstName: 'Ada', lastName: 'L' })
  })

  it('marks the scope as re-sealing while it runs, and not after', async () => {
    let during = false
    fakeApi(rekeyApi, {
      listScopeRekeyParts: vi.fn<RekeyApi['listScopeRekeyParts']>(async () => {
        during = resealingScopes.has(SCOPE)
        return { ...served(), groupName: null, icon: null, rootNodes: [], shares: [], members: [], verifications: [] }
      }),
      stageScopeRekey: vi.fn<RekeyApi['stageScopeRekey']>(async () => {}),
    })
    await run()
    expect(during).toBe(true)
    expect(resealingScopes.has(SCOPE)).toBe(false)
  })
})
