// uc.memory.upload (web, cr-0074) — the optimistic in-grid upload tile model. Pure selection/reconcile logic
// (no DOM, no object URLs) so it's unit-testable; MemoryDetail owns the reactive preview lifecycle around it.
//
// When a user picks photos for a Memory the queue's still-in-flight files render **immediately** as grid tiles
// (a local preview + progress/error state), **in the one grid order every tile obeys** — see [byCaptureTime].
// Each optimistic tile reconciles into the real decrypted thumbnail once the upload round-trip lists its media
// — matched by the `mediaId` the queue records on `done` — for a clean 1:1 handoff (never a duplicate).
import { batchKey, type UploadBatch, type UploadFile } from './uploadQueue'
import type { MediaItem } from './media'

/** The id an optimistic tile carries in the justified-layout input — namespaced so it can't collide with a
 *  real media id. */
export const optimisticTileId = (fileId: string): string => `upload:${fileId}`

/** The upload file an optimistic tile's layout id names, or null for any other tile's id (a listed media's). */
export const fileIdOfTile = (id: string): string | null => (id.startsWith('upload:') ? id.slice('upload:'.length) : null)

/**
 * The upload files that should render as optimistic tiles for `nodeId`, in enqueue order (batch seq, then file
 * order) — the order they are handed over in, not the order they are drawn in ([byCaptureTime] decides that).
 * A file shows while it is still in flight
 * (`queued` / `uploading`) or `failed` (with a retry), **or** `done` but its `mediaId` is not yet among the
 * listed media. `cancelled` files, and `done` files already reconciled into the list (same `mediaId`), drop out.
 */
export function selectOptimisticUploads(
  batches: readonly UploadBatch[],
  key: string,
  listedMediaIds: ReadonlySet<string>,
): UploadFile[] {
  const out: UploadFile[] = []
  for (const batch of batches.slice().sort((a, b) => a.seq - b.seq)) {
    if (batchKey(batch.target) !== key) continue
    for (const file of batch.files) {
      if (file.status === 'cancelled') continue
      // reconciled: the real media is now listed under the same id → hand off to the real tile
      if (file.status === 'done' && file.mediaId !== undefined && listedMediaIds.has(file.mediaId)) continue
      out.push(file)
    }
  }
  return out
}

/**
 * Which of the currently-shown thumbnail ids belong to media the fresh list no longer contains — the caller
 * revokes exactly these object URLs on an incremental reload, leaving every still-present thumbnail in place
 * (no empty-flash, no needless refetch).
 */
export function removedThumbIds(currentThumbIds: Iterable<string>, listed: readonly MediaItem[]): string[] {
  const next = new Set(listed.map((m) => m.mediaId))
  return [...currentThumbIds].filter((id) => !next.has(id))
}

/**
 * **The one grid order**, for both kinds of tile and both surfaces (the Memory gallery and the share viewer):
 * by effective capture time, direction-toggled, undated last whichever way it points, and stable within a tie.
 *
 * It takes ONE list because that is the whole rule: an in-flight tile and a listed media are sorted against
 * each other, not concatenated. An optimistic tile's key is the same `captureTimeOf` value `sealMetadata` will
 * seal for that file, so when an upload settles its media lands on the key its tile already held — and the tie
 * (equal keys) resolves to the caller's input order, which is why callers pass the optimistic tiles FIRST:
 * a tile hands its exact slot to the media it becomes, instead of swapping places with it for one frame.
 */
export function byCaptureTime<T>(entries: readonly T[], keyOf: (entry: T) => number | undefined, dir: 1 | -1): T[] {
  return entries
    .map((entry, i) => ({ entry, i, t: keyOf(entry) }))
    .sort((a, b) => {
      if (a.t === undefined && b.t === undefined) return a.i - b.i // both undated → input order
      if (a.t === undefined) return 1 // undated last, regardless of direction
      if (b.t === undefined) return -1
      return a.t === b.t ? a.i - b.i : (a.t - b.t) * dir
    })
    .map((x) => x.entry)
}
