# ADR 0128 — pdf.js decodes scanned images with its plain-JavaScript decoders

- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [cc.security.csp](../crosscutting/cc.security.csp.md)

## Context

The Depot viewer draws a PDF with pdf.js in a same-origin worker ([cc.security.csp](../crosscutting/cc.security.csp.md)
§ pdf.js, inbox #412). An image in a PDF compressed as **CCITT G4**, **JBIG2** or **JPEG 2000** draws blank: these are
the usual formats of a scanner and of archival PDF, so a scanned page is mostly empty. Our docs said the cause was the
missing WebAssembly. It is not. pdf.js 6 loads each of these decoders (CCITT included, which it decodes in the same
`jbig2` module) in two ways: as WebAssembly when `useWasm` is set, and otherwise by a dynamic `import()` of a
plain-JavaScript build of the same decoder (`jbig2_nowasm_fallback.js`, `openjpeg_nowasm_fallback.js`). Both are read
from the `wasmUrl` option, which we leave unset, so both fail.

[ADR 0127](0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md) granted `'wasm-unsafe-eval'` for the WebP
encoder and named each further WebAssembly as a decision of its own: what it is, where it comes from, how it is pinned.

**Measured** (spike, 2026-11-12, pdf.js 6.5.438, one hand-built PDF per format, each first checked against
Ghostscript): in headless Chrome, with the production policy from `buildCsp()` sent as the header (Trusted Types
included), unset `wasmUrl` draws all three blank; `wasmUrl` set and `useWasm: false` draws all three correctly, with no
violation; under that policy `useWasm: true` is refused in the worker (`CompileError` naming the policy) and pdf.js falls
back to the JavaScript build by itself. The JavaScript builds contain no `eval(` and no `new Function(`. Times on a
loaded machine (load 10–13), so only the ratio is meant: CCITT and JBIG2 the same both ways; a 4000 × 3000 JPEG 2000
page about 1.4 s as JavaScript against 0.2 s as WebAssembly.

The alternatives:

- **Leave them off.** Scans stay blank, and scans are a large share of what people keep as PDFs.
- **The WebAssembly builds from `pdfjs-dist`.** Faster for large JPEG 2000 and adds ICC colour (`qcms`, which has no
  JavaScript build). Whether that is worth it is a measurement on a quiet machine with real scans, which we have not
  made (inbox #837); drawing the image does not need it.
- **Our own build of the decoders**, as ADR 0127 did for libwebp. The decoders are part of the pdf.js release we already
  run as a prebuilt package: the parser, the interpreter and the worker are equally compiled and minified upstream and
  reach us pinned by the lockfile's integrity hash. Building two of its files ourselves would add no trust the rest of
  pdf.js does not already need. libwebp was different: no maintained build existed, so we had to choose a source.

## Decision

1. **pdf.js gets a `wasmUrl`, and `useWasm` stays `false`.** The URL points at a same-origin directory under
   `assets/pdfjs-<version>/` holding exactly the two JavaScript builds, `jbig2_nowasm_fallback.js` and
   `openjpeg_nowasm_fallback.js`. pdf.js imports one from its worker the first time a PDF holds such an image. The
   policy does not change: `script-src 'self'` admits a same-origin module import, and no source is added.
2. **They come from `pdfjs-dist` and are pinned with it**: the same package, version and lockfile integrity as the rest
   of pdf.js, copied unchanged by the build as the CMaps and fonts are. The build checks that dist holds exactly these two
   files there, byte for byte the package's, and that they build no code from strings.
3. **No WebAssembly of pdf.js ships.** No `.wasm` of `pdfjs-dist` is copied, and the directory is not named `wasm/`. Turning
   WebAssembly on is inbox #837.
4. **Their notices ship with them.** The decoders are compiled from PDFium's JBIG2 decoder (BSD-3-Clause) and OpenJPEG
   (BSD-2-Clause), wrapped by Mozilla (Apache-2.0, BSD-2-Clause); the built licence file carries the four notices of
   `pdfjs-dist`'s `wasm/` directory with the package version as their source.

## Consequences

- A scanned page draws. A profiled colour still falls back to its alternate colour space, and a large JPEG 2000 page
  is slower than it could be: both are inbox #837.
- The threat surface grows by two decoders of untrusted bytes, written in C and compiled to JavaScript over one typed
  array. They run in pdf.js's worker like the rest of its parser, so the containment is the same and so is its limit:
  the worker shares the origin, so it contains a crash and CPU, not a defect that runs code (cc.security.csp § pdf.js).
  The bounds hold for them: 32 M pixels per decoded image.
- The client grows by 129 KiB (JBIG2 and CCITT) and 331 KiB (JPEG 2000), as the package ships them (already minified),
  each loaded only when a PDF needs it; the entry bundle and the pdf.js chunk do not change.
- For inbox #837 the provenance question is answered by the same argument: WebAssembly from `pdfjs-dist` would be
  pinned as these files are. What stays open there is whether its speed and ICC colour are worth a `.wasm` in the client.

## Related

- [ADR 0127](0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md) — the grant this one does not use.
- [Depot § The viewer](../apps/depot.md) and [uc.depot.view-file](../features/depot/use-cases/uc.depot.view-file.md) A1.
