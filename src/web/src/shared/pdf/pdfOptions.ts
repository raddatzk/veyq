// The options a PDF is opened with (inbox #412). A PDF is a PEER'S document: a program in a stream format that is parsed
// and drawn by pdf.js in a worker, so every option here is a security choice, and the set lives in one pure module
// (no pdf.js import) that the build-checked runtime, the unit tests and the real-library test all read.
//
// The page's policy (`shared/security/csp.ts`) is `script-src 'self' 'wasm-unsafe-eval'` (no 'unsafe-eval'),
// `worker-src 'self'`, `connect-src 'self' …`, `object-src/frame-src 'none'`. Each option below keeps pdf.js inside it:
//
// - The bytes are `data: Uint8Array`, our decrypted buffer - never a `url`, so pdf.js opens no connection to get the
//   document, and `disableRange/Stream/AutoFetch` stay on (they only matter for a URL, and say so).
// - `useWasm: false` - pdf.js 6 has no eval path at all (the `isEvalSupported` option of older releases is gone), but it
//   would use WebAssembly for its image decoders, ICC colour transforms and PostScript functions. The policy would compile
//   it since ADR 0127 granted `'wasm-unsafe-eval'` for the WebP encoder, but no WebAssembly of pdf.js ships (inbox #837
//   holds whether its speed and ICC colour are worth it). Off, the PostScript functions run as plain JS and ICC profiles
//   fall back to their alternate colour space.
// - `wasmUrl` points at the same-origin `decoders/` directory (ADR 0128): the PLAIN-JAVASCRIPT builds of pdf.js' image
//   decoders, `jbig2_nowasm_fallback.js` (JBIG2 and CCITT G4, compiled from PDFium's JBIG2 decoder) and
//   `openjpeg_nowasm_fallback.js` (JPEG 2000, compiled from OpenJPEG). With `useWasm` false pdf.js' worker `import()`s the
//   one it needs, the first time a page holds such an image - the usual formats of a scanner, which without it draw blank.
//   They come from `pdfjs-dist`'s `wasm/` directory unchanged (pinned with the rest of pdf.js), `script-src 'self'` admits
//   the import, and they build no code from strings; `verify:pdf-worker` holds all three. The directory is `decoders/`,
//   never `wasm/`: nothing in it is WebAssembly.
// - `enableXfa: false` - XFA forms are an XML + script format; not drawn.
// - There is no scripting: pdf.js 6 runs PDF JavaScript only for a viewer that mounts its scripting manager and loads
//   `pdf.sandbox.mjs`; this one does neither, so a PDF's actions are never executed.
// - `cMapUrl`, `standardFontDataUrl`, `iccUrl` and `wasmUrl` point at SAME-ORIGIN copies the build emits (`vite.config.ts`'s
//   `pdfjsAssetsPlugin`), so a PDF that names an unembedded font or a predefined CMap, or holds a scanned image, is drawn
//   without any network origin (`connect-src 'self'` carries the fetches, `script-src 'self'` the decoders' import).
// - `verbosity: 0` (errors only) so pdf.js does not narrate a document's structure into the console.
// - `maxImageSize` bounds one decoded image (total pixels); `canvasMaxAreaInBytes` is bounded by us (see `pdfPageSize`).

/** The subset of pdf.js' `getDocument` parameters this app sets (structural, so no pdf.js type reaches the entry bundle). */
export type PdfOpenParameters = {
  data: Uint8Array
  useWasm: boolean
  enableXfa: boolean
  disableRange: boolean
  disableStream: boolean
  disableAutoFetch: boolean
  stopAtErrors: boolean
  verbosity: number
  maxImageSize: number
  cMapUrl?: string
  cMapPacked?: boolean
  standardFontDataUrl?: string
  iccUrl?: string
  wasmUrl?: string
}

/** Most pages a PDF may have to be shown; above it the file is offered for download (a page tree is also a parse cost). */
export const PDF_MAX_PAGES = 1000
/** One decoded image's most pixels (width x height): 32 M, a 8000 x 4000 scan - bigger is not drawn. */
export const PDF_MAX_IMAGE_PIXELS = 32 * 1024 * 1024
/** The most pixels one page canvas gets (width x height at device resolution), about 16 M = 4096 x 4096; a page is scaled below it. */
export const PDF_MAX_CANVAS_PIXELS = 16 * 1024 * 1024

/** pdf.js' plain-JavaScript image decoders (ADR 0128): the files `pdfjsAssetsPlugin` copies from `pdfjs-dist/wasm/` into the
 *  data directory's `decoders/`, by name - that directory also holds WebAssembly and a JavaScript interpreter, which never ship. */
export const PDF_DECODER_FILES = ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js'] as const

/** Where the build puts pdf.js' runtime data, next to the entry assets; versioned so a new release cannot be served stale. */
export function pdfDataBase(baseUrl: string, version: string): string {
  return `${baseUrl}assets/pdfjs-${version}/`
}

/** The `getDocument` parameters for `data` (a Uint8Array the call takes ownership of), with the data directory `dataBase`
 *  (null outside a browser page: then pdf.js reads no data files, and a scanned image is left out). */
export function pdfOpenParameters(data: Uint8Array, dataBase: string | null): PdfOpenParameters {
  return {
    data,
    useWasm: false,
    enableXfa: false,
    disableRange: true,
    disableStream: true,
    disableAutoFetch: true,
    stopAtErrors: false,
    verbosity: 0,
    maxImageSize: PDF_MAX_IMAGE_PIXELS,
    ...(dataBase ? { cMapUrl: `${dataBase}cmaps/`, cMapPacked: true, standardFontDataUrl: `${dataBase}standard_fonts/`, iccUrl: `${dataBase}iccs/`, wasmUrl: `${dataBase}decoders/` } : {}),
  }
}
