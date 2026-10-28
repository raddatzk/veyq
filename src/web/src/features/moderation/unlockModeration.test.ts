import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../account/login/login', () => ({ login: vi.fn() }))
vi.mock('../account/identityPins', () => ({ anchorIdentity: vi.fn() }))
vi.mock('../account/unlock/unlock', () => ({ unlockMasterKey: vi.fn() }))

import { moderationApi } from '../../api/client'
import type { ModerationEpochGrant } from '../../api/generated/moderation'
import { authenticatedCalls } from '../../test/guestMode'
import { fakeApi } from '../../test/apiMock'
import { identityPrivate, masterKey, setUnlocked, vaultState } from '../../app/vault'
import { session } from '../../app/session'
import { account } from '../../app/account'
import { anchorIdentity } from '../account/identityPins'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { clearModerationKeys, currentModerationKey } from './moderationKeys'
import { ModerationBundleMismatch, unlockModeration } from './unlockModeration'

/**
 * The moderation surface's unlock (cr-moderators-and-the-moderation-key decision 7, ADR 0099 §§ 3, 6) with the real
 * vault and the real crypto: it opens only the moderation grants, refuses a published bundle that is not the opened
 * key's, delivers after the pin check, and leaves the vault EMPTY — Master Key and identity private key gone — having
 * called no client but the moderation one (every other factory throws and is recorded, `guardedClients`).
 */
const me = v1.generateIdentity()
const them = v1.generateIdentity()
const theirKey = b64std(v1.serializeIdentityPublic(them.pub))
const THEM = '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a'
const THEIR_HEAD = b64std(new Uint8Array(32).fill(3))

function epoch(n: number, m: Uint8Array, sealedTo = me.pub.kem): ModerationEpochGrant {
  const bundle = v1.moderationBundle(m)
  return {
    epoch: n,
    kemPublicKey: b64std(bundle.kem),
    signPublicKey: b64std(bundle.sign),
    sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch: n }, sealedTo, m)),
  }
}

let m1: Uint8Array
let m2: Uint8Array
let puts: { accountId: string; moderationDelivery: { identityPublicKey: string; grants: { epoch: number; sealedGrant: string }[] } }[]

function serve(epochs: ModerationEpochGrant[], current: number, pending = [{ accountId: THEM, identityPublicKey: theirKey, keyChainHead: THEIR_HEAD, epochs: [1, 2] }]) {
  puts = []
  fakeApi(moderationApi, {
    listMyModerationGrants: async () => ({ currentEpoch: current, epochs }),
    createModerationChallenge: async () => ({ challenge: b64std(new Uint8Array(32).fill(7)) }),
    listPendingModerationDeliveries: async () => ({ deliveries: pending }),
    putModerationDelivery: async (req) => {
      puts.push(req as (typeof puts)[number])
    },
  })
}

beforeEach(() => {
  authenticatedCalls.length = 0
  clearModerationKeys()
  m1 = v1.generateModerationKey()
  m2 = v1.generateModerationKey()
  session.accessToken = 'token'
  account.email = 'deputy@example.com'
  vi.mocked(anchorIdentity).mockResolvedValue('firstUse')
  vi.mocked(login).mockResolvedValue(undefined)
  vi.mocked(unlockMasterKey).mockImplementation(async () => {
    setUnlocked(new Uint8Array(32).fill(9), { kemSeed: me.priv.kemSeed.slice(), sign: me.priv.sign.slice() }, 'cold')
  })
})

describe('unlockModeration', () => {
  it('opens the grants, delivers after the pin check, and leaves the vault empty', async () => {
    serve([epoch(1, m1), epoch(2, m2)], 2)
    const result = await unlockModeration('deputy@example.com', 'pw', 'sk')

    expect(result).toEqual({ holdsCurrent: true, delivered: 1, declined: 0 })
    // The vault is empty: nothing of the account is open on this page any more.
    expect(masterKey()).toBeNull()
    expect(identityPrivate()).toBeNull()
    expect(vaultState.unlocked).toBe(false)
    // Only the moderation keys are held, and the current one is M_2.
    expect(currentModerationKey()?.epoch).toBe(2)
    expect(b64std(currentModerationKey()!.key)).toBe(b64std(m2))
    // No scope, content or member client was called: every authenticated factory but the moderation one throws.
    expect(authenticatedCalls).toEqual([])
    // The floor unlock, recording nothing about this device; the session this page held was the account's own.
    expect(unlockMasterKey).toHaveBeenCalledWith('pw', 'sk', { noteDevice: false })
    expect(login).not.toHaveBeenCalled()
    // Each delivered grant opens, for the recipient, to the epoch's key.
    expect(anchorIdentity).toHaveBeenCalledWith(THEM, { identityPublicKey: theirKey, keyChainHead: THEIR_HEAD })
    expect(puts).toHaveLength(1)
    const sent = puts[0].moderationDelivery.grants
    expect(sent.map((g) => g.epoch)).toEqual([1, 2])
    for (const g of sent) {
      const opened = v1.openAs({ purpose: 'moderation-grant', epoch: g.epoch }, them.priv.kemSeed, fromB64std(g.sealedGrant))
      expect(b64std(opened)).toBe(b64std(g.epoch === 1 ? m1 : m2))
    }
    expect(puts[0].moderationDelivery.identityPublicKey).toBe(theirKey)
  })

  it('refuses a published bundle that is not the opened key, and still empties the vault', async () => {
    const forged = epoch(2, m2)
    forged.signPublicKey = b64std(v1.moderationBundle(v1.generateModerationKey()).sign)
    serve([epoch(1, m1), forged], 2)

    await expect(unlockModeration('deputy@example.com', 'pw', 'sk')).rejects.toEqual(new ModerationBundleMismatch(2))
    expect(masterKey()).toBeNull()
    expect(identityPrivate()).toBeNull()
    expect(currentModerationKey()).toBeNull()
    expect(puts).toEqual([])
  })

  it('declines a recipient whose key is an anomaly — the chain does not reach it from the pinned head, a retired key included', async () => {
    serve([epoch(1, m1)], 1, [{ accountId: THEM, identityPublicKey: theirKey, keyChainHead: THEIR_HEAD, epochs: [1] }])
    vi.mocked(anchorIdentity).mockResolvedValue('anomaly')
    const result = await unlockModeration('deputy@example.com', 'pw', 'sk')
    expect(result).toEqual({ holdsCurrent: true, delivered: 0, declined: 1 })
    expect(puts).toEqual([])
    expect(masterKey()).toBeNull()
  })

  it('control: a key the chain reached (advanced) is delivered', async () => {
    serve([epoch(1, m1)], 1, [{ accountId: THEM, identityPublicKey: theirKey, keyChainHead: THEIR_HEAD, epochs: [1] }])
    vi.mocked(anchorIdentity).mockResolvedValue('advanced')
    expect(await unlockModeration('deputy@example.com', 'pw', 'sk')).toEqual({ holdsCurrent: true, delivered: 1, declined: 0 })
  })

  it('a moderator still waiting for the current epoch delivers nothing and holds no current key', async () => {
    serve([epoch(1, m1), { ...epoch(2, m2), sealedGrant: undefined }], 2)
    const result = await unlockModeration('deputy@example.com', 'pw', 'sk')
    expect(result).toEqual({ holdsCurrent: false, delivered: 0, declined: 0 })
    expect(currentModerationKey()).toBeNull()
    expect(puts).toEqual([])
  })

  it('signs in first where the page holds no session of this account', async () => {
    session.accessToken = null
    serve([epoch(1, m1)], 1, [])
    await unlockModeration('Deputy@Example.com ', 'pw', 'sk')
    expect(login).toHaveBeenCalledWith('Deputy@Example.com', 'pw', 'SESSION')
  })
})
