# ADR 0141 — QR codes are read and drawn by the core: one decoder and one encoder in common Kotlin, a camera per app
- Status: Accepted
- Built: yes
- Date: 2026-11-12
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The apps read a QR code twice — a printed or saved Emergency Kit at sign-in, and device linking in both directions
([ADR 0059](0059-device-linking.md)) — and draw one for linking. The scanner parses frames anyone can point a camera at,
in the process that holds the Master Key, on five targets (Android, Apple, Linux, Windows and the JVM the tests run on).
What was on offer (read 2026-11-12, `gh api repos/<r>`):

- **ZXing (Java)** is in maintenance mode ("changes are driven by contributed patches"; last release `zxing-3.5.4`,
  2025-11-11). Its QR path is small (`qrcode/{decoder,detector,encoder}` plus `common/`), and its blackbox corpus
  `qrcode-1..6` (179 images, ≈ 12 MiB) has pass thresholds per rotation.
- **zxing-cpp** is maintained (v3.1.1, Apache-2.0) but C++20, and reaches the core's targets badly: its Kotlin/Native
  wrapper has `mingwX64` commented out and no JVM target, ships on Linux only as a shared library the host installs,
  its Android AAR reads only a CameraX `ImageProxy` or a `Bitmap`, its writer is `@ExperimentalWriterApi`.
- **No maintained Kotlin Multiplatform QR library** covers the targets: `g0dkar/qrcode-kotlin` encodes only and has no
  macOS, Linux or mingw build.
- **Each platform's own decoder** differs: ML Kit is closed and needs Google Play services, Vision and
  `AVCaptureMetadataOutput` are Apple's alone, Windows' point-of-service scanner needs package identity, zbar's last
  release is of 2024 (remembered, not measured).

## Decision

1. **One decoder, a Kotlin port of ZXing's QR path in the core's `commonMain`** (`core/.../qr/`, ported from
   `zxing-3.5.4`, commit `f651b0a0`; Apache-2.0, ZXing's `NOTICE` and licence beside it, each file naming its upstream
   files and what changed). `QrDecoder.decode(QrFrame, tryHarder)` tries the frame as it is, then inverted (a code drawn
   light on dark, the web's `attemptBoth`), each also mirrored. Every mode is parsed; a code whose text is Kanji, a
   Shift_JIS or GB2312 byte segment or a legacy ECI is answered null (no veyq code is one, and common Kotlin has no
   decoder for them) — the corpus test decodes those with the JVM's charsets. **Memory safety decides it:** a C or C++
   parser of camera frames in the process that holds the Master Key is the attack surface the port avoids. **The port
   is ours to keep:** frozen code held to ZXing's own corpus at ZXing's own thresholds, nothing in it crypto.
2. **One encoder, ported from ZXing's**, sharing the decoder's version table and Reed–Solomon: `QrEncoder.encode(text)`
   at level M with a 4-module quiet zone, one segment, a text's UTF-8 bytes without an ECI as the web's `qrcode` writes
   them; past version 40 (2331 bytes) refused. A phone draws the matrix itself; for the desktops the core writes a
   1-bit greyscale PNG (`QrPng`: stored deflate blocks, no compressor) at the largest whole scale within the pixels the
   host asks for, answered as shown bytes ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)
   decision 1), so the text never crosses the bridge.
3. **A scan is a core screen model, `QrScan<T>(accepts)`.** It takes greyscale frames of at most 1280 px on the long
   edge, reads one at a time (a frame offered while one decodes is dropped — the backpressure is that answer), skips
   every text `accepts` does not map to a `T` (or whose parse throws), and settles on the first it does: `Found(T)`.
   States `Starting`, `NoCamera`, `Denied`, `Scanning`, `Found`, `Closed`; the camera's own state is the host's, what
   a code means is the caller's `accepts`, and only `T` leaves the model. **A caller that holds a secret takes it inside
   `accepts`** (the kit's key into the sign-in model), so neither the text nor the secret waits in a state for a UI to
   hand it on. Every camera's plane is fitted to the edge by one rule (`QrFrames.fit`, box-averaged by the smallest
   whole factor); a still image (a kit's drawn page) goes to `QrDecoder.decode(…, tryHarder = true)` directly, at any size.
4. **A camera per app, each the platform's maintained, vendor-neutral API:** Android CameraX (`ImageAnalysis`, keep only
   the latest, the Y plane); Apple AVFoundation (`AVCaptureVideoDataOutput`, `420YpCbCr8BiPlanarFullRange`, the Y
   plane); Windows `MediaCapture` with a `MediaFrameReader` (NV12's Y, else converted to Gray8); Linux the xdg Camera
   portal (`ashpd`) handing a PipeWire fd to GStreamer's `pipewiresrc`, teed to a `GRAY8` sink for the core and an
   `RGBx` one for the preview — no `v4l2src` fallback: a desktop without the portal scans with the other device. **A
   device without a camera is not a failure:** `NoCamera` carries no words of its own, and each caller offers the other
   way (ADR 0059 §1: the other device scans; a kit is picked as a file). Each debug build has a test camera that feeds
   frames from an image (`VEYQ_DEV_CAMERA`; Android the emulator's virtual scene), so frame to `Found` is measured
   where no camera is.
5. **The bridge carries frames in, never a code's text out.** `Scan.Open(kind)`, `Scan.Camera(scan, state)`,
   `Scan.Frame(scan, width, height, stride)` with the plane as `veyq_call`'s inbound payload (ADR 0137 decision 1),
   `Scan.Close`, `Watched.Scan` → `Update.Scan`, whose `found` names what the kind made of the code (`SCHEME` the scheme
   alone, `KIT` that the sign-in took a kit, `LINK` the link's own step), never its text. The bridge fits the frame,
   so no host repeats the scaling rule; scans belong to no tenant (a sign-in scans before one opens).

## Consequences

- **Measured (2026-11-13):** the port reads exactly ZXing's thresholds on every set and rotation, with and without
  trying harder, none misread (`QrCorpusTest`, the corpus fetched by `:core:fetchQrCorpus`, each file held to its
  SHA-256); the web's own codes, inverted ones and a degraded set read on every target (`QrDecoderTest`); every encoded
  text reads back, and the core's PNG reads in the web's `jsqr` (`coreQrCode.test.ts`) as the web's `qrcode` output
  reads in the core. Per 1280×720 frame: about 1.3 ms on the JVM and 1.8 ms in a macOS Release framework with a code,
  6 ms without one (the inverted and mirrored retries run only after the plain reading fails) — well under a frame's
  33 ms, so the one-in-flight rule is the only throttle. `src/mobile/CLAUDE.md` § Prove your work names the tests.
- **A round trip found what the corpus did not:** ZXing (the port and ZXing itself) did not read the core's own
  crisp `veyqkit:` code at 3–5 px a module — its finder stopped once three patterns agreed, one of them a run of data
  modules. The decoder scans the frame again exhaustively when the first reading stopped early and failed; a frame
  with no code never stops early, so it costs nothing there. A port is held to its upstream corpus **and** to the
  product's own outputs, both directions.
- **Each camera ran to `Found` through its test camera** (Android a throwaway AVD's virtual scene, the iOS Simulator,
  the macOS build, Linux in the container and on the Fedora 45 VM, Windows on the ARM64 VM), and `NoCamera` and
  `Denied` were reached on each where the machine allows (Linux's `Denied` by a PipeWire virtual camera and GNOME's
  dialog). **A real camera has been used on none yet** — none of the test machines has one but the developer's Mac,
  whose first camera use asks the person: native-apps § Open lists the owner's passes.
- **The desktops get a camera** (a laptop has one); the phone remains the usual scanner.
- The camera dependencies: CameraX 1.6.2 (AndroidX, Apache-2.0) in `gradle/libs.versions.toml`, ashpd 0.14 and
  gstreamer-rs 0.25 in the Linux `Cargo.toml`, each with its maintenance check; the Flatpak needs no permission or
  module for the camera (`org.gnome.Platform//51` carries `pipewiresrc`). The Windows MSIX declares `webcam`.

## Alternatives considered

- **zxing-cpp** — maintained, but a C++ parser of attacker-pointed frames beside the Master Key, and three
  integrations of one library (an AAR, a klib, a C++20 build against Kotlin/Native's old Linux sysroot) with nothing on
  mingw or the JVM.
- **Each platform's own decoder** (ML Kit, Vision, the point-of-service scanner, zbar) — four behaviours answering a
  damaged photo differently with no shared test, two of them closed or unmaintained.
- **ZXing (Java) as a dependency** — maintenance mode, and the JVM only.
- **The host scales a camera's frame** — the first design; with the bridge between, the core fits it at no cost, and one
  rule replaces a copy per host.
