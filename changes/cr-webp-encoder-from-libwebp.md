---
id: cr-webp-encoder-from-libwebp
title: Encode space icons and member pictures with our own WebAssembly build of libwebp
status: in-progress
satisfies: [uc.spaces.set-profile-picture, uc.spaces.set-space-icon]
adrs: ["0127", "0084", "0117", "0065"]
invariants:
  - the CSP gains exactly `'wasm-unsafe-eval'` in `script-src` of the web client; never `'unsafe-eval'`, no other source, the admin web unchanged (ADR 0127 decision 1)
  - the WASM bytes in the client are the bytes CI rebuilds from the pinned libwebp tag and image digest; a mismatch is red, never waved through (decision 2)
  - every browser encodes with this encoder; the canvas never encodes WebP and no other format is stored (decision 3)
  - the encoder's directory imports nothing of veyq (decision 5)
  - an icon or a picture stays a 256 px WebP padded to 16 KiB; the pad, the seal and the contracts do not change (ADR 0084)
---

## Goal

Safari answers the canvas's `'image/webp'` with a PNG, which never fits the 16 KiB pad, so setting a photo as one's
picture or as a space icon fails there ("the image could not be prepared"). After this CR the client encodes WebP with
libwebp itself — compiled by us to WebAssembly from a pinned release tag, rebuilt and compared in CI, offered as a pull
request when libwebp releases a newer tag — in every browser alike. The CSP grants `'wasm-unsafe-eval'` for it. Why this
and not a fallback, a larger pad or an npm package: [ADR 0127](../docs/decisions/0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md).

## Context & constraints

- The pixel stage: `src/web/src/features/spaces/spaceIconImage.ts` (`renderSpaceIcon`: bake the 1:1 crop, then
  `deriveImage` with `mimeType: 'image/webp'` down a quality ladder until the bytes fit). Its callers are
  `ProfilePictureDialog.vue` and `SpaceIconDialog.vue`. `deriveImage` is Worker-first (`shared/image/mediaDeriveWorker.ts`,
  `mediaDerive.worker.ts`), with the page path as fallback (`mediaDeriver.ts`). Both paths end in `drawEncoded`
  (`shared/image/mediaPixels.ts`), whose last step is `port.encode`. That is where the WebP encode is replaced.
- The CSP: `src/web/src/shared/security/csp.ts` `buildCsp()`, emitted by `vite.config.ts`'s `cspPlugin` as
  `dist/content-security-policy.txt`. The backend sends it on every shell response (`AppShell.kt`, `AppShellRoutes.kt`).
  The model doc is [cc.security.csp](../docs/crosscutting/cc.security.csp.md).
- Pinned-input precedent: `scripts/openapi_generator_jar.sha256` + `scripts/verify_openapi_generator_jar.mjs`, held by
  `scripts/validate_build_input_pins.py`. Image digests are held by `scripts/validate_image_pins.py` and kept current by
  `.github/dependabot.yml` (docker ecosystem, weekly, Saturday).
- Licence notices: `dist/third-party-licenses.txt` is Vite's `build.license`, which lists npm packages only.
  `assert-license-sources-in-dist.mjs` requires each heading to carry an npm `Source:` line, so libwebp needs its own
  entry and a check that knows that entry's form.
- Tests run in Node unless a file opts into `happy-dom`. A Node suite can read a `.wasm` from disk and instantiate it.
  There is no canvas there, so pixels come in as RGBA.
- Measured at refine (2026-11-12): Safari 27.2's `toBlob(…, 'image/webp')` answers `image/png` (the owner, Safari's
  console). libwebp publishes tags only, no GitHub releases (`gh api repos/webmproject/libwebp/releases/latest` → 404;
  `v1.6.0` is commit `4fa2191233`, 2025-07-08). Emscripten's `DYNAMIC_EXECUTION` setting exists (`settings.js`, `=0`
  forbids `eval`/`new Function`). The admin web encodes no image (`git grep -lE "toBlob|convertToBlob|image/webp|getContext\('2d'\)" -- src/admin-web/src` → none).

## Phases

- [x] **1 encoder — the self-contained directory.** One directory, `src/web/webp-encoder/` (not a workspace member, ADR
  0127 decision 5), holding:
  - the build: a Dockerfile `FROM` a digest-pinned Emscripten image, and a Python build script that fetches the pinned
    libwebp tag, checks its commit, and compiles the **encoder only** with `-sDYNAMIC_EXECUTION=0`, `-sFILESYSTEM=0`, no
    pthreads, `-sMODULARIZE -sEXPORT_ES6`, at fixed paths inside the container;
  - the pin file: tag, commit, image digest, and the SHA-256 of each output, in the form `validate_build_input_pins.py`
    can hold;
  - the committed output (`.wasm` + glue);
  - a typed API: `encodeWebp(rgba, width, height, quality) → Uint8Array`, loading the module from bytes it is handed or
    fetches, never by streaming;
  - libwebp's `COPYING` and `PATENTS`, and a README of what it is and how to rebuild.

  Tests (Node), each asserting on the output's bytes:
  - a RIFF/WEBP header;
  - a lossy `VP8 ` chunk for opaque input, and `VP8X` + `ALPH` for input with transparency;
  - size falls as quality falls;
  - the decoded size equals the input size;
  - a photo-like fixture of 256 px fits 16,380 bytes at quality 0.9. The fixture is generated or licence-clean, never a
    user's picture, and is read as RGBA from disk.

  **Done when:** the build script, run twice from a clean checkout, writes identical bytes matching the pin file, and the
  tests are green. If two builds differ, hand back with the diff (ADR 0127 Consequences: the check is not dropped).
  Bite the size test by encoding at quality 1.0 lossless.
- [ ] **2 web — the encoder replaces the canvas WebP, and the policy grants it.**
  - **The encode:** `drawEncoded` hands the final canvas's `ImageData` to `encodeWebp` whenever `mimeType` is
    `'image/webp'`, in the Worker and on the page alike. The JPEG paths (covers, derivatives) stay on the canvas.
  - **Loading:** the `.wasm` reaches `dist/` as a hashed same-origin asset, fetched as bytes.
  - **Failure:** an encoder that fails to load, or throws, rejects the derive; the dialog shows `profilePicture.failed` /
    the icon's message.
  - **`renderSpaceIcon`** keeps its quality ladder. With the canvas gone from the WebP path, nothing returns another
    format.
  - **CSP:** `buildCsp()` adds `'wasm-unsafe-eval'` to `script-src`. `csp.test.ts` asserts exactly that source. Its
    `/script-src[^;]*unsafe-eval/` refusal (l.73) matches the new source and is rewritten to refuse `'unsafe-eval'` alone;
    bite it by adding `'unsafe-eval'`. `assert-csp-policy-in-dist.mjs` asserts the same on `dist/`, with the same bite.
  - **`assert-pdf-worker-in-dist.mjs` (v)** refuses any `.wasm` in `dist/`. Narrow it to pdf.js's `wasm/` and decoders;
    the encoder's asset is named and allowed. Bite: copy a pdf.js `.wasm` into `dist/` and see it red. pdf.js stays
    `useWasm: false` (ADR 0127 Consequences).
  - **Licence:** the built licence file carries libwebp's notice with its tag as the source, and
    `assert-license-sources-in-dist.mjs` accepts exactly that form for it.
  - **Tests:** `mediaPixels` / `mediaDeriver` / `mediaDeriveWorker` tests prove the WebP request reaches `encodeWebp` and
    never `port.encode`. Bite: route it back to `port.encode`.

  **Done when:**
  - `pnpm run check` in `src/web/` is green.
  - A browser pass sets a photo as one's picture and a transparent PNG logo as a space icon, in **Safari** (the owner's
    — an agent has no Safari; or Playwright WebKit, whose download needs the owner's go) and in Chromium (the pane). Each
    stored picture is shown, the logo keeps its transparency, and the console has no CSP violation.
  - Lane L1 (`scripts/run_stack_e2e.py`, the built policy, ADR 0117) is green.
- [ ] **3 automation — rebuild in CI, newer libwebp as a pull request.**
  - **Rebuild check:** a job in the checks pipeline, run when `src/web/webp-encoder/**` changes, rebuilds from the pin
    and fails on any byte difference.
  - **Update workflow:** a scheduled workflow, weekly and aligned with Dependabot's Saturday run, lists libwebp's stable
    `vX.Y.Z` tags (no `-rc`). On a newer one it rebuilds, runs phase 1's tests and opens a pull request with the new pin
    and output. It never merges.
    - **Its permissions** (`contents: write`, `pull-requests: write`, that workflow only) **need the owner's go in this
      session before the file lands.**
    - A pull request opened with `GITHUB_TOKEN` starts no other workflow. The CR's implementer decides how its checks
      run, and says so in the workflow's header comment.
  - **Dependabot:** add the encoder's Dockerfile directory to the docker ecosystem, so the Emscripten digest is kept
    current; `validate_image_pins.py` holds it pinned.

  **Done when:**
  - The rebuild job is green on the branch, and red when one byte of the committed `.wasm` is flipped (bite).
  - The update workflow, run once by `workflow_dispatch` with the pin set back to `v1.5.0`, opens the pull request to
    `v1.6.0` — the dispatch needs the owner's go.
- [x] **4 docs — the policy and its readers say what is true.**
  - **Edit in place** every claim that the client ships no WebAssembly or that the policy refuses it:
    `git grep -n -i -E "wasm|webassembly" -- docs src/web/src src/web/scripts src/web/vite.config.ts src/web/CLAUDE.md site/src/content ':!docs/decisions/0127-*'`.
    The hits that stay true are spared by name:
    - `src/web/CLAUDE.md`'s jsQR paragraph (a CDN wasm);
    - `site/…/hardening.md`'s Coraza line;
    - the inbox and coverage rows that only match on a test name.
  - **Which statements change:**
    - pdf.js's decoders stay off because they are not turned on (inbox #836), no longer because the policy refuses them
      (`cc.security.csp.md` § pdf.js, `docs/apps/depot.md`, `uc.depot.view-file` and its coverage, `pdfOptions.ts`,
      `vite.config.ts`, `src/web/CLAUDE.md`);
    - semantic search's deferral rests on the model's size alone (ADR 0125 l.136–137);
    - `csp.ts`'s header and `exifTags.ts`'s comment.
  - **The policy block and its `script-src` reasoning** in `cc.security.csp.md` name the grant and ADR 0127.
  - **`site/src/content/docs/run/hardening.md`** § TLS, HSTS and CSP says the policy lets the client compile its own
    WebAssembly (the picture encoder) and still refuses `eval`.
  - **Coverage:** the two use-cases' coverage YAMLs cite phase 1's and phase 2's tests, and narrow the
    `set-profile-picture` gap ("no test runs a real encoder") to what stays unrun: the crop and the bake.
  - **ADR 0127** → `Built: yes`.

  **Done when:** the trust chain (`for f in scripts/validate_*.py; do /usr/bin/python3 "$f"; done`) and
  `pnpm run check` in `site/` are green.

## Definition of done

- The `Done when` of every phase holds.
- `pnpm run check` is green in `src/web/` and `site/`.
- The trust chain is green, with its baseline taken before the first edit on a detached worktree of `HEAD`
  (`changes/README.md`).
- `docs/coverage/` is updated for both use-cases.
- The CR's structural invariant — the encoder imports nothing of veyq — is promoted to a check:
  - either the web import guard's style (a `validate_*.py` that is red on any import out of `src/web/webp-encoder/`
    other than its own files);
  - or a reason in the CR why a one-shot grep suffices.
- Approval-class steps, each the owner's in this session:
  - the update workflow's write permissions;
  - its first dispatch;
  - the Playwright WebKit download, if Safari is not passed by hand.

## Retrospective

Implement session, 2026-11-12. Phases 1 and 4 done; 2 and 3 done except what waits for the owner (§ hand-back in the
report: the update workflow's landing and dispatch, the Safari pass, lane L1).

- **Execution: lane L1 was planned as runnable here and is not.** `run_stack_e2e.py` hard-codes its admin port 18081,
  and on this Mac an ssh tunnel and a Python process of the owner's hold it, so preflight is red before anything starts
  (measured: `lsof -nP -iTCP:18081 -sTCP:LISTEN`). The images were built for it (`veyq:candidate-webp`,
  `veyq-admin:candidate-webp`, from `b5eb2aef2`). Root cause: the CR's done-when names L1 without naming the host state
  it needs (§ *a live proof that names a screen names the instance state that screen needs*, one level down: the ports).
  Next time: a CR whose done-when runs L1 says at refine whether 80/443/18081 are free on the machine that will run it.
- **Planning: the Chromium browser pass needs a signed-in session, and the CR did not say how an agent gets one.** The
  `?devlogin` path needs `.run/.env` in the worktree; copying it was refused by the session's classifier as credential
  materialization, rightly without the owner's say. So the pass ran in Chromium (the pane, Chrome 152) against this
  tree's dev server, on the real modules — `renderSpaceIcon` through the real media Worker, libwebp, the dev CSP — but
  not through the dialogs of a signed-in account; the stored picture's display was not seen. Next time: a browser pass in
  a worktree names its login route at refine (an owner-run `?devlogin`, or a seeded throwaway account).
- **Planning: three places in the CR leaned on what was not yet true or not quite meant.** "Bite the size test by
  encoding at quality 1.0 lossless" — the API the CR specifies has no lossless mode, so the bite encodes at quality 1.0
  lossy (29,680 bytes, red); "ADR 0127 → Built: yes" while decision 4's workflow waits for the owner's go (it is written
  `partly`); and "the decoded size" in a directory that holds no decoder is read from the bitstream's VP8/VP8X headers.
  Next time: a done-when that names a lever checks the lever exists in the API the same CR defines.
