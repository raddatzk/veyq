// The session's receipt read (ADR 0108 decision 7): every receipt change moves the Pot's `ledgerRevision`, so the receipt
// listing is re-read — on its own tag — whenever the revision read answers with a change, and not when it answers `304`
// (control). A failed receipt read leaves the Pot loaded and is tried again on the next read, even a `304` one.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'

vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn() }))
vi.mock('./receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { usePotSession } from './potSession'
import { readMemberPot } from './domain/potApi'
import { readMemberReceipts } from './receipts'
import { memberAccessOf, memberTreeOf } from '../../test/models'
import { ANNA, PotServer, SCOPE, person, potNode } from './potTestUtil'

let pot: PotServer
let changed: boolean
beforeEach(() => {
  vi.clearAllMocks()
  pot = new PotServer(v1.randomBytes(32))
  pot.append([{ entryId: ANNA, content: person('Anna', 1) }])
  changed = true
  vi.mocked(readMemberPot).mockImplementation(async () =>
    changed ? { notModified: false, items: [...pot.rows], revision: pot.rows.length, deleteRevision: 0, ids: null, cursor: 'c', guestWriters: [] } : { notModified: true },
  )
})

function session() {
  const node = potNode(pot.potKey)
  const scope = effectScope()
  const s = scope.run(() =>
    usePotSession({ access: () => memberAccessOf({ scopeId: SCOPE }), scopeId: () => SCOPE, node: () => node, potKey: () => pot.potKey, epochs: () => null, tree: () => memberTreeOf(() => [node], { scopeId: SCOPE }), live: false }),
  )!
  return s
}

describe('the receipt read', () => {
  it('follows the revision read: re-read on its tag after a change, not after a 304 (control)', async () => {
    vi.mocked(readMemberReceipts).mockResolvedValue({ receipts: [pot.receipt(ANNA, { name: 'a.jpg', type: 'image/jpeg' })], etag: '"t1"' })
    const s = session()
    await s.refresh()
    expect(readMemberReceipts).toHaveBeenCalledTimes(1)
    expect(vi.mocked(readMemberReceipts).mock.calls[0]![2]).toBeUndefined()
    expect(s.receipts.value).toHaveLength(1)
    changed = false
    await s.refresh()
    expect(readMemberReceipts).toHaveBeenCalledTimes(1)
    changed = true
    vi.mocked(readMemberReceipts).mockResolvedValue(null) // its tag still holds: nothing new
    await s.refresh()
    expect(readMemberReceipts).toHaveBeenCalledTimes(2)
    expect(vi.mocked(readMemberReceipts).mock.calls[1]![2]).toBe('"t1"')
    expect(s.receipts.value).toHaveLength(1)
  })

  it('a failed receipt read leaves the Pot loaded, and is tried again on the next read, a 304 one too', async () => {
    vi.mocked(readMemberReceipts).mockRejectedValueOnce(new Error('network'))
    const s = session()
    await s.refresh()
    expect(s.failed.value).toBe(false)
    expect(s.receiptsFailed.value).toBe(true)
    changed = false
    vi.mocked(readMemberReceipts).mockResolvedValue({ receipts: [], etag: '"t"' })
    await s.refresh()
    expect(readMemberReceipts).toHaveBeenCalledTimes(2)
    expect(s.receiptsFailed.value).toBe(false)
  })
})
