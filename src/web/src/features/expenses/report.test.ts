// A report of an entry reaches the whole entry (ADR 0108 decisions 6 and 7): its share carries each held revision's K_rev
// and each of its receipts' K_blob, and the moderator's review opens every revision with them alone — no Pot key — each
// only at its own address, and each receipt by its blob id. Past the share's cap of 32 keys it carries the version on
// screen first (its revision, then its receipts), then the older revisions. A receipt reported on its own hands over its
// K_blob alone.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { REPORT } from '@veyq/crypto/v1'
import { entryReportKeys, ENTRY_REVISION_ROLE, RECEIPT_KEY_ROLE, receiptReportKeys } from './report'
import { openRevisionWithKey, RevisionUnreadable } from './domain/codec'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person } from './potTestUtil'

const DINNER = '0e000000-0000-4000-9000-000000000001'

function potWithEdits(edits: number): PotServer {
  const s = new PotServer(v1.randomBytes(32))
  s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  for (let i = 0; i < edits; i++) s.append([{ entryId: DINNER, content: expense(`Dinner ${i}`, 1000 + i, ANNA, [ANNA, BEN]) }])
  return s
}

describe('entryReportKeys', () => {
  it('hands over every revision\'s K_rev, which opens that revision and no other', () => {
    const s = potWithEdits(3)
    const keys = entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, DINNER)
    expect(keys.map((k) => [k.role, k.ref])).toEqual([[ENTRY_REVISION_ROLE, null], [ENTRY_REVISION_ROLE, null], [ENTRY_REVISION_ROLE, null]])
    const rows = s.rows.filter((r) => r.entryId === DINNER).sort((a, b) => b.revision - a.revision)
    rows.forEach((row, i) => {
      const address = { scopeId: SCOPE, nodeId: POT_ID, entryId: DINNER, revision: row.revision }
      expect(openRevisionWithKey(address, keys[i]!.key, row.sealedBody)).toMatchObject({ kind: 'expense', title: `Dinner ${row.revision - 1}` })
      // another revision's key does not open this one
      expect(() => openRevisionWithKey(address, keys[(i + 1) % keys.length]!.key, row.sealedBody)).toThrow(RevisionUnreadable)
    })
    // no other record's keys
    expect(entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, ANNA)).toHaveLength(1)
  })

  it('past the share\'s cap carries the newest revisions only', () => {
    const s = potWithEdits(REPORT.maxKeysPerShare + 2)
    const keys = entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, DINNER)
    expect(keys).toHaveLength(REPORT.maxKeysPerShare)
    const newest = s.rows.filter((r) => r.entryId === DINNER).sort((a, b) => b.revision - a.revision)[0]!
    expect(openRevisionWithKey({ scopeId: SCOPE, nodeId: POT_ID, entryId: DINNER, revision: newest.revision }, keys[0]!.key, newest.sealedBody).kind).toBe('expense')
  })

  it('carries the receipts of the version on screen right after its revision, each K_blob by its blob id, and none of another entry', () => {
    const s = potWithEdits(1)
    const kept = s.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    const other = s.receipt('0e000000-0000-4000-9000-000000000002', { name: 'taxi.pdf', type: 'application/pdf' })
    s.append([{ entryId: DINNER, content: { ...expense('Dinner', 1000, ANNA, [ANNA, BEN]), receipts: [kept.receiptId] } }])
    const keys = entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, DINNER)
    expect(keys.map((k) => [k.role, k.ref])).toEqual([[ENTRY_REVISION_ROLE, null], [RECEIPT_KEY_ROLE, kept.blobId], [ENTRY_REVISION_ROLE, null]])
    expect(keys[1]!.key).toEqual(kept.blobKey)
    expect(keys.some((k) => k.ref === other.blobId)).toBe(false)
  })

  it('past the cap keeps the receipts of the version on screen before the older revisions', () => {
    const s = potWithEdits(REPORT.maxKeysPerShare)
    const kept = s.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    s.append([{ entryId: DINNER, content: { ...expense('Dinner', 1000, ANNA, [ANNA, BEN]), receipts: [kept.receiptId] } }])
    const keys = entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, DINNER)
    expect(keys).toHaveLength(REPORT.maxKeysPerShare)
    expect(keys[1]).toMatchObject({ role: RECEIPT_KEY_ROLE, ref: kept.blobId })
  })
})

describe('receiptReportKeys', () => {
  it('a receipt on its own hands over its K_blob alone; one whose wrap is not this Pot\'s hands over nothing', () => {
    const s = potWithEdits(1)
    const r = s.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    expect(receiptReportKeys(SCOPE, POT_ID, s.potKey, r)).toEqual([{ role: RECEIPT_KEY_ROLE, ref: r.blobId, key: r.blobKey }])
    // listed for another Pot: its wrap is bound to this one and does not open there
    expect(receiptReportKeys(SCOPE, '0e000000-0000-4000-b000-000000000009', s.potKey, r)).toEqual([])
  })
})
