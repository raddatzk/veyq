// @vitest-environment node
// The read side of a download (ADR 0082 decisions 7, 9, 10; cr-download-is-the-export): an archive written before the tree
// layout still imports; a ZIP and the same download unpacked into a directory import to the same tree; an application this
// build lacks, or newer content, is listed and left out; and every refusal of the hardened reader fires on a crafted archive
// BEFORE anything is written.
import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'

vi.mock('../node/tree', async (orig) => ({ ...(await orig<typeof import('../node/tree')>()), createContentNode: vi.fn(), deleteNode: vi.fn() }))

import { readFileSync } from 'node:fs'
import { registry } from '../applications/registry'
import { createContentNode, deleteNode, type TreeNode } from '../node/tree'
import type { ByteSink } from '../blob/stream'
import type { EpochKeys } from '../../features/spaces/epochs'
import type { TreeSource } from '../access'
import { memberAccessOf } from '../../test/models'
import { exportSubtree } from './exportSubtree'
import type { PayloadFile } from './hooks'
import { importSubtree, openExport, planImport, type OpenedExport } from './importSubtree'
import { pickedDirectory, type DirectoryFile, type ImportInput } from './importSource'
import { LIMITS, MAX_DEPTH, MAX_NODES, type ManifestPayload } from './manifest'
import { ZipWriter, openZip } from './zip'
import * as v1 from '@veyq/crypto/v1'
import { b64url } from '@veyq/crypto/base64'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { contentDigest } from '../blob/sharedBlob'
// on purpose the page-thread loop over the object's own bytes: what it answers is what those bytes hash to
import { digestHere } from '../blob/digest'

const FILE = 'H_FILE'
const ALBUM = 'H_ALBUM'
const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const text = (s: string) => new TextEncoder().encode(s)
/** A payload's bytes as text, read the way the upload pump reads (`slice(...).arrayBuffer()`). */
const textOf = async (f: PayloadFile) => new TextDecoder().decode(await f.slice(0, f.size).arrayBuffer())
const hex = (b: Uint8Array) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
const sha = (s: string) => hex(v1.sha256Digest(text(s)))
const access = memberAccessOf({ scopeId: 's', epochs })

const created: { kind: string; name: string; parent: string | null; bytes: string }[] = []
/** Every payload a FILE hook received, as the object `ctx.payload` returned. */
const grabbed: PayloadFile[] = []
beforeAll(() => {
  registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
    kind: FILE, icon: 'file', shareable: false, downloadsAsFile: true, contentSchema: { application: 'h', version: 1 },
    validateContent: (c) => (typeof c.note === 'string' && c.note.length > 10 ? { code: 'too_large', message: 'note' } : null),
    async exportContent(ctx) {
      const body = text(`bytes of ${ctx.node.name}`)
      return { type: 'h.file', schema: 1, file: ctx.addPayload(ctx.node.name, { size: body.length, mediaType: 'text/plain', chunks: async function* () { yield body } }) }
    },
    async importContent(ctx) {
      const f = await ctx.payload(ctx.node.content!.file as ManifestPayload, ctx.name)
      grabbed.push(f)
      const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, FILE, ctx.name)
      created[created.length - 1].bytes = await textOf(f)
      return { nodeId: made.nodeId, nodeKey: made.contentKey }
    },
  })
  registry.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
    kind: ALBUM, icon: 'file', shareable: false, contentSchema: { application: 'h', version: 1 },
    async exportContent(ctx) {
      const files = ['IMG.jpg', 'img.JPG'].map((n) => ctx.addPayload(n, { size: n.length, mediaType: 'image/jpeg', chunks: async function* () { yield text(n) } }))
      return { type: 'h.album', schema: 1, files }
    },
    async importContent(ctx) {
      const made = await createContentNode(ctx.scopeId, ctx.epochs, ctx.parent, ALBUM, ctx.name)
      const bodies: string[] = []
      for (const ref of ctx.node.content!.files as ManifestPayload[]) bodies.push(await textOf(await ctx.payload(ref, 'x')))
      created[created.length - 1].bytes = bodies.join(',')
      return { nodeId: made.nodeId, nodeKey: made.contentKey }
    },
  })
})
/** A fresh server: nothing created, ids from `new1`. */
function fresh(): void {
  created.length = 0
  grabbed.length = 0
  let n = 0
  vi.mocked(createContentNode).mockReset().mockImplementation(async (_s, _e, parent, kind, name) => {
    created.push({ kind, name, parent: parent?.id ?? null, bytes: '' })
    return { nodeId: `new${++n}`, contentKey: new Uint8Array(32).fill(9), epoch: 1 }
  })
}
beforeEach(() => {
  vi.mocked(deleteNode).mockReset().mockResolvedValue(undefined)
  fresh()
})

// --- writing archives ----------------------------------------------------------------------------------------------------

async function zipBytes(write: (w: ZipWriter) => Promise<unknown>, zip64At?: number): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  const w = new ZipWriter(sink, { modified: new Date('2026-10-14T00:00:00Z'), zip64At })
  await write(w)
  await w.finish()
  return new Uint8Array(await new Blob(parts as BlobPart[]).arrayBuffer())
}
const zipOf = (bytes: Uint8Array | Blob): ImportInput => ({ kind: 'zip', file: new File([bytes as BlobPart], 'download.zip') })
const node = (id: string, parentId: string | null, kind: string, name: string, orderIndex = 0) =>
  ({ id, parentId, kind, name, orderIndex, epoch: 1, placementRevision: 0, wrappedContentKey: '', wrappedMeta: null, metaRevision: 0, createdAt: new Date(0) }) as unknown as TreeNode
const sourceOf = (nodes: TreeNode[]): TreeSource<TreeNode> => ({
  rootId: null,
  node: (id) => nodes.find((n) => n.id === id) ?? null,
  children: (id) => nodes.filter((n) => n.parentId === id),
  keyOf: () => new Uint8Array(32).fill(1),
  nameOf: () => '',
  inIdentityFolder: () => false,
  linkTo: () => '/',
})
const TREE = [
  node('r', null, 'FOLDER', 'Trip: 2025'),
  node('f', 'r', FILE, 'notes.txt', 0),
  node('a', 'r', ALBUM, 'Lisbon', 1),
  node('s', 'a', 'FOLDER', 'Day 1', 0),
  node('g', 's', FILE, 'Café.txt', 0),
]
async function download(): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  await exportSubtree({ access, tree: sourceOf(TREE), rootId: 'r', sink, appVersion: 't' })
  return new Uint8Array(await new Blob(parts as BlobPart[]).arrayBuffer())
}
/** The download unpacked as `unzip` would: every file at its path (directory entries make no file). `nfd`: the file system
 *  hands back the decomposed spelling of every name. */
async function unpacked(bytes: Uint8Array, nfd = false): Promise<ImportInput> {
  const zip = await openZip(new Blob([bytes as BlobPart]))
  const files: DirectoryFile[] = []
  for (const e of zip.entries) {
    if (e.name.endsWith('/')) continue
    files.push({ path: nfd ? e.name.normalize('NFD') : e.name, file: new File([await zip.blob(e.name).arrayBuffer()], e.name.split('/').pop()!) })
  }
  return { kind: 'directory', name: 'Trip download', files }
}
const run = async (opened: OpenedExport) => importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened })
/** The whole flow a dialog runs; a refusal must come before the first write. */
async function attempt(input: ImportInput) {
  return run(await openExport(input))
}

// --- the round trips -----------------------------------------------------------------------------------------------------

describe('reading both layouts and both inputs', () => {
  it('an archive written before the tree layout (version 1: manifest.json, data/<id>/) still imports', async () => {
    const manifest = {
      format: 'veyq-subtree', version: 1, exportedAt: '2026-10-13T09:30:00Z', appVersion: '0.9', options: { includeLocation: false },
      root: {
        id: 'n1', kind: 'FOLDER', name: 'Old', children: [
          { id: 'n2', kind: FILE, name: 'a.txt', content: { type: 'h.file', schema: 1, file: { path: 'data/n2/a.txt', size: 5, mediaType: 'text/plain', sha256: sha('hello') } } },
        ],
      },
    }
    const bytes = await zipBytes(async (w) => {
      await w.addBytes('data/n2/a.txt', text('hello'))
      await w.addBytes('manifest.json', text(JSON.stringify(manifest)))
    })
    await attempt(zipOf(bytes))
    expect(created).toEqual([{ kind: 'FOLDER', name: 'Old', parent: null, bytes: '' }, { kind: FILE, name: 'a.txt', parent: 'new1', bytes: 'hello' }])
  })

  it('a download and the same download unpacked into a directory import to the same tree', async () => {
    const bytes = await download()
    await attempt(zipOf(bytes))
    const fromZip = [...created]
    expect(fromZip.map((c) => [c.kind, c.name, c.bytes])).toEqual([
      ['FOLDER', 'Trip: 2025', ''], [FILE, 'notes.txt', 'bytes of notes.txt'], [ALBUM, 'Lisbon', 'IMG.jpg,img.JPG'], ['FOLDER', 'Day 1', ''],
      [FILE, 'Café.txt', 'bytes of Café.txt'],
    ])
    for (const nfd of [false, true]) { // the second time, a file system that decomposes names
      fresh()
      await attempt(await unpacked(bytes, nfd))
      expect(created).toEqual(fromZip)
    }
  })

  it('a PICKED directory (webkitdirectory) reads its files by the path below the picked directory', async () => {
    const dir = await unpacked(await download())
    const files = (dir as { files: DirectoryFile[] }).files.map(({ path, file }) => {
      const f = new File([file], file.name)
      Object.defineProperty(f, 'webkitRelativePath', { value: `Trip download/${path}` })
      return f
    })
    const picked = pickedDirectory(files)!
    expect(picked).toMatchObject({ kind: 'directory', name: 'Trip download' })
    await attempt(picked)
    expect(created.map((c) => c.name)).toEqual(['Trip: 2025', 'notes.txt', 'Lisbon', 'Day 1', 'Café.txt'])
  })

  it('a directory without the manifest is no download', async () => {
    await expect(openExport({ kind: 'directory', name: 'x', files: [{ path: 'a.txt', file: new File(['x'], 'a.txt') }] })).rejects.toMatchObject({ code: 'not_an_export' })
  })
})

// --- decision 9: unknown applications and newer content ------------------------------------------------------------------

describe('what this build cannot import is listed, and left out on "Import the rest"', () => {
  const payload = (path: string, body: string) => ({ path, size: body.length, mediaType: 'text/plain', sha256: sha(body) })
  const manifest = {
    format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x',
    applications: [{ id: 'h', contentSchema: 2 }, { id: 'ledgers', contentSchema: 1 }],
    root: {
      id: 'n1', kind: 'FOLDER', name: 'Home', path: 'Home', children: [
        { id: 'n2', kind: FILE, name: 'keep.txt', path: 'Home/keep.txt', content: { type: 'h.file', schema: 1, file: payload('Home/keep.txt', 'kept') } },
        {
          id: 'n3', kind: 'LEDGER_SHEET', name: 'Budget', path: 'Home/Budget', content: { type: 'ledgers.sheet', schema: 1 }, children: [
            { id: 'n4', kind: 'FOLDER', name: 'May', path: 'Home/Budget/May' },
          ],
        },
        { id: 'n5', kind: FILE, name: 'newer.txt', path: 'Home/newer.txt', content: { type: 'h.file', schema: 2, file: payload('Home/newer.txt', 'new') } },
        { id: 'n6', kind: 'LEDGER_SHEET', name: 'Taxes', path: 'Home/Taxes', content: { type: 'ledgers.sheet', schema: 1 } },
      ],
    },
  }
  const archive = () => zipBytes(async (w) => {
    await w.addBytes('Home/keep.txt', text('kept'))
    await w.addBytes('Home/newer.txt', text('new'))
    await w.addBytes('.veyq/manifest.json', text(JSON.stringify(manifest)))
  })

  it('the dry run lists, per application, how many nodes it leaves out and why', async () => {
    const plan = planImport((await openExport(zipOf(await archive()))).manifest)
    expect(plan.nodes).toBe(2)
    expect(plan.groups).toEqual([
      { application: 'ledgers', reason: 'unknown_application', nodes: 3 },
      { application: 'h', reason: 'newer_content', nodes: 1 },
    ])
    expect(plan.skipped.map((s) => s.name)).toEqual(['Budget', 'newer.txt', 'Taxes'])
  })

  it('"Import the rest" creates exactly the rest and names what it left out', async () => {
    const result = await attempt(zipOf(await archive()))
    expect(created.map((c) => c.name)).toEqual(['Home', 'keep.txt'])
    expect(result.skipped.map((s) => s.name)).toEqual(['Budget', 'newer.txt', 'Taxes'])
  })

  it('an archive with nothing importable writes nothing', async () => {
    const only = { ...manifest, root: manifest.root.children[1] }
    const bytes = await zipBytes(async (w) => w.addBytes('.veyq/manifest.json', text(JSON.stringify({ ...only, root: { ...only.root, path: 'Budget', children: [{ ...only.root.children![0], path: 'Budget/May' }] } }))))
    const opened = await openExport(zipOf(bytes))
    expect(planImport(opened.manifest).nodes).toBe(0)
    await expect(run(opened)).rejects.toMatchObject({ code: 'nothing_to_import' })
    expect(createContentNode).not.toHaveBeenCalled()
  })
})

// --- decision 10: every refusal, on a crafted archive, before anything is written ----------------------------------------

/** A sound version 2 archive around `root`, the payload bodies at their paths. */
async function crafted(root: Record<string, unknown>, files: Record<string, string> = {}, manifestOver: Record<string, unknown> = {}) {
  const manifest = { format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [{ id: 'h', contentSchema: 1 }], root, ...manifestOver }
  return zipBytes(async (w) => {
    for (const [path, body] of Object.entries(files)) await w.addBytes(path, text(body))
    await w.addBytes('.veyq/manifest.json', text(JSON.stringify(manifest)))
  })
}
const fileNode = (path: string, body = 'x', over: Record<string, unknown> = {}) =>
  ({ id: 'n2', kind: FILE, name: path.split('/').pop()!, path, content: { type: 'h.file', schema: 1, file: { path, size: body.length, mediaType: 'text/plain', sha256: sha(body) }, ...over } })
const folder = (children: unknown[] = [], over: Record<string, unknown> = {}) => ({ id: 'n1', kind: 'FOLDER', name: 'Home', path: 'Home', children, ...over })

async function refused(input: ImportInput | Uint8Array, code: string, message?: RegExp) {
  const err = await attempt(input instanceof Uint8Array ? zipOf(input) : input).catch((e) => e)
  expect(err).toMatchObject({ code })
  if (message) expect(err.message, 'refused by the check named, not another one').toMatch(message)
  expect(createContentNode, 'nothing is written').not.toHaveBeenCalled()
}

describe('the hardened reader refuses, before anything is written', () => {
  it('control: the crafted archive itself is sound and imports', async () => {
    await attempt(zipOf(await crafted(folder([fileNode('Home/a.txt')]), { 'Home/a.txt': 'x' })))
    expect(created.map((c) => c.name)).toEqual(['Home', 'a.txt'])
  })

  describe('schema', () => {
    it('a field of the wrong type', async () => refused(await crafted(folder([], { name: 42 })), 'invalid_manifest', /name: not a string/))
    it('a content type that is not <application>.<name>', async () =>
      refused(await crafted(folder([{ ...fileNode('Home/a.txt'), content: { ...fileNode('Home/a.txt').content, type: 'Not A Type' } }]), { 'Home/a.txt': 'x' }), 'invalid_manifest', /bad content type/))
    it('an application the manifest does not list', async () =>
      refused(await crafted(folder([fileNode('Home/a.txt')]), { 'Home/a.txt': 'x' }, { applications: [] }), 'invalid_manifest', /is not listed/))
    it('a version 2 manifest at the version 1 place', async () => {
      const bytes = await zipBytes(async (w) => w.addBytes('manifest.json', text(JSON.stringify({ format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [], root: folder() }))))
      await refused(bytes, 'invalid_manifest')
    })
    it('a kind\'s own content check (a field over its contract length)', async () =>
      refused(await crafted(folder([fileNode('Home/a.txt', 'x', { note: 'far more than ten' })]), { 'Home/a.txt': 'x' }), 'too_large'))
  })

  describe('caps', () => {
    it('a name over its contract length', async () => refused(await crafted(folder([], { name: 'x'.repeat(LIMITS.name + 1) })), 'too_large'))
    it('a tree deeper than the cap', async () => {
      let n: Record<string, unknown> = { id: 'd', kind: 'FOLDER', name: 'd', path: 'Home' }
      let path = 'Home'
      const root = n
      for (let i = 0; i <= MAX_DEPTH; i++) {
        path = `${path}/d`
        const c = { id: `d${i}`, kind: 'FOLDER', name: 'd', path }
        n.children = [c]
        n = c
      }
      await refused(await crafted(root), 'too_large')
    })
    it('more nodes than the cap', async () => {
      const children = Array.from({ length: MAX_NODES }, (_, i) => ({ id: `c${i}`, kind: 'FOLDER', name: 'c', path: `Home/c${i}` }))
      await refused(await crafted(folder(children)), 'too_large')
    })
  })

  describe('paths', () => {
    // each case keeps every OTHER check satisfied, so the one named is the one that refuses
    const album = (path: string, files: string[]) =>
      ({ id: 'n7', kind: ALBUM, name: 'Album', path, content: { type: 'h.album', schema: 1, files: files.map((f) => ({ path: f, size: 1, mediaType: 'image/jpeg' })) } })
    it('a payload path with an upward segment', async () => refused(await crafted(folder([album('Home/x', ['Home/x/..'])])), 'unsafe_path', /unsafe payload path/))
    it('a node reaching into .veyq/', async () => refused(await crafted({ ...folder(), name: '.veyq', path: '.veyq' }), 'unsafe_path', /unsafe node path/))
    it('a payload that is not its node\'s', async () =>
      refused(await crafted(folder([{ ...fileNode('Home/a.txt'), content: fileNode('Home/b.txt').content }]), { 'Home/b.txt': 'x' }), 'unsafe_path', /unsafe payload path/))
    it('a node path that is not one segment below its parent', async () =>
      refused(await crafted(folder([{ ...folder(), id: 'n9', path: 'Home/x/y' }])), 'unsafe_path'))
    it('a name a file system refuses', async () => refused(await crafted(folder([fileNode('Home/CON')]), { 'Home/CON': 'x' }), 'unsafe_path'))
  })

  describe('payloads', () => {
    it('a payload the archive lacks', async () => refused(await crafted(folder([fileNode('Home/a.txt')])), 'payload_missing'))
    it('a payload that is not the declared size', async () => refused(await crafted(folder([fileNode('Home/a.txt', 'x')]), { 'Home/a.txt': 'xy' }), 'size_mismatch'))
    it('a directory payload missing, too', async () => {
      const dir = (await unpacked(await crafted(folder([fileNode('Home/a.txt')]), { 'Home/a.txt': 'x' }))) as { kind: 'directory'; name: string; files: DirectoryFile[] }
      await refused({ ...dir, files: dir.files.filter((f) => f.path !== 'Home/a.txt') }, 'payload_missing')
    })
    it('bytes that do not match their SHA-256: the import stops and trashes what it created', async () => {
      const bad = { ...fileNode('Home/a.txt', 'x') }
      bad.content.file.sha256 = sha('y')
      const err = await attempt(zipOf(await crafted(folder([bad]), { 'Home/a.txt': 'x' }))).catch((e) => e)
      expect(err.cause).toMatchObject({ code: 'checksum' })
      expect(deleteNode).toHaveBeenCalledWith('s', 'new1')
    })
  })

  describe('the ZIP structure', () => {
    const sound = () => crafted(folder([fileNode('Home/a.txt', 'abc'), { ...fileNode('Home/b.txt', 'def'), id: 'n3' }]), { 'Home/a.txt': 'abc', 'Home/b.txt': 'def' })
    /** The offset of the central record of `name`. */
    function central(bytes: Uint8Array, name: string): number {
      const v = new DataView(bytes.buffer, bytes.byteOffset)
      const eocd = bytes.length - 22
      let p = v.getUint32(eocd + 16, true)
      for (;;) {
        const n = new TextDecoder().decode(bytes.subarray(p + 46, p + 46 + v.getUint16(p + 28, true)))
        if (n === name) return p
        p += 46 + v.getUint16(p + 28, true) + v.getUint16(p + 30, true) + v.getUint16(p + 32, true)
      }
    }

    it('control: the sound archive imports', async () => {
      await attempt(zipOf(await sound()))
      expect(created.map((c) => c.name)).toEqual(['Home', 'a.txt', 'b.txt'])
    })

    it('an offset outside the file', async () => {
      const bytes = await sound()
      new DataView(bytes.buffer).setUint32(central(bytes, 'Home/b.txt') + 42, bytes.length + 100, true)
      await refused(bytes, 'damaged_archive', /lies outside the file/)
    })

    it('a declared size that is not the actual one', async () => {
      const bytes = await sound()
      const c = central(bytes, 'Home/a.txt')
      new DataView(bytes.buffer).setUint32(c + 20, 4, true)
      new DataView(bytes.buffer).setUint32(c + 24, 4, true)
      await refused(bytes, 'damaged_archive', /declared size or checksum is not the directory's/)
    })

    it('two entries that overlap', async () => {
      // `inner` is a whole local entry (header, bytes, descriptor) of b.txt; stored as the bytes of a.txt, it sits INSIDE a.txt
      const one = await zipBytes(async (w) => w.addBytes('Home/b.txt', text('def')))
      const v = new DataView(one.buffer)
      const innerEnd = v.getUint32(one.length - 22 + 16, true)
      const inner = one.subarray(0, innerEnd)
      const body = new TextDecoder('latin1').decode(inner)
      const manifest = folder([fileNode('Home/a.txt', body), { ...fileNode('Home/b.txt', 'def'), id: 'n3' }])
      const bytes = await zipBytes(async (w) => {
        await w.addFile('Home/a.txt', inner.length, [inner])
        await w.addBytes('Home/c.txt', text('zzz'))
        await w.addBytes('.veyq/manifest.json', text(JSON.stringify({ format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [{ id: 'h', contentSchema: 1 }], root: manifest })))
      })
      // point c.txt's directory line at the b.txt entry inside a.txt's bytes: same name length, so only the fields change
      const dv = new DataView(bytes.buffer)
      const c = central(bytes, 'Home/c.txt')
      bytes.set(text('Home/b.txt'), c + 46)
      dv.setUint32(c + 16, v.getUint32(innerEnd + 16, true), true) // b.txt's CRC from its own directory line
      const aData = 30 + 'Home/a.txt'.length // a.txt is the first entry: its bytes start right after its header
      dv.setUint32(c + 42, aData, true)
      await refused(bytes, 'damaged_archive', /overlaps/)
    })

    it('a ZIP64 field out of range', async () => {
      const bytes = await zipBytes(async (w) => {
        await w.addBytes('Home/a.txt', text('abc'))
        await w.addBytes('.veyq/manifest.json', text(JSON.stringify({ format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [{ id: 'h', contentSchema: 1 }], root: folder([fileNode('Home/a.txt', 'abc')]) })))
      }, 1)
      const v = new DataView(bytes.buffer)
      const recordAt = Number(v.getBigUint64(bytes.length - 22 - 20 + 8, true))
      expect(v.getUint32(recordAt, true)).toBe(0x06064b50) // the ZIP64 record is there: the lever reaches it
      v.setBigUint64(recordAt + 48, BigInt(bytes.length) * 4n, true) // the directory "starts" far past the end
      await refused(bytes, 'damaged_archive', /directory outside the file/)
    })
  })
})

// --- inbox #555: the check's digest is handed over with the file it verified ------------------------------------------

describe('the payload check hands the SHA-256 it computed to the upload', () => {
  const body = 'the payload bytes'
  const want = b64url(v1.sha256Digest(text(body)))
  /** A sound archive of one file; `claimed: false` - its manifest names no `sha256` (the contract's field is optional). */
  function archive(claimed = true) {
    const n = fileNode('Home/a.txt', body)
    if (!claimed) delete (n.content.file as { sha256?: string }).sha256
    return crafted(folder([n]), { 'Home/a.txt': body })
  }
  /** The file the hook got answers `contentDigest` from the check's digest, without a read; a copy of it is read. */
  async function handedOver(input: ImportInput) {
    await attempt(input)
    expect(grabbed).toHaveLength(1)
    const file = grabbed[0] as File
    const slice = vi.spyOn(file, 'slice')
    expect(await contentDigest(file)).toBe(want)
    expect(slice, 'answered from the check\'s digest: the file is not read again').not.toHaveBeenCalled()
    expect(await digestHere(file), 'the digest of that object\'s own bytes').toBe(want)
    // control: another object over the same bytes misses the memo, is read, and answers the same digest
    const copy = new File([file], file.name)
    const copySlice = vi.spyOn(copy, 'slice')
    expect(await contentDigest(copy)).toBe(want)
    expect(copySlice, 'a copy is hashed').toHaveBeenCalled()
  }

  it('a ZIP\'s payload', async () => handedOver(zipOf(await archive())))
  it('the same download unpacked into a directory', async () => handedOver(await unpacked(await archive())))

  // Computed, not claimed: with no `sha256` in the manifest there is no claim to hand over, so a remembered digest can
  // only be the one core's hasher produced.
  it('a manifest without a sha256: the computed digest is still handed over (ZIP)', async () => handedOver(zipOf(await archive(false))))
  it('a manifest without a sha256: the computed digest is still handed over (directory)', async () =>
    handedOver(await unpacked(await archive(false))))

  it('a sha256 of other bytes refuses with `checksum`, and the hook gets no file', async () => {
    const n = fileNode('Home/a.txt', body)
    n.content.file.sha256 = sha('other bytes')
    const err = await attempt(zipOf(await crafted(folder([n]), { 'Home/a.txt': body }))).catch((e) => e)
    expect(err.cause).toMatchObject({ code: 'checksum' })
    expect(grabbed, 'no hook received the payload').toEqual([])
    expect(created.map((c) => c.kind), 'the hook built nothing: only the folder above it').toEqual(['FOLDER'])
  })

  it('a ZIP entry that fails its CRC refuses with `checksum` (no sha256 to compare), and the hook gets no file', async () => {
    const bytes = await archive(false)
    const at = new TextDecoder('latin1').decode(bytes).indexOf(body)
    expect(at).toBeGreaterThan(0)
    bytes[at] ^= 0x01 // one flipped bit of the stored payload: the size holds, only the CRC can tell
    const err = await attempt(zipOf(bytes)).catch((e) => e)
    expect(err.cause).toMatchObject({ code: 'checksum' })
    expect(grabbed, 'no hook received the payload').toEqual([])
    expect(created.map((c) => c.kind)).toEqual(['FOLDER'])
  })
})

describe('the contract and the reader agree on every cap', () => {
  const schema = JSON.parse(readFileSync(new URL('../../../../../contracts/export/veyq-subtree.schema.json', import.meta.url), 'utf8'))
  it('each LIMITS entry is the schema\'s maxLength / maxItems of that field', () => {
    expect({
      appVersion: schema.properties.appVersion.maxLength,
      applications: schema.properties.applications.maxItems,
      kind: schema.$defs.node.properties.kind.maxLength,
      name: schema.$defs.node.properties.name.maxLength,
      path: schema.$defs.archivePath.maxLength,
      contentType: schema.$defs.content.properties.type.maxLength,
      mediaType: schema.$defs.payload.properties.mediaType.maxLength,
    }).toEqual(LIMITS)
  })
})
