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
import { exportSubtree, prepareExport } from './exportSubtree'
import { ImportFailed, importSubtree, openExport, planImport } from './importSubtree'
import type { PayloadFile } from './hooks'
import { ExportError, parseManifest, type ManifestNode, type ManifestPayload } from './manifest'
import { ZipWriter, openZip } from './zip'
import { isArchivePath } from './archiveName'
import type { ImportInput } from './importSource'
import { memberAccessOf } from '../../test/models'
import type { TreeSource } from '../access'
import * as v1 from '@veyq/crypto/v1'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { testApp, testKind } from '../../test/testApplication'

const FILE = 'T_FILE'
const COLLECTION = 'T_COLLECTION'
const ODD = 'T_ODD'
const BOX = 'T_BOX'
const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const text = (s: string) => new TextEncoder().encode(s)
/** A payload's bytes as text, read the way the upload pump reads (`slice(...).arrayBuffer()`). */
const textOf = async (f: PayloadFile) => new TextDecoder().decode(await f.slice(0, f.size).arrayBuffer())
const hexOf = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const access = memberAccessOf({ scopeId: 's', epochs })

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
  ({ id, parentId, kind, name, orderIndex, epoch: 1, placementRevision: 0, wrappedContentKey: '', wrappedMeta: null, metaRevision: 0, createdAt: new Date(0) }) as unknown as TreeNode
/** A tree as a session reads it, every key open. */
const sourceOf = (nodes: TreeNode[]): TreeSource<TreeNode> => ({
  rootId: null,
  node: (id) => nodes.find((n) => n.id === id) ?? null,
  children: (id) => nodes.filter((n) => n.parentId === id),
  keyOf: (id) => (nodes.some((n) => n.id === id) ? new Uint8Array(32).fill(1) : null),
  nameOf: (id) => nodes.find((n) => n.id === id)?.name ?? '',
  inIdentityFolder: () => false,
  linkTo: () => '/',
})

const contents = new Map<string, Uint8Array>([['f1', text('first file')], ['f2', text('second')], ['f3', text('third, in the subfolder')]])
/** A collection's files: hostile names, two that differ only in case. */
const collectionFiles: [string, string][] = [['IMG.jpg', 'upper'], ['img.JPG', 'lower'], ['../escape', 'dots'], ['CON.txt', 'device']]
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
    return { nodeId: `new${++n}`, contentKey: new Uint8Array(32).fill(9), epoch: 1 }
  })
  if (!registry.kind(FILE).supported) {
    testKind(registry, { rekeyContent: nothingOfItsOwnToRekey,
      kind: FILE, icon: 'file', downloadsAsFile: true, contentSchema: { application: 't', version: 1 },
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
        imported[imported.length - 1].bytes = await textOf(f)
        return { nodeId: made.nodeId, nodeKey: made.contentKey }
      },
    })
    testKind(registry, { rekeyContent: nothingOfItsOwnToRekey,
      kind: COLLECTION, icon: 'file', contentSchema: { application: 't', version: 1 },
      async exportContent(ctx) {
        const files = collectionFiles.map(([name, body]) => ctx.addPayload(name, { size: body.length, mediaType: 'text/plain', chunks: async function* () { yield text(body) } }))
        return { type: 't.collection', schema: 1, files }
      },
      async importContent(ctx) {
        const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, COLLECTION, ctx.name)
        const bodies = []
        for (const ref of ctx.node.content!.files as ManifestPayload[]) bodies.push(await textOf(await ctx.payload(ref, 'x')))
        imported[imported.length - 1].bytes = bodies.join(',')
        return { nodeId: made.nodeId, nodeKey: made.contentKey }
      },
    })
    testKind(registry, { rekeyContent: nothingOfItsOwnToRekey, kind: ODD, icon: 'file' }) // no hooks: cannot be downloaded
    // a container that downloads with a content of its own and imports as a plain folder (a Depot drop box's shape)
    testKind(registry, { rekeyContent: nothingOfItsOwnToRekey,
      kind: BOX, icon: 'file', contentSchema: { application: 't', version: 1 }, importsAsFolder: true,
      exportContent: async () => ({ type: 't.box', schema: 1 }),
      validateContent: (c) => (Object.keys(c).every((k) => k === 'type' || k === 'schema') ? null : { code: 'invalid_manifest', message: 'a box with more' }),
    })
  }
})

const nodes = [
  node('a', null, 'FOLDER', 'Trip'),
  node('f1', 'a', FILE, 'one.txt', 0),
  node('b', 'a', 'FOLDER', 'Sub', 1),
  node('f2', 'a', FILE, 'two.txt', 2),
  node('f3', 'b', FILE, 'three.txt'),
  node('odd', 'a', ODD, 'strange', 3),
]

async function exported(tree = nodes, rootId = 'a') {
  const m = memorySink()
  const result = await exportSubtree({ access, tree: sourceOf(tree), rootId, sink: m.sink, appVersion: 'test', now: () => new Date('2026-10-13T10:00:00Z') })
  return { m, result, archive: m.blob() }
}
/** A ZIP as an import is handed it. */
const zipOf = (b: Blob): ImportInput => ({ kind: 'zip', file: new File([b], 'download.zip') })
/** Every entry of an archive as the reader lists it, in written order. */
async function entriesOf(archive: Blob): Promise<string[]> {
  return (await openZip(archive)).entries.map((e) => e.name)
}

describe('download (format version 2: the archive reads as the tree)', () => {
  it('writes the tree as directories and files, the manifest at .veyq/ with checksums, and reports what it left out', async () => {
    const { m, result, archive } = await exported()
    expect(m.state.closed).toBe(true)
    expect(result).toMatchObject({ nodes: 5, payloads: 3 })
    expect(result.skipped).toEqual([{ name: 'strange', kind: ODD, reason: 'unsupported_kind' }])
    expect(await entriesOf(archive)).toEqual(['Trip/', 'Trip/Sub/', 'Trip/one.txt', 'Trip/Sub/three.txt', 'Trip/two.txt', '.veyq/manifest.json'])
    const { manifest, source } = await openExport(zipOf(archive))
    expect(manifest).toMatchObject({ version: 2, applications: [{ id: 't', contentSchema: 1 }] })
    expect(manifest).not.toHaveProperty('options')
    expect(manifest.root).toMatchObject({ name: 'Trip', path: 'Trip' })
    expect(manifest.root.children!.map((c) => [c.name, c.path])).toEqual([['one.txt', 'Trip/one.txt'], ['Sub', 'Trip/Sub'], ['two.txt', 'Trip/two.txt']])
    const f1 = manifest.root.children![0].content!.file as { path: string; sha256: string; size: number }
    expect(f1.path).toBe('Trip/one.txt') // a kind that IS one file is that file, at the node's own path
    expect(f1.sha256).toBe(hexOf(v1.sha256Digest(text('first file'))))
    expect(await source.blob(f1.path, '').text()).toBe('first file')
  })

  it('hostile names unpack to safe paths, numbered on a clash ignoring case, and the manifest maps every node to its path', async () => {
    const hostile = [
      node('r', null, 'FOLDER', 'Trip: 2025'),
      node('c1', 'r', 'FOLDER', 'con', 0),
      node('c2', 'r', 'FOLDER', 'CON', 1),
      node('al', 'r', COLLECTION, 'a/b. ', 2),
      node('dot', 'r', 'FOLDER', '..', 3),
      node('long', 'r', 'FOLDER', 'x'.repeat(300), 4),
    ]
    const { archive } = await exported(hostile, 'r')
    const names = await entriesOf(archive)
    expect(names).toEqual([
      'Trip_ 2025/', 'Trip_ 2025/_con/', 'Trip_ 2025/_CON (2)/', 'Trip_ 2025/a_b/', 'Trip_ 2025/_/', `Trip_ 2025/${'x'.repeat(200)}/`,
      'Trip_ 2025/a_b/IMG.jpg', 'Trip_ 2025/a_b/img (2).JPG', 'Trip_ 2025/a_b/.._escape', 'Trip_ 2025/a_b/_CON.txt', '.veyq/manifest.json',
    ])
    expect(names.every((n) => n === '.veyq/manifest.json' || isArchivePath(n.replace(/\/$/, '')))).toBe(true)
    const { manifest } = await openExport(zipOf(archive))
    const paths = new Map<string, string>()
    const walk = (n: ManifestNode) => {
      paths.set(n.name, n.path!)
      n.children?.forEach(walk)
    }
    walk(manifest.root)
    expect(Object.fromEntries(paths)).toEqual({
      'Trip: 2025': 'Trip_ 2025', con: 'Trip_ 2025/_con', CON: 'Trip_ 2025/_CON (2)', 'a/b. ': 'Trip_ 2025/a_b', '..': 'Trip_ 2025/_',
      ['x'.repeat(300)]: `Trip_ 2025/${'x'.repeat(200)}`,
    })
    // every directory node is a directory entry, every payload an entry in its node's directory
    for (const p of paths.values()) expect(names).toContain(`${p}/`)
    const collection = manifest.root.children!.find((c) => c.kind === COLLECTION)!
    expect((collection.content!.files as ManifestPayload[]).map((f) => f.path)).toEqual(names.filter((n) => n.startsWith('Trip_ 2025/a_b/') && !n.endsWith('/')))
  })

  it('a node named like the manifest directory does not take it', async () => {
    const { archive } = await exported([node('v', null, 'FOLDER', '.VEYQ')], 'v')
    expect(await entriesOf(archive)).toEqual(['.VEYQ (2)/', '.veyq/manifest.json'])
  })

  // F11: the save target is opened with this number before a byte is written, and the SW route holds the stream to it as the
  // exact Content-Length - the payloads' sum (`bytes`) left out every header, descriptor, the directory and the manifest.
  it.each([
    ['folders, files and a node left out', nodes, 'a'],
    ['an empty folder', [node('e', null, 'FOLDER', 'Leer')], 'e'],
    ['hostile and non-ASCII names', [
      node('r', null, 'FOLDER', 'Grüße: 日本 😀'), node('al', 'r', COLLECTION, 'Fotos é'), node('c', 'r', 'FOLDER', 'CON'), node('f1', 'r', FILE, 'naïve.txt'),
    ], 'r'],
  ])('announces the archive\'s exact length before writing it: %s', async (_label, tree, rootId) => {
    const prepared = await prepareExport({ access, tree: sourceOf(tree), rootId, appVersion: 'test', now: () => new Date('2026-10-13T10:00:00Z') })
    const m = memorySink()
    await prepared.write(m.sink)
    expect(m.state.closed).toBe(true)
    expect(prepared.archiveBytes).toBe(m.blob().size)
    expect(prepared.archiveBytes).toBeGreaterThan(prepared.bytes)
  })

  it('announces the same length when written again later, the manifest\'s time stamp and checksums filled in then', async () => {
    let at = new Date('2026-10-13T10:00:00Z')
    const prepared = await prepareExport({ access, tree: sourceOf(nodes), rootId: 'a', appVersion: 'test', now: () => at })
    at = new Date('2031-01-01T23:59:59.999Z')
    for (let i = 0; i < 2; i++) {
      const m = memorySink()
      await prepared.write(m.sink)
      expect(m.blob().size).toBe(prepared.archiveBytes)
    }
  })

  it('a manifest that would come out at another length than announced aborts the archive, never closes it', async () => {
    let at = new Date('2026-10-13T10:00:00Z')
    const prepared = await prepareExport({ access, tree: sourceOf(nodes), rootId: 'a', appVersion: 'test', now: () => at })
    at = new Date('+010000-01-01T00:00:00Z') // a five-digit year: the one time stamp that is not 24 characters
    const m = memorySink()
    await expect(prepared.write(m.sink)).rejects.toMatchObject({ code: 'size_mismatch' })
    expect(m.state).toEqual({ closed: false, aborted: true })
  })

  it('aborts the sink when a hook fails, never closing a half archive', async () => {
    const m = memorySink()
    const bad = [node('a', null, 'FOLDER', 'Trip'), node('x', 'a', FILE, 'x.txt')] // no content registered for x
    await expect(exportSubtree({ access, tree: sourceOf(bad), rootId: 'a', sink: m.sink, appVersion: 't' })).rejects.toThrow()
    expect(m.state.closed).toBe(false)
  })
})

describe('import', () => {
  it('recreates the tree under the target with the bytes intact, and skips what this build cannot import', async () => {
    const { archive } = await exported()
    const opened = await openExport(zipOf(archive))
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
    await importSubtree({ scopeId: 's', epochs, tree: target, parent: null, opened: await openExport(zipOf(archive)) })
    expect(imported[0].name).toBe('Trip (3)')
  })

  // ADR 0080 decision 16: a name is numbered only against its own route group's siblings — a node of
  // a kind whose application claimed a route slug (a Memory, a Pot) is another group than the folder imported here.
  it('numbers a name per route group: a routed sibling of the name leaves it free, a folder of it does not', async () => {
    if (!registry.routeSlugs().includes('t-routed')) {
      testKind(registry, { rekeyContent: nothingOfItsOwnToRekey, kind: 'T_ROUTED', icon: 'file' }, 't-routed')
      testApp(registry, 't-routed').route('t-routed')
    }
    const { archive } = await exported()
    await importSubtree({ scopeId: 's', epochs, tree: [node('t1', null, 'T_ROUTED', 'Trip')], parent: null, opened: await openExport(zipOf(archive)) })
    expect(imported[0].name).toBe('Trip')

    imported.length = 0
    const both = [node('t1', null, 'T_ROUTED', 'Trip'), node('t2', null, 'T_ROUTED', 'Trip (2)'), node('t3', null, 'FOLDER', 'Trip')]
    await importSubtree({ scopeId: 's', epochs, tree: both, parent: null, opened: await openExport(zipOf(archive)) })
    expect(imported[0].name).toBe('Trip (2)')
  })

  it('on a failure trashes the top-level node it created and says how far it got', async () => {
    const { archive } = await exported()
    failOn = 'three.txt'
    const err = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened: await openExport(zipOf(archive)) }).catch((e) => e)
    expect(err).toBeInstanceOf(ImportFailed)
    expect(err).toMatchObject({ done: 3, total: 5, rolledBack: true })
    expect(deleteNode).toHaveBeenCalledExactlyOnceWith('s', 'new1')
  })

  it('refuses a payload whose bytes do not match the manifest checksum', async () => {
    const { archive } = await exported()
    const opened = await openExport(zipOf(archive))
    const f1 = opened.manifest.root.children![0].content!.file as { sha256: string }
    f1.sha256 = '0'.repeat(64)
    const err = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened }).catch((e) => e)
    expect(err).toBeInstanceOf(ImportFailed)
    expect(err.cause).toMatchObject({ code: 'checksum' })
    expect(deleteNode).toHaveBeenCalled()
  })

  it('recognises an export by content only, and refuses a newer version', async () => {
    await expect(openExport(zipOf(new Blob([text('plain text, not a zip at all, long enough')])))).rejects.toMatchObject({ code: 'not_an_export' })
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('other.txt', text('x'))
    await w.finish()
    await expect(openExport(zipOf(m.blob()))).rejects.toMatchObject({ code: 'not_an_export' })
    const n = memorySink()
    const w2 = new ZipWriter(n.sink)
    await w2.addBytes('manifest.json', text(JSON.stringify({ format: 'veyq-subtree', version: 99, exportedAt: 'x', appVersion: 'x', root: { id: 'n1', kind: 'FOLDER', name: 'x' } })))
    await w2.finish()
    await expect(openExport(zipOf(n.blob()))).rejects.toMatchObject({ code: 'newer_version' })
  })
})

// ADR 0082 decision 4: a kind that imports as a plain folder, and the paths that follow the shown names.
describe('a kind that imports as a folder, and the shown names', () => {
  const boxTree = [
    node('box', null, BOX, 'Inbox'),
    node('g2', 'box', 'FOLDER', 'Anna'),
    node('g1', 'box', 'FOLDER', 'Anna'),
    node('f1', 'g1', FILE, 'one.txt'),
  ]
  // the session shows the two equal names apart (`Anna (2)` for g2), as a drop box's detail does
  const shown = (tree: TreeNode[]): TreeSource<TreeNode> => ({ ...sourceOf(tree), nameOf: (id) => (id === 'g2' ? 'Anna (2)' : (tree.find((n) => n.id === id)?.name ?? '')) })

  it('downloads it as a directory with its content; each path segment is the name the session shows, the manifest keeps the own name', async () => {
    const m = memorySink()
    await exportSubtree({ access, tree: shown(boxTree), rootId: 'box', sink: m.sink, appVersion: 'test' })
    const { manifest } = await openExport(zipOf(m.blob()))
    expect(manifest.root).toMatchObject({ kind: BOX, path: 'Inbox', content: { type: 't.box', schema: 1 } })
    // listed in the order the shown names number them, so an import numbers them alike
    expect(manifest.root.children!.map((c) => [c.name, c.path])).toEqual([['Anna', 'Inbox/Anna'], ['Anna', 'Inbox/Anna (2)']])
    expect(await entriesOf(m.blob())).toContain('Inbox/Anna/one.txt')
  })

  it('imports it - with no importContent - as a FOLDER, its children under it, equal names numbered among the new siblings', async () => {
    const m = memorySink()
    await exportSubtree({ access, tree: shown(boxTree), rootId: 'box', sink: m.sink, appVersion: 'test' })
    const opened = await openExport(zipOf(m.blob()))
    expect(planImport(opened.manifest)).toMatchObject({ nodes: 4, skipped: [] })
    await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened })
    expect(imported.map((i) => [i.kind, i.name, i.parent])).toEqual([
      // the folder that held one.txt is `Anna` in the archive and after the import
      ['FOLDER', 'Inbox', null], ['FOLDER', 'Anna', 'new1'], [FILE, 'one.txt', 'new2'], ['FOLDER', 'Anna (2)', 'new1'],
    ])
  })

  it('lists eleven equal names in their shown order (`G (2)` before `G (10)`), so the import numbers each as its directory is named', async () => {
    const guests = Array.from({ length: 11 }, (_, i) => node(`g${String(i).padStart(2, '0')}`, 'box', 'FOLDER', 'G'))
    const label = new Map(guests.map((g, i) => [g.id, i === 0 ? 'G' : `G (${i + 1})`]))
    // served in reverse, so only the shown names can put them in order
    const tree = [node('box', null, BOX, 'Inbox'), ...guests.reverse()]
    const m = memorySink()
    await exportSubtree({ access, tree: { ...sourceOf(tree), nameOf: (id) => label.get(id) ?? 'Inbox' }, rootId: 'box', sink: m.sink, appVersion: 'test' })
    const { manifest } = await openExport(zipOf(m.blob()))
    expect(manifest.root.children!.map((c) => c.path)).toEqual(Array.from({ length: 11 }, (_, i) => (i === 0 ? 'Inbox/G' : `Inbox/G (${i + 1})`)))
  })

  it('still checks its content (decision 10): a box content the kind refuses is refused before anything is written', async () => {
    const m = memorySink()
    await exportSubtree({ access, tree: shown(boxTree), rootId: 'box', sink: m.sink, appVersion: 'test' })
    const { manifest } = await openExport(zipOf(m.blob()))
    const tampered = { ...manifest, root: { ...manifest.root, content: { type: 't.box', schema: 1, extra: 'x' } } }
    expect(() => planImport(tampered)).toThrow(ExportError)
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
