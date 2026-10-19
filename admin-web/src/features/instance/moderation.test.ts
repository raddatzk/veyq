// uc.moderation.appoint-moderators (admin-web) — the minting facade, with the REAL crypto suite: the test opens what
// the facade sealed (the facade itself cannot — `moderation.noOpen.test.ts`), so each case asserts the key the
// moderators will actually receive, not that some bytes were sent.
//   - a rotation seals one grant per ACCEPTED moderator, for epoch currentEpoch + 1, and each opens to an `M` whose
//     bundle is the one uploaded; a pending appointee gets none;
//   - turning moderation on seals epoch 1 to the Server Owner's identity;
//   - a removal mints `REMOVAL` exactly when the server says a rotation is due;
//   - a refused mint rejects — it is never swallowed.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceModerationApi: vi.fn() }))

import {
  blockSignIn, declineProposal, epochOneFor, getModerationSteps, liftSignInBlock, removeModerator, rotateModerationKey,
  type InstanceModeration, type ModerationSteps,
} from './moderation'
import { instanceModerationApi } from '../../api/client'
import type { InstanceModerator, MintModerationEpochRequest } from '../../api/generated/instance'
import { fakeApi, stubApi } from '../../test/apiMock'
import { httpError } from '../../test/httpError'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const owner = v1.generateIdentity()
const second = v1.generateIdentity()
const pubB64 = (id: v1.Identity) => b64std(v1.serializeIdentityPublic(id.pub))

function moderator(over: Partial<InstanceModerator>): InstanceModerator {
  return {
    accountId: 'acc', address: 'a@example.com', state: 'ACCEPTED', appointedAt: new Date('2026-10-01T00:00:00Z'),
    acceptedAt: new Date('2026-10-02T00:00:00Z'), identityPublicKey: undefined, holdsCurrentEpoch: true, addressConfirmed: true,
    ...over,
  }
}

function state(over: Partial<InstanceModeration> = {}): InstanceModeration {
  return {
    enabled: true,
    currentEpoch: 3,
    currentKemPublicKey: undefined,
    rotationDueSince: undefined,
    serverOwner: { accountId: 'owner', identityPublicKey: pubB64(owner) },
    moderators: [
      moderator({ accountId: 'owner', address: 'owner@example.com', identityPublicKey: pubB64(owner) }),
      moderator({ accountId: 'second', address: 'second@example.com', identityPublicKey: pubB64(second) }),
      moderator({ accountId: 'pending', address: 'pending@example.com', state: 'PENDING', acceptedAt: undefined, holdsCurrentEpoch: false }),
    ],
    ...over,
  }
}

/** Open a grant as its recipient would, and check the `M` inside against the uploaded bundle. */
function opensToUploadedBundle(req: { kemPublicKey: string; signPublicKey: string }, sealedGrant: string, recipient: v1.Identity, epoch: number) {
  const m = v1.openAs({ purpose: 'moderation-grant', epoch }, v1.hybridSecret(recipient.priv), fromB64std(sealedGrant))
  const derived = v1.moderationBundle(m)
  expect(b64std(derived.kem)).toBe(req.kemPublicKey)
  expect(b64std(derived.sign)).toBe(req.signPublicKey)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('rotateModerationKey', () => {
  it('seals a fresh key for epoch currentEpoch + 1 to every accepted moderator, and to no pending one', async () => {
    const mocks = fakeApi(instanceModerationApi, {
      getInstanceModeration: async () => state(),
      mintModerationEpoch: async () => state({ currentEpoch: 4 }),
    })
    await rotateModerationKey('BY_HAND')
    const req: MintModerationEpochRequest = mocks.mintModerationEpoch.mock.calls[0][0].mintModerationEpochRequest
    expect(req.reason).toBe('BY_HAND')
    // The request names the epoch the grants are sealed for, so the server can refuse a mint that raced another.
    expect(req.epoch).toBe(4)
    expect(req.grants.map((g) => g.accountId).sort()).toEqual(['owner', 'second'])
    const byId = Object.fromEntries(req.grants.map((g) => [g.accountId, g]))
    expect(byId.second.identityPublicKey).toBe(pubB64(second))
    opensToUploadedBundle(req, byId.owner.sealedGrant, owner, 4)
    opensToUploadedBundle(req, byId.second.sealedGrant, second, 4)
    // bound to its epoch: the same grant does not open as the current one
    expect(() => v1.openAs({ purpose: 'moderation-grant', epoch: 3 }, v1.hybridSecret(second.priv), fromB64std(byId.second.sealedGrant))).toThrow()
    // and to its recipient: one moderator cannot open another's
    expect(() => v1.openAs({ purpose: 'moderation-grant', epoch: 4 }, v1.hybridSecret(owner.priv), fromB64std(byId.second.sealedGrant))).toThrow()
  })

  it('seals to the roster it is given without reading it again', async () => {
    const mocks = fakeApi(instanceModerationApi, {
      getInstanceModeration: async () => state(),
      mintModerationEpoch: async () => state(),
    })
    await rotateModerationKey('COMPROMISE', state({ currentEpoch: 7 }))
    expect(mocks.getInstanceModeration).not.toHaveBeenCalled()
    const req = mocks.mintModerationEpoch.mock.calls[0][0].mintModerationEpochRequest
    opensToUploadedBundle(req, req.grants[0].sealedGrant, owner, 8)
  })

  it('draws a new key each time', async () => {
    const mocks = fakeApi(instanceModerationApi, { mintModerationEpoch: async () => state() })
    await rotateModerationKey('BY_HAND', state())
    await rotateModerationKey('BY_HAND', state())
    const [a, b] = mocks.mintModerationEpoch.mock.calls.map((c) => c[0].mintModerationEpochRequest.signPublicKey)
    expect(a).not.toBe(b)
  })

  it('rejects with the server’s refusal rather than swallowing it', async () => {
    fakeApi(instanceModerationApi, {
      mintModerationEpoch: async () => {
        throw httpError(409, 'moderation_grants_incomplete')
      },
    })
    await expect(rotateModerationKey('BY_HAND', state())).rejects.toHaveProperty('response.status', 409)
  })
})

describe('epochOneFor (first-run setup)', () => {
  it('seals epoch 1 to the identity it is handed, with the bundle of that key, and calls no client', () => {
    const epoch = epochOneFor(pubB64(owner))
    opensToUploadedBundle(epoch, epoch.sealedGrant, owner, 1)
    // A fresh key every time: two setups never share a moderation key.
    expect(epochOneFor(pubB64(owner)).signPublicKey).not.toBe(epoch.signPublicKey)
    expect(instanceModerationApi).not.toHaveBeenCalled()
  })
})

describe('removeModerator', () => {
  it('rotates for the rest with reason REMOVAL when the removal left a rotation due', async () => {
    const after = state({
      rotationDueSince: new Date('2026-10-17T10:00:00Z'),
      moderators: [moderator({ accountId: 'owner', identityPublicKey: pubB64(owner) })],
    })
    const mocks = fakeApi(instanceModerationApi, {
      removeModerator: async () => after,
      mintModerationEpoch: async () => ({ ...after, rotationDueSince: undefined, currentEpoch: 4 }),
    })
    const result = await removeModerator('second')
    expect(mocks.removeModerator).toHaveBeenCalledWith({ accountId: 'second' })
    const req = mocks.mintModerationEpoch.mock.calls[0][0].mintModerationEpochRequest
    expect(req.reason).toBe('REMOVAL')
    expect(req.grants.map((g) => g.accountId)).toEqual(['owner']) // sealed to the roster the removal answered with
    expect(result.rotationDueSince).toBeUndefined() // no rotation due: the API omits the field
  })

  it('mints nothing when no rotation is due (a pending appointee held no key) — control', async () => {
    const mocks = fakeApi(instanceModerationApi, {
      removeModerator: async () => state(),
      mintModerationEpoch: async () => state(),
    })
    await removeModerator('pending')
    expect(mocks.mintModerationEpoch).not.toHaveBeenCalled()
  })

  it('rejects when the rotation after the removal is refused', async () => {
    fakeApi(instanceModerationApi, {
      removeModerator: async () => state({ rotationDueSince: new Date() }),
      mintModerationEpoch: async () => {
        throw httpError(409, 'moderation_grants_incomplete')
      },
    })
    await expect(removeModerator('second')).rejects.toHaveProperty('response.status', 409)
  })
})

// uc.moderation.take-a-measure step 5 — the Server Owner's steps on the wire: each facade call reaches its operation
// with the body the contract names, and a refusal propagates (the panel and the CLI say it, not this file).
describe('the Server Owner’s steps', () => {
  const steps: ModerationSteps = { proposals: [], signInBlocks: [] }

  it('reads the steps, declines by proposal id, and lifts by block id', async () => {
    const mocks = stubApi(instanceModerationApi, {
      getModerationSteps: steps,
      declineModerationProposal: steps,
      unblockAccountSignIn: steps,
    })
    expect(await getModerationSteps()).toBe(steps)
    await declineProposal('p-1')
    expect(mocks.declineModerationProposal).toHaveBeenCalledWith({ proposalId: 'p-1' })
    await liftSignInBlock('b-1')
    expect(mocks.unblockAccountSignIn).toHaveBeenCalledWith({ blockId: 'b-1' })
  })

  it('blocks by the trimmed address with the case, and with an explicit null case when none is named', async () => {
    const mocks = stubApi(instanceModerationApi, { blockAccountSignIn: steps })
    await blockSignIn('  member@example.com ', 'case-1')
    expect(mocks.blockAccountSignIn).toHaveBeenLastCalledWith({
      blockAccountSignInRequest: { address: 'member@example.com', caseId: 'case-1', overContent: false, statement: null },
    })
    await blockSignIn('member@example.com')
    expect(mocks.blockAccountSignIn).toHaveBeenLastCalledWith({
      blockAccountSignInRequest: { address: 'member@example.com', caseId: null, overContent: false, statement: null },
    })
  })

  it('rejects with the server’s refusal rather than swallowing it', async () => {
    fakeApi(instanceModerationApi, {
      blockAccountSignIn: async () => {
        throw httpError(409, 'cannot_block_server_owner')
      },
    })
    await expect(blockSignIn('owner@example.com')).rejects.toHaveProperty('response.status', 409)
  })
})
