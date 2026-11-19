// The item mark as the lightbox offers it (ADR 0068, uc.memory.gallery step 7, inbox #986): the tile's heart for the shown
// item. The lightbox touches no API itself (its header): the parent hands this, reading and writing the scope members'
// marks through its own `marks` door and `itemFavoritesStore`, and leaves it out where no heart is offered.
import type { MediaItem } from './media'

export interface LightboxMark {
  /** Whether the caller marked `item` — fills the heart. */
  mine(item: MediaItem): boolean
  /** How many members marked `item`: the aggregate, the most the UI shows (0 where the count is not shown). */
  count(item: MediaItem): number
  /** Toggle the caller's own mark on `item`; a write that does not land is the parent's to say. */
  toggle(item: MediaItem): void
}
