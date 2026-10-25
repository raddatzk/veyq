// @vitest-environment happy-dom
// A moderator's review of a reported entry or receipt (uc.moderation.review-a-case, ADR 0108 decision 7): the receipts the
// hold copied are listed beside the held revisions and opened with the K_blob the report share handed over for each blob
// (no Pot key), their bytes from the case's hold (the review's `chunkUrls`, `presignCaseBlobs`). A reported entry lists
// every receipt of it and none of another entry; a receipt reported on its own lists itself alone; a receipt whose key the
// share did not carry is listed as one the report cannot open, with nothing to open.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../api', () => ({ caseExpensesApi: vi.fn() }))
vi.mock('../../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), openBlobWithKey: vi.fn() }))

import * as v1 from '@veyq/crypto/v1'
import PotEntryReview from './PotEntryReview.vue'
import { caseExpensesApi } from '../api'
import { openBlobWithKey } from '../../../core/blob/transport'
import { beginReview } from '../../../core/sharing/review'
import { fakeApi } from '../../../test/apiMock'
import { realI18n, t } from '../../../test/i18n'
import { b64std } from '@veyq/crypto/base64'
import { RECEIPT_KEY_ROLE, entryReportKeys } from '../report'
import { ANNA, BEN, PotServer, POT_ID, SCOPE, expense, person } from '../potTestUtil'

const DINNER = '0e000000-0000-4000-9000-000000000001'
const TAXI = '0e000000-0000-4000-9000-000000000002'
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10])

let pot: PotServer
let end: () => void
const chunkUrls = vi.fn(async (blobId: string) => [`https://hold.test/${blobId}/0`])

beforeEach(() => {
  pot = new PotServer(v1.randomBytes(32))
  end = beginReview({ caseId: 'case-1', chunkUrls, proof: async () => ({ xVeyqModerationChallenge: 'c', xVeyqModerationSignature: 's' }) })
  vi.mocked(openBlobWithKey).mockReset().mockResolvedValue(JPEG)
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:held')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => {
  end()
  vi.restoreAllMocks()
})

function hold(receipts: ReturnType<PotServer['receipt']>[]) {
  fakeApi(caseExpensesApi, {
    listCasePotRevisions: async () => ({
      revisions: [],
      receipts: receipts.map((r) => ({ nodeId: POT_ID, receiptId: r.receiptId, entryId: r.entryId, blobId: r.blobId, chunkCount: r.chunkCount, byteSize: r.byteSize, wrappedBlobKey: r.wrappedBlobKey, wrappedBlobMeta: r.wrappedBlobMeta, createdAt: r.createdAt, droppedAt: null })),
    }),
  })
}
const mountReview = (itemId: string, keys: { role: string; ref: string | null; key: Uint8Array }[]) =>
  mount(PotEntryReview, { props: { caseId: 'case-1', itemId, scopeId: SCOPE, keys }, global: { plugins: [realI18n()] }, attachTo: document.body })
const keyOf = (r: ReturnType<PotServer['receipt']>) => ({ role: RECEIPT_KEY_ROLE, ref: r.blobId, key: r.blobKey })

describe('PotEntryReview receipts', () => {
  it('a reported entry lists its receipts by their sealed names and opens one with its handed key from the hold', async () => {
    const bill = pot.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    const menu = pot.receipt(DINNER, { name: 'menu.pdf', type: 'application/pdf' })
    const taxi = pot.receipt(TAXI, { name: 'taxi.jpg', type: 'image/jpeg' })
    hold([bill, menu, taxi])
    const w = mountReview(DINNER, [keyOf(bill), keyOf(menu)])
    await flushPromises()
    expect(w.find(`[data-receipt="${bill.receiptId}"]`).text()).toContain('bill.jpg')
    expect(w.find(`[data-receipt="${menu.receiptId}"]`).text()).toContain('menu.pdf')
    expect(w.find(`[data-receipt="${taxi.receiptId}"]`).exists()).toBe(false)
    await w.find(`[data-receipt="${bill.receiptId}"] button`).trigger('click')
    await flushPromises()
    const [key, presign] = vi.mocked(openBlobWithKey).mock.calls[0]!
    expect(key).toEqual(bill.blobKey)
    await presign({})
    expect(chunkUrls).toHaveBeenCalledWith(bill.blobId)
    expect(w.find('img').attributes('src')).toBe('blob:held')
  })

  it('a receipt reported on its own lists itself alone; one whose key the share did not carry opens nothing (control)', async () => {
    const bill = pot.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' })
    const menu = pot.receipt(DINNER, { name: 'menu.pdf', type: 'application/pdf' })
    hold([bill, menu])
    const alone = mountReview(bill.receiptId, [keyOf(bill)])
    await flushPromises()
    expect(alone.findAll('[data-receipt]').map((x) => x.attributes('data-receipt'))).toEqual([bill.receiptId])
    alone.unmount()
    const capped = mountReview(DINNER, [keyOf(bill)])
    await flushPromises()
    const missing = capped.find(`[data-receipt="${menu.receiptId}"]`)
    expect(missing.text()).toContain(t('expenses.receipts.noKey'))
    expect(missing.find('button').exists()).toBe(false)
    expect(capped.find(`[data-receipt="${bill.receiptId}"] button`).exists()).toBe(true)
  })
})

describe('PotEntryReview revisions', () => {
  function holdRevisions() {
    fakeApi(caseExpensesApi, {
      listCasePotRevisions: async () => ({
        revisions: pot.rows.map((r) => ({
          nodeId: POT_ID, entryId: r.entryId, revision: r.revision, tombstone: false, erasure: false,
          sealedBody: b64std(r.sealedBody), wrappedRevisionKey: b64std(r.wrappedRevisionKey), createdAt: r.createdAt,
        })),
        receipts: [],
      }),
    })
  }
  const titles = (w: ReturnType<typeof mountReview>) => w.findAll('.expenses-review ol .veyq-row-title').map((e) => e.text())

  it('opens every held revision of a reported entry with the keys the share handed, in whatever order they come', async () => {
    pot.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
    for (let i = 0; i < 40; i++) pot.append([{ entryId: DINNER, content: expense(`Dinner ${i}`, 1000 + i, ANNA, [ANNA, BEN]) }])
    holdRevisions()
    const keys = entryReportKeys(SCOPE, POT_ID, pot.potKey, pot.held(), pot.receipts, DINNER)
    const newestFirst = mountReview(DINNER, keys)
    await flushPromises()
    expect(titles(newestFirst)).toHaveLength(40)
    expect(titles(newestFirst).every((x) => x.includes('Dinner')), 'each revision opened by its own key').toBe(true)
    expect(titles(newestFirst)[0]).toContain('Dinner 39')
    const opened = titles(newestFirst)
    newestFirst.unmount()
    // the order is only the fast path: a share whose keys come in another order opens the same revisions
    const reversed = mountReview(DINNER, [...keys].reverse())
    await flushPromises()
    expect(titles(reversed)).toEqual(opened)
    reversed.unmount()
    // control: a key the share did not carry leaves its revision unreadable
    const short = mountReview(DINNER, keys.slice(1))
    await flushPromises()
    expect(titles(short)[0]).toBe(t('expenses.history.unreadable'))
    expect(titles(short).slice(1).every((x) => x.includes('Dinner'))).toBe(true)
  })
})
