// Web Worker that runs the image pixel stage (decode → progressive-halving downscale → encode) off the page
// thread. It runs the SAME `mediaPixels` code the page deriver runs — only the canvas port differs
// (`OffscreenCanvas` + `convertToBlob`) — so the tiers it produces are the page thread's tiers.
//
// Why a Worker at all: Chromium encodes a page-thread canvas (`toBlob`, and `convertToBlob` alike) in an idle
// period, and a tab that is not being painted (backgrounded, minimized, occluded) waits for one — measured at
// ≈0.1–1.2 s per encode in a backgrounded desktop Chrome 154 tab, against ~15–25 ms visible. A Worker's encode
// does not wait on the page's paint cycle. The operator sees nothing new: the picked Blob comes in, derivative
// bytes go back to the page, which seals them exactly as before.
//
// Two failure classes, answered apart (the page side treats them differently — `mediaDeriveWorker.ts`):
//   - `infra`  — this Worker cannot do the job (no `OffscreenCanvas`, no 2D context, no `createImageBitmap`): the
//                page latches and finishes on its own thread.
//   - `source` — the input cannot be decoded or encoded (HEIC off Safari, an empty MIME type, corrupt bytes): it
//                would fail on the page thread the same way, so the page rejects and the upload goes original-only.
import { drawEncoded, type CanvasPort } from './mediaPixels'

export type DeriveOpts = { maxEdge: number; quality: number; mimeType?: string; background?: string }
export type DeriveRequest = { id: number; source: Blob; opts: DeriveOpts }
export type DeriveResponse =
  | { id: number; ok: true; bytes: Uint8Array; source: { w: number; h: number } }
  | { id: number; ok: false; kind: 'source' | 'infra'; error: string }

const WORKER_PORT: CanvasPort<OffscreenCanvas> = {
  create: (width, height) => new OffscreenCanvas(width, height),
  encode: (canvas, mimeType, quality) => canvas.convertToBlob({ type: mimeType, quality }),
}

const ctx = self as unknown as { postMessage(message: DeriveResponse, transfer?: Transferable[]): void }

/** Feature-detected per request: a missing piece is the Worker's shortcoming, never the source's. */
function infraGap(): string | null {
  if (typeof OffscreenCanvas === 'undefined') return 'offscreen_canvas_unavailable'
  if (typeof createImageBitmap === 'undefined') return 'create_image_bitmap_unavailable'
  if (!new OffscreenCanvas(1, 1).getContext('2d')) return 'canvas_unavailable'
  return null
}

self.addEventListener('message', (event: MessageEvent<DeriveRequest>) => {
  void handle(event.data)
})

async function handle({ id, source, opts }: DeriveRequest): Promise<void> {
  const gap = infraGap()
  if (gap) {
    ctx.postMessage({ id, ok: false, kind: 'infra', error: gap })
    return
  }
  let bitmap: ImageBitmap | null = null
  try {
    bitmap = await createImageBitmap(source)
    const bytes = await drawEncoded(
      WORKER_PORT, bitmap, bitmap.width, bitmap.height, opts.maxEdge, opts.quality, opts.mimeType, opts.background,
    )
    // Transfer, don't copy: the derivative's buffer moves to the page.
    ctx.postMessage({ id, ok: true, bytes, source: { w: bitmap.width, h: bitmap.height } }, [bytes.buffer])
  } catch (err) {
    ctx.postMessage({ id, ok: false, kind: 'source', error: String(err) })
  } finally {
    bitmap?.close()
  }
}
