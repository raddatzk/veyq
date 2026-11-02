// A rotation's scope-level re-seal (ADR 0115 decision 2, `scopeRekey.ts`): every row kind the listing serves is opened
// under the old scope key, sealed under the new one at the new epoch with its own binding, and staged with the digest of
// the ciphertext text it replaces — each against a control that the old key no longer opens the re-sealed value, and
// that the value served moved nowhere else. An application's rows go through its registered participant.
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
import { recoverShareKey, sealRecoveryCopy } from '../../core/sharing/manageShares'
import { openMemberName, sealName as sealMemberName } from '../account/profile/memberName'
import { openMemberAvatar, sealAvatar } from '../account/profile/memberAvatar'
import { deriveGroupNameKey, openGroupNameWith, sealGroupName, wrapNameKeyToInvitee } from './inviteName'
import { deriveSpaceIconKey, openIcon, sealIcon } from './spaceIcon'
import { recipientIdentityDigest } from './grant'
import { MEMBER_STAGE_BATCH, replacedDigestOf, resealingScopes, stageScopeLevelRekey } from './scopeRekey'
import type { ScopeKey } from './epochs'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f1'
const ROOT = '5c09e000-0000-4000-8000-0000000000a1'
const TRASHED = '5c09e000-0000-4000-8000-0000000000a2'
const INVITEE = '5c09e000-0000-4000-8000-0000000000b1'
const MEMBER = '5c09e000-0000-4000-8000-0000000000b2'
const SUBJECT = '5c09e000-0000-4000-8000-0000000000b3'
const SHARE = 'S'.repeat(43)

const OLD: ScopeKey = { epoch: 3, key: v1.randomBytes(32) }
const NEW: ScopeKey = { epoch: 4, key: v1.randomBytes(32) }
const INVITEE_ID = v1.generateIdentity()
const inviteeB64 = b64std(v1.serializeIdentityPublic(INVITEE_ID.pub))

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

function served(): ScopeRekeyParts {
  return {
    currentEpoch: OLD.epoch,
    groupName: { encryptedName: sealGroupName(deriveGroupNameKey(OLD.key), SCOPE, OLD.epoch, 'Familie'), nameEpoch: OLD.epoch },
    icon: { sealedIcon: sealIcon(deriveSpaceIconKey(OLD.key), { scopeId: SCOPE, iconEpoch: OLD.epoch }, iconBytes), iconEpoch: OLD.epoch },
    rootNodes: [rootEntry(ROOT, rootKey, 'Reisen', false), rootEntry(TRASHED, trashedKey, 'Alt', true)],
    groupInvitees: [{ userId: INVITEE, sealedNameKey: wrapNameKeyToInvitee(deriveGroupNameKey(OLD.key), SCOPE, OLD.epoch, inviteeB64) }],
    shares: [{ shareId: SHARE, nodeId: ROOT, ...sealRecoveryCopy({ scopeId: SCOPE, nodeId: ROOT, shareId: SHARE }, OLD, kShare) }],
    members: [{
      userId: MEMBER,
      sealedName: sealMemberName(OLD.key, { scopeId: SCOPE, userId: MEMBER }, { firstName: 'Ada', lastName: 'L' }),
      sealedAvatar: sealAvatar(OLD.key, { scopeId: SCOPE, userId: MEMBER }, iconBytes),
      sealedJoinRecord: b64std(invite.sealJoinRecord(OLD.key, { tenantId: SCOPE, userId: MEMBER }, record)),
    }],
    verifications: [{
      verifierUserId: MEMBER, subjectUserId: SUBJECT, epoch: OLD.epoch,
      sealedStatement: b64std(verification.sealVerification(OLD.key, { tenantId: SCOPE, verifierUserId: MEMBER, subjectUserId: SUBJECT, epoch: OLD.epoch }, statement)),
    }],
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

const run = (anchoredKeys = new Map([[INVITEE, inviteeB64]])) =>
  stageScopeLevelRekey({ scopeId: SCOPE, rekeyId: 'r-1', oldKey: OLD, newKey: NEW, anchoredKeys })

describe('stageScopeLevelRekey — every scope-level row under the new key (ADR 0115 decision 2)', () => {
  it('stages each row kind once, re-sealed under the new key at the new epoch, naming the digest it replaces', async () => {
    const page = served()
    const mocks = api([page])
    await run()
    expect(mocks.stageScopeRekey).toHaveBeenCalledWith(expect.objectContaining({ scopeId: SCOPE, rekeyId: 'r-1' }))
    expect(staged).toHaveLength(1)
    const [s] = staged
    expect(s.fromEpoch).toBe(OLD.epoch)

    // the group name and the icon at the new epoch
    expect(openGroupNameWith(deriveGroupNameKey(NEW.key), SCOPE, NEW.epoch, s.groupName!.encryptedName)).toBe('Familie')
    expect(() => openGroupNameWith(deriveGroupNameKey(OLD.key), SCOPE, OLD.epoch, s.groupName!.encryptedName)).toThrow()
    expect(s.groupName!.replacedDigest).toBe(replacedDigestOf(page.groupName!.encryptedName))
    expect(openIcon(deriveSpaceIconKey(NEW.key), { scopeId: SCOPE, iconEpoch: NEW.epoch }, s.icon!.sealedIcon)).toEqual(iconBytes)
    expect(s.icon!.replacedDigest).toBe(replacedDigestOf(page.icon!.sealedIcon))

    // each root entry: the node's own key re-wrapped and its name re-sealed at its scope epoch and the next revision
    const [live, trashed] = s.rootNodes!
    const at = { scopeId: SCOPE, nodeId: ROOT, scopeEpoch: 1, placementRevision: 6 }
    expect(openContentKey(SCOPE, NEW.key, { id: ROOT, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: live.wrappedContentKey })).toEqual(rootKey)
    expect(openName(NEW.key, at, live.encryptedName)).toBe('Reisen')
    expect(() => openContentKey(SCOPE, OLD.key, { id: ROOT, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: live.wrappedContentKey })).toThrow()
    expect(live.slugToken).toBe(slugTokenFor(NEW.key, SCOPE, FOLDER_KIND, 'Reisen'))
    expect(live).toMatchObject({ placementRevision: 5, scopeEpoch: 1, replacedDigest: replacedDigestOf(page.rootNodes[0].wrappedContentKey) })
    expect(trashed.slugToken).toBeNull() // a trashed node's slot holds its own id
    expect(openContentKey(SCOPE, NEW.key, { id: TRASHED, scopeEpoch: 1, placementRevision: 6, wrappedContentKey: trashed.wrappedContentKey })).toEqual(trashedKey)

    // the invitee's name key: the NEW K_name sealed to the anchored key it is granted under
    const [inv] = s.groupInvitees!
    const nameKey = v1.openAs({ purpose: 'group-name-key', scopeId: SCOPE, nameEpoch: NEW.epoch }, v1.hybridSecret(INVITEE_ID.priv), fromB64std(inv.sealedNameKey!))
    expect(nameKey).toEqual(deriveGroupNameKey(NEW.key))
    expect(inv.recipientIdentityDigest).toBe(recipientIdentityDigest(inviteeB64))
    expect(inv.replacedDigest).toBe(replacedDigestOf(page.groupInvitees[0].sealedNameKey))

    // the recovery copy at the new epoch
    const [share] = s.shares!
    expect(recoverShareKey(NEW, { scopeId: SCOPE, nodeId: ROOT, shareId: SHARE }, { sealedShareKey: share.sealedShareKey, sealedShareKeyEpoch: NEW.epoch })).toEqual(kShare)
    expect(share.replacedDigest).toBe(replacedDigestOf(page.shares[0].sealedShareKey))

    // the member's profile fields, each with the digest of the field it was opened from
    const [m] = s.members!
    const address = { scopeId: SCOPE, userId: MEMBER }
    expect(openMemberName(NEW, address, m.sealedName)).toEqual({ firstName: 'Ada', lastName: 'L' })
    expect(openMemberName(OLD, address, m.sealedName)).toBeNull()
    expect(openMemberAvatar(NEW, address, m.sealedAvatar)).toEqual(iconBytes)
    expect(m.sealedAvatar!.length).toBe(page.members[0].sealedAvatar!.length) // the constant pad is kept
    expect(invite.openJoinRecord(NEW.key, { tenantId: SCOPE, userId: MEMBER }, fromB64std(m.sealedJoinRecord!))).toEqual(record)
    expect(m).toMatchObject({
      replacedNameDigest: replacedDigestOf(page.members[0].sealedName!),
      replacedAvatarDigest: replacedDigestOf(page.members[0].sealedAvatar!),
      replacedJoinRecordDigest: replacedDigestOf(page.members[0].sealedJoinRecord!),
    })

    // the verification at the new epoch, the signed statement inside unchanged
    const [v] = s.verifications!
    const slot = { tenantId: SCOPE, verifierUserId: MEMBER, subjectUserId: SUBJECT }
    expect(verification.openVerification(NEW.key, { ...slot, epoch: NEW.epoch }, fromB64std(v.sealedStatement))).toEqual(statement)
    expect(() => verification.openVerification(NEW.key, { ...slot, epoch: OLD.epoch }, fromB64std(v.sealedStatement))).toThrow()
    expect(v.replacedDigest).toBe(replacedDigestOf(page.verifications[0].sealedStatement))
  })

  it('hands every application its part with the same re-key id and both keys, after core staged', async () => {
    api([served()])
    await run()
    expect(participant).toHaveBeenCalledTimes(1)
    const ctx = participant.mock.calls[0][0]
    expect(ctx).toMatchObject({ scopeId: SCOPE, rekeyId: 'r-1', oldKey: OLD, newKey: NEW })
    expect(ctx.digestOf('abc')).toBe(b64std(v1.sha256Digest(v1.utf8('abc'))))
    expect(staged).toHaveLength(1) // control: core's stage came first
  })

  it('pages through the listing with its cursor and keeps the member cap per stage', async () => {
    const many = served()
    many.rootNodes = []
    many.groupInvitees = []
    many.shares = []
    many.verifications = []
    const member = many.members[0]
    many.members = Array.from({ length: MEMBER_STAGE_BATCH + 1 }, () => ({ ...member, sealedAvatar: null, sealedJoinRecord: null }))
    many.more = true
    many.cursor = 'p1'
    // a later page that (wrongly) serves the name again is not staged twice: the name is the first page's
    const second = { ...served(), more: false, cursor: null }
    const mocks = api([many, second])
    await run()
    expect(mocks.listScopeRekeyParts.mock.calls.map(([r]) => r.since)).toEqual([undefined, 'p1'])
    expect(staged.map((s) => s.members!.length)).toEqual([MEMBER_STAGE_BATCH, 1, 1])
    expect(staged.filter((s) => s.groupName).length).toBe(1) // the name rides on the first page only
    expect(staged[0].groupName).toBeTruthy()
    expect(staged.filter((s) => s.icon).length).toBe(1)
  })

  it('clears the name key of a pending invitee this rotation did not anchor — sealed to no anomaly, never left unstaged', async () => {
    // ADR 0111 decision 9: an omission is allowed and visible. Before, the run threw here and the commit answered
    // `rekey_parts_missing` for the invitee's row on every rotation of the group while the proposal stood.
    const OTHER = '5c09e000-0000-4000-8000-0000000000b4'
    const page = served()
    const otherId = v1.generateIdentity()
    const otherB64 = b64std(v1.serializeIdentityPublic(otherId.pub))
    page.groupInvitees.push({ userId: OTHER, sealedNameKey: wrapNameKeyToInvitee(deriveGroupNameKey(OLD.key), SCOPE, OLD.epoch, otherB64) })
    api([page])
    // the rotation anchors OTHER, and holds INVITEE's key as an anomaly (absent from the keys it grants to)
    await run(new Map([[OTHER, otherB64]]))
    const byId = new Map(staged[0].groupInvitees!.map((i) => [i.userId, i]))
    expect(byId.get(INVITEE)).toEqual({
      userId: INVITEE, sealedNameKey: null, recipientIdentityDigest: null, replacedDigest: replacedDigestOf(page.groupInvitees[0].sealedNameKey),
    })
    // control on the same page: the anchored invitee gets the new name key, sealed to its own key
    const other = byId.get(OTHER)!
    const nameKey = v1.openAs({ purpose: 'group-name-key', scopeId: SCOPE, nameEpoch: NEW.epoch }, v1.hybridSecret(otherId.priv), fromB64std(other.sealedNameKey!))
    expect(nameKey).toEqual(deriveGroupNameKey(NEW.key))
    expect(other.recipientIdentityDigest).toBe(recipientIdentityDigest(otherB64))
  })

  it('stages an unreadable profile field as one that reads the same, so one member cannot stop every rotation', async () => {
    const page = served()
    page.members[0].sealedName = b64std(v1.sealField({ purpose: 'member-name', scopeId: SCOPE, userId: MEMBER }, v1.randomBytes(32), v1.randomBytes(9)))
    api([page])
    await run()
    const [m] = staged[0].members!
    expect(openMemberName(NEW, { scopeId: SCOPE, userId: MEMBER }, m.sealedName)).toBeNull()
    expect(m.replacedNameDigest).toBe(replacedDigestOf(page.members[0].sealedName!))
    // control: the readable fields beside it re-sealed as they were
    expect(openMemberAvatar(NEW, { scopeId: SCOPE, userId: MEMBER }, m.sealedAvatar)).toEqual(iconBytes)
  })

  it('fails closed on a root entry that does not open under the old key', async () => {
    const page = served()
    page.rootNodes = [rootEntry(ROOT, rootKey, 'Reisen', false)]
    page.rootNodes[0].wrappedContentKey = wrapContentKey(v1.randomBytes(32), { scopeId: SCOPE, nodeId: ROOT, scopeEpoch: 1, placementRevision: 5 }, rootKey)
    api([page])
    await expect(run()).rejects.toThrow(v1.OpenRefused)
    expect(staged).toHaveLength(0)
  })

  it('marks the scope as re-sealing while it runs, and not after', async () => {
    let during = false
    fakeApi(rekeyApi, {
      listScopeRekeyParts: vi.fn<RekeyApi['listScopeRekeyParts']>(async () => {
        during = resealingScopes.has(SCOPE)
        return { ...served(), groupName: null, icon: null, rootNodes: [], groupInvitees: [], shares: [], members: [], verifications: [] }
      }),
      stageScopeRekey: vi.fn<RekeyApi['stageScopeRekey']>(async () => {}),
    })
    await run()
    expect(during).toBe(true)
    expect(resealingScopes.has(SCOPE)).toBe(false)
  })
})
