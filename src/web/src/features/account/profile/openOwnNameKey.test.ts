// The tenant keys `openOwnNameKey` opens belong to its caller, who zeroes them (inbox #574): `zeroOwnNameKey` zeroes the tenant
// key, and a key-material read that fails beside the open zeroes the scope it opened, since no caller ever sees it.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../api/client', () => ({ accountApi: vi.fn(), accountProfileApi: vi.fn() }))
vi.mock('../../spaces/scopes', () => ({ openScope: vi.fn() }))

import { openOwnNameKey, zeroOwnNameKey } from './profile'
import { accountApi } from '../../../api/client'
import { openScope } from '../../spaces/scopes'
import { fakeApi } from '../../../test/apiMock'
import { accountKeyMaterial, openedScope } from '../../../test/models'
import type { AccountApi } from '../../../api/generated/account'
import * as v1 from '@veyq/crypto/v1'

const USER = '5c09e000-0000-4000-8000-0000000000d1'
const zeroed = (k: Uint8Array) => k.every((b) => b === 0)

/** A tenant scope at epoch 1 — holding its one key (ADR 0115 decision 1: the rotation replaced epoch 0's). */
function tenantScope() {
  const current = v1.randomBytes(32)
  return openedScope({ scopeId: 's-1', tenantId: 't-1', key: current, epochs: { epoch: 1, key: current } })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('openOwnNameKey', () => {
  it('hands over the tenant key unzeroed at its epoch, and zeroOwnNameKey zeroes it', async () => {
    const scope = tenantScope()
    const bytes = scope.key.slice()
    vi.mocked(openScope).mockResolvedValue(scope)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockResolvedValue(accountKeyMaterial({ userId: USER })) })

    const own = (await openOwnNameKey())!
    expect(own.address).toEqual({ scopeId: 's-1', userId: USER })
    expect(own.epochs.epoch).toBe(1) // what a save states (ADR 0115 decision 3)
    expect(Array.from(own.epochs.key)).toEqual(Array.from(bytes))
    expect(Array.from(own.key)).toEqual(Array.from(bytes)) // …and what it seals under: the same one key
    expect(zeroed(own.epochs.key)).toBe(false)

    zeroOwnNameKey(own)
    expect(zeroed(scope.epochs.key)).toBe(true)
    expect(zeroed(scope.key)).toBe(true)
  })

  it('zeroes the tenant scope it opened when the key-material read beside it fails', async () => {
    const scope = tenantScope()
    vi.mocked(openScope).mockResolvedValue(scope)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockRejectedValue(new Error('offline')) })

    await expect(openOwnNameKey()).rejects.toThrow('offline')
    expect(zeroed(scope.epochs.key)).toBe(true)
  })

  it('null without a tenant grant', async () => {
    vi.mocked(openScope).mockResolvedValue(null)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockResolvedValue(accountKeyMaterial({ userId: USER })) })
    expect(await openOwnNameKey()).toBeNull()
  })
})
