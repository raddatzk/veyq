---
id: cr-lightbox-zoom-slideshow
title: The lightbox zooms (onto the original past the preview) and plays a slideshow
status: planned
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
- [ ] 2 web: Decisions 3, 4 (slideshow).
  - Tests (fake timers): advances every 5 s, a video's poster for one interval, stops at the end, pauses on interaction
    and hidden tab, one preview ahead, no EXIF/geocode call while running, details load after pausing. Bitten.
  - Done when `npm run check` is green and a real browser ran a slideshow over a Memory with a photo and a video.
- [ ] 3 docs: uc.memory.gallery step 3a and the quality note (built, what was seen), its coverage, both wikis' gallery
  text, inbox #90/#91 done.
  - Done when every `scripts/validate_*.py` is green, run on a detached worktree.

## Definition of done

- `npm run check` is green in `web/`; every `scripts/validate_*.py` is green on a detached worktree; the browser runs
  are recorded; coverage updated.

## Retrospective

_(filled at implementation)_
