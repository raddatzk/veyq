// Who may fetch the day's rates (ADR 0108 decision 8, the CR's guest question, option (a), owner 2026-10-25): the
// session's `rateService` is a member's from its tenant's set and a link guest's from its Pot read — never the instance's
// manifest, which a tenant owner's switch does not narrow. Negative: the instance has the rates on, the tenant off (its
// set lacks the entry, the guest's read names none) — null on both paths. Control: the tenant's set, the guest's read
// name it — the address.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), readGuestPot: vi.fn() }))
vi.mock('./receipts', async (orig) => ({ ...(await orig<object>()), readGuestReceipts: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { usePotSession } from './potSession'
import { readGuestPot } from './domain/potApi'
import { readGuestReceipts } from './receipts'
import { capabilityState } from '../../shared/state/capabilities'
import { EXPENSES_EXCHANGE_RATES } from '../platform/capabilities'
import { guestAccessFor, memberAccessOf, memberTreeOf } from '../../test/models'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import type { Access } from '../../core/access'
import { ANNA, PotServer, SCOPE, person, potNode } from './potTestUtil'

const RATES = 'https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}'
const entry = { id: EXPENSES_EXCHANGE_RATES, serviceUrl: RATES }

let pot: PotServer
beforeEach(() => {
  vi.mocked(readGuestReceipts).mockResolvedValue({ receipts: [], etag: null })
  pot = new PotServer(v1.randomBytes(32))
  pot.append([{ entryId: ANNA, content: person('Anna', 1) }])
  // the instance has the rates on: its manifest carries the entry with its address
  capabilityState.entries = { [EXPENSES_EXCHANGE_RATES]: entry }
  capabilityState.ids = [EXPENSES_EXCHANGE_RATES]
})
afterEach(() => {
  capabilityState.entries = {}
  capabilityState.ids = null
  capabilityState.tenantEntries = null
  capabilityState.tenantIds = null
})

function session(access: Access) {
  const node = potNode(pot.potKey)
  return effectScope().run(() =>
    usePotSession({ access: () => access, scopeId: () => SCOPE, node: () => node, potKey: () => pot.potKey, epochs: () => null, tree: () => memberTreeOf(() => [node], { scopeId: SCOPE }), live: false }),
  )!
}
const guest = () => guestAccessFor({ shareId: 'share-1', scopeId: SCOPE } as unknown as PublicShareHead)

describe('the rate table a Pot\'s session names', () => {
  it('a member: its tenant\'s set alone — off there, or not loaded, is null whatever the instance says', () => {
    const s = session(memberAccessOf({ scopeId: SCOPE }))
    expect(s.rateService.value).toBeNull() // no tenant's set loaded: the instance's manifest is not asked
    capabilityState.tenantEntries = {} // the tenant owner switched the rates off
    expect(s.rateService.value).toBeNull()
    capabilityState.tenantEntries = { [EXPENSES_EXCHANGE_RATES]: entry } // control: on for the tenant
    expect(s.rateService.value).toBe(RATES)
  })

  it('a guest: what its Pot read names — none while the tenant has the rates off, though the instance has them on', async () => {
    vi.mocked(readGuestPot).mockResolvedValueOnce({ revisions: [...pot.guestRows()], etag: '"a"', exchangeRateService: null })
    const s = session(guest())
    await s.refresh()
    expect(s.rateService.value).toBeNull()
    // control: the tenant switches them on, and the next read (its tag moved) names the address
    vi.mocked(readGuestPot).mockResolvedValueOnce({ revisions: [...pot.guestRows()], etag: '"b"', exchangeRateService: RATES })
    await s.refresh()
    expect(s.rateService.value).toBe(RATES)
    vi.mocked(readGuestPot).mockResolvedValueOnce(null) // a 304 keeps what the last read named
    await s.refresh()
    expect(s.rateService.value).toBe(RATES)
  })
})
