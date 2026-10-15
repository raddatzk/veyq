// @vitest-environment node
// A drop box downloads and imports as a plain folder (cr-download-is-the-export Decision 7): through core's REAL walker, writer,
// reader and import with Depot's REAL registration (its hooks, its `importsAsFolder`), the drop box is a directory, each guest's
// folder a directory under the name the drop box shows it by (a repeated name numbered in creation order, as the detail does),
// the files inside; the manifest keeps the kind it was. The import creates the drop box and the guests' folders as plain
// FOLDERs - no drop box, no drop key, no identity, no link - and the files through the ordinary upload job.
// Stubbed by path: Depot's listing (`listFiles`), the blob's facts and chunks (`./download`), the upload job, and core's node
// create - the writes an import makes are what is asserted.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./api', () => ({ filesApi: vi.fn(), publicShareFilesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('./download', () => ({ fileFacts: vi.fn(), fileChunkProvider: vi.fn() }))
vi.mock('./uploadFile', () => ({ fileUploadJob: vi.fn() }))
vi.mock('../../core/node/tree', async (orig) => ({ ...(await orig<typeof import('../../core/node/tree')>()), createContentNode: vi.fn(), deleteNode: vi.fn() }))

import { registry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import { createContentNode, type TreeNode } from '../../core/node/tree'
import { displayNamesOf, compareByCreation } from '../../core/node/location'
import { exportSubtree } from '../../core/export/exportSubtree'
import { importSubtree, openExport, planImport } from '../../core/export/importSubtree'
import { openZip } from '../../core/export/zip'
import { canDownload } from '../../core/export/downloadActions'
import type { ByteSink } from '../../core/blob/stream'
import type { TreeSource } from '../../core/access'
import type { EpochKeys } from '../spaces/epochs'
import { ShareRole } from '../../api/generated/sharing'
import { memberAccessOf, guestAccessOf } from '../../test/models'
import { fakeApi } from '../../test/apiMock'
import { registerDepot } from './register'
import { filesApi } from './api'
import { fileChunkProvider, fileFacts } from './download'
import { fileUploadJob, type FileUploadTarget } from './uploadFile'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { DEPOT_DROPBOX_CONTENT } from './exportImport'
import type { PayloadFile } from '../../core/export/hooks'

if (!registry.kind(DROPBOX_KIND).supported) registerDepot(registry)

const epochs = { current: 1, keys: new Map() } as unknown as EpochKeys
const access = memberAccessOf({ scopeId: 's', epochs })
const node = (id: string, parentId: string | null, kind: string, name: string, createdAt = 0, over: Partial<TreeNode> = {}): TreeNode =>
  ({ id, parentId, kind, name, orderIndex: 0, epoch: 1, placementVersion: 0, wrappedContentKey: '', wrappedMeta: null, metaVersion: 0, createdAt, ...over }) as unknown as TreeNode
// A drop box in a folder; two guests named Anna (the second sent later), each with a file.
const NODES = [
  node('box', null, DROPBOX_KIND, 'Postfach'),
  node('anna2', 'box', FOLDER_KIND, 'Dr. Anna', 2000, { shareIdentityId: 'id-2' }),
  node('anna1', 'box', FOLDER_KIND, 'Dr. Anna', 1000, { shareIdentityId: 'id-1' }),
  node('f1', 'anna1', FILE_KIND, 'first.txt'),
  node('f2', 'anna2', FILE_KIND, 'second.txt'),
]
/** The member's tree as the views read it: every key open, equal names numbered in creation order (`displayNameOf`). */
const tree: TreeSource<TreeNode> = {
  rootId: null,
  node: (id) => NODES.find((n) => n.id === id) ?? null,
  children: (id) => NODES.filter((n) => n.parentId === id),
  keyOf: (id) => (NODES.some((n) => n.id === id) ? new Uint8Array(32).fill(1) : null),
  nameOf: (id) => {
    const n = NODES.find((x) => x.id === id)
    return n ? (displayNamesOf(NODES.filter((x) => x.parentId === n.parentId), (x) => x.name, compareByCreation).get(id) ?? n.name) : ''
  },
  inIdentityFolder: (id) => !!id && id !== 'box',
  linkTo: () => '/',
}
const BODIES: Record<string, string> = { f1: 'from the first Anna', f2: 'from the second Anna' }
const BLOB = (id: string) => ({ blobId: `b-${id}`, chunkCount: 1, byteSize: 1, wrappedBlobKey: 'k', wrappedBlobMeta: null })

function memorySink() {
  const parts: Uint8Array[] = []
  const sink: ByteSink = { write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} }
  return { sink, blob: () => new Blob(parts as BlobPart[]) }
}

beforeEach(() => {
  vi.clearAllMocks()
  fakeApi(filesApi, {
    listFiles: async ({ parentId }) => ({ items: NODES.filter((n) => n.parentId === parentId && n.kind === FILE_KIND).map((n) => ({ nodeId: n.id, fileBlob: BLOB(n.id) })) }),
  })
  vi.mocked(fileFacts).mockImplementation((_s, _k, nodeId) => {
    return { size: new TextEncoder().encode(BODIES[nodeId]).length, meta: { v: 1, type: 'text/plain', name: 'x', modifiedAt: 1 } }
  })
  vi.mocked(fileChunkProvider).mockImplementation((_s, _k, nodeId) => {
    const bytes = new TextEncoder().encode(BODIES[nodeId])
    return { chunkCount: 1, getPlaintextChunk: async () => bytes } as unknown as ReturnType<typeof fileChunkProvider>
  })
})

async function download() {
  const m = memorySink()
  const result = await exportSubtree({ access, tree, rootId: 'box', sink: m.sink, appVersion: 'test', now: () => new Date('2026-10-14T10:00:00Z') })
  return { result, archive: m.blob() }
}

describe('a drop box as a download', () => {
  it('is a directory of its guests\' folders under their shown names, the files inside; the manifest keeps the kind it was', async () => {
    const { result, archive } = await download()
    expect(result.skipped).toEqual([])
    expect((await openZip(archive)).entries.map((e) => e.name).sort()).toEqual([
      // `Dr. Anna (2)` as the detail shows it - not archiveName's own suffix before an "extension" (`Dr (2). Anna`)
      '.veyq/manifest.json', 'Postfach/', 'Postfach/Dr. Anna (2)/', 'Postfach/Dr. Anna (2)/second.txt', 'Postfach/Dr. Anna/', 'Postfach/Dr. Anna/first.txt',
    ])
    const { manifest } = await openExport({ kind: 'zip', file: new File([archive], 'd.zip') })
    expect(manifest.root).toMatchObject({ kind: DROPBOX_KIND, name: 'Postfach', path: 'Postfach', content: { type: DEPOT_DROPBOX_CONTENT, schema: 1 } })
    // the manifest's names are the nodes' own; the paths are the shown names, made safe
    expect(manifest.root.children!.map((c) => [c.kind, c.name, c.path])).toEqual([
      [FOLDER_KIND, 'Dr. Anna', 'Postfach/Dr. Anna'], [FOLDER_KIND, 'Dr. Anna', 'Postfach/Dr. Anna (2)'],
    ])
    expect(manifest.applications).toEqual([{ id: 'depot', contentSchema: 1 }])
  })

  it('imports as plain folders: the drop box and each guest\'s folder a FOLDER, the files uploaded under them - no drop box, no share', async () => {
    const { archive } = await download()
    const opened = await openExport({ kind: 'zip', file: new File([archive], 'd.zip') })
    expect(planImport(opened.manifest)).toMatchObject({ nodes: 5, skipped: [] })
    let n = 0
    vi.mocked(createContentNode).mockImplementation(async () => ({ nodeId: `new${++n}`, contentKey: new Uint8Array(32).fill(n) }))
    const uploaded: { parent: string | undefined; name: string; body: string }[] = []
    vi.mocked(fileUploadJob).mockImplementation((target: FileUploadTarget, file: PayloadFile) => ({
      name: file.name, sizeBytes: file.size, extra: { file },
      run: async () => {
        uploaded.push({ parent: target.parent?.id, name: file.name, body: new TextDecoder().decode(await file.slice(0, file.size).arrayBuffer()) })
        return { nodeId: `file-${file.name}` }
      },
    }))
    await importSubtree({ scopeId: 's', epochs, tree: [], parent: { id: 'target', key: new Uint8Array(32) }, opened })
    // every node core created is a FOLDER; the two guests' folders of one name are told apart as siblings of one folder are
    expect(vi.mocked(createContentNode).mock.calls.map(([, , parent, kind, name]) => [parent?.id, kind, name])).toEqual([
      ['target', FOLDER_KIND, 'Postfach'], ['new1', FOLDER_KIND, 'Dr. Anna'], ['new1', FOLDER_KIND, 'Dr. Anna (2)'],
    ])
    // each guest's files land in the folder named as its directory in the download: the first Anna's in `Anna`
    expect(uploaded).toEqual([
      { parent: 'new2', name: 'first.txt', body: 'from the first Anna' },
      { parent: 'new3', name: 'second.txt', body: 'from the second Anna' },
    ])
  })

  it('refuses a drop box content that carries more than what it was, before anything is written (decision 10)', async () => {
    const { archive } = await download()
    const { manifest } = await openExport({ kind: 'zip', file: new File([archive], 'd.zip') })
    const tampered = { ...manifest, root: { ...manifest.root, content: { type: DEPOT_DROPBOX_CONTENT, schema: 1, dropKey: 'x' } } }
    expect(() => planImport(tampered)).toThrow(/drop box with dropKey/)
    const wrongType = { ...manifest, root: { ...manifest.root, content: { type: 'depot.file', schema: 1 } } }
    expect(() => planImport(wrongType)).toThrow(/not a Depot drop box/)
  })

  // The `files` values below are the point of the case, not leftovers: a sweep that renames `files.*` must spare them
  // (one did, and the case then read its own archive as Depot's).
  it('skips an archive written before the rename (`files.*`, application `files`): no alias reads it as Depot\'s (ADR 0086)', async () => {
    const { archive } = await download()
    const { manifest } = await openExport({ kind: 'zip', file: new File([archive], 'd.zip') })
    const before = JSON.parse(JSON.stringify(manifest).replaceAll('"depot.', '"files.').replaceAll('"id":"depot"', '"id":"files"'))
    expect(JSON.stringify(before)).toContain('"files.file"')
    expect(planImport(before)).toMatchObject({
      nodes: 0, skipped: [{ name: 'Postfach', kind: DROPBOX_KIND, application: 'files', reason: 'unknown_application', nodes: 5 }],
    })
  })

  it('is offered to a member and to a READ or READ_WRITE guest of a link above it - never to its own WRITE_ONLY guest', () => {
    const box = { kind: DROPBOX_KIND }
    expect(canDownload(access, box)).toBe(true)
    expect(canDownload(guestAccessOf(ShareRole.Read), box)).toBe(true)
    expect(canDownload(guestAccessOf(ShareRole.ReadWrite), box)).toBe(true)
    expect(canDownload(guestAccessOf(ShareRole.WriteOnly), box)).toBe(false)
    // a file still downloads as itself, through its own action: core offers no ZIP for it
    expect(canDownload(access, { kind: FILE_KIND })).toBe(false)
  })
})
