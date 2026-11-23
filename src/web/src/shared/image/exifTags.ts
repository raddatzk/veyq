// An image's metadata tags, read on the device from its file — the platform's Kotlin `media/ExifReader.kt`, drawn through
// `@veyq/core-js/media` (ADR 0155 decision 7.2, the media headers' slice), one reader for the web and the apps, held by
// contracts/platform/media/v1-vectors.json. It replaced the ExifReader library (`mattiasw/ExifReader`, which had replaced the
// abandoned `exifr`, inbox #799), whose answers the vectors were first recorded from. Callers get [ExifTags]: the tags by
// their standard names, already undone (a rational is a number, an ASCII tag a string, a date epoch-ms — the camera's wall
// clock read in the device's zone —, the GPS position signed decimal degrees), so what a caller maps from them is its own
// and a test can hand it a record. Formats: JPEG, TIFF, PNG, WebP, HEIC, AVIF; XMP is scanned by hand in the core, never
// handed to the browser's XML parser. Never the whole file: a JPEG's and a HEIF's head in windows, a PNG's or a WebP's first
// [CHUNKED_PREFIX], a bare TIFF's first [TIFF_PREFIX]. It fetches nothing. This file holds no rule.
import { exifChunkedPrefix, exifTags, exifTiffPrefix, type ExifShape } from '@veyq/core-js/media'
import { mediaFileOf } from './mediaFile'

/** The tags a caller can read, each optional: a tag the file does not carry, or carries unreadably, is absent. The core's
 *  `ExifShape` names them; see `media/web/Shapes.kt` for each one's meaning. */
export type ExifTags = { -readonly [K in keyof ExifShape]?: NonNullable<ExifShape[K]> }

/** What to read: only the three date tags (the pick-time capture time), only XMP's capture dates (the pick-time
 *  fallback where the file has no EXIF date), or every tag [ExifTags] names. */
export type ExifScope = 'date' | 'xmpDate' | 'all'

/** The most of a PNG or a WebP read: a PNG's metadata usually precedes its pixels, a WebP's follows them. */
export const CHUNKED_PREFIX = exifChunkedPrefix()
/** The most of a bare TIFF read: its IFDs, where the tags are, start at its head. */
export const TIFF_PREFIX = exifTiffPrefix()

/**
 * Read `image`'s tags for `scope`, or `undefined` when it carries none this reads. Rejects for a read the browser refuses —
 * the caller decides what that means.
 */
export async function readExifTags(image: Blob, scope: ExifScope): Promise<ExifTags | undefined> {
  // The core sets only the tags present (`media/web/MediaWeb.kt`), so its object is an ExifTags as it stands.
  return ((await exifTags(mediaFileOf(image), scope)) as ExifTags | null) ?? undefined
}
