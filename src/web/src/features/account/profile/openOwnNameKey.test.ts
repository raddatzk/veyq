// The tenant keys `openOwnNameKey` opens belong to its caller, who zeroes them (inbox #574): `zeroOwnNameKey` zeroes every
// epoch key, and a key-material read that fails beside the open zeroes the scope it opened, since no caller ever sees it.
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

/** A tenant scope at epoch 1 holding two epoch keys — the write key the newer one. */
function tenantScope() {
  const older = v1.randomBytes(32)
  const current = v1.randomBytes(32)
  return openedScope({ scopeId: 's-1', tenantId: 't-1', key: current, epochs: { current: 1, keys: new Map([[0, older], [1, current]]) } })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('openOwnNameKey', () => {
  it('hands over the tenant keys unzeroed, and zeroOwnNameKey zeroes every epoch key', async () => {
    const scope = tenantScope()
    vi.mocked(openScope).mockResolvedValue(scope)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockResolvedValue(accountKeyMaterial({ userId: USER })) })

    const own = (await openOwnNameKey())!
    expect(own.address).toEqual({ scopeId: 's-1', userId: USER })
    expect([...own.epochs.keys.values()].some(zeroed)).toBe(false)

    zeroOwnNameKey(own)
    expect([...scope.epochs.keys.values()].every(zeroed)).toBe(true)
    expect(zeroed(scope.key)).toBe(true)
  })

  it('zeroes the tenant scope it opened when the key-material read beside it fails', async () => {
    const scope = tenantScope()
    vi.mocked(openScope).mockResolvedValue(scope)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockRejectedValue(new Error('offline')) })

    await expect(openOwnNameKey()).rejects.toThrow('offline')
    expect([...scope.epochs.keys.values()].every(zeroed)).toBe(true)
  })

  it('null without a tenant grant', async () => {
    vi.mocked(openScope).mockResolvedValue(null)
    fakeApi(accountApi, { getAccountKeyMaterial: vi.fn<AccountApi['getAccountKeyMaterial']>().mockResolvedValue(accountKeyMaterial({ userId: USER })) })
    expect(await openOwnNameKey()).toBeNull()
  })
})
