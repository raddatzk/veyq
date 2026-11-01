// @vitest-environment happy-dom
// An entry's receipts in a member's Pot page (uc.expenses.attach-receipts, uc.expenses.entry-history E2, ADR 0108
// decisions 6 and 7). The revisions are written by the real write path and the receipts' wraps are real (`potTestUtil.ts`);
// only the wire is replaced. Each rule beside its control:
//   - a picked file is uploaded with the save and BEFORE the revision that lists it — a new entry's against the id its
//     first revision then takes;
//   - a file over 10 MiB, or one that claims to be neither an image nor a PDF, is refused before any upload;
//   - a removed receipt leaves the entry's revision and is named in its `droppedReceipts`; a kept one is not;
//   - a receipt the link or the quota has no room for is left out, the entry still saves, and the writer is told (E2);
//   - a blocked receipt is listed as blocked and offers no open and no download;
//   - a deleted entry is restorable until its tombstone's purge time, and not after it;
//   - a receipt is reported on its own, as an `ITEM` by its receipt id with its K_blob.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const server = vi.hoisted(() => ({
  current: null as unknown as import('../potTestUtil').PotServer,
  appends: [] as unknown[],
  uploads: [] as { target: unknown; entryId: string; receiptId: string; afterAppends: number }[],
  uploadError: null as string | null,
  toast: { success: () => {}, error: (() => {}) as (m: string) => void, showUndo: () => {}, dismissUndo: () => {} },
}))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn(async () => {}) }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => server.toast }))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../../../core/node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn(() => ({ key: server.current.potKey, epoch: 0 })) }))
vi.mock('../../../core/people/directory', async (orig) => ({ ...(await orig<object>()), loadTenantPeople: vi.fn(async () => new Map()), loadScopePeople: vi.fn(async () => []) }))
vi.mock('../domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), appendPotWrites: vi.fn() }))
vi.mock('../receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn(), uploadReceipt: vi.fn() }))
vi.mock('../../../core/report/report', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<object>()), useReportOffered: () => ref(true), openReport: vi.fn() }
})

import * as v1 from '@veyq/crypto/v1'
import PotDetail from './PotDetail.vue'
import type { NodeDetailProps } from '../../../core/applications/registry'
import { treeStore } from '../../../core/node/treeStore'
import { appendPotWrites, readMemberPot, sealPotWrites, type PotWrite } from '../domain/potApi'
import { readMemberReceipts, uploadReceipt, RECEIPT_MAX_BYTES } from '../receipts'
import { openReport } from '../../../core/report/report'
import { realI18n, t } from '../../../test/i18n'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { memberAccessOf, memberTreeOf } from '../../../test/models'
import { ANNA, BEN, PotServer, SCOPE, expense, person, potNode } from '../potTestUtil'

const DINNER = '0e000000-0000-4000-9000-000000000001'
let node: ReturnType<typeof potNode>

function mountPot(): VueWrapper {
  return mount(PotDetail, {
    props: {
      node, scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)), contentKey: server.current.potKey,
      children: { byKind: {}, folders: [] } as unknown as NodeDetailProps['children'],
      access: memberAccessOf({ scopeId: SCOPE, epochs: epochsOf(new Uint8Array(32).fill(4)) }), tree: memberTreeOf(() => treeStore.nodes, { scopeId: SCOPE }),
    },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
}
const dialog = () => document.body.querySelector('.expenses-entry-dialog') as HTMLElement | null
const buttonIn = (root: ParentNode, label: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label) as HTMLButtonElement | undefined
const receiptRow = (id: string) => dialog()!.querySelector(`[data-receipt="${id}"]`) as HTMLElement | null

async function pick(...files: File[]) {
  const input = dialog()!.querySelector('input[type="file"]') as HTMLInputElement
  Object.defineProperty(input, 'files', { value: files, configurable: true })
  input.dispatchEvent(new Event('change'))
  await flushPromises()
}
async function openEntry(w: VueWrapper, id: string) {
  await w.find(`[data-entry="${id}"]`).trigger('click')
  await flushPromises()
}
const jpeg = (name = 'bill.jpg', size = 2000) => new File([new Uint8Array(size)], name, { type: 'image/jpeg' })
const lastWrite = () => (server.appends.at(-1) as PotWrite[])[0]!

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  server.current = new PotServer(v1.randomBytes(32))
  server.appends = []
  server.uploads = []
  server.uploadError = null
  server.toast.error = vi.fn()
  vi.mocked(readMemberPot).mockImplementation(async () => {
    const rows = server.current.rows
    return { notModified: false, items: [...rows], revision: rows.length, deleteRevision: 0, ids: rows.map((r) => `${r.entryId}:${r.revision}`), cursor: 'c', guestWriters: [] }
  })
  vi.mocked(appendPotWrites).mockImplementation(async (target, key, held, writes, base) => {
    const sealed = sealPotWrites(target, key.key, held, writes, base)
    server.appends.push(writes)
    server.current.append(writes, 'user:u-anna', held)
    return sealed
  })
  vi.mocked(readMemberReceipts).mockImplementation(async () => ({ receipts: server.current.receipts.map((r) => ({ ...r })), etag: null }))
  vi.mocked(uploadReceipt).mockImplementation(async (target, _key, entryId, receiptId, file) => {
    if (server.uploadError) throw new Error(server.uploadError)
    server.uploads.push({ target, entryId, receiptId, afterAppends: server.appends.length })
    const { blobKey: _k, ...r } = server.current.receipt(entryId, { name: file.name, type: file.type }, { receiptId })
    return r
  })
  server.current.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
  server.current.append([{ entryId: DINNER, content: expense('Dinner', 6000, ANNA, [ANNA, BEN]) }])
  node = potNode(server.current.potKey)
  treeStore.nodes = [node]
})

/** Dinner holds one receipt (its second revision lists it). */
function dinnerWithReceipt(over: Parameters<PotServer['receipt']>[2] = {}) {
  const r = server.current.receipt(DINNER, { name: 'bill.jpg', type: 'image/jpeg' }, over)
  server.current.append([{ entryId: DINNER, content: { ...expense('Dinner', 6000, ANNA, [ANNA, BEN]), receipts: [r.receiptId] } }])
  return r
}

describe('receipts in the entry dialog', () => {
  it('a new entry\'s receipt is uploaded before its first revision, against the id that revision takes, and the revision lists it', async () => {
    const w = mountPot()
    await flushPromises()
    await buttonIn(w.element, t('expenses.entries.add'))!.click()
    await flushPromises()
    const [title] = [...dialog()!.querySelectorAll('input:not([type="file"])')] as HTMLInputElement[]
    const amount = dialog()!.querySelector(`input[aria-label^="${t('expenses.entry.payerAmount', { name: '' }).trim()}"]`) as HTMLInputElement
    title!.value = 'Hotel'
    title!.dispatchEvent(new Event('input'))
    amount!.value = '120'
    amount!.dispatchEvent(new Event('input'))
    await pick(jpeg('hotel.jpg'))
    expect(receiptRow(dialog()!.querySelector('[data-receipt]')!.getAttribute('data-receipt')!)!.textContent).toContain('hotel.jpg')
    expect(server.uploads).toHaveLength(0) // nothing leaves before the save
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect(server.uploads).toHaveLength(1)
    const [up] = server.uploads
    expect(up!.afterAppends).toBe(0) // uploaded before the revision
    expect(up!.target).toMatchObject({ kind: 'member', nodeId: node.id })
    const write = lastWrite()
    expect(write.entryId).toBe(up!.entryId)
    expect(write.content).toMatchObject({ title: 'Hotel', receipts: [up!.receiptId] })
    expect(write.droppedReceipts).toEqual([])
  })

  it('refuses a file over 10 MiB and one that is no image or PDF before any upload (control: a JPEG is taken)', async () => {
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    await pick(new File([new Uint8Array(RECEIPT_MAX_BYTES + 1)], 'huge.jpg', { type: 'image/jpeg' }), new File(['x'], 'notes.txt', { type: 'text/plain' }), jpeg())
    expect(dialog()!.textContent).toContain(t('expenses.receipts.tooLarge', { names: 'huge.jpg' }))
    expect(dialog()!.textContent).toContain(t('expenses.receipts.notReceipt', { names: 'notes.txt' }))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(server.uploads).toHaveLength(1)
    expect((lastWrite().content as { receipts: string[] }).receipts).toEqual([server.uploads[0]!.receiptId])
  })

  it('a removed receipt leaves the revision and is named dropped; a kept one is not (control)', async () => {
    const kept = server.current.receipt(DINNER, { name: 'keep.jpg', type: 'image/jpeg' })
    const gone = server.current.receipt(DINNER, { name: 'gone.pdf', type: 'application/pdf' })
    server.current.append([{ entryId: DINNER, content: { ...expense('Dinner', 6000, ANNA, [ANNA, BEN]), receipts: [kept.receiptId, gone.receiptId] } }])
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    expect(receiptRow(kept.receiptId)!.textContent).toContain('keep.jpg')
    await buttonIn(dialog()!, t('expenses.receipts.removeNamed', { name: 'gone.pdf' }))!.click()
    await flushPromises()
    expect(receiptRow(gone.receiptId)).toBeNull()
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(lastWrite().droppedReceipts).toEqual([gone.receiptId])
    expect((lastWrite().content as { receipts: string[] }).receipts).toEqual([kept.receiptId])
    expect(server.current.receipts.find((r) => r.receiptId === gone.receiptId)!.droppedAt).not.toBeNull()
    expect(server.current.receipts.find((r) => r.receiptId === kept.receiptId)!.droppedAt).toBeNull()
  })

  it('a receipt there is no room for is left out, the entry still saves, and the writer is told (E2)', async () => {
    server.uploadError = 'quota_exceeded'
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    await pick(jpeg('big-bill.jpg'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(dialog()).toBeNull()
    expect((lastWrite().content as { receipts: string[] }).receipts).toEqual([])
    expect(server.toast.error).toHaveBeenCalledWith(t('expenses.receipts.noRoom', { names: 'big-bill.jpg' }))
  })

  it('any other failed upload saves nothing and says which receipt (control for the above)', async () => {
    server.uploadError = 'upload_failed'
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    await pick(jpeg('bill.jpg'))
    await buttonIn(dialog()!, t('common.save'))!.click()
    await flushPromises()
    expect(server.appends).toHaveLength(0)
    expect(dialog()!.textContent).toContain(t('expenses.receipts.uploadFailed', { names: 'bill.jpg' }))
  })

  it('a blocked receipt is listed as blocked and offers no open and no download (control: an unblocked one does)', async () => {
    const blocked = server.current.receipt(DINNER, { name: 'blocked.jpg', type: 'image/jpeg' }, { blockedByOperator: true })
    const open = server.current.receipt(DINNER, { name: 'open.jpg', type: 'image/jpeg' })
    server.current.append([{ entryId: DINNER, content: { ...expense('Dinner', 6000, ANNA, [ANNA, BEN]), receipts: [blocked.receiptId, open.receiptId] } }])
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    const b = receiptRow(blocked.receiptId)!
    expect(b.textContent).toContain(t('node.blockedByOperator'))
    expect(buttonIn(b, t('expenses.receipts.openNamed', { name: 'blocked.jpg' }))).toBeUndefined()
    expect(buttonIn(b, t('expenses.receipts.downloadNamed', { name: 'blocked.jpg' }))).toBeUndefined()
    const o = receiptRow(open.receiptId)!
    expect(buttonIn(o, t('expenses.receipts.openNamed', { name: 'open.jpg' }))).toBeDefined()
    expect(buttonIn(o, t('expenses.receipts.downloadNamed', { name: 'open.jpg' }))).toBeDefined()
  })

  it('a deleted entry is restorable until its receipts are erased, and is offered no restore after it', async () => {
    dinnerWithReceipt()
    server.current.append([{ entryId: DINNER, content: { kind: 'deleted' } }])
    const tombstone = server.current.rows.at(-1)!
    tombstone.purgeAfter = new Date(Date.now() + 86_400_000)
    const before = mountPot()
    await flushPromises()
    await openEntry(before, DINNER)
    expect(buttonIn(dialog()!, t('expenses.entry.restore'))).toBeDefined()
    expect(dialog()!.textContent).toContain(t('expenses.entry.restorableUntil', { date: new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(tombstone.purgeAfter) }))
    before.unmount()
    document.body.innerHTML = ''
    tombstone.purgeAfter = new Date(Date.now() - 1000)
    const after = mountPot()
    await flushPromises()
    await openEntry(after, DINNER)
    expect(dialog()!.textContent).toContain(t('expenses.entry.notRestorable'))
    expect(buttonIn(dialog()!, t('expenses.entry.restore'))).toBeUndefined()
  })

  it('a receipt is reported on its own, as an ITEM by its receipt id carrying its K_blob, its dialog left open', async () => {
    const r = dinnerWithReceipt()
    const w = mountPot()
    await flushPromises()
    await openEntry(w, DINNER)
    await buttonIn(dialog()!, t('expenses.receipts.reportNamed', { name: 'bill.jpg' }))!.click()
    await flushPromises()
    expect(dialog()).not.toBeNull()
    const target = vi.mocked(openReport).mock.calls[0]![0].targets[0] as { kind: string; id: string; keys: () => { role: string; ref: string | null; key: Uint8Array }[] }
    expect(target).toMatchObject({ kind: 'ITEM', id: r.receiptId })
    expect(target.keys()).toEqual([{ role: 'EXPENSES_RECEIPT', ref: r.blobId, key: r.blobKey }])
  })
})
