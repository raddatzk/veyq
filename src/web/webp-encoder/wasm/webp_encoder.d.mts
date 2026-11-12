// Hand-written types for the Emscripten glue beside this file (`webp_encoder.mjs`, built by ../build.py, never edited).
// Exactly what encoder.c exports and the build's EXPORTED_RUNTIME_METHODS / INCOMING_MODULE_JS_API name: a new
// export there is added here in the same change.
export interface WebpEncoderModule {
  readonly HEAPU8: Uint8Array
  _webp_alloc(size: number): number
  _webp_free(pointer: number): void
  _webp_encode_rgba(rgba: number, width: number, height: number, quality: number): number
  _webp_result(): number
  _webp_release(): void
}

export default function createWebpEncoder(options: { wasmBinary: ArrayBuffer | Uint8Array }): Promise<WebpEncoderModule>
