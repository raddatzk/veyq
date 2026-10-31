// uc.account.erase-account (admin-web) — the Server Owner's erasure facade: each call reaches its generated operation with
// the address as typed (trimmed), the list unwraps the server's document, and the typed confirmation holds only two
// equal, non-empty addresses.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ instanceAccountsApi: vi.fn() }))

import { addressConfirmed, eraseAccount, listAccountErasures, stopAccountErasure, type PendingAccountErasure } from './accounts'
import { instanceAccountsApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'

const PENDING: PendingAccountErasure = {
  address: 'member@example.com',
  requestedAt: new Date('2026-10-18T10:00:00Z'),
  requestedBy: 'SERVER_OWNER',
  dueAt: new Date('2026-11-17T10:00:00Z'),
}

describe('accounts facade (uc.account.erase-account, admin-web)', () => {
  let api: ReturnType<typeof stub>
  const stub = () =>
    stubApi(instanceAccountsApi, {
      listAccountErasures: { erasures: [PENDING] },
      eraseAccount: PENDING,
      stopAccountErasure: undefined,
    })

  beforeEach(() => {
    api = stub()
  })

  it('list hands back the pending erasures, in the server’s order', async () => {
    expect(await listAccountErasures()).toEqual([PENDING])
    expect(api.listAccountErasures).toHaveBeenCalledOnce()
  })

  it('erase wraps the trimmed address in an AccountAddressRequest and hands back the pending erasure', async () => {
    expect(await eraseAccount(' member@example.com ')).toEqual(PENDING)
    expect(api.eraseAccount).toHaveBeenCalledWith({ serverOwnerAccountErasureRequest: { address: 'member@example.com' } })
    await eraseAccount('holder@example.com', { loseModerationKey: true })
    expect(api.eraseAccount).toHaveBeenLastCalledWith({
      serverOwnerAccountErasureRequest: { address: 'holder@example.com', confirmModerationKeyLoss: true },
    })
  })

  it('stop wraps the trimmed address the same way', async () => {
    await stopAccountErasure('member@example.com ')
    expect(api.stopAccountErasure).toHaveBeenCalledWith({ accountAddressRequest: { address: 'member@example.com' } })
  })

  it('addressConfirmed holds two equal addresses (case-insensitive), never an empty one or a near one', () => {
    expect(addressConfirmed('member@example.com', 'member@example.com')).toBe(true)
    expect(addressConfirmed(' Member@Example.com', 'member@example.com ')).toBe(true)
    expect(addressConfirmed('', '')).toBe(false)
    expect(addressConfirmed('  ', '  ')).toBe(false)
    expect(addressConfirmed('member@example.com', 'member@example.co')).toBe(false)
    expect(addressConfirmed('member@example.com', '')).toBe(false)
  })
})
