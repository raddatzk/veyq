// @vitest-environment happy-dom
// A Pot's receipts in its download, its import and its copy (uc.platform.export-import `expenses-content`,
// uc.platform.copy-node `a-pot-copies-its-live-entries`, uc.expenses.attach-receipts), through core's real walk, ZIP writer,
// import and copy with Expenses' registered hooks, and core's real blob transport against a fake control plane and store
// recorded at `fetch`: every receipt is sealed, uploaded, presigned and opened as in the app. Only the revision read and
// append are the in-memory `PotServer` (the real seal, `sealPotWrites`). What it pins:
//   - the download carries each receipt a live entry's current revision holds as a payload with its sealed name and type,
//     and the ZIP is the length it announced with them in it (F11);
//   - its import uploads each receipt into the new Pot under a new id BEFORE the batch whose revision lists it, the revision
//     listing the new ids; the balances equal the original's and every receipt opens byte-identical under the new Pot;
//   - a copy does the same, reading the original by frame, and no longer names the receipts among what it loses;
//   - a receipt the operator blocked is left out (its bytes are never read), and an import that fails part-way trashes
//     the Pot it made.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const state = vi.hoisted(() => ({
  pots: new Map<string, import('./potTestUtil').PotServer>(),
  log: [] as string[],
  target: '0e000000-0000-4000-b000-000000000099',
}))
vi.mock('./domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn(), appendPotWrites: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({
  ...(await orig<object>()),
  createContentNode: vi.fn(),
  deleteNode: vi.fn(async () => {}),
}))

import * as v1 from '@veyq/crypto/v1'
import { blobKeyContext } from '../../core/blob/keys'
import { fromB64std } from '@veyq/crypto/base64'
import { createContentNode, deleteNode, type TreeNode } from '../../core/node/tree'
import type { TreeSource } from '../../core/access'
import type { ByteSink } from '../../core/blob/stream'
import { prepareExport } from '../../core/export/exportSubtree'
import { ImportFailed, importSubtree, openExport } from '../../core/export/importSubtree'
import { prepareCopy } from '../../core/export/copySubtree'
import { MANIFEST_PATH, type ManifestNode } from '../../core/export/manifest'
import { openZip } from '../../core/export/zip'
import { memberAccessOf } from '../../test/models'
import { epochsOf } from '../spaces/epochsTestUtil'
import { i18n } from '../../i18n'
import { appendPotWrites, readMemberPot, sealPotWrites } from './domain/potApi'
import { buildLedger } from './domain/ledger'
import { potReceiptSource, receiptMeta, RECEIPT_ROLE, uploadReceipt, type HeldReceipt } from './receipts'
import { ANNA, BEN, CLEO, EUR, POT_ID, PotServer, SCOPE, expense, person, potNode } from './potTestUtil'

const TARGET = state.target
const E1 = '0e000000-0000-4000-9000-000000000001'
const E2 = '0e000000-0000-4000-9000-000000000002'
const R1 = '0e000000-0000-4000-a000-000000000001'
const R2 = '0e000000-0000-4000-a000-000000000002'
const R3 = '0e000000-0000-4000-a000-000000000003'
const access = memberAccessOf({ scopeId: SCOPE })
const epochs = epochsOf(new Uint8Array(32).fill(4))

/** Bytes that differ at every offset of a frame, over two frames (a frame is 256 KiB). */
const bytesOf = (n: number, seed: number) => new Uint8Array(n).map((_, i) => (i * 31 + seed) % 251)
const JPEG = { name: 'Ski pass.jpg', type: 'image/jpeg', bytes: bytesOf(300_000, 7), lastModified: 1_700_000_000_000 }
const PDF = { name: 'Hotel-Rechnung.pdf', type: 'application/pdf', bytes: bytesOf(1_234, 3), lastModified: 1_700_000_100_000 }
const BLOCKED = { name: 'Blocked.png', type: 'image/png', bytes: bytesOf(50, 1), lastModified: 1_700_000_200_000 }

// --- the fake control plane and store -----------------------------------------------------------------------------------

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ETag: '"t"' } })
/** Per Pot, its receipt rows as the listing serves them. */
const listed = new Map<string, Record<string, unknown>[]>()
const declared = new Map<string, Record<string, unknown>>()
const stored = new Map<string, Uint8Array[]>()
/** Blob ids a moderator blocked: their presign item is `blocked_by_operator`. */
const blocked = new Set<string>()
/** The listing's own word on a receipt, as a server out of step (or a hostile one) might give it: dropped, or another entry's. */
/** What the listing serves of a receipt beside its completion's row: released by name (a drop), held by another entry. */
const served = new Map<string, { keptAt?: null; releasedAt?: string; ownerId?: string }>()
/** A completion the server refuses (`422 upload_mismatch`), for the failed import. */
let refuseCompletion = false

function fakeServer() {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://api.test')
    const method = init?.method ?? 'GET'
    if (url.host === 'store.test') {
      const [, uploadId, index] = url.pathname.split('/')
      if (method === 'PUT') {
        const frame = new Uint8Array(await new Response(init!.body as BodyInit).arrayBuffer())
        const frames = stored.get(uploadId!) ?? []
        frames[Number(index)] = frame
        stored.set(uploadId!, frames)
        return new Response(null, { status: 200 })
      }
      state.log.push(`read:${String(declared.get(uploadId!)!.blobId)}`)
      return new Response(stored.get(uploadId!)![Number(index)] as BodyInit, { status: 200 })
    }
    const body = init?.body === undefined ? undefined : (JSON.parse(String(init.body)) as Record<string, unknown>)
    if (method === 'POST' && url.pathname === `/api/scopes/${SCOPE}/blob-uploads-batch`) {
      return json({ results: (body!.items as Record<string, unknown>[]).map((it) => {
        const uploadId = crypto.randomUUID()
        declared.set(uploadId, it)
        return { uploadId, presignedPuts: Array.from({ length: it.chunkCount as number }, (_, c) => ({ index: c, url: `https://store.test/${uploadId}/${c}` })), error: null }
      }) })
    }
    // core's blob service (ADR 0112): a Pot's receipts are its application blobs, held by their entries
    const receipts = /^\/api\/scopes\/[^/]+\/nodes\/([^/]+)\/application-blobs$/.exec(url.pathname)
    if (receipts && method === 'POST') {
      if (refuseCompletion) return json({ code: 'upload_mismatch' }, 422)
      const pot = receipts[1]!
      const d = declared.get(body!.uploadId as string)!
      expect(d.ownerId).toBe(pot) // the upload is the Pot's own
      const row = {
        id: body!.id, ownerId: body!.ownerId, role: body!.role, blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize,
        wrappedBlobKey: d.wrappedBlobKey, wrappedBlobMeta: d.wrappedBlobMeta ?? null, writer: 'user:u-anna', createdAt: new Date().toISOString(),
        keptAt: null, releasedAt: null, purgeAfter: null, blockedByOperator: false,
      }
      listed.set(pot, [...(listed.get(pot) ?? []), row])
      state.log.push(`complete:${String(body!.id)}`)
      return json(row)
    }
    if (receipts && method === 'GET') {
      const rows = (listed.get(receipts[1]!) ?? []).map((r) => ({ ...r, blockedByOperator: blocked.has(r.blobId as string), ...served.get(r.id as string) }))
      return json({ blobs: rows, more: false, cursor: null })
    }
    if (method === 'POST' && url.pathname === `/api/scopes/${SCOPE}/blob-downloads-batch`) {
      return json({ results: (body!.items as { blobId: string }[]).map(({ blobId }) => {
        if (blocked.has(blobId)) return { blobId, presignedGets: null, error: 'blocked_by_operator' }
        const uploadId = [...declared].find(([, d]) => d.blobId === blobId)![0]
        return { blobId, presignedGets: stored.get(uploadId)!.map((_, index) => ({ index, url: `https://store.test/${uploadId}/${index}` })), error: null }
      }) })
    }
    throw new Error(`unexpected ${method} ${url.pathname}`)
  }))
}

// --- the Pots -----------------------------------------------------------------------------------------------------------

const source = () => state.pots.get(POT_ID)!
const target = () => state.pots.get(TARGET)!
const fileOf = (f: typeof JPEG) => new File([f.bytes as BlobPart], f.name, { type: f.type, lastModified: f.lastModified })

beforeEach(async () => {
  state.log.length = 0
  listed.clear()
  declared.clear()
  stored.clear()
  blocked.clear()
  served.clear()
  refuseCompletion = false
  fakeServer()
  state.pots.clear()
  state.pots.set(POT_ID, new PotServer(v1.randomBytes(32)))
  state.pots.set(TARGET, new PotServer(v1.randomBytes(32), SCOPE, TARGET))
  vi.mocked(readMemberPot).mockImplementation(async (_s, nodeId) => ({
    notModified: false, items: [...state.pots.get(nodeId)!.rows], revision: 1, deleteRevision: 0, ids: null, cursor: null, guestWriters: [],
  }))
  vi.mocked(appendPotWrites).mockImplementation(async (to, key, held, writes, base) => {
    const pot = state.pots.get(to.nodeId)!
    state.log.push(`append:${writes.flatMap((w) => ('receipts' in w.content ? w.content.receipts : [])).join(',')}`)
    const sealed = sealPotWrites({ scopeId: SCOPE, nodeId: to.nodeId }, key.key, held, writes, base)
    pot.append(writes, 'user:u-anna', held, base)
    return sealed
  })
  vi.mocked(createContentNode).mockImplementation(async () => ({ nodeId: TARGET, contentKey: target().potKey, epoch: 0 }))
  vi.mocked(deleteNode).mockClear()

  const s = source()
  s.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }, { entryId: CLEO, content: person('Cleo', 3) }])
  const member = { kind: 'member' as const, scopeId: SCOPE, nodeId: POT_ID }
  const key = { key: s.potKey, epoch: 0 }
  // a new entry's receipts are completed before its first revision, as the editor does it
  await uploadReceipt(member, key, E1, R1, fileOf(JPEG))
  await uploadReceipt(member, key, E2, R2, fileOf(PDF))
  const r3 = await uploadReceipt(member, key, E2, R3, fileOf(BLOCKED))
  blocked.add(r3.blobId)
  s.append([
    { entryId: E1, content: { ...expense('Ski pass', 9001, ANNA, [ANNA, BEN, CLEO]), receipts: [R1] } },
    { entryId: E2, content: { ...expense('Hotel', 30_000, BEN, [ANNA, BEN]), receipts: [R2, R3] } },
  ])
  state.log.length = 0
})

afterEach(() => vi.unstubAllGlobals())

const sourceTree = (): TreeSource<TreeNode> => {
  const pot = potNode(source().potKey)
  return {
    rootId: null, node: (id) => (id === POT_ID ? pot : null), children: () => [], keyOf: (id) => (id === POT_ID ? source().potKey : null),
    nameOf: (id) => (id === POT_ID ? pot.name : ''), inIdentityFolder: () => false, linkTo: () => '/',
  }
}

function memorySink() {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  return { sink, blob: () => new Blob(parts as BlobPart[]) }
}

async function downloaded() {
  const prepared = await prepareExport({ access, tree: sourceTree(), rootId: POT_ID, appVersion: 'test', now: () => new Date('2026-10-25T10:00:00Z') })
  const m = memorySink()
  await prepared.write(m.sink)
  return { prepared, zip: m.blob() }
}

/** The imported Pot's receipts, opened under its own key as its view opens them. */
async function openedReceipts(potId: string, potKey: Uint8Array): Promise<Map<string, { held: HeldReceipt; name: string | undefined; type: string | undefined; bytes: Uint8Array }>> {
  const rows = (listed.get(potId) ?? []) as unknown as Record<string, unknown>[]
  const out = new Map<string, { held: HeldReceipt; name: string | undefined; type: string | undefined; bytes: Uint8Array }>()
  for (const raw of rows) {
    const held = { ...raw, receiptId: raw.id, entryId: raw.ownerId, createdAt: new Date(raw.createdAt as string), droppedAt: null, purgeAfter: null } as unknown as HeldReceipt
    const meta = receiptMeta(SCOPE, potKey, potId, held)
    out.set(held.receiptId, { held, name: meta?.name as string | undefined, type: meta?.type as string | undefined, bytes: await potReceiptSource(access, SCOPE, potId, potKey, held).read() })
  }
  return out
}

/** What every import and copy must show: the original's balances, each carried receipt under a new id listed by its entry's
 *  revision, uploaded before that revision, opening byte-identical with its name and type, bound to the new Pot alone. */
async function expectReceiptsCarried() {
  const byName = (l: ReturnType<typeof buildLedger>) => new Map(l.participants.map((p) => [p.name, Number(l.balances.get(p.id))]))
  const before = buildLedger(source().held(), EUR)
  const after = buildLedger(target().held(), EUR)
  expect(byName(after)).toEqual(byName(before))

  const opened = await openedReceipts(TARGET, target().potKey)
  const entry = (title: string) => after.entries.find((e) => e.content?.title === title)!
  const ski = entry('Ski pass').content!.receipts
  const hotel = entry('Hotel').content!.receipts
  // the blocked receipt is not carried: Hotel lists the PDF alone
  expect([ski.length, hotel.length]).toEqual([1, 1])
  expect([...ski, ...hotel].some((id) => [R1, R2, R3].includes(id)), 'the receipts are under new ids').toBe(false)
  expect([...opened.keys()].sort()).toEqual([...ski, ...hotel].sort())
  for (const [ids, file, entryId] of [[ski, JPEG, entry('Ski pass').id], [hotel, PDF, entry('Hotel').id]] as const) {
    const got = opened.get(ids[0]!)!
    expect(got.held.entryId).toBe(entryId)
    expect(got.bytes).toEqual(file.bytes)
    expect({ name: got.name, type: got.type }).toEqual({ name: file.name, type: file.type })
    // its K_blob is bound to the new Pot and the receipt role, never to the Pot it came from
    const kBlob = () => fromB64std(got.held.wrappedBlobKey)
    expect(() => v1.open(blobKeyContext(SCOPE, { blobId: got.held.blobId, ownerId: TARGET, role: RECEIPT_ROLE }), target().potKey, kBlob())).not.toThrow()
    expect(() => v1.open(blobKeyContext(SCOPE, { blobId: got.held.blobId, ownerId: POT_ID, role: RECEIPT_ROLE }), target().potKey, kBlob())).toThrow()
  }
  // each receipt is completed before the append whose revision lists it
  for (const [i, line] of state.log.entries()) {
    if (!line.startsWith('append:')) continue
    for (const id of line.slice('append:'.length).split(',').filter(Boolean)) {
      const completed = state.log.indexOf(`complete:${id}`)
      expect(completed, `${id} completed before the batch listing it`).toBeGreaterThanOrEqual(0)
      expect(completed).toBeLessThan(i)
    }
  }
}

describe('a Pot with receipts as a download', () => {
  it('announces the ZIP\'s exact length with the receipts in it, each a file under its own name', async () => {
    const { prepared, zip } = await downloaded()
    expect(prepared.payloads).toBe(3) // the CSV, the JPEG, the PDF — not the blocked one
    expect(prepared.archiveBytes).toBe(zip.size)
    const opened = await openZip(new File([zip], 'Trip.zip'))
    const names = opened.entries.map((e) => e.name)
    expect(opened.get('Trip/Ski pass.jpg')!.size).toBe(JPEG.bytes.length)
    expect(names).toEqual(expect.arrayContaining(['Trip/Ski pass.jpg', 'Trip/Hotel-Rechnung.pdf', 'Trip/Trip.csv', MANIFEST_PATH]))
    expect(names.some((n) => n.includes('Blocked'))).toBe(false)
    // the blocked receipt's bytes were never asked for
    expect(state.log.filter((l) => l.startsWith('read:'))).not.toContain(`read:${String((listed.get(POT_ID)![2] as { blobId: string }).blobId)}`)
    const manifest = JSON.parse(new TextDecoder().decode(await opened.bytes(MANIFEST_PATH, 1 << 20))) as { root: ManifestNode }
    const entries = manifest.root.content!.entries as { entry: { title: string; receipts: string[] }; receipts?: { id: string; name: string; file: { path: string; mediaType: string; size: number } }[] }[]
    const hotel = entries.find((e) => e.entry.title === 'Hotel')!
    expect(hotel.entry.receipts).toEqual([R2])
    expect(hotel.receipts).toEqual([{ id: R2, name: PDF.name, file: expect.objectContaining({ path: 'Trip/Hotel-Rechnung.pdf', mediaType: 'application/pdf', size: PDF.bytes.length }) }])
  })

  it('imports into a new Pot: balances equal, every receipt under a new id before its revision, opening byte-identical', async () => {
    const { zip } = await downloaded()
    const opened = await openExport({ kind: 'zip', file: new File([zip], 'Trip.zip') })
    const res = await importSubtree({ scopeId: SCOPE, epochs, tree: [], parent: null, opened })
    expect(res.nodeId).toBe(TARGET)
    await expectReceiptsCarried()
  })

  it('an import whose receipt upload fails trashes the Pot it made and appends no entry', async () => {
    const { zip } = await downloaded()
    const opened = await openExport({ kind: 'zip', file: new File([zip], 'Trip.zip') })
    refuseCompletion = true
    await expect(importSubtree({ scopeId: SCOPE, epochs, tree: [], parent: null, opened })).rejects.toBeInstanceOf(ImportFailed)
    expect(vi.mocked(deleteNode)).toHaveBeenCalledWith(SCOPE, TARGET)
    expect(target().rows).toHaveLength(0)
  })
})

describe('what the listing does not hold for the entry', () => {
  it('a receipt it holds dropped, or holds for another entry, is left out of the download with its bytes', async () => {
    served.set(R1, { keptAt: null, releasedAt: '2026-10-25T09:00:00Z' })
    served.set(R2, { ownerId: E1 })
    const { prepared, zip } = await downloaded()
    expect(prepared.payloads).toBe(1) // the CSV alone
    const opened = await openZip(new File([zip], 'Trip.zip'))
    const manifest = JSON.parse(new TextDecoder().decode(await opened.bytes(MANIFEST_PATH, 1 << 20))) as { root: ManifestNode }
    const entries = manifest.root.content!.entries as { entry: { receipts: string[] }; receipts?: unknown[] }[]
    expect(entries.map((e) => [e.entry.receipts, e.receipts])).toEqual([[[], undefined], [[], undefined]])
    expect(state.log.some((l) => l.startsWith('read:'))).toBe(false)
  })
})

describe('a copy of a Pot with receipts', () => {
  it('carries every receipt, read by frame, under new ids bound to the copy — as the import does', async () => {
    const prepared = await prepareCopy({ access, tree: sourceTree(), rootIds: [POT_ID], appVersion: 'test' })
    expect(prepared.leftOut).toEqual([])
    expect(prepared.bytes).toBe(JPEG.bytes.length + PDF.bytes.length)
    await prepared.run({ scopeId: SCOPE, epochs, tree: [], parent: null })
    await expectReceiptsCarried()
  })

  it('no longer names the receipts among what a copy does not carry', () => {
    for (const locale of ['en', 'de'] as const) {
      const loss = i18n.global.t('expenses.copyLoss', {}, { locale })
      expect(loss.length).toBeGreaterThan(0)
      expect(loss).not.toMatch(/receipt|Beleg/i)
    }
  })
})
