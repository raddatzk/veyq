---
id: cr-pdf-scan-decoders
title: Draw scanned PDF pages with pdf.js's plain-JavaScript image decoders
status: planned
satisfies: [uc.depot.view-file]
adrs: ["0128", "0127"]
invariants:
  - the CSP does not change; no source is added for this (ADR 0128 decision 1)
  - "`useWasm` stays `false` and no `.wasm` of `pdfjs-dist` reaches dist (decision 3)"
  - dist holds exactly the two decoder files, byte for byte `pdfjs-dist`'s, and neither builds code from strings (decision 2)
  - the decoders load only from the client's own origin, only from pdf.js's worker, only when a PDF needs one
---

## Goal

A PDF image compressed as CCITT G4, JBIG2 or JPEG 2000 draws blank in the Depot viewer today, so a scanned page is
mostly empty. After this CR pdf.js loads the plain-JavaScript builds of its decoders from a same-origin copy and those
images draw, under the policy as it is. Why the JavaScript builds and not WebAssembly or our own build:
[ADR 0128](../docs/decisions/0128-pdf-js-decodes-scanned-images-with-its-plain-javascript-decoders.md). Inbox #836;
WebAssembly stays inbox #837.

## Context & constraints

- **The options:** `src/web/src/shared/pdf/pdfOptions.ts` `pdfOpenParameters(data, dataBase)` — one pure module read by
  the runtime (`pdfjsRuntime.ts`, which passes `pdfDataBase(import.meta.env.BASE_URL, __PDFJS_VERSION__)`, `null` outside a
  document) and the tests. It sets `useWasm: false` and, when `dataBase` is given, `cMapUrl` / `standardFontDataUrl` /
  `iccUrl` under it. `wasmUrl` is unset on purpose today.
- **How pdf.js loads a decoder** (`pdfjs-dist@6.5.438`, `build/pdf.worker.mjs`, class `WasmImage`, ~l.8730–8800):
  `useWasm` true → fetch `${wasmUrl}<name>.wasm` and instantiate; failing that, or with `useWasm` false →
  `import(\`${wasmUrl}${_noWasmFilename}\`)`; failing that, the image is left out with a warning. `JBig2CCITTFaxImage`
  (`jbig2_nowasm_fallback.js`) decodes JBIG2 **and** CCITT; `JpxImage` (`openjpeg_nowasm_fallback.js`) decodes JPEG 2000.
  The import runs in the worker, so the worker's policy governs it — the backend sends the policy on every shell
  response, the worker's script included (`AppShellRoutes.kt` l.138).
- **The assets:** `src/web/vite.config.ts` `pdfjsAssetsPlugin` emits `pdfjs-dist`'s `PDFJS_DATA_DIRS` (`cmaps`,
  `standard_fonts`, `iccs`) under `assets/pdfjs-<version>/` at build, and serves the same paths in dev from a middleware
  that answers **`application/octet-stream`** for every file. A module `import()` refuses a non-script MIME type, so the
  dev middleware must answer the decoders as `text/javascript`. In production the backend types a static `.js` by its
  extension, as it does for `assets/pdf.worker.min-<hash>.js` today.
- **The guard:** `src/web/scripts/assert-pdf-worker-in-dist.mjs` (`verify:pdf-worker`, in `pnpm run check`). Its (iv)
  scans the worker and the runtime for `new Function(` / `eval(`; its (v) requires the data dirs and refuses any `wasm`
  directory and any `.wasm` but the WebP encoder's.
- **Licences:** `dist/third-party-licenses.txt` is Vite's `build.license` (npm packages whose modules are bundled), rewritten
  by `licenseSourcesPlugin` (`vite.config.ts`), which also appends libwebp's notice (ADR 0127). Files copied by
  `emitFile` are not bundled modules, so their notices are not in it. `assert-license-sources-in-dist.mjs` checks the
  file's headings and their `Source:` lines; libwebp's entry is its precedent for a non-npm-heading entry. The notices are
  `pdfjs-dist/wasm/LICENSE_JBIG2`, `LICENSE_PDFJS_JBIG2`, `LICENSE_OPENJPEG`, `LICENSE_PDFJS_OPENJPEG`.
- **The real-library test:** `src/web/src/shared/pdf/pdfEngine.real.test.ts` (node, the legacy build, hand-built PDFs in
  TypeScript). l.134–138 assert `useWasm: false` and `'wasmUrl' in o === false` for the node call (`dataBase` null).
  Node has no canvas; an image's decoded pixels are reachable through `page.getOperatorList()` and `page.objs` (the
  spike did exactly that: `paintImageXObject`'s object id → `{ width, height, kind, data }`).
- **Measured at refine** (2026-11-12, the spike, ADR 0128 § Context): with `wasmUrl` set and `useWasm: false` all three
  formats draw in headless Chrome under the production policy as the header; unset, all three are blank. The decoder files
  hold no `eval(` / `new Function(` (`grep -c` over both → 0). Sizes: 132,439 and 338,458 bytes.
- **What the browser pass cannot use:** a signed-in session in a worktree (`?devlogin` needs `.run/.env`, which the
  classifier refuses to copy — cr-webp-encoder-from-libwebp's retro) and lane L1 (its port 18081 is held on this Mac). So
  the pass below loads the **built** runtime chunk from `dist/` with the **built** policy as the header, without the app's
  login. It proves the emitted files, our options and the policy together; it does not click through the Depot UI.

## Phases

- [x] **1 client — the decoders ship and pdf.js finds them.**
  - **Options:** `pdfOpenParameters` sets `wasmUrl: \`${dataBase}decoders/\`` whenever it sets the other data URLs, and
    keeps `useWasm: false`. The directory is `decoders/`, never `wasm/`, so (v)'s refusal of a `wasm` directory keeps its
    meaning. The comment at the top of the module says what the decoders are, where they come from and that WebAssembly is
    inbox #837.
  - **Build:** `pdfjsAssetsPlugin` emits `assets/pdfjs-<version>/decoders/` with **exactly** the two named files read from
    `pdfjs-dist/wasm/` — a named list, never a `readdir` of `wasm/` (it also holds `.wasm` files and `quickjs-eval.js`).
    The dev middleware serves the same two names under `decoders/` as `text/javascript` and nothing else there.
  - **Licences:** the built licence file carries the four notices, each under a heading that names `pdfjs-dist`, its
    version and the notice's file as the source; `assert-license-sources-in-dist.mjs` accepts exactly that form and is red
    when the decoders are emitted without them.
  - **Guard** (`assert-pdf-worker-in-dist.mjs`): (iv) also scans the two decoder files; (v) requires
    `assets/pdfjs-<version>/decoders/` to hold exactly the two names, each byte-identical to `pdfjs-dist/wasm/`'s file
    (SHA-256, as the encoder is compared), and keeps refusing any `wasm` directory and any `.wasm` but the encoder's. Its
    header comment says what it now proves and its bites.
  - **Test** (`pdfEngine.real.test.ts`): three small hand-built PDFs, one image each in CCITT G4, JBIG2 (an embedded
    stream; a generic region with MMR coding is enough) and JPEG 2000, each a bitmap whose pixels the test can predict.
    The test opens each through **our** options — `pdfOpenParameters` with a `dataBase` (a `file:` URL to a temporary
    directory holding `decoders/` with the two files from `pdfjs-dist`) — and asserts the decoded image's size and pixels.
    The encoded bytes are committed in the test (or as fixture files beside it) with one line on how they were made; the
    pixel assertion is what proves them, not their provenance. The existing `'wasmUrl' in o` assertion becomes: no
    `wasmUrl` without a `dataBase`, `…/decoders/` with one, `useWasm: false` in both.
  - **Bites** (snapshot-and-restore, never `git checkout`; `changes/README.md`):
    - remove `wasmUrl` from `pdfOpenParameters` → the three decode tests RED;
    - copy `pdfjs-dist/wasm/jbig2.wasm` into `dist/assets/pdfjs-<version>/decoders/` → (v) RED;
    - copy `quickjs-eval.js` there → (v) RED (a third file);
    - flip one byte of an emitted decoder → (v) RED;
    - append `eval(0)` to an emitted decoder → (iv) RED;
    - drop one of the four notices from the built licence file → `verify:license-sources` RED.
  - **Browser pass:** `pnpm run build`, then serve `dist/` from a throwaway local server that sends
    `dist/content-security-policy.txt` as `Content-Security-Policy` on every response, and in headless system Chrome
    (Playwright from `tests/e2e`, `channel: 'chrome'`) open a scratch page that imports the emitted
    `assets/pdfjsRuntime-<hash>.js`, opens the three fixture PDFs through its `getDocument`, and draws page 1 of each to a
    canvas. Trusted Types are enforced by that policy: the page creates the `default` policy the app's would (same-origin
    script URLs only). Each image draws (its pixels as the test predicts), the console holds no policy violation, and the
    network shows each decoder fetched once from `assets/pdfjs-<version>/decoders/`. The harness stays out of the repo; the
    result is recorded in phase 2.

  **Done when:** `pnpm run check` in `src/web/` is green, every bite above was seen RED and green again, and the browser
  pass holds.

- [ ] **2 docs — every reader says scans draw.**
  - **Locator:**
    `git grep -n -i -E "jbig2|jpeg ?2000|jpx|ccitt|usewasm|wasmurl|nowasm|#837|scanned|scans\b" -- docs src/web site/src/content ':!docs/inbox.md' ':!docs/decisions/0128-*'`
    and `git grep -n -i -E "gaps|Lücken" -- site/src/content/docs/depot site/src/content/docs/de/depot`. Every hit is
    either corrected or spared by name in the commit message.
  - **Which statements change** (as measured at refine; the locator is the authority):
    - `cc.security.csp.md` § pdf.js: the WebAssembly bullet becomes the decoders' — what they are, `decoders/`, pinned by
      `pdfjs-dist`, no WebAssembly (#837); the no-code-from-strings and no-network bullets name the decoders; the threat
      note's containment sentence covers them; the verification paragraph records the browser pass (date, what it showed);
    - `docs/apps/depot.md` § The viewer (the guard's list, "What is switched off, and why", Limits and follow-ups) and the
      deferred-list line on the WASM image decoders;
    - `uc.depot.view-file.md` A1: a scanned page draws; what stays is ICC colour (#837);
    - `docs/coverage/features/depot/use-cases/uc.depot.view-file.yaml`: the `pdf-js-stays-inside-the-content-security-policy`
      claim's text, and the decode tests cited;
    - `src/web/CLAUDE.md` (the pdf.js option paragraph), the comments in `pdfOptions.ts`, `vite.config.ts` and
      `assert-pdf-worker-in-dist.mjs` that phase 1 did not already rewrite;
    - ADR 0127's consequence that names pdf.js's decoders "whose absence leaves scanned pages blank": it names #837 and
      ADR 0128 instead;
    - the published docs, both languages: `site/src/content/docs/depot/view-a-file.mdx` ("Scanned pages in some formats may
      show gaps") and `site/src/content/docs/de/depot/view-a-file.mdx` ("Gescannte Seiten in manchen Formaten …") — the
      sentence goes, nothing replaces it.
  - **ADR 0128** → `Built: yes`. **Inbox:** #836 is deleted (a done row); #837 keeps only what stays open (speed, ICC
    colour, a quiet-machine measurement), its provenance question answered by ADR 0128's consequence.

  **Done when:** the trust chain (`for f in scripts/validate_*.py; do /usr/bin/python3 "$f"; done`) and
  `pnpm run check` in `site/` are green.

## Definition of done

- The `Done when` of both phases holds.
- `pnpm run check` is green in `src/web/` and `site/`; the trust chain is green, its baseline taken before the first edit
  on a detached worktree of `HEAD` (`changes/README.md`).
- `docs/coverage/` cites the decode tests.
- No owner step is needed: no workflow, no permission, no download (the fixtures are made locally), no change to the
  policy. Lane L1 is not part of this CR (the heavy suites run once at the end of V1); the browser pass stands in for it.

## Retrospective

<!-- Filled by the implementer. -->
