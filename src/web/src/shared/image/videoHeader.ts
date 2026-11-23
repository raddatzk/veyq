// A video's display size and capture time read from its container's header, never by a player (inbox #793, #794) — the
// platform's Kotlin `media/VideoHeader.kt`, drawn through `@veyq/core-js/media` (ADR 0155 decision 7.2, the media headers'
// slice), one rule for the web and the apps, held by contracts/platform/media/v1-vectors.json. A `<video>` that loads a
// clip's metadata is a media player of its own for as long as it lives; picking 20 clips made 20 (profile S2v, inbox #786).
// MP4 / QuickTime and WebM / Matroska; the capture time MP4 / QuickTime's only (Apple's creation date, else the `mvhd`
// time). Anything else answers null and the caller asks the browser. This file holds no rule.
import { videoCaptureTime, videoSize } from '@veyq/core-js/media'
import { mediaFileOf } from './mediaFile'
import type { Dimensions } from './mediaDeriver'

/** The display size a video file's container header names, as a player shows it, or null; a refused read rejects. */
export async function videoSizeFromHeader(blob: Blob): Promise<Dimensions | null> {
  const size = await videoSize(mediaFileOf(blob))
  return size ? { w: size.w, h: size.h } : null
}

/** The capture time (epoch ms) a video file's container header names, or null; `now` bounds a time ahead of the clock. */
export async function videoCaptureTimeFromHeader(blob: Blob, now: number = Date.now()): Promise<number | null> {
  return (await videoCaptureTime(mediaFileOf(blob), now)) ?? null
}
