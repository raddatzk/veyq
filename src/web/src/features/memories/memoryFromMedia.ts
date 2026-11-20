// What a Memory's date and place read from its own media: the `auto` date range the gallery shows and the self-heal re-seals
// (uc.memory.date, cr-0067), and the GPS points the `auto` map position is the medoid of (uc.memory.place). The date and the
// place themselves - their model and seal - are `memoryDate.ts` and `memoryLocation.ts`; these read the gallery's opened
// listing (`openedListing.ts`, `media.ts`), which is loaded with the Memory's page, not with the level's rows.
import { epochMsToIsoDate } from '../../shared/isoDate'
import { writeNodeMeta, type WriteNodeMetaOptions } from '../../core/node/nodeMetaWrite'
import type { NodeMetaState } from '../../core/node/nodeMeta'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import { effectiveCapturedAt, type MediaItem } from './media'
import { openedMeta } from './openedListing'
import { normalizeDate, sameMemoryDate, type MemoryDate } from './memoryDate'
import type { LatLng } from './memoryLocation'

/**
 * The `auto`-range SELF-HEAL: materialize the range from the Memory's own `items` and re-seal it only when it
 * changed. The whole decision runs on the blob being written — on a `409` again on the fresh one — so a manual date
 * a peer set meanwhile is never overwritten, and a range already right is never re-sent (ADR 0077 point 3).
 * Returns the newest state the server confirmed or handed back, and whether this call wrote.
 */
export async function reconcileMemoryDate(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  items: MediaItem[],
  current: NodeMetaState,
  opts: WriteNodeMetaOptions = {},
): Promise<{ state: SealedMetaState; wrote: boolean }> {
  const next = computeAutoRange(scopeId, items, nodeKey)
  const { state, wrote } = await writeNodeMeta(scopeId, nodeId, nodeKey, current, (meta) => {
    const stored = normalizeDate(meta.date)
    if (stored && stored.mode !== 'auto') return null // a manual date is the user's — never overwritten
    if (sameMemoryDate(stored ?? { mode: 'auto' }, next)) return null
    if (!stored && next.from === undefined) return null // absent meta already == the empty `auto` state
    meta.date = next
    return meta
  }, undefined, opts)
  return { state, wrote }
}

/**
 * Materialize a Memory's `auto` range from its **own** media, day-truncated (local `YYYY-MM-DD`). Counts only
 * **trustworthy** capture times — a user `capturedAtOverride`, or a `capturedAt` from EXIF or a video's container — so a
 * file-modification-date fallback can't stretch the range (cr-0067). An empty result ⇒ `{ mode: 'auto' }` (the
 * "no date yet" state). `nodeKey` = the Memory's `K_node` (each media's capture-time tier is sealed under it).
 */
export function computeAutoRange(scopeId: string, items: MediaItem[], nodeKey: Uint8Array): MemoryDate {
  const days: number[] = []
  for (const item of items) {
    const tier = openedMeta(scopeId, nodeKey, item)
    if (!tier) continue
    const trustworthy = tier.capturedAtOverride !== undefined || tier.capturedAtSource === 'exif' || tier.capturedAtSource === 'container'
    const ms = effectiveCapturedAt(tier)
    if (trustworthy && ms !== undefined) days.push(ms)
  }
  if (days.length === 0) return { mode: 'auto' }
  return { mode: 'auto', from: epochMsToIsoDate(Math.min(...days)), to: epochMsToIsoDate(Math.max(...days)) }
}

/**
 * Collect the GPS points from a Memory's **own** media (its listed `wrappedMeta` tier, cr-0068) under `nodeKey`
 * (`K_node`) — the raw input to [computeMedoid] for the `auto` position. Items without a GPS tier are skipped;
 * reads only the already-listed metadata, no per-item fetch.
 */
export function mediaGpsPoints(scopeId: string, items: MediaItem[], nodeKey: Uint8Array): LatLng[] {
  const points: LatLng[] = []
  for (const item of items) {
    const tier = openedMeta(scopeId, nodeKey, item)
    if (tier && tier.gpsLat !== undefined && tier.gpsLng !== undefined) {
      points.push({ lat: tier.gpsLat, lng: tier.gpsLng })
    }
  }
  return points
}
