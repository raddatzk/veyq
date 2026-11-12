// The one entry into libwebp this module exposes: RGBA pixels in, a lossy WebP out (ADR 0127).
//
// libwebp's simple API does all of it: `WebPEncodeRGBA` encodes the colour lossy at `quality` (0-100) and, only when a
// pixel is not fully opaque, adds the alpha plane losslessly in an `ALPH` chunk under a `VP8X` header; an opaque picture
// stays a plain `VP8 ` file. Nothing else of libwebp is referenced, so the linker keeps the encoder and drops the rest.
//
// The output buffer is libwebp's, kept here until the next call or `webp_release`, because a WebAssembly function
// returns one value: the size comes back, the pointer is read with `webp_result`.
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#include <emscripten/emscripten.h>

#include "src/webp/encode.h"

static uint8_t* last_output = NULL;

EMSCRIPTEN_KEEPALIVE uint8_t* webp_alloc(size_t size) { return (uint8_t*)malloc(size); }

EMSCRIPTEN_KEEPALIVE void webp_free(void* pointer) { free(pointer); }

EMSCRIPTEN_KEEPALIVE void webp_release(void) {
  WebPFree(last_output);
  last_output = NULL;
}

// Returns the encoded size, or 0 when libwebp refused (a size out of range, out of memory).
EMSCRIPTEN_KEEPALIVE size_t webp_encode_rgba(const uint8_t* rgba, int width, int height, float quality) {
  webp_release();
  return WebPEncodeRGBA(rgba, width, height, width * 4, quality, &last_output);
}

EMSCRIPTEN_KEEPALIVE uint8_t* webp_result(void) { return last_output; }
