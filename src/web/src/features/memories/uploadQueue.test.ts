// uc.memory.upload (web, cr-0058) — Memories' adapter over core's upload queue: a picked file becomes a job whose
// runner is addMedia (or the batch's share transport), its media id lands on the settled file, the per-file hook
// fires; plus the Safari file-selection helpers (extension fallback + signature merge). The queue's own rules
// (states, order, retry, quota_exceeded, cancel, remove) are pinned in core/blob/queue.test.ts over a fake runner.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./media', () => ({ addMedia: vi.fn(), prepareMedia: vi.fn(), createBrowserDeriver: vi.fn() }))

import { addMedia, prepareMedia, type PreparedMedia } from './media'
import { NodeRekeyPendingError } from '../../shared/rekeyRetry'
import {
  enqueueUploads,
  cancelFile,
  clearUploads,
  useUploadQueue,
  isSupportedMediaFile,
  inferMediaType,
  mergeFiles,
  type UploadTarget,
} from './uploadQueue'

const TARGET: UploadTarget = { scopeId: 's', nodeId: 'n', memoryKey: new Uint8Array(32) }
const png = (name = 'photo.png') => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/png', lastModified: 1000 })
const sel = (file: File) => ({ file, mediaType: file.type || 'application/octet-stream' })

/** What the mocked `prepareMedia` resolves with: a value naming its file, so the test sees which upload got which. */
const preparedOf = (source: { name?: string }) => ({ source, sha256: `sha-${source.name}`, tiers: [], meta: { dims: null, exif: null } }) as unknown as PreparedMedia

beforeEach(() => {
  clearUploads()
  vi.clearAllMocks()
  vi.mocked(prepareMedia).mockImplementation(async (source) => preparedOf(source))
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

  // Lever: drop `restored: s.facts` from the runner - the label never reaches the seal (ADR 0130 decision 9).
  it('hands a file\'s facts on as what is sealed at birth, and none for a file without', async () => {
    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    const transport = vi.fn(async () => ({ blobId: 'b', mediaId: 'g' }))
    const { batches } = useUploadQueue()
    const facts = { trackName: 'Ridge', trackActivity: 'hiking' }
    enqueueUploads(TARGET, [{ ...sel(png('a.gpx')), facts }, sel(png('b.png'))], { label: '2' })
    enqueueUploads({ key: 'guest', upload: transport }, [{ ...sel(png('c.gpx')), facts }], { label: '1' })
    await vi.waitFor(() => expect(batches.value.flatMap((b) => b.files).every((f) => f.status === 'done')).toBe(true))

    const byName = Object.fromEntries(vi.mocked(addMedia).mock.calls.map((c) => [c[3].name, c[5]?.restored]))
    expect(byName).toEqual({ 'a.gpx': facts, 'b.png': undefined })
    // a guest's transport gets the same option, so its upload seals the label too
    expect((transport.mock.calls[0] as unknown[])[1]).toMatchObject({ restored: facts })
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

  it('makes no retry of a link upload refused quota_exceeded either (the share transport rides the same queue)', async () => {
    const upload = vi.fn().mockRejectedValue(new Error('quota_exceeded'))
    const { batches } = useUploadQueue()
    enqueueUploads({ key: 'share-1', upload }, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('failed'))
    expect(upload).toHaveBeenCalledOnce()
    expect(file.error).toBe('quota_exceeded')
  })

  it('a share batch settles through its own transport, never addMedia (cr-0101)', async () => {
    const upload = vi.fn(async (_src: unknown, o: { onProgress?: (p: number) => void }) => {
      o.onProgress?.(0.5)
      return { blobId: 'b', mediaId: 'share-media' }
    })
    const { batches } = useUploadQueue()
    enqueueUploads({ key: 'share-1', upload }, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('done'))
    expect(addMedia).not.toHaveBeenCalled()
    expect(file.mediaId).toBe('share-media')
    expect(batches.value[0].target.key).toBe('share-1')
    expect(batches.value[0].key).toBe('share-1') // the grouping key core keeps, batchKey's answer
  })

  it('carries the picked file and its media type on the queue item (the optimistic tile reads both)', () => {
    vi.mocked(addMedia).mockImplementation(() => new Promise(() => {}))
    const picked = png()
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [{ file: picked, mediaType: 'image/heic' }], { label: '1' })
    const file = batches.value[0].files[0]

    expect(file.file).toBe(picked)
    expect(file.mediaType).toBe('image/heic')
    expect(file.name).toBe('photo.png')
    expect(file.sizeBytes).toBe(3)
    expect(batches.value[0].target).toEqual(TARGET)
  })

  it("hands addMedia the item's abort signal, so a cancel from the tray reaches the upload → cancelled", async () => {
    let seen: AbortSignal | undefined
    vi.mocked(addMedia).mockImplementation(
      (_s, _n, _k, _src, _d, opts) =>
        new Promise((_resolve, reject) => {
          seen = opts?.signal
          opts?.onProgress?.(0)
          opts?.signal?.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')))
        }),
    )
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    const file = batches.value[0].files[0]

    await vi.waitFor(() => expect(file.status).toBe('uploading'))
    expect(seen).toBe(file.controller.signal)
    cancelFile(file.id)
    await vi.waitFor(() => expect(file.status).toBe('cancelled'))
  })

  it('returns null for an empty pick', () => {
    expect(enqueueUploads(TARGET, [], { label: '0' })).toBeNull()
  })
})

// Inbox #220: each job's `prepare` is the file's on-device stage, memoized, and the queue starts it for the next file
// while the current one is on the wire.
describe('uploadQueue — the next file prepared while one is on the wire (inbox #220)', () => {
  it("prepares file 2 while file 1 is on the wire, once, and hands each upload its own file's result", async () => {
    let file1OnWire!: () => void
    const onWire = new Promise<void>((r) => (file1OnWire = r))
    let file2Prepared!: () => void
    const prepared2 = new Promise<void>((r) => (file2Prepared = r))
    vi.mocked(prepareMedia).mockImplementation(async (source) => {
      if (source.name === 'b.png') {
        await onWire // file 2's stage starts only once file 1 reported its first progress ...
        file2Prepared()
      }
      return preparedOf(source)
    })
    vi.mocked(addMedia).mockImplementation(async (_s, _n, _k, source, _d, opts) => {
      opts?.onProgress?.(0)
      if (source.name === 'a.png') {
        file1OnWire()
        await prepared2 // ... and file 1 finishes only once file 2's stage ran: an overlap gated on both sides
      }
      return { blobId: 'b', mediaId: `m-${source.name}` }
    })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png('a.png')), sel(png('b.png'))], { label: '2' })
    const [a, b] = batches.value[0].files
    await vi.waitFor(() => expect(b.status).toBe('done'))

    expect(a.status).toBe('done')
    expect(vi.mocked(prepareMedia).mock.calls.map((c) => c[0].name)).toEqual(['a.png', 'b.png'])
    expect(vi.mocked(addMedia).mock.calls.map((c) => [c[3].name, c[5]?.prepared?.sha256])).toEqual([
      ['a.png', 'sha-a.png'], ['b.png', 'sha-b.png'],
    ])
    // the prepare got the item's own signal, the one its upload gets
    expect(vi.mocked(prepareMedia).mock.calls[1][2]).toBe(b.controller.signal)
  })

  it('runs the device floor after the prepare and before the upload seals anything', async () => {
    const order: string[] = []
    vi.mocked(prepareMedia).mockImplementation(async (source) => { order.push('prepare'); return preparedOf(source) })
    vi.mocked(addMedia).mockImplementation(async () => { order.push('upload'); return { blobId: 'b', mediaId: 'm' } })
    const { batches } = useUploadQueue()
    enqueueUploads({ ...TARGET, atFloor: () => order.push('floor') }, [sel(png())], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))
    expect(order).toEqual(['prepare', 'floor', 'upload'])
  })

  it('a failed attempt drops its prepared result: the automatic retry prepares again', async () => {
    vi.mocked(addMedia)
      .mockRejectedValueOnce(new Error('chunk_put_failed:500'))
      .mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))
    expect(prepareMedia).toHaveBeenCalledTimes(2)
    expect(addMedia).toHaveBeenCalledTimes(2)
  })

  it('a prepare that fails fails its upload attempt, which the retry prepares again', async () => {
    vi.mocked(prepareMedia).mockRejectedValueOnce(new Error('read_failed'))
    vi.mocked(addMedia).mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    const { batches } = useUploadQueue()
    enqueueUploads(TARGET, [sel(png())], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))
    expect(prepareMedia).toHaveBeenCalledTimes(2)
    expect(addMedia).toHaveBeenCalledOnce()
  })
})

describe('uploadQueue — a member\'s file into a node not re-keyed yet (ADR 0111 decision 11)', () => {
  const newKey = new Uint8Array(32).fill(7)

  it('pulls the node\'s path forward, re-reads its key and epoch, and uploads again under them at once', async () => {
    vi.mocked(addMedia)
      .mockRejectedValueOnce(new NodeRekeyPendingError()) // the completion's per-item refusal
      .mockResolvedValue({ blobId: 'b', mediaId: 'm' })
    const pull = vi.fn(async () => true)
    const reread = vi.fn(async () => ({ key: newKey, epoch: 1 }))
    const floors: number[] = []
    const { batches } = useUploadQueue()
    enqueueUploads({ ...TARGET, nodeEpoch: 0, atFloor: (e) => floors.push(e), pull, reread }, [sel(png())], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('done'))
    expect(pull).toHaveBeenCalledOnce()
    expect(vi.mocked(addMedia).mock.calls.map((c) => [c[2], c[5]?.nodeEpoch])).toEqual([[TARGET.memoryKey, 0], [newKey, 1]])
    expect(floors, 'the floor runs before each attempt, at the epoch it seals at').toEqual([0, 1])
  })

  it('a target with no re-read (a guest\'s, or an older caller) is sent once, as before (control)', async () => {
    vi.mocked(addMedia).mockRejectedValue(new NodeRekeyPendingError())
    const pull = vi.fn(async () => true)
    const { batches } = useUploadQueue()
    enqueueUploads({ ...TARGET, pull }, [sel(png())], { label: '1' })
    await vi.waitFor(() => expect(batches.value[0].files[0].status).toBe('failed'))
    expect(pull).not.toHaveBeenCalled()
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
