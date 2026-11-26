// uc.memory.delete-media (web) — naming a medium the server refused to trash or restore (inbox #427, #429). One
// write per medium; a refusal does not stop the rest, and each refused medium is named with its reason, one message
// per reason. Shared by the organizer's selection (MemoryBuckets), the lightbox's Undo (useMediaGalleryLive) and a
// selection's capture-time shift and its Undo (useCaptureTimeShift, uc.memory.buckets).
import { decodeHttpError } from '../../shared/httpError'
import { MetaConflictError, MetaUnreadableError } from '../../core/node/sealedMetaWrite'
import type { MediaItem } from './media'
import { openedBlobMeta } from './openedListing'

/** Why the server refused a medium's trash or restore, as the reason the message names ({@link MEDIA_REFUSAL_TEXT}): `deleteMedia`'s and `restoreMedia`'s refusals — `404 media_not_found`
 *  (purged, or the caller holds no grant any more) and `409 scope_deleting` — and `failed` for anything else (a
 *  network failure, an unexpected code). A meta write adds its own two: `changed` (a peer changed the capture time
 *  since it was read, ADR 0077) and `unreadable` (its tier does not open, so it is never rebuilt). */
export type MediaRefusal = 'gone' | 'scope_deleting' | 'blocked_by_operator' | 'changed' | 'unreadable' | 'failed'

/** What each reason says: the apps' notices' words where they say it too (ADR 0161 § The web's words), else Memories'. */
export const MEDIA_REFUSAL_TEXT: Record<MediaRefusal, string> = {
  gone: 'memory.buckets.deleteRefusedReason.gone',
  scope_deleting: 'words.delete_refused_scope_deleting',
  blocked_by_operator: 'memory.buckets.deleteRefusedReason.blocked_by_operator',
  changed: 'memory.buckets.deleteRefusedReason.changed',
  unreadable: 'memory.buckets.deleteRefusedReason.unreadable',
  failed: 'memory.buckets.deleteRefusedReason.failed',
}

export async function mediaRefusalOf(e: unknown): Promise<MediaRefusal> {
  if (e instanceof MetaConflictError) return 'changed'
  if (e instanceof MetaUnreadableError) return 'unreadable'
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
