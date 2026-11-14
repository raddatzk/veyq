// What a photo view of a Memory shows (ADR 0135 decision 2; en.memory.media § Which media a photo view shows): every medium
// except the kinds another view of the node claims - a GPX track (the Map tab's, ADR 0130) and a music track (the Music
// tab's, ADR 0135). One predicate, asked by every reader that treats a medium as a photo - the gallery's list
// (`useMediaGalleryLive`'s `items`, which the grid, the lightbox, the count, the organizer, the date and place reconcile
// and the open Memory's search documents derive from) and the space's search (`search.ts`). A type no view claims (a PDF
// an archive brought) stays a gallery item, an icon tile (uc.memory.gallery E1): hiding it would leave a medium nobody
// can see, download or delete, still counted against the quota.
import type { OpenedBlobMeta } from '../../core/blob/meta'
import { isAudioMeta } from './music/audio'
import { isTrackMeta } from './track/gpx'

/** Whether a medium with this opened `blob-meta` is a photo view's: anything but a GPX track or a music track. */
export function isGalleryMedium(meta: Pick<OpenedBlobMeta, 'type' | 'name'> | { readonly type?: unknown; readonly name?: unknown } | null | undefined): boolean {
  return !isTrackMeta(meta) && !isAudioMeta(meta)
}
