// What a photo view of a Memory shows (ADR 0135 decision 2; en.memory.media § Which media a photo view shows): only a medium
// whose original is an image or a video. One predicate, asked by every reader that treats a medium as a photo - the
// gallery's list (`useMediaGalleryLive`'s `items`, which the grid, the lightbox, the count, the organizer, the date and
// place reconcile and the open Memory's search documents derive from) and the space's search (`search.ts`). A GPX track
// (ADR 0130) and a music track (ADR 0135) are media too and keep their own positive predicates for their own lists
// (`isTrackMeta`, `isAudioMeta`); a type nobody has decided on stays out of every photo view by default.
//
// A medium whose original carries no meta at all (a blob uploaded before `blob-meta` existed, inbox #397) is shown: every
// medium that is no photo seals its type, so one without any predates them and is a photo or a video.
import type { OpenedBlobMeta } from '../../core/blob/meta'

/** Whether a medium with this opened `blob-meta` is a photo view's: an `image/*` or `video/*` type, or no meta at all. */
export function isGalleryMedium(meta: Pick<OpenedBlobMeta, 'type'> | { readonly type?: unknown } | null | undefined): boolean {
  if (meta === null || meta === undefined) return true
  const type = typeof meta.type === 'string' ? meta.type.trim().toLowerCase() : ''
  return type.startsWith('image/') || type.startsWith('video/')
}
