// uc.memory.upload (web, cr-0074) — the optimistic-tile selection + reconcile model (pure, no DOM).
import { describe, it, expect } from 'vitest'
import { selectOptimisticUploads, removedThumbIds, optimisticTileId } from './optimisticUpload'
import type { UploadBatch, UploadFile, UploadStatus } from './uploadQueue'
import type { MediaItem } from './media'

const file = (id: string, status: UploadStatus, mediaId?: string): UploadFile => ({
  id,
  name: `${id}.jpg`,
  sizeBytes: 3,
  mediaType: 'image/jpeg',
  status,
  progress: status === 'done' ? 1 : 0,
  attempts: 1,
  controller: new AbortController(),
  file: new File([new Uint8Array([1])], `${id}.jpg`, { type: 'image/jpeg' }),
  mediaId,
})

const batch = (seq: number, nodeId: string, files: UploadFile[]): UploadBatch => ({
  id: `b${seq}`,
  label: `${files.length}`,
  target: { scopeId: 's', nodeId, memoryKey: new Uint8Array(32) },
  files,
  seq,
  finishedAt: null,
  acknowledged: false,
})

const item = (mediaId: string): MediaItem => ({
  mediaId,
  original: { blobId: `blob-${mediaId}`, chunkCount: 1, byteSize: 3, wrappedBlobKey: 'k' },
  derivatives: [],
})

describe('selectOptimisticUploads', () => {
  it('builds tiles from this node’s in-flight files, in enqueue order (seq, then file order)', () => {
    const batches = [
      batch(1, 'n', [file('a', 'uploading'), file('b', 'queued')]),
      batch(0, 'n', [file('c', 'queued')]), // earlier seq → first, despite array order
    ]
    const picked = selectOptimisticUploads(batches, 'n', new Set())
    expect(picked.map((f) => f.id)).toEqual(['c', 'a', 'b'])
  })

  it('ignores batches targeting a different node', () => {
    const batches = [batch(0, 'other', [file('a', 'uploading')]), batch(1, 'n', [file('b', 'queued')])]
    expect(selectOptimisticUploads(batches, 'n', new Set()).map((f) => f.id)).toEqual(['b'])
  })

  it('keeps a failed file (it carries a retry), drops a cancelled one', () => {
    const batches = [batch(0, 'n', [file('a', 'failed'), file('b', 'cancelled')])]
    expect(selectOptimisticUploads(batches, 'n', new Set()).map((f) => f.id)).toEqual(['a'])
  })

  it('keeps a done file until its media lists, then drops it (reconcile by mediaId)', () => {
    const batches = [batch(0, 'n', [file('a', 'done', 'media-1')])]
    // not yet listed → still shown (no gap)
    expect(selectOptimisticUploads(batches, 'n', new Set()).map((f) => f.id)).toEqual(['a'])
    // listed under the same id → optimistic tile hands off to the real one
    expect(selectOptimisticUploads(batches, 'n', new Set(['media-1']))).toEqual([])
  })

  it('never drops a done file against a different media id', () => {
    const batches = [batch(0, 'n', [file('a', 'done', 'media-1')])]
    expect(selectOptimisticUploads(batches, 'n', new Set(['media-2'])).map((f) => f.id)).toEqual(['a'])
  })

  it('keeps a done upload as the uploader’s session-only own-view — the resolve lists nothing (cr-0102)', () => {
    // an UPLOADER share resolve always returns media=[], so a settled tile is never reconciled away: it stays as
    // the local-preview own-view for the whole session (keyed by the shareId, not a node id).
    const shareBatch: UploadBatch = { ...batch(0, 'n', [file('a', 'done', 'media-1')]), target: { key: 'share-1' } }
    expect(selectOptimisticUploads([shareBatch], 'share-1', new Set()).map((f) => f.id)).toEqual(['a'])
  })
})

describe('removedThumbIds', () => {
  it('returns only the thumb ids the fresh list dropped (incremental merge, no needless refetch)', () => {
    const listed = [item('m1'), item('m3')]
    expect(removedThumbIds(['m1', 'm2', 'm3'], listed).sort()).toEqual(['m2'])
  })

  it('returns nothing when every current thumb is still listed', () => {
    expect(removedThumbIds(['m1'], [item('m1'), item('m2')])).toEqual([])
  })
})

describe('optimisticTileId', () => {
  it('namespaces a file id so it can’t collide with a real media id', () => {
    expect(optimisticTileId('abc')).toBe('upload:abc')
  })
})
