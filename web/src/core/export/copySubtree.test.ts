// @vitest-environment node
// A copy (uc.platform.copy-node, `copySubtree.ts`): the download's walk handed to the import without an archive. Core's node
// writes are mocked (their wire is `tree.wire.test.ts`; a copied file's whole wire is `features/depot/copy.wire.test.ts`);
// the kinds are made-up ones with hooks of the shapes the applications' have. What this pins:
//   - the walk is planned before anything is written: what it creates, its bytes, the kinds (the losses) and every node it
//     leaves out by name - a kind whose import cannot take a payload read by frame (Decision 3), a file with nothing in it;
//   - the replay re-creates the subtree through the kinds' hooks under the destination, a drop-box-like kind as a folder,
//     `Name (2)` beside the original, several nodes as one copy;
//   - each payload reaches the hook as the original read BY FRAME, one frame per pump-sized slice, never whole;
//   - a stop part-way trashes every top node the copy created and says N of M; the original is never written.
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'

vi.mock('../node/tree', async (orig) => ({
  ...(await orig<typeof import('../node/tree')>()),
  createContentNode: vi.fn(),
  deleteNode: vi.fn(),
}))

import { registry } from '../applications/registry'
import { createContentNode, deleteNode, type TreeNode } from '../node/tree'
import { FOLDER_KIND } from '../node/kind'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { TreeSource } from '../access'
import { memberAccessOf } from '../../test/models'
import { prepareCopy, type CopyTarget } from './copySubtree'
import { framesFor, framesOfBytes } from './copySource'
import { ImportFailed } from './importSubtree'
import type { ManifestPayload } from './manifest'

const FILE = 'C_FILE' // a Depot-like file: its import reads the payload by frame (`importStreams`)
const ALBUM = 'C_ALBUM' // a Memory-like kind: both hooks, but its import needs whole payloads (no `importStreams`)
const BOX = 'C_BOX' // a drop-box-like container: copied as a plain folder
const RAW = 'C_RAW' // takes a payload by frame, but its export cannot be read by frame
const FRAME = 4
const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const access = memberAccessOf({ scopeId: 's', epochs })
const enc = (s: string) => new TextEncoder().encode(s)

const node = (id: string, parentId: string | null, kind: string, name: string, orderIndex = 0): TreeNode =>
  ({ id, parentId, kind, name, orderIndex, epoch: 1, placementVersion: 0, wrappedContentKey: '', wrappedMeta: null, metaVersion: 0, createdAt: new Date(0) }) as unknown as TreeNode
const sourceOf = (nodes: TreeNode[]): TreeSource<TreeNode> => ({
  rootId: null,
  node: (id) => nodes.find((n) => n.id === id) ?? null,
  children: (id) => nodes.filter((n) => n.parentId === id),
  keyOf: (id) => (nodes.some((n) => n.id === id) ? new Uint8Array(32).fill(1) : null),
  nameOf: (id) => nodes.find((n) => n.id === id)?.name ?? '',
  inIdentityFolder: () => false,
  linkTo: () => '/',
})

// The originals' plaintext; `frameReads` records every frame the copy read of each, `sliceSizes` every slice the hook read.
const contents = new Map<string, Uint8Array>([
  ['doc', enc('a document of eleven')], ['g1', enc('dropped')], ['pic', enc('pixels')], ['empty', new Uint8Array(0)],
])
const frameReads: string[] = []
const sliceSizes: number[] = []
const created: { name: string; parent: string | null; kind: string; bytes?: string }[] = []
let failOn: string | null = null
/** A frame the original serves with one byte too many (a size the walk did not declare). */
let corrupt: string | null = null

beforeAll(() => {
  registry.registerKind({
    kind: FILE, icon: 'x', shareable: false, downloadsAsFile: true, importStreams: true, contentSchema: { application: 'c', version: 1 },
    copyLosses: ['c.fileLoss'],
    async exportContent(ctx) {
      const bytes = contents.get(ctx.node.id)
      if (!bytes || ctx.node.id === 'unfinished') return null
      const id = ctx.node.id
      const file = ctx.addPayload(ctx.node.name, {
        size: bytes.length, mediaType: 'text/plain', modifiedAt: 5,
        chunks: async function* () { yield bytes },
        frames: {
          frameSize: FRAME,
          chunkCount: framesFor(bytes.length, FRAME),
          getPlaintextChunk: async (i) => {
            frameReads.push(`${id}:${i}`)
            const frame = bytes.slice(i * FRAME, (i + 1) * FRAME)
            return corrupt === id && i === 0 ? new Uint8Array(frame.length + 1) : frame
          },
        },
      })
      return { type: 'c.file', schema: 1, file }
    },
    async importContent(ctx) {
      if (ctx.name === failOn) throw new Error('boom')
      const f = await ctx.payload(ctx.node.content!.file as ManifestPayload, ctx.name)
      // read the way the upload pump reads: one frame-sized slice at a time
      const parts: number[] = []
      for (let at = 0; at < Math.max(1, f.size); at += FRAME) {
        const chunk = new Uint8Array(await f.slice(at, Math.min(at + FRAME, f.size)).arrayBuffer())
        sliceSizes.push(chunk.length)
        parts.push(...chunk)
      }
      const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, FILE, ctx.name)
      created[created.length - 1].bytes = new TextDecoder().decode(new Uint8Array(parts))
      expect(f.lastModified).toBe(5)
      return { nodeId: made.nodeId, nodeKey: made.contentKey }
    },
  })
  registry.registerKind({
    kind: ALBUM, icon: 'x', shareable: false, contentSchema: { application: 'c', version: 1 }, copyLosses: ['c.albumLoss'],
    async exportContent(ctx) {
      const bytes = contents.get('pic')!
      // its payload CAN be read by frame: only its import's need for whole payloads keeps it out of a copy
      const file = ctx.addPayload('pic.jpg', { size: bytes.length, mediaType: 'image/jpeg', chunks: async function* () { yield bytes }, frames: framesOfBytes(bytes) })
      return { type: 'c.album', schema: 1, files: [file] }
    },
    importContent: async () => {
      throw new Error('an album is never handed a copy')
    },
  })
  registry.registerKind({
    kind: RAW, icon: 'x', shareable: false, importStreams: true, contentSchema: { application: 'c', version: 1 },
    // its import would take a payload by frame, but its export offers none: the copy cannot read it so
    async exportContent(ctx) {
      const bytes = contents.get('pic')!
      const file = ctx.addPayload('raw.bin', { size: bytes.length, mediaType: 'application/octet-stream', chunks: async function* () { yield bytes } })
      return { type: 'c.raw', schema: 1, file }
    },
    importContent: async () => {
      throw new Error('a raw node is never handed a copy')
    },
  })
  registry.registerKind({
    kind: BOX, icon: 'x', shareable: false, contentSchema: { application: 'c', version: 1 }, importsAsFolder: true, copyLosses: ['c.boxLoss'],
    exportContent: async () => ({ type: 'c.box', schema: 1 }),
  })
})

beforeEach(() => {
  frameReads.length = 0
  sliceSizes.length = 0
  created.length = 0
  failOn = null
  corrupt = null
  let n = 0
  vi.mocked(createContentNode).mockReset().mockImplementation(async (_s, _e, parent, kind, name) => {
    created.push({ name, parent: parent?.id ?? null, kind })
    return { nodeId: `new${++n}`, contentKey: new Uint8Array(32).fill(9) }
  })
  vi.mocked(deleteNode).mockReset().mockResolvedValue(undefined)
})

// Reisen/ ─ doc.txt (5 frames)          Archiv/
//         ─ Lissabon (ALBUM)
//         ─ Postfach (BOX) ─ Anna/ ─ drop.txt
//         ─ leer.txt (an empty file), halb.txt (an upload that never finished), roh.bin (no frames)
const NODES = [
  node('reisen', null, FOLDER_KIND, 'Reisen'), node('archiv', null, FOLDER_KIND, 'Archiv', 1),
  node('doc', 'reisen', FILE, 'doc.txt', 0), node('album', 'reisen', ALBUM, 'Lissabon', 1), node('box', 'reisen', BOX, 'Postfach', 2),
  node('anna', 'box', FOLDER_KIND, 'Anna'), node('g1', 'anna', FILE, 'drop.txt'),
  node('empty', 'reisen', FILE, 'leer.txt', 3), node('unfinished', 'reisen', FILE, 'halb.txt', 4), node('raw', 'reisen', RAW, 'roh.bin', 5),
]
const tree = sourceOf(NODES)
const target = (parent: CopyTarget['parent'], over: Partial<CopyTarget> = {}): CopyTarget => ({ scopeId: 's', epochs, tree: NODES, parent, ...over })

describe('prepareCopy: the plan, before anything is written', () => {
  it('says what it creates and how many bytes, the kinds it copies, and every node it leaves out by name - reading no payload', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['reisen'], appVersion: 'test' })
    // Reisen, doc.txt, Postfach, Anna, drop.txt, leer.txt
    expect(prepared.nodes).toBe(6)
    expect(prepared.bytes).toBe(contents.get('doc')!.length + contents.get('g1')!.length)
    expect(prepared.leftOut).toEqual([
      { name: 'halb.txt', kind: FILE, reason: 'nothing_to_export' },
      { name: 'Lissabon', kind: ALBUM, reason: 'not_copyable' },
      { name: 'roh.bin', kind: RAW, reason: 'not_copyable' },
    ])
    expect(prepared.kinds.sort()).toEqual([BOX, FILE, FOLDER_KIND].sort())
    expect(frameReads).toEqual([])
    expect(createContentNode).not.toHaveBeenCalled()
  })
})

describe('run: the replay under the destination', () => {
  it('re-creates the subtree through the hooks, the box as a plain folder, `Name (2)` beside the original - the original untouched', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['reisen'], appVersion: 'test' })
    const progress: string[] = []
    const result = await prepared.run(target(null, { onProgress: (p) => progress.push(`${p.nodesDone}/${p.nodesTotal}`) }))
    expect(result.nodeIds).toEqual(['new1'])
    expect(created).toEqual([
      { name: 'Reisen (2)', parent: null, kind: FOLDER_KIND },
      { name: 'doc.txt', parent: 'new1', kind: FILE, bytes: 'a document of eleven' },
      { name: 'Postfach', parent: 'new1', kind: FOLDER_KIND },
      { name: 'Anna', parent: 'new3', kind: FOLDER_KIND },
      { name: 'drop.txt', parent: 'new4', kind: FILE, bytes: 'dropped' },
      { name: 'leer.txt', parent: 'new1', kind: FILE, bytes: '' },
    ])
    expect(progress.at(-1)).toBe('6/6')
    // no write names an original: every parent is the destination or a node the copy created
    expect(created.every((c) => c.parent === null || c.parent.startsWith('new'))).toBe(true)
    expect(deleteNode).not.toHaveBeenCalled()
  })

  it('reads each original by frame - every frame once, every slice at most a frame - never the file whole', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['doc'], appVersion: 'test' })
    await prepared.run(target({ id: 'archiv', key: new Uint8Array(32) }))
    expect(created).toEqual([{ name: 'doc.txt', parent: 'archiv', kind: FILE, bytes: 'a document of eleven' }])
    expect(frameReads).toEqual(['doc:0', 'doc:1', 'doc:2', 'doc:3', 'doc:4'])
    expect(Math.max(...sliceSizes)).toBe(FRAME)
  })

  it('several nodes are one copy: each under the destination in order, each name freed among its siblings', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['doc', 'empty', 'album'], appVersion: 'test' })
    expect(prepared.nodes).toBe(2)
    expect(prepared.leftOut).toEqual([{ name: 'Lissabon', kind: ALBUM, reason: 'not_copyable' }])
    const result = await prepared.run(target({ id: 'reisen', key: new Uint8Array(32) }))
    expect(result.nodeIds).toEqual(['new1', 'new2'])
    expect(created.map((c) => [c.name, c.parent])).toEqual([['doc.txt (2)', 'reisen'], ['leer.txt (2)', 'reisen']])
  })

  it('a stop part-way trashes every top node it created and says N of M; nothing else is undone', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['doc', 'box'], appVersion: 'test' })
    failOn = 'drop.txt'
    const error = await prepared.run(target({ id: 'archiv', key: new Uint8Array(32) })).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ImportFailed)
    expect(error).toMatchObject({ done: 3, total: 4, rolledBack: true, leftover: null })
    expect(vi.mocked(deleteNode).mock.calls.map(([, id]) => id)).toEqual(['new1', 'new2'])
  })

  it('a frame that is not the size the walk declared stops the copy and trashes what it created', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['reisen'], appVersion: 'test' })
    corrupt = 'doc'
    const error = await prepared.run(target(null)).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ImportFailed)
    expect((error as ImportFailed).cause).toMatchObject({ code: 'size_mismatch' })
    expect(vi.mocked(deleteNode).mock.calls.map(([, id]) => id)).toEqual(['new1'])
  })

  it('a copy of nothing copyable writes nothing', async () => {
    const prepared = await prepareCopy({ access, tree, rootIds: ['album'], appVersion: 'test' })
    expect(prepared.nodes).toBe(0)
    await expect(prepared.run(target(null))).rejects.toMatchObject({ code: 'nothing_to_import' })
    expect(createContentNode).not.toHaveBeenCalled()
  })
})
