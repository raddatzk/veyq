import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./api', () => ({ filesApi: vi.fn(), publicShareFilesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('./download', () => ({ fileFacts: vi.fn(), fileChunkProvider: vi.fn() }))
vi.mock('./uploadFile', () => ({ fileUploadJob: vi.fn() }))

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { exportFile, importFile, validateDropBoxContent, validateFileContent, DEPOT_DROPBOX_CONTENT, DEPOT_FILE_CONTENT } from './exportImport'
import { fileUploadJob } from './uploadFile'
import { filesApi, publicShareFilesApi } from './api'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import type { PublicShareHead } from '../../core/sharing/resolvePublicShare'
import { stubApi } from '../../test/apiMock'
import type { FileListPage } from '../../api/generated/depot'
import { fileChunkProvider, fileFacts } from './download'
import type { ExportNodeContext, ImportNodeContext } from '../../core/export/hooks'
import type { ManifestNode } from '../../core/export/manifest'

const ctx = (over: Partial<ExportNodeContext> = {}) => {
  const payloads: { name: string; source: Parameters<ExportNodeContext['addPayload']>[1] }[] = []
  const c = {
    access: memberAccessOf({ scopeId: 's' }), tree: { keyOf: () => new Uint8Array(32) },
    scopeId: 's', node: { id: 'f', parentId: 'p', name: 'a.txt' }, nodeKey: new Uint8Array(32), cache: new Map(),
    addPayload: (name: string, source: Parameters<ExportNodeContext['addPayload']>[1]) => {
      payloads.push({ name, source })
      return { path: `Trip/${name}`, size: source.size, mediaType: source.mediaType }
    },
    ...over,
  } as unknown as ExportNodeContext
  return { c, payloads }
}

let listFiles: ReturnType<typeof stubApi<typeof filesApi, 'listFiles'>>['listFiles']
const BLOB = { blobId: 'b', chunkCount: 1, byteSize: 100, wrappedBlobKey: 'k', wrappedBlobMeta: null }
const LISTING: FileListPage = { items: [{ nodeId: 'f', fileBlob: BLOB }, { nodeId: 'empty', fileBlob: null }] }
const run = vi.fn()
const abandon = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  listFiles = stubApi(filesApi, { listFiles: LISTING }).listFiles
  vi.mocked(fileFacts).mockReturnValue({ size: 5, meta: { v: 1, type: 'text/plain', name: 'a.txt', modifiedAt: 1700000000000 } })
  vi.mocked(fileChunkProvider).mockReturnValue({ chunkCount: 2, getPlaintextChunk: async (i: number) => new Uint8Array(i === 0 ? [1, 2, 3] : [4, 5]) } as unknown as ReturnType<typeof fileChunkProvider>)
  vi.mocked(fileUploadJob).mockReturnValue({ run, abandon } as unknown as ReturnType<typeof fileUploadJob>)
})

describe('exportFile', () => {
  it('declares the blob as a payload with its type and modified time, and streams the plaintext chunks', async () => {
    const { c, payloads } = ctx()
    const content = await exportFile(c)
    expect(content).toMatchObject({ type: DEPOT_FILE_CONTENT, schema: 1, file: { path: 'Trip/a.txt', size: 5, mediaType: 'text/plain' } })
    expect(listFiles).toHaveBeenCalledWith({ scopeId: 's', parentId: 'p' })
    expect(payloads[0].source.modifiedAt).toBe(1700000000000)
    const got: number[] = []
    for await (const chunk of payloads[0].source.chunks()) got.push(...chunk)
    expect(got).toEqual([1, 2, 3, 4, 5])
  })

  it('has nothing to export for a file whose upload never finished', async () => {
    const { c, payloads } = ctx({ node: { id: 'empty', parentId: 'p', name: 'x' } as unknown as ExportNodeContext['node'] })
    expect(await exportFile(c)).toBeNull()
    expect(payloads).toEqual([])
  })

  it('a READ guest lists through its link, once for the whole subtree, and never calls a member\'s API', async () => {
    const listShareFiles = stubApi(publicShareFilesApi, { listShareFiles: LISTING }).listShareFiles
    const cache = new Map()
    const guest = guestAccessOf('READ', { head: { scopeId: 's' } as unknown as PublicShareHead, pwToken: 'pw' })
    const first = ctx({ cache, access: guest })
    expect(await exportFile(first.c)).toMatchObject({ type: DEPOT_FILE_CONTENT })
    await exportFile(ctx({ cache, access: guest, node: { id: 'f', parentId: 'other', name: 'a.txt' } as unknown as ExportNodeContext['node'] }).c)
    expect(listShareFiles).toHaveBeenCalledTimes(1)
    expect(listShareFiles).toHaveBeenCalledWith({ shareId: 's1', xSharePassword: 'pw' })
    expect(listFiles).not.toHaveBeenCalled()
    // the chunks come through the link's presign (a url resolver), not the member's
    expect(vi.mocked(fileChunkProvider).mock.calls[0][4]).toEqual(expect.any(Function))
  })

  it('lists a folder once per run', async () => {
    const cache = new Map()
    await exportFile(ctx({ cache }).c)
    await exportFile(ctx({ cache }).c)
    expect(listFiles).toHaveBeenCalledTimes(1)
  })
})

describe('importFile', () => {
  const node = { id: 'n2', kind: 'FILE', name: 'a.txt', content: { type: DEPOT_FILE_CONTENT, schema: 1, file: { path: 'data/n2/a.txt', size: 5, mediaType: 'text/plain' } } } as ManifestNode
  const importCtx = (over: Partial<ImportNodeContext> = {}) =>
    ({ scopeId: 's', epochs: {}, parent: null, name: 'a (2).txt', node, payload: vi.fn(async () => new File(['12345'], 'a (2).txt')), cache: new Map(), ...over }) as unknown as ImportNodeContext

  it('uploads the payload as a file under the free name, through the ordinary upload job', async () => {
    run.mockResolvedValue({ nodeId: 'new' })
    const c = importCtx()
    expect(await importFile(c)).toMatchObject({ nodeId: 'new' })
    expect(c.payload).toHaveBeenCalledWith(node.content!.file, 'a (2).txt')
    expect(vi.mocked(fileUploadJob).mock.calls[0][0]).toMatchObject({ scopeId: 's', parent: null })
    expect(abandon).not.toHaveBeenCalled()
  })

  it('lets the job abandon its empty node when the upload fails', async () => {
    run.mockRejectedValue(new Error('quota_exceeded'))
    await expect(importFile(importCtx())).rejects.toThrow('quota_exceeded')
    expect(abandon).toHaveBeenCalledTimes(1)
  })

  it('refuses a node whose content is not a Depot file', async () => {
    await expect(importFile(importCtx({ node: { id: 'n', kind: 'FILE', name: 'x', content: { type: 'other' } } as ManifestNode }))).rejects.toThrow('import_invalid_content')
  })
})

// Depot's own content schema (ADR 0082): core's envelope leaves `content` opaque, and this file is what Depot's two
// validators answer for.
describe("Depot's content schema", () => {
  const schema = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/export/depot/content.schema.json'), 'utf8'))
  const d = schema.$defs

  it('names the two content types this module writes, and only those', () => {
    expect(schema.oneOf.map((r: { $ref: string }) => d[r.$ref.replace('#/$defs/', '')].properties.type.const)).toEqual([DEPOT_FILE_CONTENT, DEPOT_DROPBOX_CONTENT])
  })

  it('agrees with the validators: a file needs its payload, a drop box carries nothing but its type and schema', () => {
    expect(d.file.required).toEqual(['type', 'file'])
    expect(validateFileContent({ type: DEPOT_FILE_CONTENT, schema: 1 })).toMatchObject({ code: 'invalid_manifest' })
    expect(validateFileContent({ type: DEPOT_FILE_CONTENT, file: { path: 'a', size: 1 } })).toBeNull()
    expect(d.dropBox.additionalProperties).toBe(false)
    expect(Object.keys(d.dropBox.properties)).toEqual(['type', 'schema'])
    expect(validateDropBoxContent({ type: DEPOT_DROPBOX_CONTENT, schema: 1, dropKey: 'x' })).toMatchObject({ code: 'invalid_manifest' })
    expect(validateDropBoxContent({ type: DEPOT_DROPBOX_CONTENT, schema: 1 })).toBeNull()
  })
})
