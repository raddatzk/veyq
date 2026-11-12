// The WebP encoder's API (ADR 0127): RGBA pixels, their size and a quality in, WebP bytes out. It is libwebp, built
// by ./build.py from the tag in ./libwebp.pin, and the only way into it — the client imports this file and nothing
// else of this directory. This directory imports nothing of veyq (scripts/validate_webp_encoder_imports.py), so it
// can be lifted out whole.
//
// The module is instantiated from bytes, never streamed: from bytes the caller hands `loadWebpEncoder`, or else from
// the `.wasm` fetched from the page's own origin on the first encode. So nothing depends on the `Content-Type` a
// server gives a `.wasm`, and a test in Node hands the file it read from disk. Compiling it needs the page's CSP to
// allow 'wasm-unsafe-eval' (cc.security.csp).
import type { WebpEncoderModule } from './wasm/webp_encoder.mjs'

let loading: Promise<WebpEncoderModule> | null = null

async function instantiate(bytes: ArrayBuffer | Uint8Array | undefined): Promise<WebpEncoderModule> {
  // Imported here, not at the top: the glue and the module are loaded when a picture is encoded, not with the page.
  const { default: createWebpEncoder } = await import('./wasm/webp_encoder.mjs')
  if (bytes) return createWebpEncoder({ wasmBinary: bytes })
  // One expression with `import.meta.url`, so the bundler emits the `.wasm` as a hashed asset of the client's origin.
  const response = await fetch(new URL('./wasm/webp_encoder.wasm', import.meta.url))
  if (!response.ok) throw new Error(`webp_encoder_unavailable: ${response.status}`)
  return createWebpEncoder({ wasmBinary: await response.arrayBuffer() })
}

/**
 * Load the encoder once: from `bytes` when given, else by fetching it. A failed load is forgotten, so the next encode
 * tries again rather than failing for the rest of the session.
 */
export function loadWebpEncoder(bytes?: ArrayBuffer | Uint8Array): Promise<WebpEncoderModule> {
  if (!loading) {
    loading = instantiate(bytes)
    loading.catch(() => {
      loading = null
    })
  }
  return loading
}

/**
 * Encode `width`×`height` RGBA pixels (4 bytes a pixel, row by row, not premultiplied) to a lossy WebP at `quality`
 * (0–1, as the canvas takes it). A picture with any pixel not fully opaque keeps its alpha (a `VP8X` file with an `ALPH`
 * chunk); an opaque one is a plain `VP8 ` file. Rejects when the encoder cannot load or libwebp refuses the input.
 */
export async function encodeWebp(
  rgba: Uint8Array | Uint8ClampedArray,
  width: number,
  height: number,
  quality: number,
): Promise<Uint8Array> {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new RangeError(`webp_encode_size: ${width}x${height}`)
  }
  if (rgba.length !== width * height * 4) throw new RangeError(`webp_encode_pixels: ${rgba.length} for ${width}x${height}`)
  if (!(quality >= 0 && quality <= 1)) throw new RangeError(`webp_encode_quality: ${quality}`)
  const encoder = await loadWebpEncoder()
  const input = encoder._webp_alloc(rgba.length)
  if (!input) throw new Error('webp_encode_out_of_memory')
  let size: number
  try {
    encoder.HEAPU8.set(rgba, input)
    size = encoder._webp_encode_rgba(input, width, height, quality * 100)
  } finally {
    encoder._webp_free(input)
  }
  try {
    if (!size) throw new Error('webp_encode_failed')
    const output = encoder._webp_result()
    // A copy, read after the encode: memory may have grown during it, which replaces the HEAPU8 view.
    return encoder.HEAPU8.slice(output, output + size)
  } finally {
    encoder._webp_release()
  }
}
