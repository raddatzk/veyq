---
id: cr-lightbox-zoom-slideshow
title: The lightbox zooms (onto the original past the preview) and plays a slideshow
status: in-progress
satisfies: [uc.memory.gallery]
adrs: ["0047", "0079"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

The Memory lightbox gains zoom (double-click/tap, pinch, ctrl+wheel, drag to pan; the original fetched past the
preview's resolution when it is browser-decodable and under 50 MB) and a slideshow (5 s, a video's poster for one
interval, stop at the end, pause on interaction and a hidden tab, one preview ahead, no EXIF / place backfill while it
runs). Member and guest alike. Decided by the coordinator on the owner's behalf, 2026-10-14 (inbox #90, #91).

## Context & constraints

- [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) step 3a owns the behaviour.
- **Measured:** one component, `features/memory/components/MediaLightbox.vue` (member and guest; the guest's loaders
  come from its source - `features/memory/source.ts`); it shows the PREVIEW tier (2048 px edge), decrypts to an object
  URL revoked on navigation; `originalLoader` decrypts the whole original; no pointer/touch/wheel handlers, no timers;
  `loadDetails` per item fetches EXIF and, with consent and GPS but no name, geocodes and writes a place name back.
- **Reuse:** `shared/ui/ImageCropper.vue`'s pan/zoom math and pointer capture; `shared/state/visibilityPoller.ts`
  (`createVisibilityPoller`) for the slideshow's tick (skips hidden, no overlap, clean stop).
- **Memory:** the original is held whole only for the zoom, capped at 50 MB of ciphertext and a decodable type; its object
  URL is revoked on navigation and close, like the preview's.
- **Invariant:** operator-blind - the original travels as ciphertext and is decrypted on the device, as a download is.

## Inventory (commands)

- `grep -n "onKeydown\|loadPreview\|loadDetails\|resolvePlaceForCurrent\|playVideo\|originalLoader\|suspendKeys" web/src/features/memory/components/MediaLightbox.vue`
- `sed -n 1040,1060p web/src/features/memory/media.ts`; `grep -n TIER_SPECS -A3 web/src/shared/image/mediaDeriver.ts`
- `grep -n "loaders" -A9 web/src/features/memory/source.ts`; `web/src/shared/ui/ImageCropper.vue`; `web/src/shared/state/visibilityPoller.ts`
- `grep -n "media-lightbox-stage" -A12 web/src/styles/media.css` (and its dark twin in `dark.css`)
- Tests: `web/src/features/memory/components/MediaLightbox.test.ts`, `MemoryDetail.caps.test.ts`, `FolderShareGuest.test.ts`.

## Decisions

1. **Zoom state** lives in a small composable (scale, offset, clamping) built on ImageCropper's math; the stage applies a
   CSS transform. Double-click/tap toggles 1x / 2x at the pointer; two-pointer pinch and ctrl+wheel zoom continuously
   (1x - 8x); a drag pans while zoomed (pointer capture). Navigation, close and Escape reset it; ArrowLeft/Right do not
   navigate while zoomed (Escape first un-zooms).
2. **The original for zoom:** when the displayed scale exceeds the preview's natural resolution, the original is loaded
   once per item through `originalLoader` if its sealed media type is one `<img>` decodes and its size is under 50 MB;
   it replaces the preview in place at the same transform; a failure keeps the preview silently. A video does not zoom.
3. **Slideshow:** a play/pause control in the lightbox's toolbar; a `createVisibilityPoller` tick every 5 s advances;
   a video shows its poster for one interval and is never auto-played; on the last item the slideshow stops; any key,
   pointer, zoom or details-panel interaction and `suspendKeys` pause it; the next item's preview is fetched one ahead
   (and revoked if not shown). While playing, `loadDetails` does not run its EXIF read or place backfill; pausing on an
   item runs it.
4. **Accessibility:** the controls are labelled buttons; the slideshow state is announced (`aria-live`); zoom has a
   keyboard path (`+` / `-` / `0`).

## Phases

- [ ] 1 web: Decisions 1, 2, 4 (zoom).
  - Tests: the gestures change the scale and offsets within bounds; navigation resets; arrows do not navigate while
    zoomed; the original is loaded once past the threshold for a decodable type under the cap, never otherwise, and its
    URL is revoked on navigation; a guest's lightbox zooms through its own loaders. Bitten.
  - Done when `npm run check` is green and a real browser zoomed a photo (wheel and double-click) on the dev instance.
  - **Built and unit-proven; the browser pass is OPEN** (the implementer ran in a worktree no browser pane can serve):
    `npm run check` green, every new test bitten. The box stays open until the main session zoomed a photo after the merge.
- [ ] 2 web: Decisions 3, 4 (slideshow).
  - Tests (fake timers): advances every 5 s, a video's poster for one interval, stops at the end, pauses on interaction
    and hidden tab, one preview ahead, no EXIF/geocode call while running, details load after pausing. Bitten.
  - Done when `npm run check` is green and a real browser ran a slideshow over a Memory with a photo and a video.
  - **Built and unit-proven; the browser pass is OPEN** (as phase 1): `npm run check` green, every new test bitten, the
    lightbox suite 8 of 8 runs green. The box stays open until the main session ran a slideshow after the merge.
- [x] 3 docs: uc.memory.gallery step 3a and the quality note (built, what was seen), its coverage, both wikis' gallery
  text, inbox #90/#91 done.
  - Done when every `scripts/validate_*.py` is green, run on a detached worktree.
  - "Both wikis' gallery text" has no `wiki/` page to land on (measured: `grep -rniE "original|zoom|slideshow|gallery"
    wiki/` names no gallery page; `is-my-data-safe` speaks for this use-case and stays true). The bilingual end-user text
    that said "the original is only fetched when you download it" is the use-case's own EN/DE section - updated there.
    The "what was seen" half is the merge's browser pass; 3a says so until then.

## Definition of done

- `npm run check` is green in `web/`; every `scripts/validate_*.py` is green on a detached worktree; the browser runs
  are recorded; coverage updated.

## Retrospective

**Built** (worktree branch, three commits): phase 1 - `shared/ui/usePanZoom.ts` (ImageCropper's zoom-about-a-point and
pan clamp, re-framed for a CONTAINED image: the pan is bounded to the overhang, an axis that fits stays centred; the
gestures as plain handlers) and `features/memory/lightboxZoom.ts` (the three gates for the original); phase 2 - the
slideshow inside `MediaLightbox.vue` over `createVisibilityPoller`; phase 3 - these docs. `MemoryDetail` needed no change:
it already handed the lightbox the source's `original` loader, so the guest's zoom reads through `openShareOriginal`.

**Interpretations the CR left room for, taken and stated** (each is one line to change):
- "fetched one ahead (and revoked if not shown)": the prefetch holds the decrypt's PROMISE, never an object URL - a URL
  is minted only for the item on screen, so an unshown prefetch has nothing to revoke and is simply dropped (on pause,
  on navigation elsewhere, on close).
- "a type `<img>` decodes": JPEG, PNG, GIF, WebP, AVIF, BMP. HEIC is out (Files' viewer TRIES it, since Safari decodes it;
  here a miss costs a whole-original fetch of up to 50 MB for nothing). The original is also `decode()`d before the swap,
  so a sealed type that lies keeps the preview.
- "the displayed scale exceeds the preview's natural resolution": counted in DEVICE pixels (fitted width x zoom x
  `devicePixelRatio` > the preview's natural width), so a retina screen asks earlier.
- ctrl+wheel: `exp(-deltaY x 0.005)` per pixel (~1.65x per 100 px notch), Firefox's line/page deltas converted first.
- "pause on hidden tab": a real PAUSE (state and button flip), not the poller's skip - the poller alone would advance the
  moment the tab came back.
- The slideshow stops ON the last item (not one interval later), so the last item's details load at once.

**What the bites caught** (every new test was bitten; levers in the report): two tests that could not fail. (1) The
"Enter on the toggle does not pause" check dispatched on an UNATTACHED mount, whose events never reach a `window`
listener, and read the DOM before the re-render - green against a sabotaged exclusion; it now mounts `attachTo`, probes
that the key reached the window, and flushes. (2) The 1x drag test could not tell the early return from the clamp; it now
asserts no grab state at 1x.

**A trap, now a comment at the line:** a Vue `@pointerdown.capture` on the lightbox root made the stage's own Vue
pointer handlers DROP presses intermittently - Vue's invoker stamps an event (`e._vts`) at the first Vue handler and later
Vue handlers drop an event stamped no later than they were attached. Measured: the zoom tests failed in 5 of 5 full-file
runs with it, 0 of 6 without; why the stage's handler counted as attached that late is not established. The root's pause
listeners are native (`addEventListener`, capture). First suspected was a scheduling stall splitting the double-click
(load average 30+); a 300 000 ms double-tap window did not remove the flake, which ruled it out. The tests still freeze
`performance.now` so the double-tap cannot depend on the machine.

**happy-dom:** its `WheelEvent` is no `MouseEvent` and drops `ctrlKey` (the test sets it on the instance); it lays nothing
out, so each zoom test sets the image's fitted and natural size by hand - the coverage gap says so.

**Handed back** (not decided here): (a) an original whose preview is not downscaled (preview edge < 2048, so the original
has no more pixels) is still fetched past the threshold - the CR's rule, literally; skipping it would save the fetch.
(b) While zoomed the arrow keys are inert; panning with them would give the keyboard a pan path.

