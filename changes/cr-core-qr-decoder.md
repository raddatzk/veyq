---
id: cr-core-qr-decoder
title: QR codes in the Kotlin core — one decoder and one encoder in common Kotlin, one scan model, a camera per app
status: in-progress
satisfies: []
adrs: ["0129", "0130", "0123", "0059"]
invariants:
  - "one QR decoder and one QR encoder for every app, in the core's commonMain (ADR 0129 decision 1): no platform decodes or encodes a QR itself, no platform library (ML Kit, Vision, AVCaptureMetadataOutput, Windows' point-of-service scanner, zbar) is reached for a code's text"
  - "the decoder is memory-safe code: pure Kotlin, no C or C++ parser of camera frames in the process that holds the Master Key"
  - "what a code says never crosses the bridge: frames go in (ADR 0130 inbound payload), a drawn code comes out as shown bytes, the core keeps the text and answers only what the scan was accepted as"
  - "no new dependency that is deprecated, vendor-locked or closed (root CLAUDE.md); each added one checked and recorded where it is added"
  - "Android, the Apple app, Linux, Windows and the web behave as before outside the scanner; `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` and both desktops' `run.py build` stay green in every phase"
---

## Goal

The apps need to **read** a QR code twice before Memories — a printed or saved Emergency Kit
(`changes/cr-app-kit-sign-in.md`, written in parallel, inbox #842) and device linking in both directions
(`changes/cr-app-device-linking.md`, ADR 0059) — and to **draw** one for linking. No app reads or draws a QR today.
This CR builds the shared floor both stand on: a QR decoder and encoder in the core, a core scan model that takes
camera frames and hands back the first code a caller accepts, and on every app a camera view that feeds it, with the
state "this device has no camera" said rather than hidden.

It is its own CR because two CRs depend on it and neither owns it. **Dependency order:** phases 1 and 3 are what
`cr-app-kit-sign-in` needs to read a kit's QR from a rendered PDF page; phases 4 and 5 are what it needs to scan a
printed kit; `cr-app-device-linking` needs all of them.

## Context & constraints

- **Read first:** ADR 0129 (decisions 1, 2, 5), ADR 0130 (decision 1, and decision 3 for a file by path), ADR 0123
  decision 9 (each platform's guideline), ADR 0059 §1 (whichever device has a camera scans) and §4 (a code never
  names a server), `src/mobile/CLAUDE.md` whole (the bridge's traps, each platform's run and drive verbs).
- **The web's reference:** `src/web/src/features/account/emergencyKit/KitScanner.vue` (getUserMedia, a frame loop,
  an `accepts` predicate that skips codes not ours), `emergencyKit.ts:37` (`inversionAttempts: 'attemptBoth'`),
  `pairing/PairingCode.vue:27` (`qrcode`, margin 1, a white plate).
- **Where what runs:** as in the Depot CR that preceded this one — Kotlin on this Mac; Linux in its container
  (`linux/run.py run --port 8097 --devlogin --no-secret-service`), Broadway has no camera; Windows on the ARM64 test VM
  (no camera); Android on a throwaway AVD (never `veyq_pixel`), whose virtual-scene camera can show a poster image;
  Apple on a throwaway simulator (no camera) and the macOS build (this Mac's camera). The dev backend is never started
  twice or stopped.

## Measured at refine (2026-11-12)

Commands from `src/mobile` unless named.

| # | What | Command |
|---|---|---|
| R1 | **No QR or camera code in any app.** No hit for zxing, mlkit, qrcode, barcode, CameraX, AVCapture, MediaCapture, pipewire, ashpd; no camera permission in `Info.plist` or the Android manifest. GStreamer is in the Linux container for Depot's playback (`gstreamer1-plugins-good`, `-bad-free`, `-openh264`). | `grep -rniE 'zxing\|mlkit\|qrcode\|barcode\|camerax\|AVCapture\|MediaCapture\|pipewire\|ashpd' core android apple linux windows`; `grep -n gstreamer linux/Containerfile` |
| R2 | **ZXing (Java) is in maintenance mode**: its README says "The project is in maintenance mode, meaning, changes are driven by contributed patches"; last release `zxing-3.5.4`, 2025-11-11. | `gh api repos/zxing/zxing/releases --jq '.[0].tag_name'`; the README |
| R3 | **zxing-cpp is maintained** (v3.1.1 of 2026-07-29, a commit on 2026-11-12, Apache-2.0) **but reaches the core's targets badly:** its Kotlin/Native wrapper builds android-native, Apple and Linux targets, **`mingwX64` commented out**, no JVM target; on `linuxX64`/`linuxArm64` it ships only as a shared library the host must install (its `wrappers/kn/README.md`, zxing-cpp#939); its Android AAR reads a CameraX `ImageProxy` or a `Bitmap` only (`readYBuffer` private); its writer API is `@ExperimentalWriterApi`. It is C++20. | `gh api repos/zxing-cpp/zxing-cpp/releases --jq '.[0]'`; `gh api repos/zxing-cpp/zxing-cpp/contents/wrappers/kn/build.gradle.kts`; `.../wrappers/android/zxingcpp/src/main/java/zxingcpp/BarcodeReader.kt` |
| R4 | **No maintained Kotlin Multiplatform QR library covers the core's targets:** `g0dkar/qrcode-kotlin` (encoder only, MIT) builds jvm, android, iOS and wasmJs — no macOS, Linux or mingw; last release v4.3.0, 2025-02-26. | `gh api repos/g0dkar/qrcode-kotlin/contents/build.gradle.kts` |
| R5 | **ZXing's QR reader is small and its corpus is public:** `core/src/main/java/com/google/zxing/qrcode/{decoder,detector,encoder}` plus `common/` (binarizer, `BitMatrix`, `PerspectiveTransform`, `GridSampler`, Reed–Solomon); its blackbox corpus `core/src/test/resources/blackbox/qrcode-1..6` is 179 images, ≈ 12.3 MiB, each set with pass thresholds per rotation in `QRCodeBlackBox{1..6}TestCase` (set 1: 17/14/17/14 of 20 at 0°/90°/180°/270°). | `gh api repos/zxing/zxing/contents/core/src/test/resources/blackbox/qrcode-1` (and 2–6); the TestCase files |
| R6 | **The bridge already has an inbound payload nothing uses:** `veyq_call` carries bytes beside the JSON (ADR 0130 § Context); ADR 0130 decision 1 decides only the outbound half. | `sed -n 14,30p docs/decisions/0130-*.md`; `grep -n CName bridge/src/bridgedMain/kotlin/me/raddatz/veyq/mobile/bridge/Exports.kt` |
| R7 | **The core builds its native C dependencies from pinned tarballs** (`core/sodium/build_libsodium.py`, utf8proc) per slice — no C++ dependency yet. | `grep -n 'cinterops.create' core/build.gradle.kts` |

Remembered, not measured (each a phase's first measurement where it matters): ML Kit's barcode scanner is closed and
needs Google Play services or a bundled closed model; Apple's Vision and `AVCaptureMetadataOutput` decode QR but only
on Apple; Windows' camera barcode scanner (`Windows.Devices.PointOfService`) needs package identity, which the
unpackaged Windows app lacks; zbar's last release is 0.23.93 (2024).

## Decisions

### D1 — One decoder, ported from ZXing's QR reader into the core's commonMain

The decoder is a **Kotlin port of ZXing's QR path** (Apache-2.0; its `NOTICE` and the upstream commit kept beside the
port in `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/qr/`), QR only: `HybridBinarizer`, the finder/alignment
detector with `PerspectiveTransform` and `GridSampler`, the format/version readers, data masks, Reed–Solomon over
GF(256), and the bit-stream modes byte, alphanumeric and numeric (Kanji, structured append and ECI beyond UTF-8/ISO
8859-1 are left out: no veyq code uses them). It tries the inverted image when the plain one finds nothing (a code
drawn light on dark, what the web's `attemptBoth` covers) and the mirrored reading (ZXing's own retry).

Why, against the alternatives (R2–R4):
- **Memory safety decides it.** The scanner parses frames an attacker can point a camera at, in the process that holds
  the Master Key. A C++ decoder (zxing-cpp, zbar) is that parser in an unsafe language; a Kotlin one is not.
- **One implementation, one fixture set, every target** — `jvmTest` included, so the corpus runs where images are easy
  to load. zxing-cpp would be three integrations (an AAR on Android, a klib on Apple, a C++20 build against Kotlin/
  Native's old Linux sysroot, nothing on mingw) of one library; per-platform decoders (Vision, ML Kit, zbar) would be
  four behaviours and two of them closed or unmaintained.
- **A port is ours to keep, and that is accepted.** ZXing is in maintenance mode, so this is not a dependency on it:
  the port is frozen code held to ZXing's own corpus at ZXing's own thresholds, and nothing in it is crypto.

### D2 — One encoder, ported from ZXing's QR encoder, and a PNG for the desktops

`qr/QrEncoder` (ZXing's `Encoder` and `MatrixUtil`, sharing the decoder's Reed–Solomon and version tables) turns a
text into a module matrix at error-correction level **M** with a 4-module quiet zone; a phone draws the matrix itself
(Compose `Canvas`, SwiftUI `Canvas`), crisp, black on a white plate in both themes (the web's plate). For the desktops
the core writes the matrix as a **1-bit greyscale PNG** at an integer scale the host asks for (`qr/QrPng`: stored
deflate blocks, CRC-32, Adler-32 — no compressor needed), answered as shown bytes (ADR 0130 decision 1), so the text
never crosses the bridge.

### D3 — A scan is a core screen model, `QrScan`

`QrScan<T>(accepts: (String) -> T?)`: `offer(frame)` takes a greyscale frame (`width`, `height`, `rowStride`, the
bytes), **drops it when a decode is in flight** (one at a time, off the caller's thread), and settles on the first
text `accepts` maps to a `T`; texts it does not accept are skipped silently, as the web's scanner skips them. State:
`Starting`, `NoCamera`, `Denied`, `Scanning`, `Found(T)`, `Closed`. The camera's own state (`NoCamera`, `Denied`)
comes from the host; what a code means is the caller's `accepts` (the kit's `veyqkit:` parser, the pairing parsers
of `cr-app-device-linking`). Frames are the luminance plane (Y of YUV, or a GRAY8 conversion), at most 1280 px on the
long edge; the host scales down, the model refuses larger.

### D4 — A camera per app, each the platform's maintained, vendor-neutral API

| App | Camera | Frames | No camera / denied |
|---|---|---|---|
| Android | CameraX (`androidx.camera` core, camera2, lifecycle, view — AndroidX, Apache-2.0) `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`; `PreviewView` | the Y plane | `FEATURE_CAMERA_ANY` absent → `NoCamera`; the runtime `CAMERA` permission refused → `Denied` |
| iOS, macOS | AVFoundation `AVCaptureSession` + `AVCaptureVideoDataOutput` (`420YpCbCr8BiPlanarFullRange`); `AVCaptureVideoPreviewLayer` | the Y plane | no `AVCaptureDevice` for `.video` → `NoCamera`; `authorizationStatus` denied → `Denied`; `NSCameraUsageDescription` (and the macOS camera entitlement where the app is sandboxed) |
| Windows | `MediaCapture` + `MediaFrameReader` (Windows App SDK / WinRT), preview through `MediaPlayerElement` over the frame source | `Gray8` or the Y of `NV12` | no `VideoCapture` device → `NoCamera`; the privacy setting off → `Denied` |
| Linux | the xdg **Camera portal** (`ashpd`, maintained, MIT) → a PipeWire fd → GStreamer `pipewiresrc` (`gstreamer`, `gstreamer-app` from gstreamer-rs) with a `tee`: `GRAY8` to an `appsink` for the core, `RGBx` to a `gdk::MemoryTexture` for the preview | `GRAY8` | no portal or `IsCameraPresent` false → `NoCamera`; the portal refuses → `Denied`. No `v4l2src` fallback: a desktop without the portal scans with the other device |

**A device without a camera is not a failure:** each caller offers the other way (ADR 0059 §1 — the other device
scans; a kit is picked as a file). `QrScan`'s `NoCamera` carries no words of its own; the caller says what to do
instead. Each app has a **debug-only test camera** that feeds frames from a file (Android's emulator virtual scene with
a poster; Apple, Windows and Linux a `VEYQ_DEV_CAMERA=<image>` the debug build reads, the Linux one through GStreamer's
`filesrc`/`imagefreeze` in place of `pipewiresrc`), so the path from frame to `Found` is measured where no camera is.

### D5 — The bridge carries frames in, never a code's text out

`Scan.Open(kind)` → `Answer.ScanOpened(scan)`; `Scan.Frame(scan, width, height, stride)` with the luminance as
`veyq_call`'s inbound payload (R6), answered at once (the frame dropped or taken); `Scan.Camera(scan, state)` for
`NoCamera`/`Denied`; `Scan.Close(scan)`; `Watched.Scan(scan)` → `Update.Scan(state)`, whose `Found` names what the
caller's model moved to (e.g. a kit read, a pairing answered), never the text. A host sends the next frame only after
the previous call answered — the backpressure is the call itself. The inbound payload is **ADR 0130 decision 1's other
half**, written into that ADR in phase 6. `SecretFreeSchemaTest` stays as it is: no field carries the text.

## Phases

- [x] **1 the decoder** — `qr/` in commonMain (D1); ZXing's blackbox sets `qrcode-1..6` fetched by a Gradle task
  pinned by the zxing commit and each file's SHA-256 into `build/` (never committed), read in `jvmTest` through
  `javax.imageio`, each set held to its upstream thresholds per rotation; our own fixtures committed small (≤ 200 KiB
  in all) under `core/src/commonTest/resources/qr/`: a `veyqpair:`, a `veyqsignin:` and a `veyqkit:` code as the web's
  `qrcode` draws them, the same inverted, and a photo-like degraded set (rotated, perspective, blurred, low contrast)
  made once by a committed script, as greyscale PGM so the native tests read them too; the per-frame time of a
  1280×720 frame measured on JVM and `macosArm64` and recorded. · Done when: `./gradlew :core:jvmTest
  :core:macosArm64Test` green with the corpus at or above every upstream threshold; a bite — Reed–Solomon's correction
  disabled — turns the corpus red.

  **Status: done (2026-11-13).**

  **Built:**
  - core `qr/` (commonMain): ZXing's QR path ported from tag `zxing-3.5.4`, commit `f651b0a0` (Apache-2.0; `qr/NOTICE`
    with ZXing's own NOTICE, `qr/LICENSE-ZXING`; each file names its upstream files and what changed) — `BitMatrix`
    and `BitSource`, `Binarizer` (Hybrid with the global histogram below 40 px), `Geometry` (`PerspectiveTransform`,
    `GridSampler`), `Detector` (finder and alignment pattern finders), `ReedSolomon` (QR's GF(256): decoder, and the
    encoder phase 2 uses), `Version` (with `ErrorCorrectionLevel`, `FormatInformation`, `DataMask`, `Mode`),
    `BitStreamParser` (with `guessCharset`), `QrDecoder`. Public: `QrFrame(width, height, rowStride, luminance)` and
    `QrDecoder.decode(frame, tryHarder = false): String?`, which tries the frame as it is and then inverted, each also
    mirrored.
  - `:core:fetchQrCorpus` (`core/build.gradle.kts`): the 363 files of `qrcode-1..6` from raw.githubusercontent.com at the
    pinned commit into `build/qr-corpus`, each held to its SHA-256 in `core/qr/zxing-corpus.sha256`; `jvmTest` depends
    on it. `QrCorpusTest` (jvmTest) runs `AbstractBlackBoxTestCase`'s procedure: each image rotated bicubically to 0°,
    90°, 180°, 270° (`QrImages.rotate`, upstream's code), its luminance as `BufferedImageLuminanceSource` takes it, read
    as a pure code first and then through the detector, with and without trying harder; text and `ERRORS_CORRECTED`
    compared; no misread allowed.
  - Own fixtures: `core/qr/make_fixtures.py` (Python; the modules from the web's own `qrcode` through `node`) writes ten
    PGMs (176 418 bytes) under `core/src/commonTest/resources/qr/`: a `veyqpair:`, a `veyqsignin:` (margin 1, black on
    white) and the v1 vectors' `veyqkit:` with a server (margin 0, `#181426`, page white around), each inverted, and the
    pairing code rotated 25°, in perspective, blurred and low-contrast with noise and a shadow (seeded; a rerun writes
    the same bytes, measured by two runs' `shasum`). `QrDecoderTest` (commonTest, every target) reads them through
    `readRepoFile` (JVM `java.io`, native POSIX).

  **Met:**
  - `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64 :core:compileKotlinMingwX64`
    green. `QrCorpusTest` reads **exactly upstream's thresholds** on every set and rotation, with and without trying
    harder, none misread (qrcode-1 17/14/17/14 of 20, -2 32/30/31/31 of 35, -3 38/39/36/39 of 42, -4 36/35/35/35 of 48,
    -5 19 of 19 at each, -6 15/14/13/14 of 15) — the port reads what ZXing reads, no more, no less; the whole corpus in
    about 4.6 s.
  - Bites (`scratchpad/bite/bite.py`, one mutation at a time, `/bin/cp -f` restore, then the green run above), all six
    bit: Reed–Solomon's correction disabled → `QrCorpusTest` red (the CR's bite); the inverted retry removed → the
    inverted fixtures red; the mirrored retry removed → `aCodeSeenInAMirrorReads` red; Shift_JIS guessed as ISO-8859-1 →
    the corpus misreads; errors corrected not counted → the corpus' `ERRORS_CORRECTED` red; one pinned hash changed →
    `:core:fetchQrCorpus` refuses the file.
  - Every own fixture reads on the JVM and on `macosArm64`, with and without trying harder; a grey frame, noise and the
    top half of a code read nothing. ZXing itself (compiled from the pinned commit, `PlanarYUVLuminanceSource`) reads the
    same fixtures the same way, the inverted ones not at all (it tries no inversion).
  - The per-frame time (`aFramesCostIsMeasured`, printed, never asserted; this Mac, M-series): a 1280×720 frame with a
    pairing code **1.3 ms on the JVM, 13.5 ms on `macosArm64`**, without a code 1.5 ms / 19.4 ms; a kit page rendered
    2400×3394 (the code 610 px) 8.6 ms / 82 ms, trying harder 11.7 ms / 143 ms. The native numbers are the debug test
    binary's (no optimisation); a release framework is faster.

  **Deviations:**
  - **Kanji, Hanzi and the legacy ECIs are parsed, not decoded** (the CR left them out). Measured: 14 of qrcode-2's 35
    texts are Japanese (Shift_JIS byte segments and Kanji mode), so upstream's 32-of-35 threshold cannot be met without
    them. Common Kotlin has no Shift_JIS or GB2312 decoder, and a 7 000-entry table for codes veyq never draws is
    weight without use. So `BitStreamParser` reads every mode into `QrContent` pieces — text for numeric, alphanumeric
    and UTF-8/ISO-8859-1/ASCII bytes, the raw bytes and the set's name for the rest —, `QrDecoder.decode` answers null
    for a code with such a piece, and the corpus test decodes those pieces with the JVM's charsets. Structured append's
    header is read and ignored; FNC1 is honoured as upstream.
  - **The pure-code reading is ported** (`QrDecoder.pure`, internal) because upstream's harness tries it first; only the
    corpus test calls it.
  - The symbology identifier (`]Q1`) of two metadata files is not compared (the port keeps no result metadata but the
    errors corrected).
  - Both desktops' `run.py build` were not run: phases 1–3 add only common Kotlin, so what reaches the desktops is the
    core's compile for their targets, run above (`compileKotlinLinuxX64`, `compileKotlinMingwX64`).

- [ ] **2 the encoder and the PNG** — D2. · Done when: every fixture text encodes and decodes back through phase 1's
  decoder at level M, the largest byte payload level M holds (version 40, 2331 bytes) included and one byte more refused; a matrix from the core decodes in the web's `jsqr` (one
  committed PNG read by a test in `src/web/` — the one web line this CR adds) and the web's `qrcode` output decodes in
  the core (phase 1's fixtures); `QrPng` output reads back through `javax.imageio` to the same modules.
- [ ] **3 the scan model** — `QrScan` (D3) in commonMain over a played clock and decoder. · Done when: `commonTest`
  holds one decode in flight (a second frame dropped), a foreign code skipped, the first accepted one settling,
  `Closed` stopping it, an oversized frame refused; core checks green.
- [ ] **4 Android and Apple** — the camera views (D4) on the core's `QrScan`, with the test camera; the camera
  dependencies recorded in `gradle/libs.versions.toml` with their maintenance check. · Done when: on the throwaway AVD
  the virtual scene's poster of a fixture code settles a scan; on the simulator the test camera does; on this Mac the
  macOS build scans a code shown on another screen; `NoCamera` and `Denied` each reached once by hand; the Android and
  Apple builds green.
- [ ] **5 the bridge, Linux and Windows** — D5's calls with `WireTest` samples and `DispatchTest` (a frame in, a
  dropped frame, `Found` updated, `Close`); the Linux camera (D4, the crates recorded in `Cargo.toml` with their
  check) and the Windows camera (D4). **First measurement, stop and ask if it fails:** that the Camera portal and
  `pipewiresrc` run in the container's session at all (Broadway has no camera, so the test camera is the proof there
  and a real Linux session's camera the owner's step). · Done when: `./gradlew :bridge:jvmTest` and both `run.py
  build`/`test` green; each desktop's test camera settles a scan in its pass; the Windows VM's `NoCamera` seen.
- [ ] **6 docs and harvest** — a new ADR (numbered when written): *QR codes are read and drawn by the core* (D1–D3,
  the rejected alternatives with R2–R4); ADR 0130 decision 1 gains the inbound payload (D5); `native-apps.md` (what is
  built); `src/mobile/CLAUDE.md` gains each platform's camera trap met on the way. · Done when: the trust-chain guards
  (`for f in scripts/validate_*.py; do /usr/bin/python3 "$f"; done`) green; this CR deleted.

## Definition of done

Every app reads a QR through the one core decoder from its own camera or a test camera, says when it has none, and
the desktops draw a core-made code; the corpus thresholds, the round trips and the web interop are tests that run on
every `:core:jvmTest`.

## Owner questions

1. **A camera on the Linux and Windows desktops at all?** The CR builds it (a laptop has one); if the owner prefers
   desktops never to scan (the phone always does), phase 5 shrinks to the code's PNG and `NoCamera` everywhere.
2. **Real-camera passes** on a Linux laptop, a Windows laptop and an iPhone are the owner's steps (none of the test
   machines has a camera but this Mac); without them those three camera paths stay measured only through the test
   camera.

**Decided** (the coordinator, 2026-11-12, under the owner's standing mandate to decide and report; the owner may overturn any):
- 1: yes, the desktops get a camera (phase 5 as written).
- 2: the three real-camera passes stay the owner's steps; until then those paths are measured through the test camera only.
