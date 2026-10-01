---
id: cr-derive-media-tiers-in-a-worker
title: the image pixel stage runs in a Worker, so an upload in a backgrounded tab stops waiting about a second per JPEG encode
status: planned
satisfies: ["uc.memory.upload", "en.memory.derivatives"]
invariants:
  - "operator-blind — untouched. The Worker is a same-origin module Worker in the user's own browser. It receives the picked Blob and hands back derivative bytes to the page, which seals them exactly as today. No pixel, byte or dimension goes anywhere new, and no phase edits `sealFrame`, `sealMetadata`, `planBlob` or the wire shape."
  - "derivative output is unchanged by construction — the Worker runs the same `drawEncoded` / progressive-halving code as the page thread (phase 1 makes it one module both import), on `OffscreenCanvas` + `convertToBlob` instead of `HTMLCanvasElement` + `toBlob`. Tier sizes, qualities, the ≤2× halving rule and the preview→thumbnail order ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md) § The three tiers) do not move."
  - "the Worker is an optimisation, never a requirement ([argonWorker.ts](../packages/crypto/src/argonWorker.ts) is the precedent). No Worker, no `OffscreenCanvas`, a Worker that fails to load or dies mid-request: the request finishes on today's page-thread path. An undecodable source still uploads original-only (uc.memory.upload E4) — a decode failure inside the Worker is a *source* failure, not an infrastructure failure, and must not be retried on the page thread."
  - "the wire stays strictly serial and the queue stays one file at a time — this CR moves WHERE a file's pixel stage runs, not WHEN. Nothing here overlaps files (that is the closed phase 3 of `cr-upload-prepare-visibly-and-decode-once`; see Open question 5)."
  - "no contract change — `contracts/` and `backend/` are untouched (a `git status` proves it)."
---

## Open for the owner

**Fourth round answered by the owner, 2026-10-01: every recommendation added since the third answer is taken too** (incl. the granter-lock questions Q1–Q3 and the rotation CR's Q8/Q9).

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

Each question has a recommendation. Nothing below phase 0 starts until they are answered.

1. **Lever (a), the Worker, or a newly measured lever (c): swap `toBlob` for the synchronous `toDataURL` on the
   page thread?** Both remove the same stall (see Inventory). (c) is about five lines in `drawEncoded`. It
   encodes synchronously on the page thread, measured at 15–59 ms for a 1536×2048 preview in the preview
   Chromium, and it carries a base64 round trip. That cost lands on every device, weak phones included, where it
   is unmeasured and likely several times larger. (a) moves decode, draw and encode off the page thread
   entirely, and it is the substrate for preparing files in parallel later (a pool of 4 Workers prepared 12
   photos in 333 ms vs 1 186 ms for one, measured). **Recommendation: (a).** Choose (c) if a small diff matters
   more than keeping sync encodes off weak devices. Phase 1 is the same either way, and the (c) route replaces
   phase 2 with a single-site change plus the same measurement.
2. **Scope: does every `deriveImage` caller go through the Worker, or only the batch upload?** The callers
   are the member upload, the contributor upload, the standalone cover and the space icon. The icon calls
   `deriveImage` in a quality-lowering loop, so it pays the stall once per iteration in a backgrounded tab.
   **Recommendation: all of them, through the one factory.** `createBrowserDeriver().deriveImage` becomes
   Worker-first, so no call site changes and no second code path exists to drift.
3. **Lever (b), the tile's own dimension probe: drop it from #141?** Measured at the pick moment, 12 tiles
   plus 12 probes against none: file 1's pixel stage finished at 406 vs 82 ms (headless Chrome 153, visible),
   689 vs 397 ms (WebKit 18.4, visible) and 2 081 vs 2 060 ms (preview Chromium, hidden). So (b) is worth about
   0.3 s once per batch on pick → first PUT, nothing per file, and nothing in a backgrounded tab. It costs an
   emit through `OptimisticTile` → `useOptimisticTiles` → both surfaces (2 hops in `ShareView`, 3 in
   `MemoryDetail`). **Recommendation: drop it, with the numbers recorded in the row.** Not worth the plumbing
   while the per-file stall is 2 s.
4. **Video posters stay on the page thread and keep the stall: one encode, about 1 s per video in a
   backgrounded Chromium tab.** `deriveVideoPoster` needs a `<video>` element, which a Worker does not have.
   Routing the drawn frame to the Worker (`createImageBitmap(video)` → transfer) would remove it, but it touches
   the Safari-fragile poster path (`firstPresentedFrame`, `VIDEO_EVENT_TIMEOUT_MS`). **Recommendation: accept it
   for now.** Next to a video's multi-MB wire time, 1 s is small. It is named here, not hidden.
5. **Re-open the closed prepare-ahead overlap afterwards?** The 2026-09-20 run closed it because the wire was
   7 % of the batch. If this CR's after-measurement lands where the Inventory predicts, the wire becomes most of
   a backgrounded batch, and the overlap's ceiling (min(prep, wire) per file) becomes real. **Recommendation:
   decide it on this CR's after-measurement and nothing earlier.** The DoD records the wire share for exactly
   that purpose.

## Goal

A 12-photo batch took 27.3 s on 2026-09-20, and inbox #141 attributed the 93 % not spent on the wire to
"main-thread decode/downscale/seal". **This refine measured that attribution and found it wrong.** In a visible
tab the whole image pixel stage costs **52 ms per photo** in Chrome and **71 ms** in WebKit, so 12 photos need
about 0.6–0.9 s of it, not 25 s. The 25 s comes from **waiting**, not computing. On the page thread,
Chromium's `canvas.toBlob`, and `OffscreenCanvas.convertToBlob` alike, **waits for an idle period** before
encoding. A tab that is not being painted never gets one, so every encode falls through to a fallback about
**1 s** later. That holds for a backgrounded tab, a tab in an occluded window, and the automation pane the
measurement was taken in. Two encodes per photo (preview, thumbnail) make about 2 s of pure wait per photo.
In a Worker the same encode takes 48 ms and 7 ms.

That is also the realistic way a batch upload is used: pick 200 photos, then switch to something else while
they go. Today that costs about 2 s per photo of idle time, roughly 7 minutes for 200, before any network
cost. This CR moves the image pixel stage into a module Worker, where encoding does not wait on the page's
paint cycle. It does not make a visible tab faster, and it does not claim to: there the measured difference
is noise (52 vs 53 ms per photo).

## Context & constraints

- **[uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md)** owns the flow.
  **[en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)** owns the tier specs,
  the halving rule and the decode-once order. This CR changes where those run and nothing about what they
  produce. The coverage criterion `original-decoded-once-per-upload` stays true and its test is untouched,
  because `media.test.ts` injects a stub deriver and never reaches the browser deriver.
- **The precedent is [argonWorker.ts](../packages/crypto/src/argonWorker.ts) + [argon.worker.ts](../packages/crypto/src/argon.worker.ts)**:
  a lazily created module Worker, a `workerUnavailable` latch, a per-request fallback closure, `postMessage`
  failure handling, and a test file ([argonWorker.test.ts](../packages/crypto/src/argonWorker.test.ts)) that
  proves the fallback and the degradation with a `DyingWorker` stub. Copy its **shape**, not its error
  handling. Argon has one failure class, and this CR has two (see Traps).
- **CSP already admits it.** `worker-src 'self'` ([csp.ts](../web/src/shared/security/csp.ts)) covers any
  same-origin module Worker. Only the comment that names the Argon worker as *the* worker needs the second one.
- **Out of scope, named:** (b) the tile probe (Open question 3), the video poster (question 4), overlap or
  parallel preparation (question 5), and the tile's full-resolution `<img>` (the "fifth decode" declined by the
  predecessor CR, still a product question about the instant preview).

### Substrate

Refined 2026-09-28 against `main` @ `faf9ef4`, worktree clean. Line numbers drift, so **re-read the
surrounding code rather than trusting a number.** Ten refines ran in parallel that day. Before the first edit,
run `ls changes/` and `git log --oneline -10 -- web/src/features/memory/` and re-read anything that landed on
`mediaDeriver.ts`, `media.ts` or `share.ts`. #142, which the 2026-09-20 refine sequenced this after, is **done**,
so that hazard is gone.

### Inventory — measured

**Pixel stage per photo, 12 synthetic 3024×4032 JPEGs.** The bench was a logic-verbatim copy of
`drawEncoded` / `drawStep` / `scaleToMaxEdge` / `deriveTiers`' image branch, parameterised over the canvas
kind. It was run by this refine on an M-series Mac with 14 cores and lives in the refine's scratch, not the
repo; the file sizes each engine's generator produced are given per row.

| Engine, tab state | Page thread today, per photo | Worker (`OffscreenCanvas`), per photo | 12 photos serial, page → Worker |
|---|---|---|---|
| preview Chromium 152, **hidden** (~366 KB/photo) | **2 097 ms**: decode 20, preview encode **1 058**, preview re-decode 6, thumb draw 5, thumb encode **1 006** | **89 ms**: decode 19, preview encode 48, thumb encode 7 | **25 165 → 1 186 ms** |
| headless Chrome 153, visible | 52 ms: decode 15, preview encode 24, re-decode 5, thumb 4+2 | 53 ms | 621 → 637 ms |
| Playwright WebKit 18.4, visible (~919 KB/photo) | 71 ms: decode 33, preview encode 22, re-decode 11, thumb encode 3 | 78 ms | 857 → 948 ms |

**The stall is scheduling, not work.** Measured in the preview Chromium, hidden:

| Probe | Result |
|---|---|
| `toBlob` of a **1×1** canvas | 1 001–1 003 ms |
| `OffscreenCanvas.convertToBlob` on the **page** thread (1×1 / full pipeline) | 1 004–1 016 ms / 2 062–2 134 ms per photo, so a page-thread `OffscreenCanvas` does **not** avoid it |
| `toDataURL('image/jpeg', 0.85)` of a 1536×2048 canvas, synchronous | 15–59 ms, no stall (lever (c)) |
| Worker pool, 12 photos: k=1 / k=2 / k=4 | 1 186 / 561 / 333 ms |
| Page-thread long tasks (> 50 ms) during the 25 s serial run | 124 ms total, so the page thread was idle-waiting, not busy |
| 12 tile probes alone (`createImageBitmap` of each original) | 59 ms (69 ms visible Chrome; 475 ms WebKit) |

**Other on-device costs, for scale.** Node 23 on the same Mac, with the repo's own dependencies: a 256 KiB frame
seal (`@noble/ciphers` AES-GCM) takes 3.2 ms (78 MiB/s), so a photo's 3–5 frames take ~15 ms. `exifr.parse`
with `EXIF_PARSE_OPTIONS` takes 0.08 ms on the 1.6 KB fixture. That is a lower bound, since a real JPEG's APP1
segment is larger, but it is not seconds.

**What this says about the 2026-09-20 number** (*inferred, strongly*): (27 335 − 1 862) / 12 = **2 123 ms per
photo off the wire**, against a **2 097 ms** hidden-tab pixel stage measured here. The original run was almost
certainly taken in a hidden tab. The automation pane reports `document.visibilityState === 'hidden'` even
when fronted, a known quirk of that tooling. So the row's measurement was real, but it measured the
backgrounded case, not the visible one. In a visible tab the same batch would be about 12 × (~0.07 s on device
+ ~0.16 s on the wire + control plane), a few seconds. That is unmeasured against the dev backend, which is why
phase 0 exists.

**Not measured, and what would decide it:**
- **Desktop Chrome with the tab backgrounded or the window occluded.** The ~1 s floor is measured in the
  preview Chromium 152 (same Blink). That real Chrome shows it is *inferred*: the idle-task start timeout in
  Blink's async blob creator is *remembered*, not read. Phase 0 decides it. A headless-Chrome background-tab
  emulation was tried and discarded: it reported `visible` and stalled one `toBlob` for 34.8 s, which is not a
  usable substrate.
- **Safari and Firefox with the tab backgrounded.** Unknown. They decide *how many users* the gain reaches,
  not whether (a) beats (b). Phase 0 measures Safari if the owner has it at hand. iOS Safari suspends a
  backgrounded page outright, so neither thread runs there and this CR changes nothing on iOS.
- **Weak devices.** Every number above is from a 14-core desktop.

### Inventory — locators

Run these; do not trust a count copied from here.

| Question | Command |
|---|---|
| Every canvas encode on the page thread (the stall sites) | `grep -rn "toBlob\|convertToBlob\|toDataURL" web/src --include="*.ts" --include="*.vue" \| grep -v "\.test\."` |
| Every constructor of the real deriver (who inherits the Worker) | `grep -rn "createBrowserDeriver" web/src \| grep -v "\.test\."` |
| Every `deriveImage` call (the Worker's actual traffic) | `grep -rn "\.deriveImage(" web/src --include="*.ts" --include="*.vue" \| grep -v "\.test\."` |
| DOM reads inside the pixel code that a Worker module must not contain | `grep -n "document\.\|HTMLCanvasElement\|HTMLVideoElement" web/src/features/memory/mediaDeriver.ts` |
| Every existing Worker, and the CSP line and comment naming them | `grep -rn "new Worker(" web/src packages/*/src; grep -n "worker-src" web/src/shared/security/csp.ts` |
| The web unit-test environment: the default is node, but 81 files opt into happy-dom per file, and whether happy-dom defines `Worker` is **not measured** | `sed -n '/test: {/,/},/p' web/vite.config.ts; grep -rh "@vitest-environment" web/src \| sort \| uniq -c` |
| Claims about where derivation runs, in docs | `grep -rn "main thread\|on the client\|decode → downscale\|Worker" docs/features/memory/enablers/en.memory.derivatives.md docs/features/memory/use-cases/uc.memory.upload.md docs/crosscutting/cc.security.csp.md` |

The two angles on "who reaches the Worker" disagree, which is the point of running both. `createBrowserDeriver`
has more constructors than there are `deriveImage` callers: `useOptimisticTiles` constructs one but only probes
and makes posters. That is why the Worker must be created **lazily on the first `deriveImage`** and held at
module level, not per deriver instance, or opening a gallery would spawn a Worker for nothing.

The first locator also returns two sites this CR leaves alone: `spaceIconImage.ts`'s square-crop `toBlob`,
which runs in an interactive, visible dialog, and `QRCode.toDataURL`, a library call unrelated to media.

**Spot-check the row the argument leans on before phase 2:** the first locator must list `drawEncoded`'s
`toBlob` in `mediaDeriver.ts`. If the encode has moved or changed shape since refine, re-derive the stall
before building a Worker around it.

### Traps

- **Two failure classes, not one.** `argonWorker` retries every worker-side failure on the page thread,
  because Argon cannot fail on its input. A media decode can. An HEIC off Safari, an empty MIME type, or corrupt
  bytes make `createImageBitmap` throw in the Worker exactly as it would on the page. Retrying that on the page
  thread doubles the cost of every underivable file and hides nothing. So the Worker answers
  `{ ok: false, kind: 'source' }` for a decode or encode failure (the promise rejects, and `deriveTiers`' catch
  makes it original-only, as today) and `{ ok: false, kind: 'infra' }` for a missing `OffscreenCanvas` or 2D
  context (latch, then finish on the page thread). A worker `error` event or a throwing `postMessage` is infra.
- **A Worker that never loads is invisible.** The latch-and-fallback shape means a mis-bundled worker URL or a
  CSP refusal degrades silently to today's behaviour, and every unit test stays green, because the unit tests
  run *only* the fallback. The live after-measurement is the only proof the Worker ran, and it is a sharp one:
  in a backgrounded Chromium tab the fallback **cannot** go below about 2 s per photo. A per-photo figure under
  about 300 ms is therefore proof of the Worker path. Say so in the retrospective, with the number.
- **`mimeType` and `background` must cross the message.** The space icon passes `mimeType: 'image/webp'` and
  relies on alpha. A `background` fill applies only to the final canvas. A Worker request that forwards only
  `{maxEdge, quality}` would silently JPEG-flatten every icon. The message carries the whole `opts` object.
- **Transfer, don't copy, the result.** The derivative bytes come back as a transferred `ArrayBuffer`. The
  source goes in as a `Blob`, which is cloned by reference, so the original's bytes are never copied into the
  Worker.
- **`drawStep` calls `document.createElement` at use, not at import**, so the shared pixel module may be
  imported by the Worker as long as nothing DOM-touching runs at module top level. The fourth locator is the
  check.

## Decisions

1. **(a) over (b), on the numbers.** Per photo, (a) removes about 2 s in a backgrounded Chromium tab (measured
   2 097 → 89 ms) and nothing in a visible one. (b) removes about 0.3 s once per batch in a visible tab and
   nothing per file. The 2026-09-20 refine ranked (a) first because "only (a) reaches the two encodes". It
   reaches them, but not because they are CPU-heavy: the Worker's encode does not wait for a paint that never
   comes. The CR says so, so nobody later expects a visible-tab speed-up from it.
2. **Worker-first inside `createBrowserDeriver().deriveImage`**, with a module-level lazy Worker, a
   `workerUnavailable` latch, and a per-request fallback to today's page-thread implementation (Open question 2).
   `deriveVideoPoster` and `probeDimensions` stay on the page thread.
3. **One pixel module, two canvas ports.** `drawEncoded` / `drawStep` take a port
   `{ create(w, h), encode(canvas, type, quality) }`. The page port is `HTMLCanvasElement` + `toBlob`, exactly
   today. The Worker port is `OffscreenCanvas` + `convertToBlob`. The halving arithmetic becomes a pure,
   exported `halvingPlan(w, h, maxEdge)` that both ports execute, so the one rule has one implementation.
4. **One Worker, serial.** The queue is serial, so one request is in flight at a time. A pool is not built here
   (Open question 5).
5. **No permanent guard.** The property is placement plus degradation. The degradation is pinned by unit
   bites, and placement can only be proven live (Traps). A grep-guard for "no `toBlob` in `mediaDeriver.ts`"
   would be wrong: the page port legitimately keeps it.

## Phases

- [x] 0 measure — **the before-number, and the entry gate.** No code. Use a fixed batch of 12 photos at
      3024×4032 against the dev backend with S3, as on 2026-09-20. Pick in **desktop Chrome**, twice: (i) the tab
      stays visible, (ii) switch to another tab right after the pick and come back when the tray is done. Per
      run, record pick → first PUT, batch total, and time on the wire: the sum of `PUT` durations to the store
      origin from `performance.getEntriesByType('resource')`, because the devtools network list of the automation
      pane hides cross-origin requests. Repeat (ii) in Safari if at hand. Write all numbers into the
      retrospective. **Gate, phrased against what phase 2 moves** (the page-thread encode wait): if run (ii)'s
      batch total minus its wire time is under 12 × 0.5 s, then desktop Chrome does not show the stall, the
      Worker has no wall-time payoff, and the CR **stops here** and reports to the owner. That is a legitimate
      outcome, not a failure. · Done when: both runs recorded, and the gate decided in writing.

- [x] 1 web — **one pixel module, two ports (behaviour-preserving refactor).** Extract
      `halvingPlan(w, h, maxEdge): { steps: {w,h}[]; final: {w,h} }` from `drawEncoded`'s loop, and make
      `drawEncoded` / `drawStep` take the canvas port (Decision 3). The page deriver passes today's
      `HTMLCanvasElement` + `toBlob` port, so no runtime behaviour changes. ·
      **Bite (refactor lever, B1):** a pure test pins `halvingPlan` on the three geometries the tiers actually
      see: the preview of a 3024×4032 original (no step, final 1536×2048); the thumbnail from that preview (one
      step 768×1024, final 384×512); and a direct 4032×3024 → 512 (steps 2016×1512 and 1008×756, final
      512×384). Lever 1: change the loop's `>` to `>=`. The thumbnail case gains a 384×512 step and B1 reddens.
      Lever 2: drop the `* 2`. The loop then halves all the way down to the target, so the preview case gains a
      1536×2048 step and B1 reddens. Both levers edit the
      extracted function, so B1 guards the rule the doc pins rather than a copy of it. · Done when:
      `npm run check` green in `web/`, B1 green and red under each lever, and no runtime behaviour changed (the
      page port is the old code path).

- [x] 2 web — **the Worker, Worker-first behind the one factory.** Add `mediaDerive.worker.ts` next to
      `mediaDeriver.ts`: it imports the pixel module, answers `{id, ok, bytes (transferred), source}` or
      `{id, ok: false, kind: 'source' | 'infra', error}`, and feature-detects `OffscreenCanvas` and its 2D
      context per request, answering `infra` if either is absent. `createBrowserDeriver().deriveImage` dispatches
      to a lazily created module-level Worker (the `new URL('./mediaDerive.worker.ts', import.meta.url)` form the
      Argon worker uses). It latches and finishes on the page port on `infra`, a worker `error` event, or a
      throwing `postMessage`, and rejects on `source`. Update the CSP comment to name both workers. The new tests set or remove the `Worker`
      global themselves with `vi.stubGlobal` (argonWorker.test.ts's pattern), and never rely on the environment
      lacking one. `media.test.ts` injects its own stub deriver and is unaffected. ·
      **Bite (B2, fallback carries the whole request):** with no `Worker` global, `deriveImage(blob, opts)`
      reaches the page-thread implementation with `opts` intact, including `mimeType` and `background`. Lever:
      forward only `{maxEdge, quality}`, and B2 reddens. ·
      **Bite (B3, degradation and latch):** a `DyingWorker` stub (argonWorker.test.ts's shape) fires `error` on
      the first `postMessage`. That request resolves on the fallback, and a second request constructs **no** new
      Worker. Lever: drop the latch assignment, and the constructor count goes to 2 and B3 reddens. ·
      **Bite (B4, a source failure is not retried):** a stub Worker answers `{ok: false, kind: 'source'}`.
      `deriveImage` rejects, the fallback is called **zero** times, and through `deriveTiers` the upload is
      original-only (`derivatives: []`). Lever: route every `ok: false` to the fallback, and the fallback count
      goes to 1 and B4 reddens. ·
      **Bite (B5, infra is retried):** a stub answers `{ok: false, kind: 'infra'}`. The request resolves on the
      fallback and the latch is set. Lever: treat `infra` like `source`, and the promise rejects and B5 reddens.
      B4 and B5 are the pair that tells the two classes apart. Either alone is satisfied by a front door that
      handles only one class. · Done when: `npm run check` green, B2–B5 green and each red under its lever,
      `contracts/` and `backend/` untouched.

- [ ] 3 measure + docs + coverage — **the after-number, and the claims.** Repeat phase 0's two runs (and
      Safari if it was measured) on the same batch and substrate, and record them beside the before-numbers. The
      **expected** shape, *inferred* from the Inventory: (ii) backgrounded drops from ≥ 24 s towards
      12 × (≈ 0.1 s + wire + control plane), and (i) visible moves within noise. **A visible-tab regression over
      10 % is a finding**, not noise to wave through, because Worker start-up is the only new cost and it is paid
      once. State in the retrospective that (ii)'s per-photo figure is the Worker-liveness proof (Traps).
      Docs: in `en.memory.derivatives.md` § *Generation & attachment*, add one paragraph on where the pixel stage
      runs, why (the paint-gated encode, with the measured numbers), and that it degrades to the page thread. In
      `uc.memory.upload`'s coverage file, add a criterion under `docs/coverage/features/memory/use-cases/`, e.g.
      `pixel-stage-off-the-page-thread`: *"the image pixel stage runs in a Worker where the browser offers one;
      a Worker that cannot run finishes the request on the page thread; an undecodable source still uploads
      original-only without a page-thread retry"*, with the phase-2 test file as its ref. Record the wire share of
      run (ii) in the #141 row as the number Open question 5 is decided on. · **Re-run the docs locator at
      this phase**, since phases 1–2 wrote new comments. · Done when: after-numbers recorded,
      `python3 scripts/validate_coverage.py` green, and the docs locator returns no sentence that still places
      the pixel stage on the page thread without the Worker.

## Definition of done

**Baseline first.** Before the first edit, run `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`
and `ls scripts/validate_*.py | wc -l`. A red afterwards is then attributable to this CR. Paste the count
from that command, not from here. After phase 3: every guard green, `npm run check` green in `web/`, and
`git status -- contracts backend` empty. B1–B5 are green, and each was seen red under its named lever.

**The measurement is the deliverable.** Record both runs before and after (batch total, pick → first PUT, wire
time, wire share) in the retrospective and in the #141 row. The CR is done when the backgrounded run shows the
per-photo stall gone and the visible run shows no regression, or when phase 0's gate stopped it with its
numbers written down.

## Retrospective

### Measurements

**Substrate (all rows, measured):** a separate **desktop Google Chrome 154.0.8037.58** instance with its own
throw-away profile, driven over CDP, on an Apple M4 Pro (14 cores), macOS. The page bundles the **real**
`deriveTiers` + `createBrowserDeriver` (the before-row from `a706db7`'s `mediaDeriver.ts`, the after-row from
the worktree's), served from a loopback HTTP origin, and derives 12 synthetic 3024×4032 JPEGs (≈1.86 MB each,
seeded, identical across runs) serially, as the queue does. **Pixel stage only — no backend, no seal, no wire.**
The dev-backend run phase 0 names (pick → first PUT, wire time) was **not** taken: the implement brief forbade
touching the owner's dev instance. So every figure below is the pixel stage alone, which is a **lower bound** on
"batch total minus wire". Safari: not measured. "Background tab" = another tab activated in the same window
(`document.visibilityState === 'hidden'` confirmed per photo); "minimized" = the window minimized via CDP.

| Phase | Tab state | 12 photos, serial (ms) | per photo |
|---|---|---|---|
| 0 (before) | visible | 828, 774, 799, 833, 882 | ≈ 65–73 ms |
| 0 (before) | background tab | 7 812, 6 543, 6 870, 7 957, 7 936 | ≈ 545–663 ms |
| 0 (before) | minimized window | 6 968, 8 892 | ≈ 580–741 ms |

Encode probes in the same pages (page-thread `toBlob`, 4 calls each): visible, a 1536×2048 JPEG 14–27 ms and a
384×512 one 1–24 ms; hidden, 94–1 305 ms (one 6 819 ms outlier) and 7–1 185 ms. A 1×1 `toBlob` took 0–7 ms
hidden. So **desktop Chrome 154 does stall, but not at the preview Chromium's fixed ~1 s floor**: the wait per
encode varies between ~0.1 s and ~1.2 s from run to run, and per photo it costs ≈ 0.5–0.7 s, not ≈ 2 s.

**Gate (phase 0), decided:** run (ii)'s pixel stage alone is 6.5–8.9 s over five hidden runs, every one above
12 × 0.5 s = 6.0 s, and the full batch minus its wire can only be larger. **Proceed.** The margin is narrow
(8–48 %), and the payoff in desktop Chrome is ≈ 0.5–0.7 s per photo — about a quarter of what the Inventory
predicted from the preview Chromium.
