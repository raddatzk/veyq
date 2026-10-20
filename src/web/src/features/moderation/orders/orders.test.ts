// uc.moderation.authority-order (web) — the moderator's door, with the REAL crypto suite:
//   - a pasted share URL leaves as its id alone: its fragment — the share's key — is never in the request;
//   - the reference is sealed to the current epoch, bound to the order id the request carries, and opens again here;
//   - the call carries a fresh proof over its own operation and no case;
//   - an unreadable link and an empty reference are refused with nothing sent.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ moderationApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: <T>(f: () => Promise<T>) => f() }))
vi.mock('../proof', async (orig) => ({ ...(await orig<object>()), proofFor: vi.fn() }))

import { moderationApi } from '../../../api/client'
import type { AuthorityOrder, AuthorityOrderRequest } from '../../../api/generated/moderation'
import { fakeApi } from '../../../test/apiMock'
import { clearModerationKeys, holdModerationKeys } from '../moderationKeys'
import { proofFor } from '../proof'
import { enterOrder, listOrders, OrderInvalid, openReferenceOf, parseShareReference } from './orders'

const SHARE = '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i'
const KEY_FRAGMENT = 'k=c2hhcmUta2V5LXRoYXQtbXVzdC1uZXZlci1sZWF2ZQ'
const ORDER_ID = '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b'
const EPOCH = 2

function recorded(req: AuthorityOrderRequest): AuthorityOrder {
  return {
    id: req.id, kind: req.kind, shareId: req.shareId, receivedAt: req.receivedAt, enteredAt: new Date(), enteredBy: 'mod',
    found: true, blockedAt: new Date(), caseId: 'case', holdUntil: new Date(), statementDeferredUntil: undefined,
    epoch: req.reference.epoch, sealedReference: req.reference.sealedReference, providerName: undefined, contactPoint: undefined,
  }
}

let key: Uint8Array
let api: ReturnType<typeof stub>
const stub = () =>
  fakeApi(moderationApi, {
    enterModerationOrder: async (p) => recorded(p.authorityOrderRequest),
    listModerationOrders: async () => ({ orders: [] }),
  })

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(proofFor).mockResolvedValue({ xVeyqModerationChallenge: 'challenge', xVeyqModerationSignature: 'signature' })
  api = stub()
  key = v1.generateModerationKey()
  holdModerationKeys(new Map([[EPOCH, key.slice()]]), EPOCH)
})

afterEach(clearModerationKeys)

describe('parseShareReference', () => {
  it('takes the id from a share URL and drops its fragment, takes a bare id, refuses anything else', () => {
    expect(parseShareReference(`https://club.example.org/s/${SHARE}#${KEY_FRAGMENT}`)).toBe(SHARE)
    expect(parseShareReference(` ${SHARE} `)).toBe(SHARE)
    expect(parseShareReference('https://club.example.org/s/short#k=x')).toBeNull()
    expect(parseShareReference('')).toBeNull()
  })
})

describe('enterOrder', () => {
  it("sends a pasted URL's id alone — the share's key in its fragment never leaves", async () => {
    await enterOrder({ kind: 'REMOVAL', share: `https://club.example.org/s/${SHARE}#${KEY_FRAGMENT}`, reference: 'r', receivedAt: new Date() }, () => ORDER_ID)
    const sent = api.enterModerationOrder.mock.calls[0][0]
    expect(sent.authorityOrderRequest.shareId).toBe(SHARE)
    expect(JSON.stringify(sent)).not.toContain(KEY_FRAGMENT)
    expect(JSON.stringify(sent)).not.toContain('#')
  })

  it('seals the reference to the current epoch bound to the order id, under a proof over its own operation', async () => {
    const order = await enterOrder({ kind: 'REMOVAL', share: SHARE, reference: ' BKA ST-2026-0417 ', receivedAt: new Date() }, () => ORDER_ID)
    expect(proofFor).toHaveBeenCalledWith('enterModerationOrder')
    const sent = api.enterModerationOrder.mock.calls[0][0]
    expect(sent.xVeyqModerationSignature).toBe('signature')
    const req = sent.authorityOrderRequest
    expect([req.id, req.reference.epoch]).toEqual([ORDER_ID, EPOCH])
    expect(JSON.stringify(req)).not.toContain('BKA ST-2026-0417')
    expect(v1.openOrderReference(EPOCH, ORDER_ID, v1.moderationKemSecret(key), fromB64std(req.reference.sealedReference))).toBe('BKA ST-2026-0417')
    // and the page opens it again from the order the server answered
    expect(openReferenceOf(order)).toBe('BKA ST-2026-0417')
    expect(openReferenceOf({ ...order, id: ORDER_ID.replace(/b$/, 'c') })).toBeNull()
  })

  it('refuses an unreadable link and an empty reference with nothing sent', async () => {
    await expect(enterOrder({ kind: 'REMOVAL', share: 'nope', reference: 'r', receivedAt: new Date() })).rejects.toEqual(new OrderInvalid('share'))
    await expect(enterOrder({ kind: 'REMOVAL', share: SHARE, reference: ' ', receivedAt: new Date() })).rejects.toEqual(new OrderInvalid('reference'))
    expect(api.enterModerationOrder).not.toHaveBeenCalled()
  })

  it('lists the recorded orders under a proof over its own operation', async () => {
    expect(await listOrders()).toEqual([])
    expect(proofFor).toHaveBeenCalledWith('listModerationOrders')
  })
})
