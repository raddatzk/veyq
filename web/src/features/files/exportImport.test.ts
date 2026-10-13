import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./api', () => ({ filesApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('./download', () => ({ fileFacts: vi.fn(), fileChunkProvider: vi.fn() }))
vi.mock('./uploadFile', () => ({ fileUploadJob: vi.fn() }))

import { exportFile, importFile, FILES_FILE_CONTENT } from './exportImport'
import { fileUploadJob } from './uploadFile'
import { filesApi } from './api'
import { stubApi } from '../../test/apiMock'
import type { FileListPage } from '../../api/generated/files'
import { fileChunkProvider, fileFacts } from './download'
import type { ExportNodeContext, ImportNodeContext } from '../../core/export/hooks'
import type { ManifestNode } from '../../core/export/manifest'

const ctx = (over: Partial<ExportNodeContext> = {}) => {
  const payloads: { name: string; source: Parameters<ExportNodeContext['addPayload']>[1] }[] = []
  const c = {
    scopeId: 's', node: { id: 'f', parentId: 'p', name: 'a.txt' }, nodeKey: new Uint8Array(32), cache: new Map(), epochs: {}, options: { includeLocation: true },
    addPayload: (name: string, source: Parameters<ExportNodeContext['addPayload']>[1]) => {
      payloads.push({ name, source })
      return { path: `data/n1/${name}`, size: source.size, mediaType: source.mediaType }
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
    expect(content).toMatchObject({ type: FILES_FILE_CONTENT, schema: 1, file: { path: 'data/n1/a.txt', size: 5, mediaType: 'text/plain' } })
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

  it('lists a folder once per run', async () => {
    const cache = new Map()
    await exportFile(ctx({ cache }).c)
    await exportFile(ctx({ cache }).c)
    expect(listFiles).toHaveBeenCalledTimes(1)
  })
})

describe('importFile', () => {
  const node = { id: 'n2', kind: 'FILE', name: 'a.txt', content: { type: FILES_FILE_CONTENT, schema: 1, file: { path: 'data/n2/a.txt', size: 5, mediaType: 'text/plain' } } } as ManifestNode
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

  it('refuses a node whose content is not a Files file', async () => {
    await expect(importFile(importCtx({ node: { id: 'n', kind: 'FILE', name: 'x', content: { type: 'other' } } as ManifestNode }))).rejects.toThrow('import_invalid_content')
  })
})
