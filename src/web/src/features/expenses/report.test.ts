// A report of an entry reaches the whole entry (ADR 0108 decisions 6 and 7): its share carries each held revision's K_rev
// and each of its receipts' K_blob, and the moderator's review opens every revision with them alone — no Pot key — each
// only at its own address, and each receipt by its blob id. It carries every key up to the share's cap of 256; an entry
// past it is not sent as an item at all — the dialog offers its Pot instead (inbox #540). A receipt reported on its own
// hands over its K_blob alone.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { REPORT } from '@veyq/crypto/v1'
import { entryReportKeyCount, entryReportKeys, ENTRY_REVISION_ROLE, RECEIPT_KEY_ROLE, receiptReportKeys, reportEntry } from './report'
import { closeReport, reportRequest, type ReportTarget } from '../../core/report/report'
import type { Access } from '../../core/access'
import { openRevisionWithKey, RevisionUnreadable } from './domain/codec'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person } from './potTestUtil'

const DINNER = '0e000000-0000-4000-9000-000000000001'

// Each edit goes through the real write path against the revisions held so far, opened once each: `append`'s default
// opens every row again on every call, which over the cap's 256 edits is quadratic — 1.8 s of the fixture alone on an
// Apple Silicon core and past the 5 s test timeout on a 4-vCPU CI runner (measured 2026-11-05), for the same rows.
function potWithEdits(edits: number): PotServer {
  const s = new PotServer(v1.randomBytes(32))
  let held = s.held()
  const append = (writes: Parameters<PotServer['append']>[0]) => {
    const from = s.rows.length
    s.append(writes, undefined, held)
    held = [...held, ...s.held(s.rows.slice(from))]
  }
  append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  for (let i = 0; i < edits; i++) append([{ entryId: DINNER, content: expense(`Dinner ${i}`, 1000 + i, ANNA, [ANNA, BEN]) }])
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

  it('carries every key up to the share\'s cap, the revisions newest first, and the codec takes them', () => {
    const s = potWithEdits(REPORT.maxKeysPerShare)
    const keys = entryReportKeys(SCOPE, POT_ID, s.potKey, s.held(), s.receipts, DINNER)
    expect(keys).toHaveLength(REPORT.maxKeysPerShare)
    expect(entryReportKeyCount(s.held(), s.receipts, DINNER)).toBe(REPORT.maxKeysPerShare)
    expect(v1.decodeReportKeys(v1.encodeReportKeys(keys))).toHaveLength(REPORT.maxKeysPerShare)
    const rows = s.rows.filter((r) => r.entryId === DINNER).sort((a, b) => b.revision - a.revision)
    const oldest = rows[rows.length - 1]!
    const at = { scopeId: SCOPE, nodeId: POT_ID, entryId: DINNER, revision: oldest.revision }
    expect(openRevisionWithKey(at, keys[keys.length - 1]!.key, oldest.sealedBody).kind, 'the oldest revision is reached too').toBe('expense')
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
})

describe('reportEntry', () => {
  const access = { mode: 'member', scopeId: SCOPE, epochs: null } as unknown as Access
  const pot: ReportTarget = { kind: 'NODE', id: POT_ID, keys: () => null }

  it('reports an entry within the cap as one item, its Pot offered only as what holds it', () => {
    const s = potWithEdits(REPORT.maxKeysPerShare - 1)
    s.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    reportEntry(access, POT_ID, s.potKey, s.held(), s.receipts, DINNER, pot)
    expect(reportRequest.value?.targets).toEqual([expect.objectContaining({ kind: 'ITEM', id: DINNER })])
    expect(reportRequest.value?.targets[0]).not.toHaveProperty('oversize')
    closeReport()
  })

  it('one key past the cap marks the entry too large and offers its Pot, never a share that leaves part of it out', () => {
    const s = potWithEdits(REPORT.maxKeysPerShare)
    s.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    reportEntry(access, POT_ID, s.potKey, s.held(), s.receipts, DINNER, pot)
    expect(reportRequest.value?.targets[0]).toMatchObject({ kind: 'ITEM', id: DINNER, oversize: true })
    expect(reportRequest.value?.enclosing).toBe(pot)
    closeReport()
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
