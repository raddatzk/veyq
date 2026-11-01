// @vitest-environment happy-dom
// A rotation that lands WHILE an import or a copy is under way (inbox #670, ADR 0111 decision 11). The run creates its
// nodes at the epoch it started with; another member then rotates the scope, so what the run created lags too, and the
// run's own later writes into those nodes — a create below one, Memories' media, a Pot's appends and receipts, a Depot
// file's content, a Memory's cover — meet `node_rekey_pending`, or `stale_epoch` once another member's device re-keyed
// the node first. Each is one write that pulls the node's path forward on this device, re-reads its key and is sealed
// again under it: the run lands, nothing is created twice. A node below a drop box is an owner's to re-key: the pull says
// no and the write waits for an owner's device.
//
// The server here is an in-memory model of the epoch gate (`NodeEpochGate`, `NodesHandler.create`): every node's stored
// epoch, the scope's current one, and the gate's two refusals. The pull (`pullRekeyPath`) is modelled as what it does on
// a member's device — the path re-keyed to the current epoch under a fresh key, the scope re-opened at that epoch — and is
// pinned on its own in `core/node/rekey/rekeyScope.test.ts`; here, that the run asks for it, for which node, and what each
// write does with the answer. The kinds' seals are real (a Pot's revisions, a file's and a receipt's blob plans).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import type { EpochKeys } from '../../features/spaces/epochs'

const held = vi.hoisted(() => ({ epochs: { current: 0, keys: new Map() } as EpochKeys }))
vi.mock('../../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return { activeSpace: reactive({ scopeId: '5c09e000-0000-4000-8000-0000000000cc' }), activeEpochKeys: () => held.epochs }
})
vi.mock('../../api/client', async (orig) => ({ ...(await orig<object>()), contentApi: vi.fn(), applicationBlobsApi: vi.fn() }))
vi.mock('../../features/expenses/api', async (orig) => ({ ...(await orig<object>()), expensesApi: vi.fn() }))
vi.mock('../../features/depot/api', async (orig) => ({ ...(await orig<object>()), filesApi: vi.fn() }))
vi.mock('../../features/memories/api', async (orig) => ({ ...(await orig<object>()), mediaApi: vi.fn() }))
vi.mock('../node/rekey/rekeyScope', async (orig) => ({ ...(await orig<object>()), pullRekeyPath: vi.fn() }))
vi.mock('../node/rekey/floor', async (orig) => ({ ...(await orig<object>()), nodeKeyForWrite: vi.fn() }))
vi.mock('../node/treeStore', async (orig) => ({ ...(await orig<object>()), loadTree: vi.fn(async () => {}) }))
vi.mock('../blob/transport', async (orig) => ({ ...(await orig<object>()), reserveAndPump: vi.fn() }))
vi.mock('../node/cover/coverWrite', async (orig) => ({ ...(await orig<object>()), uploadCoverImage: vi.fn(), writeNodeCover: vi.fn() }))
vi.mock('../../features/memories/media', async (orig) => ({ ...(await orig<object>()), addMedia: vi.fn(), addCopiedMedia: vi.fn(), prepareMedia: vi.fn() }))

import { replaySubtrees, ImportFailed, type ReplayItem } from './importSubtree'
import { applicationBlobsApi, contentApi } from '../../api/client'
import { expensesApi } from '../../features/expenses/api'
import { filesApi } from '../../features/depot/api'
import { mediaApi } from '../../features/memories/api'
import type { MediaApi, MediaListPage } from '../../api/generated/media'
import { b64std } from '@veyq/crypto/base64'
import { fakeApi } from '../../test/apiMock'
import type { ContentApi, CreateNodeRequest, NodeSummary } from '../../api/generated/content'
import type { ExpensesApi } from '../../api/generated/expenses'
import type { ApplicationBlob, ApplicationBlobsApi } from '../../api/generated/blob'
import type { FileBlobRef, FilesApi } from '../../api/generated/depot'
import { MANIFEST_FORMAT, MANIFEST_VERSION, type Manifest, type ManifestNode } from './manifest'
import { FOLDER_KIND } from '../node/kind'
import { openName } from '../node/tree'
import { pullRekeyPath } from '../node/rekey/rekeyScope'
import { NodeKeyBelowFloorError, nodeKeyForWrite } from '../node/rekey/floor'
import { NodeRekeyPendingError, StaleNodeEpochError } from '../../shared/rekeyRetry'
import { reserveAndPump } from '../blob/transport'
import { uploadCoverImage, writeNodeCover, type UploadedCover, type WriteNodeCoverResult } from '../node/cover/coverWrite'
import { addCopiedMedia, addMedia, prepareMedia, type PreparedMedia } from '../../features/memories/media'
import { CHAPTER_KIND, MEMORY_KIND } from '../../features/memories/kinds'
import { CHAPTER_CONTENT, MEMORY_CONTENT } from '../../features/memories/exportImport'
import { POT_KIND } from '../../features/expenses/kinds'
import { EXPENSES_POT_CONTENT } from '../../features/expenses/exportImport'
import { openServedRevisions } from '../../features/expenses/domain/revisions'
import { ANNA, expense } from '../../features/expenses/potTestUtil'
import { FILE_KIND } from '../../features/depot/kinds'
import { DEPOT_FILE_CONTENT } from '../../features/depot/exportImport'

const SCOPE = '5c09e000-0000-4000-8000-0000000000cc'
const PARENT = 'f0000000-0000-4000-8000-0000000000aa'
const PARENT_KEY = v1.randomBytes(32)

/** A thrown generated-client error carrying `409 { code }`, the shape `isCodedError` reads. */
const refusal = (code: string) => Object.assign(new Error(code), {
  response: new Response(JSON.stringify({ code }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
})

/** The server: each node's stored epoch, parent and current key (as the last re-key left it), the scope's current epoch. */
type Node = { epoch: number; parentId: string | null; key: Uint8Array | null; kind: string }
let server: { current: number; nodes: Map<string, Node>; ownerOnly: Set<string> }
/** Run once right after the create of a node of this kind landed — where the test has another member rotate. */
let afterCreate: Partial<Record<string, (nodeId: string) => void>>

/** `NodeEpochGate.check`: the stated epoch is the stored one, and the stored one is the scope's current one. */
function gate(nodeId: string, stated: number): void {
  const node = server.nodes.get(nodeId)!
  if (stated !== node.epoch) throw refusal('stale_epoch')
  if (node.epoch < server.current) throw refusal('node_rekey_pending')
}
/** A medium's completion through the same gate - answered per item inside a 200 (`MediaStore.attach`), which the media
 *  transport throws as these two (found live, inbox #701: the stale one was once a bare `Error`). */
function mediaGate(nodeId: string, stated: number): void {
  const node = server.nodes.get(nodeId)!
  if (stated !== node.epoch) throw new StaleNodeEpochError()
  if (node.epoch < server.current) throw new NodeRekeyPendingError()
}
/** Another member rotates the scope (and, with `rekeyed`, its device re-keys those nodes before this run writes again). */
function rotate(rekeyed: string[] = []): void {
  server.current++
  for (const id of rekeyed) rekey(id)
}
function rekey(id: string): void {
  const node = server.nodes.get(id)!
  if (node.epoch < server.current) Object.assign(node, { epoch: server.current, key: v1.randomBytes(32) })
}
/** The scope's epoch keys a device holds once it opened the scope at `current`. */
const EPOCH_KEYS = [v1.randomBytes(32), v1.randomBytes(32), v1.randomBytes(32)]
const epochsAt = (current: number): EpochKeys => ({ current, keys: new Map(EPOCH_KEYS.slice(0, current + 1).map((k, e) => [e, k])) })
const keyOf = (id: string) => server.nodes.get(id)!.key!
const ofKind = (kind: string) => [...server.nodes.entries()].filter(([, n]) => n.kind === kind).map(([id]) => id)
const one = (kind: string) => ofKind(kind)[0]!

const pull = vi.fn()
const creates: CreateNodeRequest[] = []
let pot: { appendPotRevisions: ReturnType<typeof vi.fn> }
let blobs: { completeApplicationBlob: ReturnType<typeof vi.fn> }
let files: { completeFile: ReturnType<typeof vi.fn> }
let plans: { blobId: string; chunkCount: number; byteSize: number; wrappedBlobKey: string }[]

beforeEach(() => {
  vi.clearAllMocks()
  // a download's original goes through its on-device stage before `addMedia` (inbox #784); a byte string of 7s decodes to nothing
  vi.mocked(prepareMedia).mockImplementation(async (source) => ({ source, sha256: '', tiers: [], meta: { dims: null } }) as unknown as PreparedMedia)
  creates.length = 0
  afterCreate = {}
  server = { current: 0, nodes: new Map([[PARENT, { epoch: 0, parentId: null, key: PARENT_KEY, kind: FOLDER_KIND }]]), ownerOnly: new Set() }
  held.epochs = epochsAt(0)

  // `NodesHandler.create`: the node's epoch is the scope's current one (or, deferred, its parent's as stated), the parent
  // passes the gate, and a node epoch still below the current one after it is stale
  fakeApi(contentApi, {
    createNode: vi.fn<ContentApi['createNode']>(async ({ createNodeRequest: r }) => {
      creates.push(r)
      if (r.epoch !== server.current && (!r.parentId || r.epoch !== r.parentEpoch)) throw refusal('stale_epoch')
      if (r.parentId) gate(r.parentId, r.parentEpoch!)
      if (r.epoch !== server.current) throw refusal('stale_epoch')
      server.nodes.set(r.nodeId, { epoch: r.epoch, parentId: r.parentId ?? null, key: null, kind: r.kind })
      afterCreate[r.kind]?.(r.nodeId)
      return { nodeId: r.nodeId }
    }),
    listNodes: vi.fn<ContentApi['listNodes']>(async () =>
      [...server.nodes.entries()].map(([id, n]) => ({ id, parentId: n.parentId, kind: n.kind, epoch: n.epoch, wrappedMeta: null, metaRevision: 0 }) as unknown as NodeSummary)),
  })
  pot = fakeApi(expensesApi, {
    appendPotRevisions: vi.fn<ExpensesApi['appendPotRevisions']>(async (r) => gate(r.nodeId, r.potAppendRequest.nodeEpoch!)),
  })
  // a Pot's receipt is completed through core's blob service (ADR 0112), through the same gate
  blobs = fakeApi(applicationBlobsApi, {
    completeApplicationBlob: vi.fn<ApplicationBlobsApi['completeApplicationBlob']>(async (r) => {
      const q = r.applicationBlobCompleteRequest
      gate(r.nodeId, q.nodeEpoch!)
      const p = plans.at(-1)!
      return { ...p, id: q.id, ownerId: q.ownerId, role: q.role, createdAt: new Date(), keptAt: null, releasedAt: null, blockedByOperator: false } as unknown as ApplicationBlob
    }),
  })
  files = fakeApi(filesApi, {
    completeFile: vi.fn<FilesApi['completeFile']>(async (r) => {
      gate(r.nodeId, r.fileCompleteRequest.nodeEpoch!)
      return { ...plans.at(-1)!, createdAt: new Date() } as unknown as FileBlobRef
    }),
  })
  plans = []
  vi.mocked(reserveAndPump).mockImplementation(async (ps) => {
    plans.push(...ps.map((p) => ({ blobId: p.blobId, chunkCount: p.chunkCount, byteSize: p.byteSize, wrappedBlobKey: p.wrappedBlobKey })))
    return ps.map((_, i) => `upload-${plans.length}-${i}`)
  })
  // a medium's completion states the node's epoch through the same gate, refused per item
  const media = async (_s: string, nodeId: string, _k: Uint8Array, _src: unknown, ...rest: unknown[]) => {
    const opts = rest.at(-1) as { nodeEpoch: number }
    mediaGate(nodeId, opts.nodeEpoch)
    return { blobId: crypto.randomUUID(), mediaId: crypto.randomUUID() }
  }
  vi.mocked(addMedia).mockImplementation(media as unknown as typeof addMedia)
  vi.mocked(addCopiedMedia).mockImplementation(media as unknown as typeof addCopiedMedia)
  vi.mocked(uploadCoverImage).mockResolvedValue({ uploadId: 'cover-up', plan: { blobId: 'b', chunkCount: 1, byteSize: 1, wrappedBlobKey: 'w', ownerId: 'o' } } as unknown as UploadedCover)
  vi.mocked(writeNodeCover).mockImplementation(async (a) => {
    gate(a.nodeId, a.current.nodeEpoch!)
    return { wrappedMeta: 'm', metaRevision: 1, replaced: null } as unknown as WriteNodeCoverResult
  })

  // the pull on a member's device: the node's path re-keyed to the current epoch, the scope re-opened at it - or, for a
  // path through a node only an owner re-keys, only the scope re-opened (`RekeyPathNotMineError`), and the write falls
  // back to the waits
  vi.mocked(pullRekeyPath).mockImplementation((_scope, ids) => async () => {
    pull(ids)
    const path = new Set<string>()
    for (const id of ids) for (let at: string | null = id; at; at = server.nodes.get(at)!.parentId) path.add(at)
    held.epochs = epochsAt(server.current)
    if ([...path].some((id) => server.ownerOnly.has(id))) return false
    for (const id of path) rekey(id)
    return true
  })
  // the re-read: the node's key and epoch as the re-keyed tree holds them, through the device floor - and only once this
  // device holds the epoch the re-key minted it at (the chain opens from the scope root's entry under `E_{epoch}`)
  vi.mocked(nodeKeyForWrite).mockImplementation((_scope, epochs, _tree, id) => {
    const node = server.nodes.get(id)!
    if (node.epoch < epochs.current) throw new NodeKeyBelowFloorError(id, node.epoch, epochs.current)
    return node.key && epochs.keys.has(node.epoch) ? { key: node.key, epoch: node.epoch } : null
  })
})
afterEach(() => vi.useRealTimers())

const manifestOf = (root: ManifestNode): Manifest =>
  ({ format: MANIFEST_FORMAT, version: MANIFEST_VERSION, exportedAt: '2026-10-28T00:00:00Z', appVersion: 'test', root }) as Manifest
const payloadFile = (name: string, type = 'image/jpeg') => new File([new Uint8Array(64).fill(7)], name, { type, lastModified: 1 })
const item = (root: ManifestNode, purpose: 'import' | 'copy' = 'import'): ReplayItem => ({
  manifest: manifestOf(root),
  payload: vi.fn(async (ref: { path: string; mediaType: string }) => payloadFile(ref.path.split('/').pop()!, ref.mediaType)) as unknown as ReplayItem['payload'],
  plan: { untrusted: false },
  purpose,
})
const replay = (root: ManifestNode, purpose?: 'import' | 'copy') =>
  replaySubtrees({ scopeId: SCOPE, epochs: held.epochs, tree: [], parent: { id: PARENT, key: PARENT_KEY, epoch: 0 }, items: [item(root, purpose)] })

const photo = (n: number) => ({ file: { path: `Rome/p${n}.jpg`, size: 64, mediaType: 'image/jpeg' }, name: `p${n}.jpg` })
const copiedPhoto = (n: number) => ({ ...photo(n), copy: { original: { type: 'image/jpeg' }, tiers: [] } })
const memory = (media: unknown[], extra: Record<string, unknown> = {}, children?: ManifestNode[]): ManifestNode => ({
  id: 'm', name: 'Rome', kind: MEMORY_KIND, content: { type: MEMORY_CONTENT, schema: 1, media, ...extra }, ...(children ? { children } : {}),
})
/** Each medium's completion as sent: the node, the key it sealed under and the epoch it stated. */
const mediaWrites = (fn: typeof addMedia | typeof addCopiedMedia) =>
  vi.mocked(fn).mock.calls.map((c) => ({ nodeId: c[1] as string, key: c[2] as Uint8Array, epoch: (c.at(-1) as { nodeEpoch: number }).nodeEpoch }))

describe('a rotation lands while an import or a copy is under way (inbox #670)', () => {
  it('a Memory\'s next medium meets node_rekey_pending: the Memory is pulled forward on this device and that medium sealed again under its new key', async () => {
    let added = 0
    vi.mocked(addMedia).mockImplementation(async (_s, nodeId, _k, _src, _d, opts) => {
      mediaGate(nodeId, opts!.nodeEpoch!)
      if (++added === 1) rotate() // another member rotates right after the first medium landed
      return { blobId: crypto.randomUUID(), mediaId: crypto.randomUUID() }
    })
    await replay(memory([photo(1), photo(2), photo(3)]))
    const rome = one(MEMORY_KIND)
    expect(pull).toHaveBeenCalledTimes(1)
    expect(pull).toHaveBeenCalledWith([rome])
    const writes = mediaWrites(addMedia)
    expect(writes.map((w) => w.epoch)).toEqual([0, 0, 1, 1]) // p1, p2 refused, p2 again, p3 under the key the pull left
    expect(writes[2]!.key).toEqual(keyOf(rome))
    expect(writes[3]!.key).toEqual(keyOf(rome))
    expect(server.nodes.get(rome)!.epoch).toBe(1)
    expect(ofKind(MEMORY_KIND)).toHaveLength(1) // the Memory was not created twice
    // p2 is sealed again, never prepared again: its second write carries the stage its first one had (inbox #784)
    expect(prepareMedia).toHaveBeenCalledTimes(3)
    const prepared = vi.mocked(addMedia).mock.calls.map((c) => c[5]!.prepared)
    expect(prepared[2]).toBe(prepared[1])
    expect(new Set(prepared).size).toBe(3)
  })

  it('a copied Memory\'s medium meets stale_epoch - another member\'s device re-keyed it first - and lands after one pull and re-read', async () => {
    afterCreate[MEMORY_KIND] = (id) => rotate([PARENT, id])
    await replay(memory([copiedPhoto(1)]), 'copy')
    const rome = one(MEMORY_KIND)
    // the pull re-keys nothing: it brings this device to the scope's epoch, under which the re-read opens the new key
    expect(pull).toHaveBeenCalledWith([rome])
    const writes = mediaWrites(addCopiedMedia)
    expect(writes.map((w) => w.epoch)).toEqual([0, 1])
    expect(writes[1]!.key).toEqual(keyOf(rome))
  })

  it('what is created below a node the run made after the rotation states the epoch the device re-opened the scope at', async () => {
    afterCreate[FOLDER_KIND] = () => rotate()
    const trips: ManifestNode = { id: 'f', name: 'Trips', kind: FOLDER_KIND, children: [memory([])] }
    await replay(trips)
    const folder = ofKind(FOLDER_KIND).find((id) => id !== PARENT)!
    expect(pull).toHaveBeenCalledWith([folder])
    const last = creates.at(-1)!
    expect(last).toMatchObject({ kind: MEMORY_KIND, parentId: folder, parentEpoch: 1, epoch: 1 })
    expect(openName(keyOf(folder), { scopeId: SCOPE, nodeId: last.nodeId, epoch: 1, placementRevision: 0 }, last.encryptedName)).toBe('Rome')
  })

  it('a copy of two nodes to the scope root: the second create, after the rotation, re-opens the scope and states its epoch', async () => {
    let made = 0
    afterCreate[FOLDER_KIND] = () => void (++made === 1 && rotate())
    const items = ['A', 'B'].map((name) => item({ id: name, name, kind: FOLDER_KIND }, 'copy'))
    await replaySubtrees({ scopeId: SCOPE, epochs: held.epochs, tree: [], parent: null, items })
    expect(pull).toHaveBeenCalledWith([]) // the root's path is empty: the pull only brings this device to the scope's epoch
    expect(creates.map((r) => [r.parentId ?? null, r.epoch])).toEqual([[null, 0], [null, 0], [null, 1]])
  })

  it('a Memory pulled forward by its own media hands its new key to the chapters created below it', async () => {
    let added = 0
    vi.mocked(addMedia).mockImplementation(async (_s, nodeId, _k, _src, _d, opts) => {
      if (++added === 1) rotate()
      mediaGate(nodeId, opts!.nodeEpoch!)
      return { blobId: crypto.randomUUID(), mediaId: crypto.randomUUID() }
    })
    const day: ManifestNode = { id: 'c', name: 'Day 1', kind: CHAPTER_KIND, content: { type: CHAPTER_CONTENT, schema: 1, media: [] } }
    await replay(memory([photo(1)], {}, [day]))
    const rome = one(MEMORY_KIND)
    expect(pull).toHaveBeenCalledTimes(1) // the medium's: the chapter's create is not refused
    const chapter = creates.at(-1)!
    expect(chapter).toMatchObject({ kind: CHAPTER_KIND, parentId: rome, parentEpoch: 1, epoch: 1 })
    expect(openName(keyOf(rome), { scopeId: SCOPE, nodeId: chapter.nodeId, epoch: 1, placementRevision: 0 }, chapter.encryptedName)).toBe('Day 1')
  })

  it('a Memory\'s cover after the rotation is uploaded and written under the new key; the row ahead of the key is caught before the upload', async () => {
    afterCreate[MEMORY_KIND] = (id) => rotate([PARENT, id]) // re-keyed elsewhere: the row the cover reads is ahead
    await replay(memory([], { cover: { file: { path: 'Rome/cover.jpg', size: 64, mediaType: 'image/jpeg' } } }))
    const rome = one(MEMORY_KIND)
    expect(pull).toHaveBeenCalledWith([rome])
    expect(uploadCoverImage).toHaveBeenCalledTimes(1) // nothing was uploaded under the old key
    expect(vi.mocked(uploadCoverImage).mock.calls[0]![2]).toEqual(keyOf(rome))
    expect(vi.mocked(writeNodeCover).mock.calls[0]![0]).toMatchObject({ nodeKey: keyOf(rome), current: { nodeEpoch: 1 } })
  })

  const R1 = '0e000000-0000-4000-a000-000000000001'
  const potNode = (receipts: boolean): ManifestNode => ({
    id: 'p', name: 'Ski', kind: POT_KIND,
    content: {
      type: EXPENSES_POT_CONTENT, schema: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [],
      participants: [{ id: ANNA, joined: 1, name: 'Anna' }],
      entries: [{
        id: '0e000000-0000-4000-9000-000000000001', entry: { ...expense('Taxi', 100, ANNA, [ANNA]), receipts: receipts ? [R1] : [] },
        ...(receipts ? { receipts: [{ id: R1, name: 'taxi.jpg', file: { path: 'Ski/taxi.jpg', size: 64, mediaType: 'image/jpeg' } }] } : {}),
      }],
    },
  })

  it('a Pot\'s append meets node_rekey_pending: the batch is sealed again under the Pot\'s new key and lands', async () => {
    afterCreate[POT_KIND] = () => rotate()
    await replay(potNode(false))
    const ski = one(POT_KIND)
    expect(pull).toHaveBeenCalledWith([ski])
    const sent = pot.appendPotRevisions.mock.calls.map((c) => c[0].potAppendRequest)
    expect(sent.map((r) => r.nodeEpoch)).toEqual([0, 1])
    // the batch that landed - the participant and the entry - opens under the Pot's key now
    expect(sent[1]!.revisions).toHaveLength(2)
    expect(openServedRevisions(SCOPE, ski, keyOf(ski), sent[1]!.revisions.map(wire)).every((h) => h.unreadable === null)).toBe(true)
  })

  it('a Pot\'s receipt meets node_rekey_pending: it is uploaded again under the new key, and the batch after it lands at once', async () => {
    afterCreate[POT_KIND] = () => rotate()
    await replay(potNode(true))
    const ski = one(POT_KIND)
    expect(pull).toHaveBeenCalledTimes(1)
    expect(blobs.completeApplicationBlob.mock.calls.map((c) => c[0].applicationBlobCompleteRequest.nodeEpoch)).toEqual([0, 1])
    expect(pot.appendPotRevisions.mock.calls.map((c) => c[0].potAppendRequest.nodeEpoch)).toEqual([1])
    expect(openServedRevisions(SCOPE, ski, keyOf(ski), pot.appendPotRevisions.mock.calls[0]![0].potAppendRequest.revisions.map(wire))
      .every((h) => h.unreadable === null)).toBe(true)
  })

  it('a Depot file\'s content meets stale_epoch - re-keyed elsewhere while it uploaded - and is uploaded again under the new key', async () => {
    afterCreate[FILE_KIND] = (id) => rotate([PARENT, id])
    const report: ManifestNode = { id: 'd', name: 'report.pdf', kind: FILE_KIND, content: { type: DEPOT_FILE_CONTENT, schema: 1, file: { path: 'report.pdf', size: 64, mediaType: 'application/pdf' } } }
    await replay(report)
    const file = one(FILE_KIND)
    expect(pull).toHaveBeenCalledWith([file])
    expect(files.completeFile.mock.calls.map((c) => c[0].fileCompleteRequest.nodeEpoch)).toEqual([0, 1])
  })

  it('a download\'s corrected capture time and place name land with the medium: a re-key right after the last medium leaves nothing to open under the old key (inbox #700)', async () => {
    // the server's media rows: each tier as the server holds it, re-sealed under the node's key by any re-key since
    const served: { nodeId: string; mediaId: string; tier: object }[] = []
    vi.mocked(addMedia).mockImplementation(async (_s, nodeId, _k, _src, _d, opts) => {
      mediaGate(nodeId, opts!.nodeEpoch!)
      const mediaId = crypto.randomUUID()
      served.push({ nodeId, mediaId, tier: { v: 1, capturedAt: 1, ...opts!.restored } })
      rotate([PARENT, nodeId]) // another member's device rotates and re-keys the Memory right after its last medium landed
      return { blobId: crypto.randomUUID(), mediaId }
    })
    const metaCtx = (mediaId: string) => ({ purpose: 'media-meta', scopeId: SCOPE, mediaId }) as const
    const media = fakeApi(mediaApi, {
      listNodeMedia: vi.fn<MediaApi['listNodeMedia']>(async ({ nodeId }) => ({
        media: served.filter((m) => m.nodeId === nodeId).map((m) => ({
          mediaId: m.mediaId, blobId: crypto.randomUUID(), chunkCount: 1, byteSize: 64, wrappedBlobKey: 'k', metaRevision: 1, updatedAt: new Date(),
          wrappedMeta: b64std(v1.sealField(metaCtx(m.mediaId), keyOf(nodeId), new TextEncoder().encode(JSON.stringify(m.tier)))),
        })),
        mediaRevision: 1, deleteRevision: 0,
      }) as unknown as MediaListPage),
      updateMediaMeta: vi.fn<MediaApi['updateMediaMeta']>(),
    })
    const corrected = '2023-11-14T22:15:00.000Z'
    await replay(memory([{ ...photo(1), capturedAt: corrected, capturedAtCorrected: true, location: { place: 'Trastevere' } }]))
    expect(mediaWrites(addMedia).map((w) => w.epoch)).toEqual([0])
    expect(vi.mocked(addMedia).mock.calls[0]![5]!.restored).toEqual({ capturedAtOverride: Date.parse(corrected), placeName: 'Trastevere' })
    expect([media.listNodeMedia, media.updateMediaMeta].map((f) => f.mock.calls.length), 'nothing re-read, nothing re-sealed after the medium').toEqual([0, 0])
  })

  it('below a drop box (an owner\'s to re-key) the run\'s next medium waits - and lands once an owner\'s device re-keyed the Memory', async () => {
    vi.useFakeTimers()
    server.ownerOnly.add(PARENT)
    afterCreate[MEMORY_KIND] = () => rotate()
    const run = replay(memory([photo(1)]))
    await vi.advanceTimersByTimeAsync(1_000)
    const rome = one(MEMORY_KIND)
    expect(pull).toHaveBeenCalledWith([rome])
    expect(mediaWrites(addMedia)).toHaveLength(1) // refused, and waiting: the pull said no
    rekey(PARENT)
    rekey(rome) // an owner's device
    await vi.advanceTimersByTimeAsync(2_000) // the first wait
    await run
    const writes = mediaWrites(addMedia)
    expect(writes.map((w) => w.epoch)).toEqual([0, 1])
    expect(writes[1]!.key).toEqual(keyOf(rome))
  })

  it('below a drop box that no owner re-keys, the waits run out and the run is rolled back, never written under the old key', async () => {
    vi.useFakeTimers()
    server.ownerOnly.add(PARENT)
    afterCreate[MEMORY_KIND] = () => rotate()
    const run = replay(memory([photo(1)])).catch((e: unknown) => e)
    await vi.advanceTimersByTimeAsync(20_000)
    const failed = await run
    expect(failed).toBeInstanceOf(ImportFailed)
    expect(mediaWrites(addMedia).every((w) => w.epoch === 0)).toBe(true)
  })
})

/** A sent revision as a server serves it back. */
function wire(r: { entryId: string; revision: number; sealedBody: string; wrappedRevisionKey: string }) {
  const bytes = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0))
  return { entryId: r.entryId, revision: r.revision, createdAt: new Date(), sealedBody: bytes(r.sealedBody), wrappedRevisionKey: bytes(r.wrappedRevisionKey) }
}
