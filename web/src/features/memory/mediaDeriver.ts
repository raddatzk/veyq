// en.memory.derivatives (web) — the on-device pixel stage split out of `media.ts` (cr-0143).
// A media's role-tagged derivatives (thumbnail/preview; a video's first-frame poster) are generated here:
// decode → progressive-halving downscale → JPEG re-encode, all client-side (operator-blind — the server never
// sees a pixel). The stage sits behind an injectable `MediaDeriver` so `media.ts`'s add-media wiring is
// unit-testable against a stub; the real Canvas/`<video>` path is exercised by a live browser round-trip.
import { type MediaDerivativeKind } from '../../api/generated/media'

/**
 * Derivative tier specs (en.memory.derivatives → *The three tiers*). `preview` is pinned from the legacy E2EE
 * client; `thumbnail` was raised 400→512 px (cr-0070) so hi-DPI grid tiles aren't upscaled into mush. Parity is
 * by-property (a valid JPEG at the target edge), so apple/android carry the same 512.
 */
export const TIER_SPECS: Record<MediaDerivativeKind, { maxEdge: number; quality: number }> = {
  THUMBNAIL: { maxEdge: 512, quality: 0.8 }, // gallery grid (512 for retina headroom, cr-0070)
  PREVIEW: { maxEdge: 2048, quality: 0.85 }, // lightbox
}

/** A media's pristine pixel dimensions, used to lay out the justified gallery grid (cr-0059). */
export type Dimensions = { w: number; h: number }

/** The source to attach: the pristine original bytes + their MIME type, plus the file's creation date (cr-0066). */
export type AddMediaSource = { bytes: Uint8Array; mediaType: string; lastModified?: number }

/**
 * The platform-native pixel stage behind an interface: the real one uses Canvas + `<video>`; tests inject a
 * stub. A derivative need only be a valid JPEG at the target longest edge — parity here is by **property**, not
 * bytes (en.memory.derivatives → *Quality notes*).
 */
export interface MediaDeriver {
  /**
   * Downscale an image Blob to `maxEdge` at `quality`. Encodes JPEG by default; pass `mimeType: 'image/webp'`
   * for an alpha-preserving encode (a space icon, uc.spaces.set-space-icon). `background` fills the canvas before
   * drawing (a hex/CSS colour) so a transparent source does not flatten onto the canvas default (transparent
   * black → black once JPEG-flattened); omit it to keep alpha (only meaningful with an alpha-capable `mimeType`).
   */
  deriveImage(
    source: Blob,
    opts: { maxEdge: number; quality: number; mimeType?: string; background?: string },
  ): Promise<{ bytes: Uint8Array; source: Dimensions | null }>
  /** Decode a video's first frame to a JPEG poster (used for both the thumbnail and the preview tier). */
  deriveVideoPoster(source: Blob): Promise<Uint8Array>
  /**
   * Probe the source's intrinsic pixel `{w,h}` for the justified grid (cr-0059). Optional + best-effort.
   *
   * **A decode of its own, so it is for sources nothing else decodes** — the in-flight upload tile (which needs
   * an aspect ratio before the file's turn in the queue) and videos (whose poster path reports no size). An
   * image on the upload path must NOT come through here: [deriveTiers] already decodes it once and hands the
   * geometry back, and re-probing would pay for that decode twice.
   */
  probeDimensions?(source: Blob, mediaType: string): Promise<Dimensions | null>
}

/**
 * Longest-edge target math (pure): scale `width`×`height` so the longest edge is ≤ `maxEdge`, preserving the
 * aspect ratio and **never upscaling**. Each side is at least 1px.
 */
export function scaleToMaxEdge(width: number, height: number, maxEdge: number): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= maxEdge || longest === 0) return { width, height }
  const scale = maxEdge / longest
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

/** The real browser deriver — Canvas decode/downscale + `<video>` first-frame poster. DOM-only (used live). */
export function createBrowserDeriver(): MediaDeriver {
  return {
    async deriveImage(source, { maxEdge, quality, mimeType = 'image/jpeg', background }) {
      const bitmap = await createImageBitmap(source)
      try {
        // The intrinsic size rides back with the bytes: this decode already knows it, and it is exactly what
        // the sealed metadata tier wants. Without it the same original is decoded a second time just to measure.
        const bytes = await drawEncoded(bitmap, bitmap.width, bitmap.height, maxEdge, quality, mimeType, background)
        return { bytes, source: { w: bitmap.width, h: bitmap.height } }
      } finally {
        bitmap.close()
      }
    },
    async deriveVideoPoster(source) {
      const url = URL.createObjectURL(source)
      const video = document.createElement('video')
      try {
        video.muted = true
        video.playsInline = true // Safari: decode inline without demanding a user gesture / fullscreen
        video.preload = 'auto'
        video.src = url
        // Safari will NOT decode a frame of an offscreen, never-played <video>: `loadeddata`/`seeked` either never
        // fire (upload hangs — works in Chrome/Firefox, which are lax) or fire with no decoded frame to draw (an
        // empty poster). So once metadata is in: seek to a representative offset (0 is often a black/undecoded
        // frame), NUDGE the decoder with a muted play() (autoplay-allowed; fire-and-forget so it can't re-introduce
        // a hang), then wait for a genuinely PRESENTED frame. All waits are timeout-guarded, so a source the
        // browser can't decode falls back cleanly (deriveTiers' catch → original-only) instead of hanging.
        await onceEvent(video, 'loadedmetadata')
        video.currentTime = Math.min(0.1, (video.duration || 0) / 2)
        void video.play().catch(() => {}) // prime the decode pipeline; never awaited
        await firstPresentedFrame(video)
        video.pause()
        // the poster is encoded at the preview tier and reused for the (smaller) thumbnail tier as-is
        return drawEncoded(video, video.videoWidth, video.videoHeight, TIER_SPECS.PREVIEW.maxEdge, TIER_SPECS.PREVIEW.quality)
      } finally {
        video.removeAttribute('src')
        video.load() // detach the source so Safari releases the decoder/buffers promptly
        URL.revokeObjectURL(url)
      }
    },
    async probeDimensions(source, mediaType) {
      if (mediaType.startsWith('image/')) {
        const bitmap = await createImageBitmap(source)
        try {
          return { w: bitmap.width, h: bitmap.height }
        } finally {
          bitmap.close()
        }
      }
      if (mediaType.startsWith('video/')) {
        const url = URL.createObjectURL(source)
        try {
          const video = document.createElement('video')
          video.muted = true
          video.preload = 'metadata'
          video.src = url
          await onceEvent(video, 'loadedmetadata') // width/height are known here — Safari never fires `loadeddata` offscreen
          return { w: video.videoWidth, h: video.videoHeight }
        } finally {
          URL.revokeObjectURL(url)
        }
      }
      return null
    },
  }
}

async function drawEncoded(
  src: CanvasImageSource, w: number, h: number, maxEdge: number, quality: number,
  mimeType = 'image/jpeg', background?: string,
): Promise<Uint8Array> {
  const { width, height } = scaleToMaxEdge(w, h, maxEdge)
  // Progressive-halving downscale. A single large drawImage (e.g. 4032→512 ≈ 8×) stays soft even at
  // imageSmoothingQuality:'high' — the browser applies ONE bilinear pass, not an iterative filter, so it drops
  // high-frequency detail. Halving in ≤2× steps (each with high smoothing) preserves that detail, approximating
  // the multi-tap resample the legacy server did with libvips (frontend-noe2ee derived server-side; operator-blind
  // forces it on-device here). scaleToMaxEdge never upscales, so a small source just skips the loop.
  // The intermediate steps keep alpha (no fill); the background is applied only on the FINAL canvas that is encoded.
  let curSrc: CanvasImageSource = src
  let curW = w
  let curH = h
  while (curW > width * 2 || curH > height * 2) {
    const nextW = Math.max(Math.round(curW / 2), width)
    const nextH = Math.max(Math.round(curH / 2), height)
    curSrc = drawStep(curSrc, nextW, nextH)
    curW = nextW
    curH = nextH
  }
  const canvas = drawStep(curSrc, width, height, background)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, mimeType, quality))
  if (!blob) throw new Error('encode_failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** One high-quality `drawImage` onto a fresh canvas at `width`×`height` (each downscale step of drawEncoded).
 *  `background` (a CSS colour) fills the canvas before drawing, so a transparent source lands on it rather than
 *  the transparent-black default; omit it to preserve alpha (only meaningful with an alpha-capable encode). */
function drawStep(src: CanvasImageSource, width: number, height: number, background?: string): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  if (background) {
    ctx.fillStyle = background
    ctx.fillRect(0, 0, width, height)
  }
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(src, 0, 0, width, height)
  return canvas
}

/** Cap the wait for a <video> to expose metadata / decode a seeked frame. Safari silently fires NEITHER the event
 *  NOR `error` for an offscreen, never-played element, which would hang the whole upload before its first network
 *  call. On timeout the caller's catch falls back to original-only (no poster), so the upload still proceeds.
 *  Generous, so a slow decode of a large frame on a slow machine is not cut off spuriously. Exported for tests. */
export const VIDEO_EVENT_TIMEOUT_MS = 15_000

/** Resolve on the first `ev`, reject on `error` OR after `timeoutMs` (the guard against Safari's silent no-op). */
export function onceEvent(
  el: HTMLMediaElement,
  ev: 'loadedmetadata' | 'seeked',
  timeoutMs = VIDEO_EVENT_TIMEOUT_MS,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error(`video_${ev}_timeout`)) }, timeoutMs)
    const ok = () => { cleanup(); resolve() }
    const bad = () => { cleanup(); reject(new Error(`video_${ev}_failed`)) }
    const cleanup = () => {
      clearTimeout(timer)
      el.removeEventListener(ev, ok)
      el.removeEventListener('error', bad)
    }
    el.addEventListener(ev, ok, { once: true })
    el.addEventListener('error', bad, { once: true })
  })
}

/** Resolve once a decoded frame is actually PRESENTED (and thus drawable). `requestVideoFrameCallback` is the only
 *  signal a frame reached the compositor, so prefer it; otherwise fall back to the post-seek `seeked` event.
 *  Timeout-guarded (Safari can present nothing for an offscreen source), so the caller's catch degrades to
 *  original-only rather than hanging. Exported for tests. */
export function firstPresentedFrame(video: HTMLVideoElement, timeoutMs = VIDEO_EVENT_TIMEOUT_MS): Promise<void> {
  const rvfc = (video as HTMLVideoElement & { requestVideoFrameCallback?: (cb: () => void) => number })
    .requestVideoFrameCallback
  if (typeof rvfc !== 'function') return onceEvent(video, 'seeked', timeoutMs)
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('video_frame_timeout')), timeoutMs)
    rvfc.call(video, () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

/**
 * Generate the role-tagged derivatives for a source (image: two downscales; video: one poster for both), and
 * hand back the original's intrinsic size when the decode knew it.
 *
 * **The original is decoded ONCE.** The thumbnail is derived from the already-downscaled PREVIEW rather than a
 * second pass over the full-resolution source: 4032 → 2048 → 512 traverses the same ≤2× halving steps that two
 * independent runs would ([en.memory.derivatives](../../../../docs/features/memory/enablers/en.memory.derivatives.md)
 * § The three tiers), so the pinned downscale rule is honoured rather than traded for the saving. The order is
 * therefore load-bearing: PREVIEW must be produced first, because THUMBNAIL now reads it.
 *
 * `dimensions` is null for a video (the poster path reports no size), for an underivable source, and for any
 * deriver that does not report a size; the caller falls back to a probe there, which is the only place that
 * decode is still worth paying for.
 */
export async function deriveTiers(
  source: AddMediaSource,
  deriver: MediaDeriver,
): Promise<{ derivatives: { kind: MediaDerivativeKind; bytes: Uint8Array }[]; dimensions: Dimensions | null }> {
  const blob = new Blob([source.bytes as BlobPart], { type: source.mediaType })
  // Best-effort: a codec the browser can't decode (e.g. HEIC off Safari), an empty MIME type, or corrupt bytes
  // must NOT fail the upload — fall back to original-only (the gallery shows an icon, uc.memory.upload E4).
  try {
    if (source.mediaType.startsWith('image/')) {
      const preview = await deriver.deriveImage(blob, TIER_SPECS.PREVIEW)
      const previewBlob = new Blob([preview.bytes as BlobPart], { type: 'image/jpeg' })
      const thumbnail = await deriver.deriveImage(previewBlob, TIER_SPECS.THUMBNAIL)
      return {
        derivatives: [{ kind: 'THUMBNAIL', bytes: thumbnail.bytes }, { kind: 'PREVIEW', bytes: preview.bytes }],
        dimensions: preview.source,
      }
    }
    if (source.mediaType.startsWith('video/')) {
      const poster = await deriver.deriveVideoPoster(blob) // one poster serves both tiers
      return { derivatives: [{ kind: 'THUMBNAIL', bytes: poster }, { kind: 'PREVIEW', bytes: poster }], dimensions: null }
    }
  } catch {
    /* underivable → original only */
  }
  return { derivatives: [], dimensions: null } // no derivable frame → original only (the gallery falls back to an icon)
}
