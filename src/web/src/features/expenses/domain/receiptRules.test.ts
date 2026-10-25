// The rules of a Pot's receipts, each beside its control: what may be added (an image or a PDF by its claim), how an opened
// one is shown (by its BYTES — a claimed PDF that is text is only offered for download), which receipts a revision drops
// (removed ones, this writer's own leftovers and orphans older than the revision edited — never another writer's newer
// upload, never one already dropped, never another entry's), and that a deleted entry is restorable only until its
// receipts are erased (uc.expenses.entry-history E2).
import { describe, it, expect } from 'vitest'
import { claimsReceipt, purgeAfterOf, receiptsToDrop, receiptView, restoreOffered } from './receiptRules'
import { ANNA, BEN, PotServer, expense, person } from '../potTestUtil'

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10])
const PDF = new TextEncoder().encode('%PDF-1.7\n%âã\n1 0 obj')
const TEXT = new TextEncoder().encode('a receipt that is text')
const SVG = new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')

describe('claimsReceipt', () => {
  it('takes an image or a PDF by its type, else by its extension, and nothing else', () => {
    expect(claimsReceipt({ type: 'image/jpeg', name: 'x' })).toBe(true)
    expect(claimsReceipt({ type: 'application/pdf', name: 'x' })).toBe(true)
    expect(claimsReceipt({ type: '', name: 'bill.PDF' })).toBe(true)
    expect(claimsReceipt({ type: '', name: 'bill.heic' })).toBe(true)
    expect(claimsReceipt({ type: 'text/plain', name: 'bill.pdf' })).toBe(false) // a type the browser reports wins
    expect(claimsReceipt({ type: '', name: 'notes.txt' })).toBe(false)
    expect(claimsReceipt({ type: 'application/zip', name: 'x.zip' })).toBe(false)
  })
})

describe('receiptView', () => {
  it('decides by the bytes: an image, a PDF, and anything else only for download', () => {
    expect(receiptView(JPEG)).toEqual({ kind: 'image', type: 'image/jpeg' })
    expect(receiptView(PDF)).toEqual({ kind: 'pdf' })
    expect(receiptView(TEXT)).toEqual({ kind: 'other' })
    // an SVG is markup a peer wrote: never drawn
    expect(receiptView(SVG)).toEqual({ kind: 'other' })
    expect(receiptView(new Uint8Array())).toEqual({ kind: 'other' })
  })
})

describe('receiptsToDrop', () => {
  const ENTRY = '0e000000-0000-4000-9000-000000000001'
  const OTHER = '0e000000-0000-4000-9000-000000000002'
  const at = (minute: number) => new Date(1_767_225_600_000 + minute * 60_000)
  const row = (receiptId: string, entryId: string, minute: number, droppedAt: Date | null = null) => ({ receiptId, entryId, createdAt: at(minute), droppedAt })
  const on = { receipts: ['kept', 'removed'], createdAt: at(10) }
  const listing = [
    row('kept', ENTRY, 1),
    row('removed', ENTRY, 2),
    row('orphan', ENTRY, 5), // completed before the revision edited, held by none
    row('theirs', ENTRY, 12), // another writer's, completed after the revision edited
    row('mine', ENTRY, 13), // this writer's, added and removed again
    row('gone', ENTRY, 3, at(4)), // dropped before
    row('elsewhere', OTHER, 1),
  ]

  it('names what the revision edited held and no longer keeps, this writer\'s own, and older orphans', () => {
    expect(receiptsToDrop(listing, ENTRY, ['kept'], on, new Set(['mine'])).sort()).toEqual(['mine', 'orphan', 'removed'])
  })

  it('leaves a receipt the revision keeps, another writer\'s newer one, a dropped one and another entry\'s alone (control)', () => {
    const dropped = receiptsToDrop(listing, ENTRY, ['kept', 'removed', 'orphan', 'mine'], on, new Set(['mine']))
    expect(dropped).toEqual([])
    expect(receiptsToDrop(listing, ENTRY, [], on, new Set())).not.toContain('theirs')
    expect(receiptsToDrop(listing, ENTRY, [], on, new Set())).not.toContain('gone')
    expect(receiptsToDrop(listing, ENTRY, [], on, new Set())).not.toContain('elsewhere')
  })

  it('a new entry drops only this writer\'s own uploads', () => {
    expect(receiptsToDrop(listing, ENTRY, [], null, new Set(['mine']))).toEqual(['mine'])
  })
})

describe('purgeAfterOf and restoreOffered', () => {
  const DINNER = '0e000000-0000-4000-9000-000000000001'
  function pot(): PotServer {
    const s = new PotServer()
    s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
    s.append([{ entryId: DINNER, content: expense('Dinner', 1000, ANNA, [ANNA, BEN]) }])
    s.append([{ entryId: DINNER, content: { kind: 'deleted' } }])
    s.rows[s.rows.length - 1]!.purgeAfter = new Date('2026-09-01T00:00:00Z')
    return s
  }

  it('a deleted entry is restorable until its tombstone\'s purge time, and not after it', () => {
    const s = pot()
    const purge = purgeAfterOf(s.held(), DINNER)
    expect(purge).toEqual(new Date('2026-09-01T00:00:00Z'))
    expect(restoreOffered(purge, new Date('2026-08-31T23:59:59Z'))).toBe(true)
    expect(restoreOffered(purge, new Date('2026-09-01T00:00:00Z'))).toBe(false)
    expect(restoreOffered(purge, new Date('2026-10-01T00:00:00Z'))).toBe(false)
  })

  it('a time on an earlier tombstone does not count once the entry was restored (control), and no time is offered', () => {
    const s = pot()
    s.append([{ entryId: DINNER, content: expense('Dinner', 1000, ANNA, [ANNA, BEN]) }])
    expect(purgeAfterOf(s.held(), DINNER)).toBeNull()
    expect(restoreOffered(null, new Date('2100-01-01T00:00:00Z'))).toBe(true)
  })
})
