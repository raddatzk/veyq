// uc.moderation.authority-order (admin-web) — the Server Owner's door, with the REAL crypto suite: the test opens what
// the facade sealed (the facade itself cannot — `moderation.noOpen.test.ts`).
//   - a pasted share URL leaves as its id alone: its fragment — the share's key — is never in the request;
//   - the reference is sealed to the current epoch's public half, bound to the order id the request carries;
//   - a share that is neither a URL nor an id, an empty reference, and moderation off are refused with nothing sent;
//   - the kind's own fields go only with their kind.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceModerationApi: vi.fn() }))

import { enterOrder, OrderInvalid, parseShareReference, type AuthorityOrder } from './orders'
import { instanceModerationApi } from '../../api/client'
import type { AuthorityOrderRequest, InstanceModeration } from '../../api/generated/instance'
import { fakeApi } from '../../test/apiMock'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'
const KEY_FRAGMENT = 'k=c2hhcmUta2V5LXRoYXQtbXVzdC1uZXZlci1sZWF2ZQ'
const ORDER_ID = '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b'
const m = v1.generateModerationKey()
const kem = b64std(v1.moderationBundle(m).kem)

function moderation(over: Partial<InstanceModeration> = {}): InstanceModeration {
  return {
    enabled: true, currentEpoch: 3, currentKemPublicKey: kem, rotationDueSince: undefined,
    serverOwner: { accountId: 'owner', identityPublicKey: 'aWRlbnRpdHk=' }, moderators: [], ...over,
  }
}

function recorded(req: AuthorityOrderRequest): AuthorityOrder {
  return {
    id: req.id, kind: req.kind, shareId: req.shareId, receivedAt: req.receivedAt, enteredAt: new Date(), enteredBy: 'owner',
    found: true, blockedAt: new Date(), caseId: 'case', holdUntil: new Date(), statementDeferredUntil: undefined,
    epoch: req.reference.epoch, sealedReference: req.reference.sealedReference, providerName: undefined, contactPoint: undefined,
  }
}

function api(over: Partial<InstanceModeration> = {}) {
  return fakeApi(instanceModerationApi, {
    getInstanceModeration: async () => moderation(over),
    enterInstanceModerationOrder: async (p) => recorded(p.authorityOrderRequest),
  })
}

beforeEach(() => vi.clearAllMocks())

describe('parseShareReference', () => {
  it('takes the id from a share URL and drops its fragment, takes a bare id, refuses anything else', () => {
    expect(parseShareReference(`https://club.example.org/s/${SHARE}#${KEY_FRAGMENT}`)).toBe(SHARE)
    expect(parseShareReference(`  ${SHARE}  `)).toBe(SHARE)
    expect(parseShareReference(`club.example.org/s/${SHARE}`)).toBe(SHARE)
    expect(parseShareReference('https://club.example.org/s/too-short#k=x')).toBeNull()
    expect(parseShareReference('')).toBeNull()
  })
})

describe('enterOrder', () => {
  it("sends a pasted URL's id alone — the share's key in its fragment never leaves", async () => {
    const mocks = api()
    await enterOrder(
      { kind: 'REMOVAL', share: `https://club.example.org/s/${SHARE}#${KEY_FRAGMENT}`, reference: 'BKA ST-2026-0417', receivedAt: new Date() },
      () => ORDER_ID,
    )
    const sent = mocks.enterInstanceModerationOrder.mock.calls[0][0]
    expect(sent.authorityOrderRequest.shareId).toBe(SHARE)
    const wire = JSON.stringify(sent)
    expect(wire).not.toContain(KEY_FRAGMENT)
    expect(wire).not.toContain('#')
  })

  it('seals the reference to the current epoch, bound to the order id it sends', async () => {
    const mocks = api()
    await enterOrder({ kind: 'REMOVAL', share: SHARE, reference: '  BKA ST-2026-0417  ', receivedAt: new Date() }, () => ORDER_ID)
    const req = mocks.enterInstanceModerationOrder.mock.calls[0][0].authorityOrderRequest
    expect(req.id).toBe(ORDER_ID)
    expect(req.reference.epoch).toBe(3)
    expect(JSON.stringify(req)).not.toContain('BKA ST-2026-0417')
    const opened = v1.openOrderReference(3, ORDER_ID, v1.moderationKemSecret(m), fromB64std(req.reference.sealedReference))
    expect(opened).toBe('BKA ST-2026-0417')
    expect(() => v1.openOrderReference(3, ORDER_ID.replace(/b$/, 'c'), v1.moderationKemSecret(m), fromB64std(req.reference.sealedReference))).toThrow()
  })

  it('refuses an unreadable share, an empty reference and moderation off, with nothing sent', async () => {
    const mocks = api()
    await expect(enterOrder({ kind: 'REMOVAL', share: 'not a share', reference: 'r', receivedAt: new Date() })).rejects.toEqual(new OrderInvalid('share'))
    await expect(enterOrder({ kind: 'REMOVAL', share: SHARE, reference: '   ', receivedAt: new Date() })).rejects.toEqual(new OrderInvalid('reference'))
    api({ currentEpoch: undefined, currentKemPublicKey: undefined, enabled: false })
    await expect(enterOrder({ kind: 'REMOVAL', share: SHARE, reference: 'r', receivedAt: new Date() })).rejects.toEqual(new OrderInvalid('moderationOff'))
    expect(mocks.enterInstanceModerationOrder).not.toHaveBeenCalled()
  })

  it("sends the kind's own fields with their kind only", async () => {
    const mocks = api()
    const until = new Date('2026-11-20T00:00:00Z')
    await enterOrder({ kind: 'PRESERVATION', share: SHARE, reference: 'r', receivedAt: new Date(), holdDays: 30, deferStatementUntil: until })
    await enterOrder({ kind: 'INFORMATION', share: SHARE, reference: 'r', receivedAt: new Date(), holdDays: 30, deferStatementUntil: until })
    await enterOrder({ kind: 'REMOVAL', share: SHARE, reference: 'r', receivedAt: new Date(), holdDays: 30, deferStatementUntil: until })
    const [preserve, inform, remove] = mocks.enterInstanceModerationOrder.mock.calls.map((c) => c[0].authorityOrderRequest)
    expect([preserve.holdDays, preserve.deferStatementUntil]).toEqual([null, null])
    expect([inform.holdDays, inform.deferStatementUntil]).toEqual([30, null])
    expect([remove.holdDays, remove.deferStatementUntil]).toEqual([null, until])
  })
})
