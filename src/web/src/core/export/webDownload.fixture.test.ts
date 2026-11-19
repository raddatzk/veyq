// @vitest-environment happy-dom
// The download the web writes, committed for the core to import (ADR 0152 decision 4, the reverse of the core's
// `app-memory-download.zip`): `contracts/export/import-cases/web-download.zip`, a folder holding a Depot file, a drop box
// with a guest's folder, a Pot with an expense and its receipt, and a Memory with a photo and a chapter - walked by core's
// real `exportSubtree` with each application's registered export hook, over its reads stubbed here. The core's
// `WebDownloadImportTest` opens, plans and imports it.
//
// The committed file is held to what this writes, byte for byte: a change to the web's archive shows here first. A
// deliberate one rewrites it with `VEYQ_WRITE_IMPORT_FIXTURE=1 pnpm exec vitest run src/core/export/webDownload.fixture.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../features/depot/api', () => ({ filesApi: vi.fn(), publicShareFilesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../features/depot/download', async (orig) => ({ ...(await orig<object>()), fileFacts: vi.fn(), fileChunkProvider: vi.fn() }))
vi.mock('../../features/expenses/domain/potApi', async (orig) => ({ ...(await orig<object>()), readMemberPot: vi.fn() }))
vi.mock('../../features/expenses/receipts', async (orig) => ({ ...(await orig<object>()), readMemberReceipts: vi.fn(), receiptChunks: vi.fn(), receiptSize: vi.fn() }))
vi.mock('../../features/memories/media', async (orig) => ({
  ...(await orig<object>()),
  listMediaPages: vi.fn(), unsealMeta: vi.fn(), originalBlobMeta: vi.fn(), originalMediaType: vi.fn(), loadMediaExif: vi.fn(), chunkProvider: vi.fn(),
  plaintextSize: vi.fn(),
}))
vi.mock('../people/directory', () => ({ loadTenantPeople: vi.fn() }))

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import type { ByteSink } from '../blob/stream'
import type { TreeSource } from '../access'
import type { TreeNode } from '../node/tree'
import { sealNodeMeta } from '../node/nodeMeta'
import { memberAccessOf, treeNode } from '../../test/models'
import { stubApi } from '../../test/apiMock'
import { filesApi } from '../../features/depot/api'
import { fileChunkProvider, fileFacts } from '../../features/depot/download'
import { readMemberPot } from '../../features/expenses/domain/potApi'
import { readMemberReceipts, receiptChunks, receiptSize } from '../../features/expenses/receipts'
import { ANNA, BEN, POT_ID, PotServer, SCOPE, expense, person, potNode } from '../../features/expenses/potTestUtil'
import { chunkProvider, listMediaPages, loadMediaExif, originalBlobMeta, originalMediaType, plaintextSize, unsealMeta, type MediaItem } from '../../features/memories/media'
import { loadTenantPeople } from '../people/directory'
import { exportSubtree } from './exportSubtree'
import { openExport, planImport } from './importSubtree'

const FIXTURE = resolve(__dirname, '../../../../../contracts/export/import-cases/web-download.zip')
const PHOTO = new Uint8Array(readFileSync(resolve(__dirname, '../../features/memories/__fixtures__/exif-sample.jpg')))
const NOTES = new TextEncoder().encode('Packing list: passport, charger, sunscreen.\n')
const RECEIPT = PHOTO.slice() // a photographed receipt: any image is one
const EXPENSE = '0e000000-0000-4000-9000-000000000001'
const MEMORY_KEY = new Uint8Array(32).fill(7)
const OTHER_KEY = new Uint8Array(32).fill(3)
const NOW = new Date('2026-11-19T12:00:00.000Z')
const ROOT = '0f000000-0000-4000-8000-000000000001'
const FILE_ID = '0f000000-0000-4000-8000-000000000002'
const BOX = '0f000000-0000-4000-8000-000000000003'
const GUEST = '0f000000-0000-4000-8000-000000000004'
const MEMORY = '0f000000-0000-4000-8000-000000000005'
const CHAPTER = '0f000000-0000-4000-8000-000000000006'

const pot = new PotServer(new Uint8Array(32).fill(5))
pot.append([{ entryId: ANNA, content: person('Anna', 1) }, { entryId: BEN, content: person('Ben', 2) }])
const held = pot.receipt(EXPENSE, { name: 'Dinner.jpg', type: 'image/jpeg', bytes: RECEIPT }, { receiptId: '0e000000-0000-4000-a000-000000000001' })
pot.append([{ entryId: EXPENSE, content: { ...expense('Dinner', 4200, ANNA, [ANNA, BEN]), receipts: [held.receiptId] } }])

const TREE: TreeNode[] = [
  treeNode({ id: ROOT, parentId: null, kind: 'FOLDER', name: 'Trip', orderIndex: 0 }),
  treeNode({ id: FILE_ID, parentId: ROOT, kind: 'FILE', name: 'notes.txt', orderIndex: 0 }),
  treeNode({ id: BOX, parentId: ROOT, kind: 'DEPOT_DROPBOX', name: 'Inbox', orderIndex: 1 }),
  treeNode({ id: GUEST, parentId: BOX, kind: 'FOLDER', name: 'Anna', orderIndex: 0 }),
  { ...potNode(pot.potKey), parentId: ROOT, name: 'Budget', orderIndex: 2 },
  treeNode({
    id: MEMORY, parentId: ROOT, kind: 'MEMORY', name: 'Lisbon', orderIndex: 3,
    wrappedMeta: sealNodeMeta(MEMORY_KEY, { scopeId: SCOPE, nodeId: MEMORY }, { v: 1, date: { mode: 'single', from: '2025-09-14' } }),
  }),
  treeNode({ id: CHAPTER, parentId: MEMORY, kind: 'CHAPTER', name: 'Day one', orderIndex: 0 }),
]
const tree: TreeSource<TreeNode> = {
  rootId: null,
  node: (id) => TREE.find((n) => n.id === id) ?? null,
  children: (id) => TREE.filter((n) => n.parentId === id),
  keyOf: (id) => (id === POT_ID ? pot.potKey : id === MEMORY || id === CHAPTER ? MEMORY_KEY : OTHER_KEY),
  nameOf: () => '',
  inIdentityFolder: () => false,
  linkTo: () => '/',
}
const one = (bytes: Uint8Array) => ({ chunkCount: 1, getPlaintextChunk: async () => bytes })
const photo = { mediaId: 'p1', ownerKey: 'user:u-anna', original: { byteSize: PHOTO.length, chunkCount: 1 }, derivatives: [] } as unknown as MediaItem

beforeEach(() => {
  stubApi(filesApi, { listFiles: { items: [{ nodeId: FILE_ID, fileBlob: { blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', wrappedBlobMeta: null } }] } })
  vi.mocked(fileFacts).mockReturnValue({ size: NOTES.length, meta: { v: 1, type: 'text/plain', name: 'notes.txt', modifiedAt: 1_760_000_000_000 } } as ReturnType<typeof fileFacts>)
  vi.mocked(fileChunkProvider).mockReturnValue(one(NOTES) as unknown as ReturnType<typeof fileChunkProvider>)
  vi.mocked(readMemberPot).mockResolvedValue({ notModified: false, items: [...pot.rows], revision: 1, deleteRevision: 0, ids: null, cursor: null, guestWriters: [] } as unknown as Awaited<ReturnType<typeof readMemberPot>>)
  vi.mocked(readMemberReceipts).mockResolvedValue({ receipts: pot.receipts } as unknown as Awaited<ReturnType<typeof readMemberReceipts>>)
  vi.mocked(receiptSize).mockReturnValue(RECEIPT.length)
  vi.mocked(receiptChunks).mockReturnValue({ ...one(RECEIPT), meta: () => ({ v: 1, type: 'image/jpeg', name: 'Dinner.jpg', modifiedAt: 1_760_000_100_000 }) } as unknown as ReturnType<typeof receiptChunks>)
  vi.mocked(listMediaPages).mockImplementation(async (_s, nodeId) =>
    ({ notModified: false, items: nodeId === MEMORY ? [photo] : [], mediaRevision: 1, deleteRevision: 0, mediaIds: null, cursor: null }) as unknown as Awaited<ReturnType<typeof listMediaPages>>)
  vi.mocked(unsealMeta).mockReturnValue({ v: 1, capturedAt: 1_757_836_800_000, gpsLat: 38.7, gpsLng: -9.1 } as ReturnType<typeof unsealMeta>)
  vi.mocked(originalBlobMeta).mockReturnValue({ v: 1, type: 'image/jpeg', name: 'IMG_0001.jpg', modifiedAt: 1_757_836_800_000 } as ReturnType<typeof originalBlobMeta>)
  vi.mocked(originalMediaType).mockReturnValue('image/jpeg')
  vi.mocked(plaintextSize).mockReturnValue(PHOTO.length)
  vi.mocked(chunkProvider).mockReturnValue(one(PHOTO) as unknown as ReturnType<typeof chunkProvider>)
  vi.mocked(loadMediaExif).mockResolvedValue(null as unknown as Awaited<ReturnType<typeof loadMediaExif>>)
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map())
})

async function written(): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  const result = await exportSubtree({ access: memberAccessOf({ scopeId: SCOPE, epochs: { epoch: 1, key: v1.randomBytes(32) } }), tree, rootId: ROOT, sink, appVersion: '1', now: () => NOW })
  expect(result.skipped, 'every kind of the tree is written').toEqual([])
  return new Uint8Array(await new Blob(parts as BlobPart[]).arrayBuffer())
}

describe('contracts/export/import-cases/web-download.zip', () => {
  it('is what the web writes of a folder with a file, a drop box, a Pot with a receipt and a Memory with a photo and a chapter', async () => {
    const bytes = await written()
    if (process.env.VEYQ_WRITE_IMPORT_FIXTURE) writeFileSync(FIXTURE, bytes)
    expect(existsSync(FIXTURE), 'the fixture is committed').toBe(true)
    expect(Buffer.from(readFileSync(FIXTURE)).equals(Buffer.from(bytes)), 'the committed archive is what the web writes, byte for byte').toBe(true)

    // and the web's own importer plans all of it
    const opened = await openExport({ kind: 'zip', file: new File([bytes as BlobPart], 'Trip.zip') })
    const plan = planImport(opened.manifest)
    expect(plan.skipped).toEqual([])
    expect(plan.nodes).toBe(TREE.length)
    expect(opened.manifest.applications?.map((a) => a.id).sort()).toEqual(['depot', 'expenses', 'memories'])
  })
})
