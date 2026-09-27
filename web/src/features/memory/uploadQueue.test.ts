// uc.memory.upload (web, cr-0058) — the upload queue: enqueue → sequential upload via addMedia → done, with
// progress, cancel (abort), and one automatic retry on a transient failure; plus the Safari file-selection
// helpers (extension fallback + signature merge).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', () => ({ addMedia: vi.fn() }))

import { addMedia } from './media'
import {
  enqueueUploads,
  cancelFile,
  retryFile,
  removeFile,
  clearUploads,
  useUploadQueue,
  isSupportedMediaFile,
  inferMediaType,
  mergeFiles,
  isRetryable,
  type UploadTarget,
} from './uploadQueue'

const TARGET: UploadTarget = { scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }
const png = (name = 'photo.png') => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/png', lastModified: 1000 })
const sel = (file: File) => ({ file, mediaType: file.type || 'application/octet-stream' })

beforeEach(() => {
  clearUploads()
  vi.clearAllMocks()
})

describe('uploadQueue — runner', () => {
  it('uploads a file via addMedia and marks it done, reporting progress', async () => {
    vi.mocked(addMedia).mockImplementation(async (_s, _n, _k, _src, _d, opts) => {
      opts?.onProgress?.(0.5)
      opts?.onProgress?.(1)
      return { blobId: 'b', mediaId: 'm' }
    })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1 photo' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('done'))
    expect(file.progress).toBe(1)
    expect(addMedia).toHaveBeenCalledOnce()
    const [scopeId, nodeId, , source] = vi.mocked(addMedia).mock.calls[0]
    expect(scopeId).toBe('s')
    expect(nodeId).toBe('n')
    expect(source.mediaType).toBe('image/png')
  })

  it('hands the upload the picked File itself and never reads it whole (ADR 0013 Consequences)', async () => {
    // A large video read whole — and then sealed whole — dies in the tab long before any server limit; the pump
    // reads the File a frame at a time, so the queue must pass it on unread.
    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    const file = png()
    const readWhole = vi.spyOn(file, 'arrayBuffer')
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(file)], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))

    expect(readWhole).not.toHaveBeenCalled()
    expect(vi.mocked(addMedia).mock.calls[0][3].blob).toBe(file)
  })

  it('records the mediaId addMedia returns on the settled file (cr-0074 reconcile key)', async () => {
    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'media-xyz' })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('done'))
    expect(file.mediaId).toBe('media-xyz')
  })

  it('fires onFileDone per file as it settles, before the batch onComplete (cr-0111 per-file reconcile)', async () => {
    vi.mocked(addMedia).mockImplementation(async (_s, _n, _k, source) => ({ blobId: 'b', mediaId: `m-${source.mediaType}` }))
    const done: string[] = []
    let completeSawCount = -1
    enqueueUploads(
      TARGET,
      [sel(png('a.png')), sel(new File([new Uint8Array([9])], 'b.gif', { type: 'image/gif' }))],
      {
        label: '2',
        onFileDone: (f) => { done.push(f.mediaId!) },
        onComplete: () => { completeSawCount = done.length },
      },
    )
    // each file fires its OWN completion hook (a per-file reconcile trigger)…
    await vi.waitFor(() => expect(done.length).toBe(2))
    expect(new Set(done)).toEqual(new Set(['m-image/png', 'm-image/gif']))
    // …and the batch onComplete fires only after both per-file hooks (so per-file reconcile precedes batch settle)
    expect(completeSawCount).toBe(2)
  })

  it('uploads multiple files sequentially (FIFO)', async () => {
    const order: string[] = []
    vi.mocked(addMedia).mockImplementation(async (_s, _n, _k, source) => {
      order.push(source.mediaType)
      return { blobId: 'b', mediaId: 'm' }
    })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png('a.png')), sel(new File([new Uint8Array([9])], 'b.gif', { type: 'image/gif' }))], { label: '2' })

    await vi.waitFor(() => expect(batches.value[0].files.every((f) => f.status === 'done')).toBe(true))
    expect(order).toEqual(['image/png', 'image/gif'])
  })

  it('cancelling an uploading file aborts it and marks it cancelled', async () => {
    vi.mocked(addMedia).mockImplementation(
      (_s, _n, _k, _src, _d, opts) =>
        new Promise((_resolve, reject) => {
          opts?.onProgress?.(0) // first PUT progress → the file flips preparing → uploading (cr-0105 follow-up)
          const abort = () => reject(new DOMException('cancelled', 'AbortError'))
          if (opts?.signal?.aborted) return abort() // cancel may land before addMedia is reached (real addMedia throws upfront too)
          opts?.signal?.addEventListener('abort', abort)
        }),
    )
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('uploading'))
    cancelFile(file.id)
    await vi.waitFor(() => expect(file.status).toBe('cancelled'))
  })

  it('cancelling a still-preparing file (no progress yet) also aborts it → cancelled', async () => {
    // The pre-network phase (read + derive + seal) reports no progress, so the file sits in `preparing`. A video
    // whose on-device decode is slow lives here — cancel must abort it, not no-op (cr-0105 follow-up).
    vi.mocked(addMedia).mockImplementation(
      (_s, _n, _k, _src, _d, opts) =>
        new Promise((_resolve, reject) => {
          const abort = () => reject(new DOMException('cancelled', 'AbortError'))
          if (opts?.signal?.aborted) return abort()
          opts?.signal?.addEventListener('abort', abort)
        }), // deliberately never calls onProgress → stays 'preparing'
    )
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('preparing'))
    cancelFile(file.id)
    await vi.waitFor(() => expect(file.status).toBe('cancelled'))
  })

  it('retries once on a transient failure, then succeeds', async () => {
    let calls = 0
    vi.mocked(addMedia).mockImplementation(async () => {
      calls += 1
      if (calls === 1) throw new Error('chunk_put_failed:500')
      return { blobId: 'b', mediaId: 'm' }
    })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('done'))
    expect(calls).toBe(2)
    expect(file.attempts).toBe(2)
  })

  // uc.admin.tenant-storage-quota / ADR 0073 §6: no room left is not transient — a retry cannot help, so none is made.
  // The test above is this one's control: the same queue still retries every other failure once.
  it('fails a quota_exceeded file on its first attempt, with its reason, and makes no retry', async () => {
    vi.mocked(addMedia).mockRejectedValue(new Error('quota_exceeded'))
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('failed'))
    expect(file.error).toBe('quota_exceeded')
    expect(file.attempts).toBe(1)
    expect(addMedia).toHaveBeenCalledOnce()
    expect(isRetryable(file)).toBe(false)
  })

  it('makes no retry of a link upload refused quota_exceeded either (the share transport rides the same queue)', async () => {
    const upload = vi.fn().mockRejectedValue(new Error('quota_exceeded'))
    const { batches } = useUploadQueue()
    enqueueUploads({ key: 'share-1', upload }, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('failed'))
    expect(upload).toHaveBeenCalledOnce()
    expect(file.error).toBe('quota_exceeded')
  })

  it('removeFile drops a settled file (cr-0102 uploader delete-own) and empties an exhausted batch', async () => {
    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'media-xyz' })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png('a.png')), sel(png('b.png'))], { label: '2' })
    const [fileA, fileB] = batches.value[0].files
    await vi.waitFor(() => expect(fileA.status).toBe('done'))
    await vi.waitFor(() => expect(fileB.status).toBe('done'))

    removeFile(fileA.id) // one gone, the batch (and the other file) remains
    expect(batches.value[0].files.map((f) => f.name)).toEqual(['b.png'])

    removeFile(fileB.id) // the last file gone → the whole batch is dropped from the queue
    expect(batches.value).toHaveLength(0)
  })

  it('marks a file failed after the retry is exhausted; retryFile re-runs it', async () => {
    vi.mocked(addMedia).mockRejectedValue(new Error('chunk_put_failed:500'))
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('failed'))
    expect(file.error).toContain('chunk_put_failed')

    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    retryFile(file.id)
    await vi.waitFor(() => expect(file.status).toBe('done'))
  })
})

describe('uploadQueue — Safari file-selection helpers', () => {
  it('accepts a file by extension when its MIME type is empty (Photos export)', () => {
    expect(isSupportedMediaFile(new File([], 'IMG_0001.HEIC', { type: '' }))).toBe(true)
    expect(isSupportedMediaFile(new File([], 'clip.MOV', { type: '' }))).toBe(true)
    expect(isSupportedMediaFile(new File([], 'notes.txt', { type: '' }))).toBe(false)
  })

  it('infers a media type from the extension when the MIME is empty', () => {
    expect(inferMediaType(new File([], 'IMG.HEIC', { type: '' }))).toBe('image/heic')
    expect(inferMediaType(new File([], 'clip.mov', { type: '' }))).toBe('video/quicktime')
    expect(inferMediaType(new File([], 'photo.png', { type: 'image/png' }))).toBe('image/png')
  })

  it('merges later input reads without dropping earlier picks, de-duped by signature', () => {
    const a = new File([new Uint8Array([1])], 'a.png', { type: 'image/png', lastModified: 1 })
    const aDup = new File([new Uint8Array([1])], 'a.png', { type: 'image/png', lastModified: 1 })
    const b = new File([new Uint8Array([2])], 'b.png', { type: 'image/png', lastModified: 2 })

    const merged = mergeFiles([a], [aDup, b])
    expect(merged.map((f) => f.name)).toEqual(['a.png', 'b.png']) // a kept once, b added
  })
})
