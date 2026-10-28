// uc.memory.upload (web, cr-0074 + cr-0101) — the optimistic in-grid upload-tile lifecycle, shared by the
// internal gallery (MemoryDetail) and the anonymous contributor viewer (ShareView). The global upload queue's
// still-in-flight files for a given grouping `key` (a Memory node id, or a shareId) render immediately as grid
// tiles: an instant local preview (a `URL.createObjectURL` of the user's own file — never uploaded in the clear)
// + a per-tile progress / failed-with-retry state, **at their capture-time place in the one grid order**
// ([uc.memory.gallery](../../../../../docs/features/memory/use-cases/uc.memory.gallery.md)). Each reconciles into
// the real decrypted thumbnail once its media lists (matched by the `mediaId` the queue records on `done`) — a
// change of content, never of place, because both sides derive the key from the same `captureTimeOf` rule. The
// pure selection/merge logic lives in ./optimisticUpload; this composable owns the reactive preview lifecycle.
import { reactive, computed, watch, onBeforeUnmount, type Ref } from 'vue'
import { captureTimeOf, createBrowserDeriver, readCaptureTime } from './media'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useUploadQueue, type UploadFile } from './uploadQueue'
import { selectOptimisticUploads, optimisticTileId } from './optimisticUpload'

/**
 * The view-model of one optimistic tile — everything the grid + [OptimisticTile] need to render it.
 *
 * **What places a tile and what only the tile draws are kept apart** (inbox #640). Every field but `upload` is the
 * grid's input or the preview, and changes only when a tile appears, gets its probe's answer or leaves. The stage and
 * the progress change on every chunk, so they stay on the queue's own reactive item, `upload`, and are read where they
 * are drawn: a chunk re-renders its one tile, never the gallery's tile list or the justified layout over every tile.
 */
export type OptimisticTileVM = {
  /** The id it carries in the justified-layout input (namespaced so it can't collide with a real media id). */
  layoutId: string
  fileId: string
  name: string
  url: string | null
  aspectRatio: number
  isVideo: boolean
  /** The grid sort key — the same one `sealMetadata` will seal for this file (EXIF date, else the file's).
   *  Present from the first frame: the file-date half needs no decode, and the EXIF half refines it in the
   *  probe pass below, before any byte is uploaded. Undefined only for a file carrying neither. */
  capturedAt?: number
  /** The queue's item, live: its `status`, its `progress`, the code a `failed` one stopped on (`error`, e.g.
   *  `quota_exceeded`) and the media id it settled to (`mediaId`, which cr-0102's delete-own targets). Read only where
   *  it is drawn or acted on — never into what lays the grid out. */
  upload: Readonly<Pick<UploadFile, 'status' | 'progress' | 'error' | 'mediaId'>>
}

/** Whether two tile lists place and show the same tiles: the same items, each with the same layout fields and preview. */
function sameTiles(a: readonly OptimisticTileVM[], b: readonly OptimisticTileVM[]): boolean {
  return (
    a.length === b.length &&
    a.every((x, i) => {
      const y = b[i]
      return x.upload === y.upload && x.url === y.url && x.aspectRatio === y.aspectRatio && x.capturedAt === y.capturedAt
    })
  )
}

/**
 * Reactive optimistic tiles for a grouping `key` (the Memory node id, or a shareId), reconciled against the
 * currently-listed media ids. Owns the local preview object URLs (mint on appear, revoke on drop / unmount).
 * `donorPreviewFor` hands a just-listed media's still-live preview to the caller for a seamless thumbnail swap.
 */
export function useOptimisticTiles(key: Ref<string>, listedMediaIds: Ref<ReadonlySet<string>>) {
  const { batches } = useUploadQueue()
  const deriver = createBrowserDeriver()
  // `url` is null for a video until its first-frame poster is derived: an <img> can't decode raw video bytes, so a
  // video's preview is a locally-derived poster (badge drawn over it, #105) — matching the persisted tile. Images
  // use the raw file blob url directly.
  type Preview = { url: string | null; aspectRatio: number; donated: boolean; capturedAt?: number }
  const previews = reactive(new Map<string, Preview>())

  const optimisticFiles = computed(() => selectOptimisticUploads(batches.value, key.value, listedMediaIds.value))
  // The same tiles are the same value (inbox #640): `optimisticFiles` re-runs on each stage change, since it reads
  // `status` to drop a cancelled or reconciled file, and a re-run that changed no tile hands back the list the grid
  // already laid out — a computed whose value is unchanged triggers no reader.
  const optimisticTiles = computed<OptimisticTileVM[]>((previous) => {
    const next = optimisticFiles.value.map((f): OptimisticTileVM => {
      const p = previews.get(f.id)
      return {
        layoutId: optimisticTileId(f.id),
        fileId: f.id,
        name: f.name,
        url: p?.url ?? null,
        aspectRatio: p?.aspectRatio ?? galleryDisplayAspectRatio(undefined, undefined),
        isVideo: f.mediaType.startsWith('video/'),
        capturedAt: p?.capturedAt,
        upload: f,
      }
    })
    return previous && sameTiles(previous, next) ? previous : next
  })

  // Own the preview lifecycle: mint a local preview + probe the aspect ratio (and, for a video, a poster) as each
  // file appears; revoke + prune when it drops (cancelled / reconciled). A donated url is skipped — the caller's
  // thumbs own its revoke.
  watch(
    optimisticFiles,
    (files) => {
      const present = new Set(files.map((f) => f.id))
      for (const [fileId, p] of previews) {
        if (present.has(fileId)) continue
        if (!p.donated && p.url) URL.revokeObjectURL(p.url)
        previews.delete(fileId)
      }
      for (const f of files) {
        if (previews.has(f.id)) continue
        const isVideo = f.mediaType.startsWith('video/')
        // image: the raw file is a valid <img> source; video: no <img>-decodable source yet — a poster follows.
        previews.set(f.id, {
          url: isVideo ? null : URL.createObjectURL(f.file),
          aspectRatio: galleryDisplayAspectRatio(undefined, undefined),
          donated: false,
          // The fallback half of the capture-time rule, available synchronously — so the tile has a place in
          // the sort from the frame it appears, rather than landing among the undated and moving once the
          // probe below returns.
          capturedAt: captureTimeOf(undefined, f.file.lastModified).capturedAt,
        })
        void probePreview(f, isVideo)
      }
    },
    { immediate: true },
  )

  async function probePreview(f: UploadFile, isVideo: boolean) {
    try {
      // The capture-time key is refined to the EXIF date here, in the same pass that refines the aspect ratio
      // and derives a video poster — one read of the picked file, before it is queued onto the wire.
      const { capturedAt } = await readCaptureTime(f.file, f.mediaType, f.file.lastModified)
      const withDate = previews.get(f.id)
      if (withDate) previews.set(f.id, { ...withDate, capturedAt })
      const dims = await deriver.probeDimensions?.(f.file, f.mediaType)
      const withDims = previews.get(f.id)
      if (withDims && dims) previews.set(f.id, { ...withDims, aspectRatio: galleryDisplayAspectRatio(dims.w, dims.h) })
      if (!isVideo) return
      // derive the first-frame poster so a video shows a picture + a play badge, not a bare icon (#105)
      const poster = await deriver.deriveVideoPoster(f.file)
      const p = previews.get(f.id)
      if (p) previews.set(f.id, { ...p, url: URL.createObjectURL(new Blob([poster as BlobPart], { type: 'image/jpeg' })) })
    } catch {
      // best-effort: keep the fallback aspect ratio; a video with no derivable poster stays icon-only
    }
  }

  /** A just-listed media's still-live preview url, marked donated (its revoke passes to the caller's thumbs). */
  function donorPreviewFor(mediaId: string): string | null {
    for (const b of batches.value) {
      for (const f of b.files) {
        if (f.status !== 'done' || f.mediaId !== mediaId) continue
        const p = previews.get(f.id)
        if (p && p.url && !p.donated) {
          previews.set(f.id, { ...p, donated: true })
          return p.url
        }
      }
    }
    return null
  }

  onBeforeUnmount(() => {
    for (const p of previews.values()) if (!p.donated && p.url) URL.revokeObjectURL(p.url) // donated urls are owned by the caller's thumbs
    previews.clear()
  })

  return { optimisticTiles, donorPreviewFor }
}
