// uc.memory.delete-media (web) — naming a medium the server refused to trash or restore (inbox #427, #429). One
// write per medium; a refusal does not stop the rest, and each refused medium is named with its reason, one message
// per reason. Shared by the organizer's selection (MemoryBuckets) and the lightbox's Undo (useMediaGalleryLive).
import { decodeHttpError } from '../../shared/httpError'
import type { MediaItem } from './media'
import { openedBlobMeta } from './openedListing'

/** Why the server refused a medium's trash or restore, as the reason the message names
 *  (`memory.buckets.deleteRefusedReason.*`): `deleteMedia`'s and `restoreMedia`'s refusals — `404 media_not_found`
 *  (purged, or the caller holds no grant any more) and `409 scope_deleting` — and `failed` for anything else (a
 *  network failure, an unexpected code). */
export type MediaRefusal = 'gone' | 'scope_deleting' | 'blocked_by_operator' | 'failed'

export async function mediaRefusalOf(e: unknown): Promise<MediaRefusal> {
  const { code } = await decodeHttpError(e)
  if (code === 'media_not_found') return 'gone'
  if (code === 'scope_deleting') return 'scope_deleting'
  if (code === 'blocked_by_operator') return 'blocked_by_operator' // a restore of a blocked medium (451)
  return 'failed'
}

/** The file name sealed in a medium's original blob meta, or undefined — the caller names it by its place then. */
export function sealedMediaName(scopeId: string, memoryKey: Uint8Array | null, item: MediaItem | undefined): string | undefined {
  return (item && memoryKey ? openedBlobMeta(scopeId, memoryKey, item)?.name?.trim() : undefined) || undefined
}

/** The refused writes of `results` (one per medium, in `names`' order), their names grouped by reason. */
export async function refusalsByReason(results: PromiseSettledResult<unknown>[], names: string[]): Promise<Map<MediaRefusal, string[]>> {
  const refused = new Map<MediaRefusal, string[]>()
  for (const [i, r] of results.entries()) {
    if (r.status !== 'rejected') continue
    const why = await mediaRefusalOf(r.reason)
    refused.set(why, [...(refused.get(why) ?? []), names[i]])
  }
  return refused
}
