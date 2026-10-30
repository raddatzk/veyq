// uc.memory.upload (web, cr-0074 + cr-0101) — the optimistic in-grid upload-tile lifecycle, shared by the
// internal gallery (MemoryDetail) and the anonymous contributor viewer (ShareView). The global upload queue's
// still-in-flight files for a given grouping `key` (a Memory node id, or a shareId) render immediately as grid
// tiles: a local preview (drawn on the device from the user's own file — never uploaded in the clear)
// + a per-tile progress / failed-with-retry state, **at their capture-time place in the one grid order**
// ([uc.memory.gallery](../../../../../docs/features/memory/use-cases/uc.memory.gallery.md)). Each reconciles into
// the real decrypted thumbnail once its media lists (matched by the `mediaId` the queue records on `done`) — a
// change of content, never of place, because both sides derive the key from the same `captureTimeOf` rule. The
// pure selection/merge logic lives in ./optimisticUpload; this composable owns the reactive preview lifecycle.
import { reactive, computed, watch, onBeforeUnmount, type Ref } from 'vue'
import { captureTimeOf, createBrowserDeriver, readCaptureTime, tileVideoPosterOf, TIER_SPECS } from './media'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useUploadQueue, type UploadFile } from './uploadQueue'
import { selectOptimisticUploads, optimisticTileId, fileIdOfTile } from './optimisticUpload'

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
 * currently-listed media ids. Owns the local preview object URLs (mint when drawn, revoke on drop / unmount).
 * `donorPreviewFor` hands a just-listed media's still-live preview to the caller for a seamless thumbnail swap.
 *
 * **A photo's preview is tile-sized, and drawn only for a tile near the viewport** (inbox #785, ADR 0105's rule for
 * thumbnails). Showing the picked file itself made every tile near the viewport a decode of the whole photo — 48.8 MB for
 * 12 megapixels — held by the renderer for as long as the batch uploaded: 50 photos of 5.2 MB kept the tab's renderer at
 * 1.03-1.04 GiB against 0.24 GiB before the pick (profile S2, 2026-10-29). Instead the grid announces the tiles it mounts,
 * nearest to the viewport's centre first ([wantPreviews], `MediaGrid`'s `window`), and each such tile's photo is drawn at
 * the gallery thumbnail's size ([TIER_SPECS] `THUMBNAIL`) by the deriver — the media Worker, the decode freed once the
 * small JPEG is encoded — ONE at a time, so a large pick never decodes many at once (inbox #558). Now the renderer stays at
 * 0.49-0.53 GiB through the same upload, its peak 0.61-0.67 GiB against 1.11-1.18 GiB, in the same time (0.44-0.46 GiB
 * with no preview at all). A photo the deriver cannot read falls back to the file itself, as before.
 *
 * **A video's poster joins the same queue** (inbox #786): its first frame, for a tile near the viewport, one decode at a
 * time with the photos. It used to be derived for every picked video at once, at the lightbox's 2048 px: picking 20 clips of 1080p (profile S2v, 2026-10-29) kept 34-41
 * `<video>` players alive and 18-20 of them decoding at once, the renderer peaking at 1.51-1.78 GiB within 0.5-2.4 s of
 * the pick against 0.94-0.96 GiB with no tile poster, and the main thread's 20 large encodes cost the upload 3.5 s at
 * throttle 4. Now 2 decode at once (this queue's and the upload's own), the peak is 1.09-1.12 GiB, the upload takes
 * 8.3-8.8 s (12.4-12.5 s at throttle 4) against 8.7-9.1 s (14.3-14.7 s), and a tile whose file is uploaded before its
 * turn shows the listed thumbnail without a poster of its own. A video the browser cannot decode stays icon-only.
 * The probe below reads every picked video's size at once, but from its container's header (`videoHeader.ts`, inbox
 * #793): a `<video>` each that loaded its metadata made 22 players alive at once at the pick, now 2, and the upload took
 * 12.8-13.5 s at throttle 4 against 12.0-12.3 s now (profile S2v, 2026-10-29).
 *
 * **The tile does not draw the video itself** (inbox #795): each draw of a 1080p frame onto a canvas left memory in the
 * renderer for the rest of the upload, and the tiles' own 512 px draws, beside the upload's, held it at 0.87-0.89 GiB
 * against 0.69-0.73 GiB without tile posters. The tile takes the PREVIEW-tier poster its upload stores (`videoPosterOf`,
 * drawn by whichever of the two asks first) and the deriver scales that JPEG down, as it does a photo: 21 players instead
 * of 37, the renderer's peak 0.95-0.97 GiB against 1.08-1.12 GiB and the upload 11.1-11.4 s against 12.4-13.4 s at
 * throttle 4, the first tile picture about 0.1 s later (profile S2v, 2026-10-30). Its median fell only to 0.84-0.86 GiB
 * (0.82-0.84 at throttle 4): the rest is the real frame, the posters asked for ahead of the upload (about 0.08 GiB, early
 * only) and the worker's decode of each 2048 px poster to scale it (0.04-0.06 GiB, to the end); showing the pictures costs
 * nothing measurable, and only a video tile without its own frame reaches the floor (uc.memory.upload § Quality notes).
 * A tile wanted once its upload took the poster waits for its listed thumbnail (`tileVideoPosterOf`).
 */
export function useOptimisticTiles(key: Ref<string>, listedMediaIds: Ref<ReadonlySet<string>>) {
  const { batches } = useUploadQueue()
  const deriver = createBrowserDeriver()
  // `url` is null until the tile's picture is drawn: a photo's tile-sized preview, or — an <img> can't decode raw video
  // bytes — a video's tile-sized first-frame poster (badge drawn over it, #105), matching the persisted tile; it stays
  // null for a video the browser cannot decode. `drawn` says it was asked of the deriver (it may still be on its way).
  type Preview = { url: string | null; aspectRatio: number; donated: boolean; capturedAt?: number; drawn: boolean }
  const previews = reactive(new Map<string, Preview>())
  /** The tiles each grid (owner) has mounted, as upload file ids, nearest to its viewport's centre first. */
  const wanted = new Map<string, readonly string[]>()
  let drawing = false
  let disposed = false

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
        // no picture yet: a photo's preview and a video's poster are drawn once its tile is near the viewport ([drawNext])
        previews.set(f.id, {
          url: null,
          drawn: false,
          aspectRatio: galleryDisplayAspectRatio(undefined, undefined),
          donated: false,
          // The fallback half of the capture-time rule, available synchronously — so the tile has a place in
          // the sort from the frame it appears, rather than landing among the undated and moving once the
          // probe below returns.
          capturedAt: captureTimeOf(undefined, f.file.lastModified).capturedAt,
        })
        void probePreview(f)
      }
      drawNext()
    },
    { immediate: true },
  )

  /** The tiles `owner`'s grid mounts now, by their layout ids, nearest first (`MediaGrid`'s `window`); other ids are
   *  ignored. Their previews are drawn in that order, the next once the one before it is. */
  function wantPreviews(owner: string, ids: readonly string[]) {
    wanted.set(owner, ids.map(fileIdOfTile).filter((id): id is string => id !== null))
    drawNext()
  }

  /** Draw the first wanted tile's picture not drawn yet — a photo's preview or a video's poster — unless one is being
   *  drawn: one decode at a time, photos and videos in the one queue. */
  function drawNext() {
    if (drawing || disposed) return
    const files = new Map(optimisticFiles.value.map((f) => [f.id, f]))
    const id = [...new Set([...wanted.values()].flat())].find((w) => files.has(w) && previews.get(w)?.drawn === false)
    if (id === undefined) return
    const { file, mediaType } = files.get(id)!
    previews.set(id, { ...previews.get(id)!, drawn: true })
    drawing = true
    const jpeg = (bytes: Uint8Array) => URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'image/jpeg' }))
    const picture: Promise<string | null> = mediaType.startsWith('video/')
      ? // an <img> can't decode video bytes: a video's picture is its first frame, scaled to the thumbnail's size from the
        // poster its upload stores, so the video is drawn once for both (inbox #795); a video the browser cannot decode
        // stays icon-only (no url), as its listed tile does, and one whose upload already took the poster waits for it
        tileVideoPosterOf(file, deriver)
          .then((poster) => poster && deriver.deriveImage(new Blob([poster as BlobPart], { type: 'image/jpeg' }), TIER_SPECS.THUMBNAIL))
          .then((tile) => tile && jpeg(tile.bytes), () => null)
      : deriver.deriveImage(file, TIER_SPECS.THUMBNAIL).then(
          ({ bytes }) => jpeg(bytes),
          () => URL.createObjectURL(file), // a photo the deriver cannot read: the file itself, as an <img> may still show it
        )
    picture
      .then((url) => {
        if (url === null) return
        const p = previews.get(id)
        if (p && !disposed) previews.set(id, { ...p, url })
        else URL.revokeObjectURL(url) // its tile left, or the view did, while it was drawn
      })
      .finally(() => {
        drawing = false
        drawNext()
      })
  }

  async function probePreview(f: UploadFile) {
    try {
      // The capture-time key is refined to the EXIF date here, in the same pass that refines the aspect ratio — one
      // read of the picked file's head, before it is queued onto the wire. No picture is drawn here: that is [drawNext]'s.
      const { capturedAt } = await readCaptureTime(f.file, f.mediaType, f.file.lastModified)
      const withDate = previews.get(f.id)
      if (withDate) previews.set(f.id, { ...withDate, capturedAt })
      const dims = await deriver.probeDimensions?.(f.file, f.mediaType)
      const withDims = previews.get(f.id)
      if (withDims && dims) previews.set(f.id, { ...withDims, aspectRatio: galleryDisplayAspectRatio(dims.w, dims.h) })
    } catch {
      // best-effort: keep the fallback aspect ratio
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
    disposed = true
    for (const p of previews.values()) if (!p.donated && p.url) URL.revokeObjectURL(p.url) // donated urls are owned by the caller's thumbs
    previews.clear()
  })

  return { optimisticTiles, donorPreviewFor, wantPreviews }
}
