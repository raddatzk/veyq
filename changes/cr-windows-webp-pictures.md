---
id: cr-windows-webp-pictures
title: Windows without the WebP Image Extension — the members' pictures decoded by the app (#885)
status: planned
satisfies: ["uc.spaces.set-profile-picture"]
adrs: ["0139", "0127", "0136"]
invariants:
  - "a picture is opened on the device only, as now; nothing about it reaches the server or a third party"
---

## Goal

On a Windows PC where the person removed Microsoft's WebP Image Extension, every member shows their initials and nothing
says why (inbox #885). The owner chose to decode the pictures without it (below).

## Context & constraints

- Re-derived at `43918c9b0` (measured): the web writes every member's picture as WebP (ADR 0127). Windows decodes it through
  WIC (`Avatars.cs`, `BitmapImage`), which has WebP only through the extension; `AvatarsTest`'s WebP case is the decode test
  whose bite showed the fallback to initials (ADR 0139 decision 6, native-apps § Open, measured 2026-11-11). The extension is
  provisioned in Windows 11's image and removable; a sideloaded MSIX cannot depend on it (no framework package, ADR 0139
  decision 6). Only the members' pictures are WebP: covers, tiles and previews are JPEGs the uploading client made.
- No word in `src/mobile/words/words.json` says this (`grep -n -i 'webp\|extension' src/mobile/words/words.json`: only the
  iOS share extension's sign-in hint).

## The answer (owner 2026-11-18: "Selbst dekodieren")

**B: decode it in the app.** Windows decodes a member's picture itself when WIC cannot, so the pictures always show and
nothing needs saying: A's notice, its two words and the Store link are dropped.

Where the decoding lives (decided here, technical):
- **libwebp's decoder in the core's bridged build** (Linux arm64/x86-64, Windows x86-64), built by Kotlin/Native's own
  clang from **the same pin the web's encoder has** (`src/web/webp-encoder/libwebp.pin`: tag and commit, the clone
  refused unless it is that commit), as utf8proc is built (`core/konan_toolchain.py`). One libwebp in the repository, one
  update path: ADR 0127's weekly workflow moves the pin, and the core builds against it. libwebp is the WebM project's,
  maintained, BSD-3-Clause with Google's patent grant (a royalty-free codec, so ADR 0123's "the app ships no codec:
  patents" does not reach it: that answer was about media codecs such as HEVC, where patents bind). Nothing in the MSIX
  but the DLL grows: no package dependency (ADR 0139 decision 6).
- **Rejected:** a managed decoder on the C# side (ImageSharp is under the Six Labors Split License, not vendor-neutral
  open source; SkiaSharp ships Skia's native binaries for one format; Magick.NET is larger still), and a libwebp DLL
  of its own beside the core (a second native build and pin for the same library).
- **The hand-off is a PNG**: `Picture.Decode` takes a WebP inward beside the call and answers `Bytes("image/png")`, RGBA
  in stored deflate blocks as `QrPng` writes them. PNG is WIC's own codec on every Windows, so the host's draw path
  stays the one it has (`BitmapImage`); no pixel format crosses the wire.
- **WIC stays the first try**: with the extension present nothing changes; a WebP WIC refuses goes through the core.
  Linux could take the same call where glycin's loaders are missing; it keeps glycin now (the Flatpak runtime carries
  them, ADR 0139 decision 2).
- **Only the members' pictures are always WebP** on a desktop (space icons are WebP too, but no desktop draws one).
  Covers, tiles, banners and every tier are JPEGs the uploading client made. A WebP a person uploaded as content (a
  photo's original, a receipt, a Depot file) is the same gap; it is named in the inbox, not fixed here.

## Phases

- [ ] 1 core: libwebp's decoder built and linked into the bridged slices; `WebpPicture` decodes a WebP to a PNG
  (`bridgedTest`, red first: a stub answering null); `Picture.Decode` on the wire (`DispatchTest`).
  · Done when: `:core:jvmTest :bridge:jvmTest` green, `run.py test --tests '*Webp*'` green on the VM.
- [ ] 2 Windows: `Avatars` decodes through `PictureDecode.Decode` (WIC first, the core on a WebP WIC refuses);
  `AvatarsTest` forces WIC's WebP to fail and still draws the picture (red first), `CoreTest` decodes through the real
  DLL. · Done when: `windows/run.py build` green; a member's picture drawn on the VM.
- [ ] 3 a pass on the VM with the extension removed and put back (the owner's: a system component, VM settings are theirs).
- [ ] 4 harvest — ADR 0139 decision 6, ADR 0123's Windows consequence, native-apps § Open, `src/mobile/CLAUDE.md`,
  inbox #885 deleted, block B done, this CR deleted.

## Definition of done

As the phases; `scripts/validate_*.py` green.

## Retrospective

(filled at the end)
