---
id: cr-svg-on-the-apps
title: An SVG on Android, Apple, Windows and Linux as on the web - drawn with nothing run or fetched, a foreignObject refused, sharp at every zoom
status: in-progress
satisfies: []
adrs: ["0155", "0047", "0123"]
invariants:
  - "an SVG's script never runs and nothing it names is fetched, on any app (ADR 0155 § The file facts' slice D9)"
  - "an SVG holding a foreignObject is refused before any renderer sees it, as the web refuses it (D14's taint gate)"
  - "no decrypted SVG is written to disk to show it (ADR 0047)"
---

# An SVG on the apps (inbox #1059)

The web draws a peer's SVG in image mode, refuses a `foreignObject` and repaints per zoom (ADR 0155 D11, D14). Linux
drew one at its own size (no zoom); Android, Apple and Windows showed its text. This CR gives each app the web's
guarantees and a sharp zoom.

## Phases

1. **Core** — `SvgView` (apps/depot): the refusal (`refused`), the web view's page (`document`), the zoom steps and
   script, the raster cap (`plan`); `FileView.open` refuses a `foreignObject` SVG whole. The bridge's `File.SvgPage` and
   `File.SvgRaster`. Tests: `SvgViewTest`, bitten.
2. **Android** — the system `WebView` in image mode, closed around it (`SvgPicture.kt`).
3. **Apple** — `WKWebView` in image mode, closed around it (`SvgPicture.swift`).
4. **Windows** — WebView2 in image mode on PdfWeb's hardened browser (`SvgWeb.cs`, `SvgWebTest`).
5. **Linux** — glycin's librsvg kept, drawn again at each zoom's size within the cap (`file_page.rs`).
6. **Harvest** — ADR 0155 D15, `docs/apps/depot.md` § The viewer, inbox #1059; this CR deleted.

## Decision log

- **The refusal is the core's, by markup** — the apps have no taint read-back; `SvgView.refused` scans the whole file
  for `foreignObject` (any case, any prefix) after undoing character references to a fixed point (an internal entity
  `&#60;foreign&#79;bject` becomes markup where used; `&#38;#…;` one round later), and refuses what it cannot read
  (invalid UTF-8, a declared encoding other than UTF-8/ASCII, references nested past 8 rounds). Internal entities alone
  are not refused: Illustrator's exports declare them. Linux, which never drew one anyway (librsvg ignores it), now
  refuses it too, as the web.
- **Android: the system WebView, no library** — no platform API decodes SVG (`ImageDecoder`, `VectorDrawable` take none);
  AndroidSVG's last release is 1.4 of 2019-05-28 (Maven Central metadata, read 2026-11-22), so it falls under "no
  abandoned dependency"; Coil's SVG module wraps it. The WebView is the platform's maintained Chromium, updated by Play.
  Script off, `blockNetworkLoads`, file/content access off, Safe Browsing off, metrics opt-out, every request but the
  page and the picture answered 403, no navigation, no long-press. Zoom: the WebView's own (pinch, `zoomBy`).
- **Apple: WKWebView, not NSImage** — macOS's `NSImage(data:)` draws SVG through CoreSVG (measured: 12 hostile files,
  0 listener hits) but iOS has no public decoder for arbitrary SVG data; one path for both is WebKit's image mode.
  Measured on macOS 26.7.3 (a probe over the same page and a scheme handler): 10 hostile files, 0 listener hits, only
  `view.html` and `picture.svg` asked; zoom ×8 laid the 200 px picture out at 1600 px.
- **Windows: WebView2 on PdfWeb's browser, not SvgImageSource** — Direct2D's SVG (WinUI's `SvgImageSource`, Win2D) draws
  no `<text>` and only re-rasterizes the whole picture; WebView2 is Chromium, the web's own image mode, and PdfWeb already
  holds the hardened, measured browser (no resolver, no proxy, InPrivate, a folder deleted after it).
- **The zoom lays the picture out again** (`SvgView.zoomScript`: CSS `zoom` on the `<img>`), the host's own script —
  WebKit and WebView2 run it with the page's script off. Each engine paints the vectors at the new size and only the
  tiles in view, so the cap is the engine's tiling; the page holds no bitmap of the picture.
- **Linux: glycin kept, redrawn at the zoom's size** — the loader is asked for the size (`PixbufLoader::set_size`), so
  librsvg renders vectors at it; the core's cap (`File.SvgRaster`, 4096² px) bounds the one raster, so a large picture at
  a far zoom is coarser than the web's tile (no region rendering through gdk-pixbuf).
