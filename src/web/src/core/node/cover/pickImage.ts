// What core's cover dialog accepts as a cover source (inbox #411): an image, not empty, up to 40 MB. The picking itself is the
// dialog's own `<input type=file accept="image/*,.heic,.heif">` (`components/CoverDialog.vue`). Whether this browser can
// DRAW the picked image is the dialog's cropper's answer, not this one: an HEIC is an image here in every browser, and only
// Safari shows it (the dialog then says so, `node.cover.cannotShow`).
import { extensionOf } from '../../blob/sniff'
import { MAX_COVER_SOURCE_BYTES } from './coverWrite'

/**
 * The image types a cover source is known by from its name alone, for a file the system hands over untyped: an HEIC from
 * the Photos app arrives with an empty type in Chrome and Firefox (the Memories upload names it the same way,
 * `features/memories/uploadQueue.ts`).
 */
const TYPE_BY_EXTENSION: Readonly<Record<string, string>> = {
  heic: 'image/heic',
  heif: 'image/heif',
  svg: 'image/svg+xml',
}

/** The media type a picked cover source is handled as: its own, else the one its extension names, else ''. */
export function coverMediaType(file: Pick<File, 'type'> & { name?: string }): string {
  return file.type || TYPE_BY_EXTENSION[extensionOf(file.name)] || ''
}

/** The i18n key of why `file` cannot be a cover, or null when it can: a non-image, an empty file, or one over the limit. */
export function coverFileProblem(file: Pick<File, 'type' | 'size'> & { name?: string }): string | null {
  if (!coverMediaType(file).startsWith('image/') || file.size === 0) return 'node.cover.notImage'
  if (file.size > MAX_COVER_SOURCE_BYTES) return 'node.cover.tooLarge'
  return null
}
