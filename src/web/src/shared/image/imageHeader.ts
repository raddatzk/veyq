// An image's pixel size read from the first bytes of its file, never from decoded pixels (inbox #558) — the platform's
// Kotlin `media/ImageHeader.kt`, drawn through `@veyq/core-js/media` (ADR 0155 decision 7.2, the media headers' slice), one
// rule for the web and the apps, held by contracts/platform/media/v1-vectors.json. The pick-time tile probe
// (`mediaDeriver.ts` `probeDimensions`) needs only an aspect ratio, and a 12-megapixel photo decoded is 48.8 MB against the
// few kilobytes that name its size: picking 50 such photos decoded them all at once, the renderer at 2.30-2.79 GiB against
// 1.14-1.27 GiB read from the header (profile S2, 2026-10-29). JPEG (turned by its EXIF orientation, as a browser draws
// it), PNG, GIF and WebP; anything else answers null and the caller asks the browser. This file holds no rule.
import { imageSize } from '@veyq/core-js/media'
import { mediaFileOf } from './mediaFile'
import type { Dimensions } from './mediaDeriver'

/** The pixel size the file's header names, as a browser shows it, or null; a read the browser refuses rejects. */
export async function imageSizeFromHeader(blob: Blob): Promise<Dimensions | null> {
  const size = await imageSize(mediaFileOf(blob))
  return size ? { w: size.w, h: size.h } : null
}
