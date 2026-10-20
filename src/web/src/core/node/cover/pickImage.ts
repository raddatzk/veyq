// What core's cover dialog accepts as a cover source (inbox #411): an image, not empty, up to 40 MB. The picking itself is the
// dialog's own `<input type=file accept="image/*">` (`components/CoverDialog.vue`).
import { MAX_COVER_SOURCE_BYTES } from './coverWrite'

/** The i18n key of why `file` cannot be a cover, or null when it can: a non-image, an empty file, or one over the limit. */
export function coverFileProblem(file: Pick<File, 'type' | 'size'>): string | null {
  if (!file.type.startsWith('image/') || file.size === 0) return 'node.cover.notImage'
  if (file.size > MAX_COVER_SOURCE_BYTES) return 'node.cover.tooLarge'
  return null
}
