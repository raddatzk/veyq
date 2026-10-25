// A Pot's re-key (ADR 0109, ADR 0108 decision 4) through the real crypto: every revision's K_rev is re-wrapped under the
// Pot's new key at the same address and staged under the commit's re-key id, page after page — no body is sent, so none
// can change and every digest the heads name stays. A wrap that does not open under the old key fails the node (fail
// closed: core does not commit it), and nothing of that page is staged. Each receipt's K_blob moves the same way, bound to
// (blob, Pot, `EXPENSES_RECEIPT`) as before — its blob id from the Pot's receipt listing, since the re-key listing names
// only the receipt — and a receipt whose blob no listing names fails the node rather than going unstaged.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./api', () => ({ expensesApi: vi.fn() }))
vi.mock('./receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { rekeyPotContent } from './rekey'
import { expensesApi } from './api'
import type { ExpensesApi, PotRekeyPage } from '../../api/generated/expenses'
import { fakeApi } from '../../test/apiMock'
import { revisionKeyOf, revisionDigest } from './domain/codec'
import { openServedRevisions } from './domain/revisions'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person } from './potTestUtil'
import { readMemberReceipts, RECEIPT_ROLE } from './receipts'
import { blobKeyContext } from '../../core/blob/keys'

const newKey = v1.randomBytes(32)
let server: PotServer
const api = { pages: [] as PotRekeyPage[], staged: [] as unknown[] }

beforeEach(() => {
  fakeApi(expensesApi, {
    listPotRekey: async ({ since }: { since?: string }) => api.pages[since ? Number(since) : 0]!,
    stagePotRekey: async (req: Parameters<ExpensesApi['stagePotRekey']>[0]) => {
      api.staged.push(req)
    },
  })
  api.staged = []
  server = new PotServer(v1.randomBytes(32))
  server.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  server.append([{ entryId: '0e000000-0000-4000-9000-000000000001', content: expense('Dinner', 4000, ANNA, [ANNA, BEN]) }])
  const wire = server.rows.map((r) => ({ entryId: r.entryId, revision: r.revision, wrappedRevisionKey: b64std(r.wrappedRevisionKey) }))
  // Two pages: the cursor of the first is the index of the second.
  api.pages = [{ revisions: wire.slice(0, 2), receipts: [], more: true, cursor: '1' }, { revisions: wire.slice(2), receipts: [], more: false, cursor: null }]
  vi.mocked(readMemberReceipts).mockImplementation(async () => ({ receipts: [...server.receipts], etag: null }))
})

const ctx = (oldKey: Uint8Array) => ({
  scopeId: SCOPE, rekeyId: 'rk-1', node: { id: POT_ID, kind: 'EXPENSES_POT', epoch: 3, trashed: false }, oldKey, newKey, meta: null, shareRootKeys: () => null,
})

describe('rekeyPotContent', () => {
  it('re-wraps every K_rev under the new key, page after page, and stages no body', async () => {
    await rekeyPotContent(ctx(server.potKey))
    expect(api.staged).toHaveLength(2)
    const staged = api.staged.flatMap((s) => (s as { potRekeyStage: { fromEpoch: number; revisions: { entryId: string; revision: number; wrappedRevisionKey: string }[] } }).potRekeyStage.revisions)
    expect(api.staged.every((s) => (s as { rekeyId: string; potRekeyStage: { fromEpoch: number } }).rekeyId === 'rk-1' && (s as { potRekeyStage: { fromEpoch: number } }).potRekeyStage.fromEpoch === 3)).toBe(true)
    expect(staged.map((s) => Object.keys(s).sort())).toEqual(staged.map(() => ['entryId', 'revision', 'wrappedRevisionKey']))
    for (const row of server.rows) {
      const s = staged.find((x) => x.entryId === row.entryId && x.revision === row.revision)!
      const address = { scopeId: SCOPE, nodeId: POT_ID, entryId: row.entryId, revision: row.revision }
      expect(revisionKeyOf(address, newKey, fromB64std(s.wrappedRevisionKey))).toEqual(revisionKeyOf(address, server.potKey, row.wrappedRevisionKey))
    }
    // what a reader holds after the commit: the same bodies under the new wraps, every digest unchanged
    const after = server.rows.map((r) => ({ ...r, wrappedRevisionKey: fromB64std(staged.find((x) => x.entryId === r.entryId && x.revision === r.revision)!.wrappedRevisionKey) }))
    const held = openServedRevisions(SCOPE, POT_ID, newKey, after)
    expect(held.every((h) => h.body !== null)).toBe(true)
    expect(held.map((h) => h.digest)).toEqual(server.rows.map((r) => revisionDigest(r.sealedBody)))
  })

  it('fails closed on a wrap that does not open under the old key (control: the right key above re-keys)', async () => {
    await expect(rekeyPotContent(ctx(v1.randomBytes(32)))).rejects.toThrow()
    expect(api.staged).toEqual([])
  })

  it('re-wraps every receipt\'s K_blob under the new key, bound to its blob, the Pot and the receipt role as before', async () => {
    const DINNER = '0e000000-0000-4000-9000-000000000001'
    const bill = server.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    const dropped = server.receipt(DINNER, { name: 'old.pdf', type: 'application/pdf' }, { droppedAt: new Date() })
    api.pages[1]!.receipts = [bill, dropped].map((r) => ({ receiptId: r.receiptId, wrappedBlobKey: r.wrappedBlobKey }))
    await rekeyPotContent(ctx(server.potKey))
    const staged = api.staged.flatMap((s) => (s as { potRekeyStage: { receipts?: { receiptId: string; wrappedBlobKey: string }[] } }).potRekeyStage.receipts ?? [])
    expect(staged.map((r) => r.receiptId)).toEqual([bill.receiptId, dropped.receiptId])
    for (const r of [bill, dropped]) {
      const s = staged.find((x) => x.receiptId === r.receiptId)!
      expect(v1.open(blobKeyContext(SCOPE, { blobId: r.blobId, ownerId: POT_ID, role: RECEIPT_ROLE }), newKey, fromB64std(s.wrappedBlobKey))).toEqual(r.blobKey)
    }
  })

  it('fails closed on a receipt whose blob no listing names, staging nothing of its page', async () => {
    api.pages[1]!.receipts = [{ receiptId: '0e000000-0000-4000-a000-00000000dead', wrappedBlobKey: b64std(v1.randomBytes(80)) }]
    await expect(rekeyPotContent(ctx(server.potKey))).rejects.toThrow('rekey_receipt_unknown')
    expect(api.staged).toHaveLength(1) // the first page only
  })
})
