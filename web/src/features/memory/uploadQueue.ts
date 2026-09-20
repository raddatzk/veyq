// uc.memory.upload (web, cr-0058) — the global upload queue, ported from legacy/frontend-noe2ee's
// useUploadQueue and rewired onto the contracts `addMedia`. A module singleton so uploads survive navigation;
// the TopBar UploadStatusMenu renders `useUploadQueue()` and the gallery enqueues against the open Memory.
//
// First slice: SEQUENTIAL (one file at a time), fraction-based progress (no speed/ETA), one automatic retry on
// a transient failure, explicit retry on a hard failure. Operator-blind is entirely `addMedia`'s concern.
import { reactive, computed } from 'vue'
import { addMedia, type AddMediaSource, type AddMediaOptions } from './media'

// `preparing` = picked up, but still in the pre-network phase (read into memory, derive poster/dimensions, seal).
// For a video this can take a visible moment — especially the on-device frame decode — so it gets its own state
// instead of masquerading as a stuck 0% `uploading`. It flips to `uploading` the instant the first PUT progress
// arrives (media.ts `onProgress?.(0)`, right before the chunk PUTs).
export type UploadStatus = 'queued' | 'preparing' | 'uploading' | 'done' | 'failed' | 'cancelled'

/** How one file is sealed + uploaded — the authed `addMedia` (default) or a share-scoped transport (cr-0101). */
export type UploadFn = (source: AddMediaSource, opts: AddMediaOptions) => Promise<{ blobId: string; mediaId: string }>

export type UploadFile = {
  id: string
  name: string
  sizeBytes: number
  mediaType: string
  status: UploadStatus
  progress: number // 0..1
  error?: string
  attempts: number
  controller: AbortController
  file: File
  /** The media id `addMedia` returned once this file settled `done` — lets the gallery reconcile its optimistic
   *  in-grid tile against the listed media by id (cr-0074). Undefined until then. */
  mediaId?: string
}

/**
 * Where a batch's media attach + how they upload. The **authed** curator path passes `{ scopeId, nodeId,
 * memoryKey }` (the default transport is `addMedia`). The **share** contributor path (cr-0101) passes a
 * `{ key, upload }` — its own grouping key (for the optimistic tiles) + a share-scoped upload fn. `key` defaults
 * to `nodeId`, so the internal gallery is unchanged.
 */
export type UploadTarget = {
  scopeId?: string
  nodeId?: string
  memoryKey?: Uint8Array
  /** Grouping key for the optimistic tiles (which view an in-flight file belongs to); defaults to `nodeId`. */
  key?: string
  /** The transport for this batch; defaults to the authed `addMedia` over `scopeId`/`nodeId`/`memoryKey`. */
  upload?: UploadFn
}

/** The grouping key of a batch — its explicit `key`, else its `nodeId` (the authed default). */
export const batchKey = (t: UploadTarget): string | undefined => t.key ?? t.nodeId

export type UploadBatch = {
  id: string
  label: string
  target: UploadTarget
  files: UploadFile[]
  seq: number
  finishedAt: number | null
  acknowledged: boolean
  onComplete?: (batch: UploadBatch) => void
  /** Per-file settle hook (cr-0111): fired the moment ONE file finishes `done`, so the gallery reconciles that
   *  file's optimistic tile immediately instead of waiting for the whole batch's `onComplete`. */
  onFileDone?: (file: UploadFile) => void
}

const MAX_ATTEMPTS = 2 // one automatic retry on a transient failure

const batches = reactive<UploadBatch[]>([])
let seq = 0
let running = false

// --- file-selection helpers (Safari Photos import, uc.memory.upload E3) ----

// Photos exports HEIC/MOV with an EMPTY MIME type, so validation + the media type fall back to the extension.
const SUPPORTED_EXT = new Set([
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'avif', 'heic', 'heif', 'tif', 'tiff', 'bmp',
  'mov', 'mp4', 'm4v', 'webm', 'avi', 'mkv',
])
const EXT_MIME: Record<string, string> = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp',
  avif: 'image/avif', heic: 'image/heic', heif: 'image/heif', tif: 'image/tiff', tiff: 'image/tiff',
  bmp: 'image/bmp', mov: 'video/quicktime', mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm',
  avi: 'video/x-msvideo', mkv: 'video/x-matroska',
}
const extOf = (name: string): string => name.split('.').pop()?.toLowerCase() ?? ''

/** Accept a file by MIME, or by extension when the MIME is empty (Safari Photos). */
export function isSupportedMediaFile(file: File): boolean {
  if (file.type.startsWith('image/') || file.type.startsWith('video/')) return true
  return SUPPORTED_EXT.has(extOf(file.name))
}

/** The media type to derive/seal under — the file's own MIME, or one inferred from the extension when empty. */
export function inferMediaType(file: File): string {
  return file.type || EXT_MIME[extOf(file.name)] || 'application/octet-stream'
}

const fileSignature = (f: File): string => `${f.name}:${f.size}:${f.lastModified}:${f.type}`

/** Merge a fresh `input.files` read into the running selection WITHOUT dropping earlier picks (Safari populates
 *  the input late / across repeated picks). De-dupes by signature (which includes `type`, so an empty-type
 *  variant and its later typed variant are distinct). */
export function mergeFiles(existing: File[], incoming: File[]): File[] {
  const seen = new Set(existing.map(fileSignature))
  const merged = existing.slice()
  for (const f of incoming) {
    const sig = fileSignature(f)
    if (!seen.has(sig)) {
      seen.add(sig)
      merged.push(f)
    }
  }
  return merged
}

/** How long after a pick the input is re-read, in ms. Spread wide enough to outlast a slow Photos import. */
const PICK_RETRY_DELAYS = [250, 750, 1500, 3000]
/** The input is released this long after the LAST re-read — see the clear's own note in `collectPickedFiles`. */
const PICK_RELEASE_AFTER = PICK_RETRY_DELAYS[PICK_RETRY_DELAYS.length - 1] + 100

/**
 * Drive one pick of a `<input type="file">` through uc.memory.upload E3's re-read window and hand each batch to
 * `enqueue`. Lives here rather than in a component because E3 is a property of **the picker** — every screen
 * that lets someone choose files needs it, and a second copy is a screen that silently loses photos.
 *
 * Two rules the shape encodes, both of which a plausible-looking implementation gets wrong:
 *
 * - **The window never delays the files already in hand.** The first read enqueues immediately and each re-read
 *   enqueues only what is genuinely new, as an additional batch. Gathering across the window and enqueueing
 *   once satisfies E3's letter and makes every browser pay a quiet second for the one browser this exists for.
 * - **Clearing the input is the window's TEARDOWN, not part of the pick.** Safari goes on filling the list it
 *   handed over at pick time; `input.value = ''` swaps a fresh empty list into the element, so a clear in the
 *   same tick as the first read throws away exactly the late arrivals the window exists to catch — while four
 *   timers still run and four empty reads still look perfectly correct.
 *
 * It knows nothing about *where* the files go: the caller's `enqueue` closes over its own `UploadTarget`, so the
 * curator's `{ scopeId, nodeId, memoryKey }` and a contributor's share-scoped `upload` are equally invisible here.
 *
 * Returns a **canceller** that tears the window down — call it on unmount, and on a fresh pick, which supersedes
 * whatever the previous window was still waiting for.
 */
export function collectPickedFiles(input: HTMLInputElement, enqueue: (files: File[]) => void): () => void {
  /** Every file already handed to `enqueue` for THIS pick — the de-dupe memory each re-read is checked against. */
  let enqueued: File[] = []
  let cancelled = false
  const timers: ReturnType<typeof setTimeout>[] = []

  const collect = () => {
    if (cancelled) return
    const incoming = Array.from(input.files ?? []).filter(isSupportedMediaFile)
    const merged = mergeFiles(enqueued, incoming)
    const fresh = merged.slice(enqueued.length) // only what this read added
    enqueued = merged
    if (fresh.length > 0) enqueue(fresh)
  }

  collect()
  for (const delay of PICK_RETRY_DELAYS) timers.push(setTimeout(collect, delay))
  timers.push(setTimeout(() => { input.value = '' }, PICK_RELEASE_AFTER))

  return () => {
    cancelled = true
    for (const t of timers) clearTimeout(t)
  }
}

// --- queue -----------------------------------------------------------------

function nextQueued(): UploadFile | null {
  for (const batch of batches.slice().sort((a, b) => a.seq - b.seq)) {
    const file = batch.files.find((f) => f.status === 'queued')
    if (file) return file
  }
  return null
}

function finalize(batch: UploadBatch): void {
  const settled = (f: UploadFile) => f.status === 'done' || f.status === 'failed' || f.status === 'cancelled'
  if (batch.finishedAt === null && batch.files.every(settled)) {
    batch.finishedAt = Date.now()
    batch.onComplete?.(batch)
  }
}

async function uploadOne(file: UploadFile): Promise<void> {
  const batch = batches.find((b) => b.files.includes(file))
  if (!batch) return
  file.status = 'preparing' // pre-network: read + derive + seal; flips to 'uploading' on the first PUT progress
  file.progress = 0
  file.attempts += 1
  try {
    const bytes = new Uint8Array(await file.file.arrayBuffer())
    const t = batch.target
    // the batch's transport: the share-scoped upload fn, or the authed addMedia over scope/node/K_mem (default).
    // the file's creation date is passed so a source with no EXIF date can fall back to it (cr-0066).
    const upload: UploadFn = t.upload ?? ((source, opts) => addMedia(t.scopeId!, t.nodeId!, t.memoryKey!, source, undefined, opts))
    const { mediaId } = await upload({ bytes, mediaType: file.mediaType, lastModified: file.file.lastModified, name: file.name }, {
      onProgress: (p) => {
        if (file.status === 'preparing') file.status = 'uploading' // first PUT progress → the network phase began
        file.progress = p
      },
      signal: file.controller.signal,
    })
    file.mediaId = mediaId // the gallery reconciles its optimistic tile against the listed media by this id (cr-0074)
    file.status = 'done'
    file.progress = 1
    batch.onFileDone?.(file) // cr-0111: reconcile THIS file's tile now, not only at the batch's onComplete
  } catch (e) {
    if (file.controller.signal.aborted || (e as { name?: string })?.name === 'AbortError') {
      file.status = 'cancelled'
    } else if (file.attempts < MAX_ATTEMPTS) {
      file.status = 'queued' // one automatic retry on a transient failure
    } else {
      file.status = 'failed'
      file.error = (e as Error)?.message ?? 'upload_failed'
    }
  }
  finalize(batch)
}

function scheduleRunner(): void {
  if (running) return
  running = true
  void (async () => {
    try {
      let file: UploadFile | null
      while ((file = nextQueued())) await uploadOne(file)
    } finally {
      running = false
      if (nextQueued()) scheduleRunner() // a retry re-queued after the loop ended
    }
  })()
}

/** Enqueue a batch of selected files against a Memory. Returns the batch id, or null when nothing was given. */
export function enqueueUploads(
  target: UploadTarget,
  selections: { file: File; mediaType: string }[],
  opts: { label: string; onComplete?: (batch: UploadBatch) => void; onFileDone?: (file: UploadFile) => void } = { label: '' },
): string | null {
  if (selections.length === 0) return null
  const files: UploadFile[] = selections.map((s) => ({
    id: crypto.randomUUID(),
    name: s.file.name,
    sizeBytes: s.file.size,
    mediaType: s.mediaType,
    status: 'queued',
    progress: 0,
    attempts: 0,
    controller: new AbortController(),
    file: s.file,
  }))
  const batch: UploadBatch = {
    id: crypto.randomUUID(),
    label: opts.label || `${files.length}`,
    target,
    files,
    seq: seq++,
    finishedAt: null,
    acknowledged: false,
    onComplete: opts.onComplete,
    onFileDone: opts.onFileDone,
  }
  batches.push(batch)
  scheduleRunner()
  return batch.id
}

export function cancelFile(fileId: string): void {
  for (const batch of batches) {
    const file = batch.files.find((f) => f.id === fileId)
    if (!file) continue
    if (file.status === 'queued') file.status = 'cancelled'
    // in-flight (preparing or uploading): abort — uploadMediaVia's throwIfAborted checkpoints map it → cancelled
    else if (file.status === 'preparing' || file.status === 'uploading') file.controller.abort()
    finalize(batch)
    return
  }
}

export function cancelBatch(batchId: string): void {
  const batch = batches.find((b) => b.id === batchId)
  batch?.files.forEach((f) => cancelFile(f.id))
}

export function cancelAll(): void {
  batches.slice().forEach((b) => cancelBatch(b.id))
}

/** Retry a hard-failed file (resets its attempts). */
export function retryFile(fileId: string): void {
  for (const batch of batches) {
    const file = batch.files.find((f) => f.id === fileId)
    if (!file || file.status !== 'failed') continue
    file.status = 'queued'
    file.attempts = 0
    file.error = undefined
    batch.finishedAt = null
    scheduleRunner()
    return
  }
}

/**
 * Remove a settled file from the queue entirely (cr-0102 uploader delete-own) — drops its optimistic tile and
 * lets the preview lifecycle revoke its object URL. Empties the batch if it was the last file. Only a settled
 * file should be removed; an in-flight file must be cancelled first.
 */
export function removeFile(fileId: string): void {
  for (const batch of batches) {
    const idx = batch.files.findIndex((f) => f.id === fileId)
    if (idx === -1) continue
    batch.files.splice(idx, 1)
    if (batch.files.length === 0) {
      const bi = batches.indexOf(batch)
      if (bi !== -1) batches.splice(bi, 1)
    }
    return
  }
}

/** Hide a finished batch from the tray. */
export function acknowledgeBatch(batchId: string): void {
  const batch = batches.find((b) => b.id === batchId)
  if (batch) batch.acknowledged = true
}

/** Test/teardown: cancel everything and empty the queue. */
export function clearUploads(): void {
  cancelAll()
  batches.length = 0
  running = false
}

/** Reactive view for the UI: the un-acknowledged batches + a count of still-active files. */
export function useUploadQueue() {
  const visibleBatches = computed(() => batches.filter((b) => !b.acknowledged))
  const activeFileCount = computed(() =>
    batches.reduce(
      (n, b) => n + b.files.filter((f) => f.status === 'queued' || f.status === 'preparing' || f.status === 'uploading').length,
      0,
    ),
  )
  return { batches: visibleBatches, activeFileCount }
}
