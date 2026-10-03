# Enabler: Media derivatives — thumbnail / preview / original

- **ID:** en.memory.derivatives
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **refined** — ready to cut a CR. All four design points are settled (role-tagged blobs with
  **per-blob keys**, **no backfill**, **first-frame video poster**, the tier sizes); see *Decisions*.
- **Provides:** a **three-tier** view of each media item — a small **thumbnail** (the grid), a medium
  **preview** (the lightbox), and the **original** (download only) — each its own encrypted blob, generated
  **on the client** so the operator never sees a derivable pixel. Lets the gallery render fast and
  bandwidth-light while keeping the pristine original for download.
- **Serves (use-cases):** [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (grid = thumbnail, lightbox =
  preview, download = original); the **add-media** upload flow is where the derivatives are generated.
- **Depends on:** [en.memory.media](en.memory.media.md) — a derivative is **just another blob**, sealed by the
  exact same `K_blob`-under-`K_mem` chunked-AEAD transport; through it [en.memory.tree](en.memory.tree.md) +
  `uc.account.unlock` (identity → `K_SC` → `K_mem`).
- **Preconditions:** an **unlocked** vault; an **open Memory** whose `K_mem` is unwrapped; a configured object
  store — a **hard instance requirement** ([ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md),
  so a running instance always has one).
- **Postconditions (success):** for a media item, up to **three role-tagged blobs** (thumbnail, preview,
  original) exist in the store; the server holds only ciphertext chunks + the role tags + the `K_blob` wraps.
  No plaintext pixel, no content key.

## The three tiers

| role | used by | size / format | fetched when |
|------|---------|---------------------------------------------|--------------|
| **thumbnail** | gallery **grid** | JPEG, longest edge **512 px**, quality **0.80** | listing a Memory |
| **preview** | **lightbox** | JPEG, longest edge **2048 px**, quality **0.85** | opening an item |
| **original** | **download** | **pristine**, as uploaded (full res + metadata) | explicit download only |

- **The sizes are product defaults, each with a reason.** **Thumbnail 512 px** (cr-0070, raised from 400): a
  ~200 px grid tile on a 2×/3× display must not be upscaled into mush. **Preview 2048 px**: covers a lightbox
  on a high-DPI screen without shipping the original. Tunable — but change them deliberately: a re-derive of
  existing media is expensive and there is **no backfill**. (WebP would be smaller — an optional swap, not the
  default.)
- **Use a high-quality downscale filter.** The thumbnail is a large single-step reduction (e.g. 4032 → 512 ≈ 8×);
  the *cheap* default resampler (canvas `imageSmoothingQuality: 'low'`) aliases fine detail into a smear. Web
  uses a **progressive-halving** canvas downscale — ≤2× steps at `imageSmoothingQuality='high'`, since a single
  big `drawImage` is one bilinear pass and stays soft. **Deriving server-side (libvips) would sidestep all of
  this and is not an option** — the server never sees a pixel (operator-blind). That constraint is why this
  enabler exists at all.
- **Video:** decode the **first frame** to a JPEG **poster**, used for **both** the thumbnail and the preview
  (a still — no motion preview tier this pass). The poster is no longer the *only* way to see a video: the
  lightbox **plays the original in place** ([ADR 0047](../../../decisions/0047-inline-video-playback-range-streaming.md),
  cr-inline-video-playback) — the poster is what the grid + the un-played lightbox show, and the original is
  fetched on **play or download**. A compressed/streamable video *tier* is still future work (it needs a
  transcode enabler).
- Each tier is sealed **exactly like any media blob** — chunked AEAD under its **own fresh random `K_blob`**
  wrapped under the Memory's `K_mem` ([en.memory.media](en.memory.media.md)). **No new crypto primitive** — a
  derivative is another blob with a role tag.
- **Operator-blind generation.** Thumbnail + preview are produced **on the client** (decode → downscale →
  re-encode → seal), so the operator never sees a derivable pixel. Re-encoding strips EXIF from the
  derivatives; the **original** is kept **byte-pristine** (full resolution + metadata) and leaves the device
  only on explicit download.
- **Why the lightbox shows the preview, not the original:** the lightbox is a *viewing* surface, not a
  download — the preview keeps it fast and cheap; the multi-MB original is fetched only when the user actually
  downloads.

## Generation & attachment (in the upload flow)

On upload (the add-media flow), the client derives **preview + thumbnail** (image: downscale; video: first-frame
poster), seals **each as its own blob** under a fresh random `K_blob`, and uploads all three in **one batch**
(the control plane is already batch-shaped, [en.memory.media](en.memory.media.md)). The
**media** record then references the blobs **by role** — `original` plus `derivatives: [{ kind, blob }]`,
`kind ∈ THUMBNAIL | PREVIEW`. A consumer picks by role and falls back to the original when the role is absent.
A type that is neither image nor video (no derivable frame) attaches the **original only**; the gallery falls
back to an icon + name. **Upload always generates the derivatives** — there is no produce-later path.

**The source is decoded once, and the order is what makes that possible.** The **preview** is derived from the
original; the **thumbnail** is then derived from that already-downscaled preview rather than from a second pass
over the full-resolution source. 4032 → 2048 → 512 traverses the same ≤2× halving steps two independent runs
would, so § *The three tiers*' downscale rule is honoured rather than traded for the saving — but it does mean
the two derivations are **ordered**, not interchangeable. The intrinsic size that decode measures is handed
back with the bytes, so the sealed metadata tier's geometry costs no decode of its own either.

**Where the image pixel stage runs: in a Worker where the browser offers one.** Web derives the image tiers in a
same-origin module Worker on `OffscreenCanvas`; the page thread runs the identical code (one pixel module behind
a canvas port, so the tiers cannot drift between the two) only as the fallback. The reason is scheduling, not
CPU: Chromium encodes a page-thread canvas (`toBlob`, and a page-thread `OffscreenCanvas` alike) in an idle
period, and a tab nobody paints — backgrounded, minimized, occluded — can wait for one; a Worker's encode does
not. That is the realistic way a large batch is uploaded: pick, then switch away. **How much it buys depends on
the browser and on how busy the device is, and it can be nothing.** Measured over 12 photos at 3024×4032 (pixel
stage only, page path vs Worker, interleaved): in the preview Chromium 152 every hidden-tab encode waited ≈ 1 s
(≈ 2.1 s per photo on the page thread, 89 ms in a Worker); in desktop Chrome 154 on a busy machine the page path
took 22.6–73.6 s per batch and the Worker 5.2–27.3 s; on the same machine quiet, both took ≈ 2.8–3.2 s
backgrounded and ≈ 0.8 s visible — no paint-gated wait at all, only the slower scheduling a backgrounded tab
gets, which reaches its Worker too. A visible tab gains nothing, and nothing claims it does. The Worker is an
optimisation, never a requirement: no Worker, no `OffscreenCanvas`, or a Worker that fails to load or dies
finishes the request on the page thread, and the Worker stays off for the session — with one console warning per
page when a Worker existed and could not run, and `web`'s `check` fails a build that emits no Worker chunk
(`verify:media-worker`). An undecodable source fails
in the Worker exactly as it would on the page and uploads original-only, with no page-thread retry. The video
poster stays on the page thread (a Worker has no `<video>`), so a backgrounded tab still pays that wait once per
video.

## Decisions (settled)

1. **Attachment / keying.** The `media` record carries `original` + `derivatives: [{ kind, blob }]` —
   **role-tagged, opaque blob ids**, a contract delta on `media.openapi.yaml` (start/complete/list). Each blob
   keeps **its own `K_blob`** (wrapped under `K_mem`) — **not** a key shared across the three. *Why per-blob:*
   the `chunk_frame` AAD binds only `index ‖ total` (position **within** a blob); a shared key would give
   thumbnail-chunk-`i` and preview-chunk-`i` the same `(key, AAD)`, letting the blind store **splice a chunk
   across derivatives undetectably**. Per-blob keys forbid that for free — no `chunk_frame`/vector change. The
   `<original-name>-<thumbnail|preview>.<suffix>` naming is a **client-side download-filename** convention only
   (derived from the decrypted original name); **storage stays opaque blob ids** — filenames are operator-blind
   under `K_mem`.
2. **No backfill.** The instance is treated as **fresh** — any current pre-derivative media (cr-0028) is
   simply wiped, not migrated. Every image/video media therefore has its derivatives from upload; the gallery
   may **assume** they exist (the only fallback is the rare non-image/non-video type).
3. **Video = first-frame poster** (JPEG), used for both thumbnail and preview; the video original only on
   download. Motion/short-clip preview is a later pass.
4. **Tier sizes:** thumbnail JPEG longest-edge 512 / q0.80 (raised from 400 for hi-DPI, cr-0070), preview
   JPEG longest-edge 2048 / q0.85 (see *The three tiers* for why each).

## Quality notes

- **Operator-blind end to end.** Derivatives are generated and sealed on the client; the store/server hold
  only ciphertext + role tags + wraps. A smaller derivative leaks no more than the original already does
  (its exact ciphertext size — nothing pads it).
- **The pixel stage is verified by property, not by bytes.** The *sealing* of every derivative stays
  vector-gated and byte-exact (`chunk_frame`, [en.memory.media](en.memory.media.md)). The *pixel* stage before
  it — decode → downscale → JPEG re-encode — runs on the browser's own codecs and **is not byte-reproducible**:
  a codec or engine update legitimately shifts the output. So it is asserted by **property** (decodable,
  longest edge ≤ target, dimensions/quality in range), never pinned to a vector. A byte assertion here would
  fail on a Chrome update and teach everyone to ignore it. This is the one deliberate exception to
  vector-gated verification, confined to the lossy re-encode — the crypto layer is unaffected.
- **Reuses the media transport verbatim.** No new endpoint *shape* beyond the role tagging — same
  start → direct PUT → complete+attach → direct GET → open ([en.memory.media](en.memory.media.md)),
  batch-shaped (now three blobs per item).
- **The original stays pristine.** Only the original is byte-identical to what the user uploaded (full
  resolution + metadata); it is fetched only on explicit download.

> **No end-user section.** As an enabler this has no standalone "what this means for you" story — its end-user
> value surfaces through [uc.memory.gallery](../use-cases/uc.memory.gallery.md).

## Related

- Feature: [Memory](../_index.md)
- Enablers: [en.memory.media](en.memory.media.md) (the blob transport this reuses),
  [en.memory.tree](en.memory.tree.md) (the Memory + `K_mem`)
- Use-cases: [uc.memory.gallery](../use-cases/uc.memory.gallery.md) (consumes the three tiers)
- Decisions: [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) (deferred
  thumbnails/previews — **this enabler is where they land**),
  [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (`K_blob` under `K_mem`)
