// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../node/tree', async (orig) => ({
  ...(await orig<typeof import('../node/tree')>()),
  nodeContentKey: vi.fn(() => new Uint8Array(32).fill(1)),
  openContentKey: vi.fn(() => new Uint8Array(32).fill(2)),
  createContentNode: vi.fn(),
  deleteNode: vi.fn(),
}))

import { registry } from '../applications/registry'
import { createContentNode, deleteNode, type TreeNode } from '../node/tree'
import type { ByteSink } from '../blob/stream'
import type { EpochKeys } from '../../features/spaces/epochs'
import { exportSubtree } from './exportSubtree'
import { ImportFailed, importSubtree, openExport, planImport } from './importSubtree'
import { ExportError, parseManifest, type ManifestPayload } from './manifest'
import { ZipWriter } from './zip'
import * as v1 from '@veyq/crypto/v1'

const FILE = 'T_FILE'
const ODD = 'T_ODD'
const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const text = (s: string) => new TextEncoder().encode(s)

function memorySink() {
  const parts: Uint8Array[] = []
  const state = { closed: false, aborted: false }
  const sink: ByteSink = {
    write: async (b) => void parts.push(b.slice()),
    close: async () => void (state.closed = true),
    abort: async () => void (state.aborted = true),
  }
  return { sink, state, blob: () => new Blob(parts as BlobPart[]) }
}
const node = (id: string, parentId: string | null, kind: string, name: string, orderIndex = 0): TreeNode =>
  ({ id, parentId, kind, name, orderIndex, epoch: 1, placementVersion: 0, wrappedContentKey: '', wrappedMeta: null, metaVersion: 0, createdAt: new Date(0) }) as unknown as TreeNode

const contents = new Map<string, Uint8Array>([['f1', text('first file')], ['f2', text('second')], ['f3', text('third, in the subfolder')]])
const imported: { name: string; parent: string | null; bytes: string; kind: string }[] = []
let failOn: string | null = null

beforeEach(() => {
  imported.length = 0
  failOn = null
  vi.mocked(createContentNode).mockReset()
  vi.mocked(deleteNode).mockReset().mockResolvedValue(undefined)
  let n = 0
  vi.mocked(createContentNode).mockImplementation(async (_s, _e, parent, kind, name) => {
    imported.push({ name, parent: parent?.id ?? null, bytes: '', kind })
    return { nodeId: `new${++n}`, contentKey: new Uint8Array(32).fill(9) }
  })
  if (!registry.kind(FILE).supported) {
    registry.registerKind({
      kind: FILE, icon: 'x', shareable: false,
      async exportContent(ctx) {
        const bytes = contents.get(ctx.node.id)!
        const file = ctx.addPayload(ctx.node.name, { size: bytes.length, mediaType: 'text/plain', chunks: async function* () { yield bytes.subarray(0, 3); yield bytes.subarray(3) } })
        return { type: 't.file', schema: 1, file }
      },
      async importContent(ctx) {
        const ref = ctx.node.content!.file as ManifestPayload
        if (ctx.name === failOn) throw new Error('boom')
        const f = await ctx.payload(ref, ctx.name)
        const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, FILE, ctx.name)
        imported[imported.length - 1].bytes = await f.text()
        return { nodeId: made.nodeId, nodeKey: made.contentKey }
      },
    })
    registry.registerKind({ kind: ODD, icon: 'x', shareable: false }) // no hooks: cannot be exported
  }
})

const tree = [
  node('a', null, 'FOLDER', 'Trip'),
  node('f1', 'a', FILE, 'one.txt', 0),
  node('b', 'a', 'FOLDER', 'Sub', 1),
  node('f2', 'a', FILE, 'two.txt', 2),
  node('f3', 'b', FILE, 'three.txt'),
  node('odd', 'a', ODD, 'strange', 3),
]

async function exported() {
  const m = memorySink()
  const result = await exportSubtree({ scopeId: 's', epochs, tree, rootId: 'a', sink: m.sink, options: { includeLocation: true }, appVersion: 'test', now: () => new Date('2026-10-13T10:00:00Z') })
  return { m, result, archive: m.blob() }
}

describe('export', () => {
  it('writes the tree, the payloads and a manifest with their checksums, and reports what it left out', async () => {
    const { m, result, archive } = await exported()
    expect(m.state.closed).toBe(true)
    expect(result).toMatchObject({ nodes: 5, payloads: 3 })
    expect(result.skipped).toEqual([{ name: 'strange', kind: ODD, reason: 'unsupported_kind' }])
    const { manifest, zip } = await openExport(archive)
    expect(manifest.root.name).toBe('Trip')
    expect(manifest.root.children!.map((c) => c.name)).toEqual(['one.txt', 'Sub', 'two.txt'])
    const f1 = manifest.root.children![0].content!.file as { path: string; sha256: string; size: number }
    expect(f1.path).toBe('data/n2/one.txt')
    expect(f1.sha256).toBe(Array.from(v1.sha256Digest(text('first file')), (x) => x.toString(16).padStart(2, '0')).join(''))
    expect(new TextDecoder().decode(await zip.bytes(f1.path, 100))).toBe('first file')
  })

  it('aborts the sink when a hook fails, never closing a half archive', async () => {
    const m = memorySink()
    const bad = [node('a', null, 'FOLDER', 'Trip'), node('x', 'a', FILE, 'x.txt')] // no content registered for x
    await expect(exportSubtree({ scopeId: 's', epochs, tree: bad, rootId: 'a', sink: m.sink, options: { includeLocation: true }, appVersion: 't' })).rejects.toThrow()
    expect(m.state.closed).toBe(false)
  })
})

describe('import', () => {
  it('recreates the tree under the target with the bytes intact, and skips what this build cannot import', async () => {
    const { archive } = await exported()
    const opened = await openExport(archive)
    expect(planImport(opened.manifest)).toMatchObject({ nodes: 5, bytes: 10 + 6 + 23 })
    const progress = vi.fn()
    const res = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened, onProgress: progress })
    expect(res.nodes).toBe(5)
    expect(imported.map((i) => [i.kind, i.name, i.parent])).toEqual([
      ['FOLDER', 'Trip', null], [FILE, 'one.txt', 'new1'], ['FOLDER', 'Sub', 'new1'], [FILE, 'three.txt', 'new3'], [FILE, 'two.txt', 'new1'],
    ])
    expect(imported.filter((i) => i.bytes).map((i) => i.bytes).sort()).toEqual(['first file', 'second', 'third, in the subfolder'].sort())
    expect(progress).toHaveBeenLastCalledWith(expect.objectContaining({ nodesDone: 5, bytesDone: 39 }))
    expect(deleteNode).not.toHaveBeenCalled()
  })

  it('names a clash "Name (2)", looking at the target siblings and at what this run created', async () => {
    const { archive } = await exported()
    const target = [node('t1', null, 'FOLDER', 'Trip'), node('t2', null, 'FOLDER', 'Trip (2)')]
    await importSubtree({ scopeId: 's', epochs, tree: target, parent: null, opened: await openExport(archive) })
    expect(imported[0].name).toBe('Trip (3)')
  })

  it('on a failure trashes the top-level node it created and says how far it got', async () => {
    const { archive } = await exported()
    failOn = 'three.txt'
    const err = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened: await openExport(archive) }).catch((e) => e)
    expect(err).toBeInstanceOf(ImportFailed)
    expect(err).toMatchObject({ done: 3, total: 5, rolledBack: true })
    expect(deleteNode).toHaveBeenCalledExactlyOnceWith('s', 'new1')
  })

  it('refuses a payload whose bytes do not match the manifest checksum', async () => {
    const { archive } = await exported()
    const opened = await openExport(archive)
    const f1 = opened.manifest.root.children![0].content!.file as { sha256: string }
    f1.sha256 = '0'.repeat(64)
    const err = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened }).catch((e) => e)
    expect(err).toBeInstanceOf(ImportFailed)
    expect(err.cause).toMatchObject({ code: 'checksum' })
    expect(deleteNode).toHaveBeenCalled()
  })

  it('recognises an export by content only, and refuses a newer version', async () => {
    await expect(openExport(new Blob([text('plain text, not a zip at all, long enough')]))).rejects.toMatchObject({ code: 'not_an_export' })
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('other.txt', text('x'))
    await w.finish()
    await expect(openExport(m.blob())).rejects.toMatchObject({ code: 'not_an_export' })
    const n = memorySink()
    const w2 = new ZipWriter(n.sink)
    await w2.addBytes('manifest.json', text(JSON.stringify({ format: 'veyq-subtree', version: 99, exportedAt: 'x', appVersion: 'x', root: { id: 'n1', kind: 'FOLDER', name: 'x' } })))
    await w2.finish()
    await expect(openExport(n.blob())).rejects.toMatchObject({ code: 'newer_version' })
  })
})

describe('parseManifest', () => {
  const base = (root: unknown) => ({ format: 'veyq-subtree', version: 1, exportedAt: 'x', appVersion: 'x', root })
  const file = (path: string) => ({ id: 'n1', kind: FILE, name: 'a', content: { type: 't.file', file: { path, size: 1, mediaType: 'x' } } })
  it.each(['../x', 'data/n1/../x', '/etc/passwd', 'data/n1/a/b', 'data\\n1\\a', 'data/n1/..', 'other/n1/a'])('refuses the payload path %s', (p) => {
    expect(() => parseManifest(base(file(p)))).toThrow(ExportError)
  })
  it('accepts a plain data path, refuses duplicate ids', () => {
    expect(() => parseManifest(base(file('data/n1/a.txt')))).not.toThrow()
    expect(() => parseManifest(base({ id: 'n1', kind: 'FOLDER', name: 'a', children: [{ id: 'n1', kind: 'FOLDER', name: 'b' }] }))).toThrow(ExportError)
  })
})
