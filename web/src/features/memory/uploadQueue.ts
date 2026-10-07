// uc.memory.upload (web, cr-0058) — Memories' side of the global upload queue. The queue itself (the six states,
// the sequential runner, the one automatic retry that `quota_exceeded` never gets, cancel/retry/remove/acknowledge,
// the reactive view) is core's, `core/blob/queue.ts` (cr-files-application B2); this module is the thin adapter that
// turns picked media files into queue jobs whose runner is `addMedia` (or a share-scoped transport), plus the media
// picker helpers. The gallery enqueues against the open Memory and its optimistic tiles read this module's
// `useUploadQueue()` (Memories' batches only); the top bar's status menu is core's and reads core's queue, every
// application's batches (cr-files-application B3).
//
// Media specifics: `preparing` is the pre-network phase (derive poster/dimensions, read the metadata — sealing is
// not part of it, the file is read and sealed one frame at a time as it is sent), and it flips to `uploading` on
// media.ts's `onProgress?.(0)` right before the chunk PUTs. Operator-blind is entirely `addMedia`'s concern.
import { computed } from 'vue'
import { addMedia, type AddMediaSource, type AddMediaOptions } from './media'
import {
  enqueue as enqueueJobs,
  useUploadQueue as useCoreUploadQueue,
  type UploadItem,
  type UploadBatch as CoreUploadBatch,
} from '../../core/blob/queue'

export {
  QUOTA_EXCEEDED,
  isRetryable,
  cancelFile,
  cancelBatch,
  cancelAll,
  retryFile,
  removeFile,
  acknowledgeBatch,
  clearUploads,
  type UploadStatus,
} from '../../core/blob/queue'

/** What one media upload settles to — `addMedia`'s result; the queue keeps it on the item as `result`. */
export type MediaUploadResult = { blobId: string; mediaId: string }

/** How one file is sealed + uploaded — the authed `addMedia` (default) or a share-scoped transport (cr-0101). */
export type UploadFn = (source: AddMediaSource, opts: AddMediaOptions) => Promise<MediaUploadResult>

/** Memories' own fields on a queue item (its job's `extra`); the queue never reads them. */
type MediaFileFields = {
  mediaType: string
  file: File
  /** The media id `addMedia` returned once this file settled `done` — lets the gallery reconcile its optimistic
   *  in-grid tile against the listed media by id (cr-0074). Undefined until then. */
  mediaId?: string
}

export type UploadFile = UploadItem<MediaUploadResult, MediaFileFields>

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

/** A Memories batch: core's, carrying the `target` it was enqueued against. Its per-file settle hook is the
 *  queue's `onItemDone`, which `enqueueUploads` wires to the caller's `onFileDone`. */
export type UploadBatch = CoreUploadBatch<MediaUploadResult, MediaFileFields, { target: UploadTarget }>

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

// --- the queue adapter -------------------------------------------------------

/** Enqueue a batch of selected files against a Memory. Returns the batch id, or null when nothing was given. */
export function enqueueUploads(
  target: UploadTarget,
  selections: { file: File; mediaType: string }[],
  opts: { label: string; onComplete?: (batch: UploadBatch) => void; onFileDone?: (file: UploadFile) => void } = { label: '' },
): string | null {
  // the batch's transport: the share-scoped upload fn, or the authed addMedia over scope/node/K_mem (default).
  // the file's creation date is passed so a source with no EXIF date can fall back to it (cr-0066).
  const upload: UploadFn =
    target.upload ?? ((source, o) => addMedia(target.scopeId!, target.nodeId!, target.memoryKey!, source, undefined, o))
  return enqueueJobs<MediaUploadResult, MediaFileFields, { target: UploadTarget }>(
    selections.map((s) => ({
      name: s.file.name,
      sizeBytes: s.file.size,
      extra: { mediaType: s.mediaType, file: s.file },
      // the File itself, never its bytes: the pump reads and seals it a frame at a time (ADR 0013 Consequences)
      run: ({ onProgress, signal }) =>
        upload({ blob: s.file, mediaType: s.mediaType, lastModified: s.file.lastModified, name: s.file.name }, { onProgress, signal }),
    })),
    {
      label: opts.label,
      key: batchKey(target),
      extra: { target },
      onComplete: opts.onComplete,
      onItemDone: (file) => {
        // the gallery reconciles its optimistic tile against the listed media by this id (cr-0074)
        file.mediaId = file.result?.mediaId
        opts.onFileDone?.(file) // cr-0111: reconcile THIS file's tile now, not only at the batch's onComplete
      },
    },
  )
}

/** Reactive view for the UI: the un-acknowledged Memories batches + a count of still-active files. Only the
 *  batches `enqueueUploads` made (the ones carrying a `target`) are Memories' to render; another application's
 *  batches carry neither a `target` nor a `file`, so the cast below holds exactly for what the filter keeps. */
export function useUploadQueue() {
  const core = useCoreUploadQueue()
  const batches = computed(() => core.batches.value.filter((b) => 'target' in b) as unknown as UploadBatch[])
  return { batches, activeFileCount: core.activeFileCount }
}

