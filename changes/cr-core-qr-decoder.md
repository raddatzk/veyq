---
id: cr-core-qr-decoder
title: QR codes in the Kotlin core — one decoder and one encoder in common Kotlin, one scan model, a camera per app
status: in-progress
satisfies: []
adrs: ["0130", "0131", "0123", "0059"]
invariants:
  - "one QR decoder and one QR encoder for every app, in the core's commonMain (ADR 0130 decision 1): no platform decodes or encodes a QR itself, no platform library (ML Kit, Vision, AVCaptureMetadataOutput, Windows' point-of-service scanner, zbar) is reached for a code's text"
  - "the decoder is memory-safe code: pure Kotlin, no C or C++ parser of camera frames in the process that holds the Master Key"
  - "what a code says never crosses the bridge: frames go in (ADR 0131 inbound payload), a drawn code comes out as shown bytes, the core keeps the text and answers only what the scan was accepted as"
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

- **Read first:** ADR 0130 (decisions 1, 2, 5), ADR 0131 (decision 1, and decision 3 for a file by path), ADR 0123
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
| R6 | **The bridge already has an inbound payload nothing uses:** `veyq_call` carries bytes beside the JSON (ADR 0131 § Context); ADR 0131 decision 1 decides only the outbound half. | `sed -n 14,30p docs/decisions/0131-*.md`; `grep -n CName bridge/src/bridgedMain/kotlin/me/raddatz/veyq/mobile/bridge/Exports.kt` |
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
deflate blocks, CRC-32, Adler-32 — no compressor needed), answered as shown bytes (ADR 0131 decision 1), so the text
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
the previous call answered — the backpressure is the call itself. The inbound payload is **ADR 0131 decision 1's other
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

- [x] **2 the encoder and the PNG** — D2. · Done when: every fixture text encodes and decodes back through phase 1's
  decoder at level M, the largest byte payload level M holds (version 40, 2331 bytes) included and one byte more refused; a matrix from the core decodes in the web's `jsqr` (one
  committed PNG read by a test in `src/web/` — the one web line this CR adds) and the web's `qrcode` output decodes in
  the core (phase 1's fixtures); `QrPng` output reads back through `javax.imageio` to the same modules.

  **Status: done (2026-11-13).**

  **Built:**
  - core `qr/QrEncoder.kt`: ZXing's `Encoder`, `MatrixUtil`, `MaskUtil` (and the parts of `BitArray`, `ByteMatrix` it
    uses) at level M, sharing the decoder's `Version` table (its alignment centres too) and `ReedSolomonEncoder`. Public:
    `QrEncoder.encode(text): QrMatrix` — the smallest version, the mask the penalty rules choose, an
    IllegalArgumentException past version 40 — and `QrMatrix` (`size`, `side` with the 4-module `QUIET_ZONE`,
    `dark(x, y)` over the drawn grid, the zone included). One segment, as upstream: numeric, alphanumeric, or the text's
    **UTF-8 bytes without an ECI**, as the web's `qrcode` writes a byte segment (upstream defaults to ISO-8859-1).
  - core `qr/QrPng.kt`: `QrPng.write(matrix, scale)` — a 1-bit greyscale PNG, stored deflate blocks, CRC-32 and
    Adler-32, at most `MAX_SIDE` 4096 px; version 40 at scale 8 (1480 px) is about 275 KB.
  - The interop: `src/web/src/features/account/pairing/__fixtures__/core-pairing-code.png` (the pairing fixture's text at
    scale 4, 3 676 bytes) and `coreQrCode.test.ts`, the web's one new test, which unpacks it with `node:zlib` and reads it
    with `jsqr`; the core's `QrPngImageTest` holds the file to `QrPng`'s output (rewritten with `VEYQ_WRITE_QR_FIXTURE=1`).
    The other direction — the web's `qrcode` read by the core — is phase 1's fixtures.

  **Met:**
  - `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64 :core:compileKotlinMingwX64`
    green; in `src/web`, `pnpm exec vitest run src/features/account/pairing/coreQrCode.test.ts`, `pnpm exec eslint` on it
    and `pnpm run typecheck` green.
  - `QrEncoderTest`: the three fixture texts and an empty, a numeric, an alphanumeric, a non-Latin (`Grüße — 漢字 ✓ 🙂`) and
    a link text each read back through phase 1's decoder at 3 px a module; each at level M (read from its format
    information) with a light quiet zone of four modules; 2331 bytes are version 40 and read back at 2 px a module, 2332
    are refused. `QrPngImageTest`: `javax.imageio` reads the kit's code, the 2331-byte code and a one-digit code pixel for
    pixel as the matrix; `java.util.zip.Inflater` inflates a 131 087-byte stream across three stored blocks with its
    Adler-32. The corpus still at upstream's thresholds, none misread.
  - Bites (`scratchpad/bite/bite.py`, `phase2.json`), eleven, ten bit: data not masked, the exhaustive retry removed, the
    empty-segment rule removed, the PNG's polarity inverted, Adler-32 started at 0, a stored block of 65 536 bytes, the
    capacity off by one, level L, a quiet zone of 3, the web fixture stale (the zlib header changed) — each its named
    test red. **Missed, as an equivalent mutant:** `chooseVersion` answering version 40 for an oversized text — the
    second refusal (`terminateBits`) still refuses it; both turned off together, the test is red. The web side bitten by
    hand: data not masked, the fixture rewritten from it → `coreQrCode.test.ts` red (`jsqr` reads nothing); restored and
    rewritten → green.

  **Deviations:**
  - **Phase 1's decoder gained two rules the round trip found.** (1) ZXing (the port, and ZXing itself, compiled from the
    pinned commit) does not read the core's own `veyqkit:` code drawn crisp at 3, 4 or 5 px a module: its finder stops
    once three confirmed patterns agree, and a run of data modules passed for the bottom-left one (rows 132 vs 172 at 3
    px). `QrDecoder.read` now scans the whole frame again (`FinderPatternFinder.find(exhaustive = true)`) when the first
    reading stopped early and failed; a frame with no code never stops early, so it costs nothing there, and the
    corpus' counts did not change. This matters for `cr-app-kit-sign-in`, whose kit page is a crisp rendering. (2) An
    empty byte segment is the empty text (upstream's guess names Shift_JIS for zero bytes, which the core leaves
    encoded).
  - The encoder writes UTF-8 without an ECI (above), and only level M: no caller of the CR asks for another.

- [x] **3 the scan model** — `QrScan` (D3) in commonMain over a played clock and decoder. · Done when: `commonTest`
  holds one decode in flight (a second frame dropped), a foreign code skipped, the first accepted one settling,
  `Closed` stopping it, an oversized frame refused; core checks green.

  **Status: done (2026-11-13).**

  **Built:**
  - core `qr/QrScan.kt`: `QrScan<T : Any>(accepts, decode = QrDecoder.decode, scope)` — `state`/`current`/`watch` as the
    other screen models; `State` `Starting`, `NoCamera`, `Denied`, `Scanning`, `Found(value)`, `Closed`; `camera(Camera)`
    (`RUNNING`, `NONE`, `DENIED`) from the host; `offer(frame): Boolean` — taken and decoded on the model's scope while
    `Scanning` and no other decode runs, else dropped (false), a frame over `MAX_EDGE` 1280 px on its long edge an
    IllegalArgumentException —; `close()`. A code `accepts` maps to null, or whose parse throws, is skipped and the scan
    goes on; only `Found`'s `T` leaves the model. Once `Found` or `Closed`, no frame is read and the camera changes
    nothing; a decode still running when the scan closes cannot settle it.

  **Met:**
  - `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64 :core:compileKotlinMingwX64`
    green. `QrScanTest` (commonTest, seven cases): one decode in flight and a second frame dropped; a foreign code skipped
    and the first accepted one settling, nothing read after it, the camera then changing nothing; a parser that throws
    skipping its code; `close` stopping the scan even with a decode in flight that ignores cancellation (as the real,
    CPU-bound decode does); nothing read before the camera runs, `NoCamera` and `Denied` as states; frames over 1280 px
    refused on either edge; the core's own decoder settling a scan on the rotated fixture, the states watched in order.
  - Bites (`phase3.json`), seven, all bit: the in-flight guard removed, a foreign code ending the scan, the parser's
    throw not caught, a decode settling after `close`, an oversized frame taken, a frame read before the camera runs, the
    camera moving a found scan. The `close` bite first **missed**: the played decoder suspended cancellably, so `close`'s
    job cancel hid the state guard; the played decoder now has a non-cancellable mode, which the real decoder is, and
    the bite then bit.

  **Deviations:**
  - **No played clock**: D3's model has no timing of its own — the backpressure is the in-flight flag, a host sends its
    next frame when the last call answered —, so the tests play the decoder and the dispatcher (`runTest`), not a clock.
  - `offer` refuses an oversized frame with an IllegalArgumentException rather than a third answer: a host that sends one
    has a bug, not a state; phase 5's `Scan.Frame` answers it as a refusal.

  **For phase 4 on, and for `cr-app-kit-sign-in`:**
  - A still image goes to `QrDecoder.decode(frame, tryHarder = true)` directly, not through `QrScan`: the decoder has no
    size limit (a 2400×3394 kit page measured above, about 8 MB of luminance and a 1 MB bit matrix); `QrScan`'s 1280 px
    bound is for a camera's stream only. A page with the code small on it reads; a crisp rendering is what the
    exhaustive retry of phase 2 exists for.
  - The text stays in the core: the caller's parser is `accepts` (a `veyqkit:` parser returning its kit data), and the
    model hands on only `Found(T)`. Through the bridge (phase 5) only luminance comes in — a frame, or a rendered page
    as one more inbound payload — and the answer names what the model moved to; on Android and Apple a Kotlin or Swift
    view holds the `QrScan` and observes it, so nothing is bridged at all.
  - Each host converts its camera's frame to `QrFrame` itself: the Y plane with its row stride (`rowStride` > `width` is
    taken as is), scaled to at most 1280 px on the long edge. A JVM decode of 1280×720 costs about 1 ms, the native debug
    test binary's about 14 ms; a release build should be measured once on a device in phase 4.

- [ ] **4 Android and Apple** — the camera views (D4) on the core's `QrScan`, with the test camera; the camera
  dependencies recorded in `gradle/libs.versions.toml` with their maintenance check. · Done when: on the throwaway AVD
  the virtual scene's poster of a fixture code settles a scan; on the simulator the test camera does; on this Mac the
  macOS build scans a code shown on another screen; `NoCamera` and `Denied` each reached once by hand; the Android and
  Apple builds green.

  **Status: built and measured (2026-11-13); the box stays open for the owner's three passes below (the macOS real camera, Apple's `Denied`, a real iPhone).**

  **Built:**
  - core: `qr/QrFrames.kt` — `fit(width, height, rowStride, luminance)`, a camera's plane taken as it is when it fits
    `QrScan.MAX_EDGE`, else box-averaged down by the smallest whole factor (one rule for every camera), and
    `timedDecode(report)`, the core's decoder reporting each frame's size, milliseconds and whether it read — never the
    text; `QrScan.ready`, so a camera copies a frame out of its buffer only when the scan would take it. `appleMain`
    `qr/AppleQr.kt`: the scan made for Swift (which sees neither default arguments nor a suspending decoder) and a frame
    from an `NSData` plane with one `memcpy`.
  - Android: `QrCamera.kt` — CameraX `Preview` (`PreviewView`) and `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`, YUV,
    1280×720 asked, the nearest lower then higher taken) bound to the screen's lifecycle, the Y plane offered; no
    `FEATURE_CAMERA_ANY`, or a CameraX start that finds no camera, → `NONE`; the `CAMERA` permission asked when the scan
    opens, refused → `DENIED`. It draws the preview while starting or scanning and hands every other state to its caller
    (`other`), so the view says no words of its own. Manifest: `CAMERA`, and `camera.any`/`camera` as not required.
    `DevScan` (debug; release a stub): the extra `veyq.devScan=1` shows the camera alone over a scan accepting any veyq
    code by its scheme, the states and times to logcat `veyq-scan`. CameraX 1.6.2 (`camera-core`, `-camera2`,
    `-lifecycle`, `-view`) in `libs.versions.toml` with its check; the verification metadata written from an empty
    Gradle home (`:android:assembleDebug :android:assembleRelease`: CameraX and what it brings — Guava 33.3.1-android,
    Media3 1.9.0 through `camera-video`, Dagger 2.59 —, 362 lines).
  - Apple: `QrCamera.swift` — an `AVCaptureSession` (`.hd1280x720` where offered, `420YpCbCr8BiPlanarFullRange`, late
    frames discarded), the back camera on iOS, the default on a Mac; no device → `NONE`, authorization asked once,
    refused → `DENIED`; `QrCameraView` (the preview layer, every other state the caller's) over `QrCameraModel`.
    `VEYQ_DEV_CAMERA=<image>` (debug): the image drawn grey is the frame offered ten times a second. `DevScan.swift` +
    `VeyqApp`: `VEYQ_DEV_SCAN=1` (debug) shows the harness. `NSCameraUsageDescription` in `project.yml`; the macOS app is
    neither sandboxed nor hardened, so no camera entitlement.

  **Met:**
  - `./gradlew :core:jvmTest --tests '*qr.Qr*' :core:macosArm64Test --tests '*qr.*'` green: `QrFramesTest` 6, `QrScanTest`
    8, `AppleQrTest` 2 on `macosArm64`. `./gradlew :android:assembleDebug :android:assembleRelease` green (with
    `ANDROID_HOME`); the macOS build (the brief's `xcodebuild … -destination 'platform=macOS' …`), a Release macOS build
    and the iOS Simulator build (`-destination 'id=<sim>'`) `BUILD SUCCEEDED`. Guards (`scripts/validate_*.py`, without
    guard_gaps) green.
  - **Android, a throwaway AVD** (`veyq_qr_tmp`, android-36 google_apis arm64, deleted after): the virtual scene's wall
    poster set to the web's interop fixture code (`-virtualscene-poster wall=<png>`; the 1-bit PNG did not load, an
    8-bit RGB copy did), the scene's camera moved to it over the emulator's gRPC (`-grpc 8590`, `setPhysicalModel`
    POSITION -0.6 0.3 2.6, ROTATION 0 180 0 by `curl --http2-prior-knowledge`) → logcat `decoded 640x480 in 6.4 ms, a
    code read`, `state Found(veyqpair)`, the harness showing the scheme. **Denied** by "Don't allow" in the permission
    dialog (`state Denied`). **NoCamera** with the emulator started `-camera-back none -camera-front none`: the image
    still claims `camera.any`, CameraX's start threw `InitializationException` out of `ProcessCameraProvider.get()` and
    **crashed the app** — fixed (caught, `NONE`), then `no camera: InitializationException`, `state NoCamera`; the final
    build re-ran the Found pass.
  - **Apple:** the iOS Simulator (a throwaway iPhone 18 Pro, iOS 27.0, deleted after) has no camera: `VEYQ_DEV_SCAN=1`
    → `state NoCamera`; with `VEYQ_DEV_CAMERA=<1280×720 frame with the pairing code>` → `decoded 1280x720 in 14.1 ms, a
    code read`, `state Found(veyqpair)`. The macOS debug build with the test camera: `Found(veyqpair)` likewise.
  - **The time per frame in a release build** (each decode's `veyq-scan` line; this Mac under a load average of 30–250
    from parallel work, so the tails are the machine's, the medians the decoder's):
    | build | frame | with a code | no code (median / p90) |
    |---|---|---|---|
    | macOS **Release** (Kotlin `linkReleaseFrameworkMacosArm64`; Swift built with `SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEBUG` so the harness exists) | 1280×720 | 1.8–1.9 ms (5 runs) | 6.3 / 8.4 ms (176 frames) |
    | macOS debug | 1280×720 | 14.6 ms | 19.7 / 21.4 ms |
    | Android **release** APK (non-debuggable, the harness copied into the release source set for this build only, signed with the debug key; not committed) on the AVD | 640×480 | 89 ms (one frame, under load) | 0.8 / 51 ms (148 frames) |
    | Android debug on the AVD | 640×480 | 6.0–6.4 ms | 1.4 / 68 ms (796 frames) |

    A frame with no code costs more than one with a code on native (the inverted and mirrored retries run only when
    the plain reading fails). A release native decode of a 720p frame is well under a frame's 33 ms; no throttling is
    needed beyond the one-in-flight rule.
  - Bites (`scratchpad/qr4/bite.py`, one mutation at a time, `/bin/cp -f` restore), five, all bit: `fit` scaling by the
    floor factor (1281 px kept) → `QrFramesTest` red; `fit` reading rows at the width, not the stride → red; `ready`
    ignoring a decode in flight → `QrScanTest` red; the timing saying every frame read → `QrFramesTest` red; `AppleQr`
    ignoring the row stride → `AppleQrTest` red (macosArm64). The platform paths were bitten by the device itself: the
    no-camera AVD crashed the first build (above), and Denied/NoCamera/Found were each read from the log.

  **Deviations:**
  - **The emulator's virtual-scene camera delivers 640×480 at most**, not the 1280×720 asked; the 720p path is measured
    on Apple (the test camera's 1280×720 frame) and in `QrFramesTest`.
  - **The poster is not in view by default** (it hangs behind the scene camera's start): the pass moves the camera over
    gRPC, which needs the emulator started with `-grpc <port>` (no auth, and bound on every interface while it ran; the
    default endpoint asks for a JWT). A one-off for the pass, nothing committed.
  - **The macOS real-camera pass ("a code shown on another screen") and Apple's `Denied` are not run**: the first camera
    use asks the person through macOS's privacy prompt, a human step by the brief, and resetting it (`tccutil`) is a
    system setting not mine to change. Owner's steps below.
  - The Android release measurement used a build with the debug harness copied in (restored with `/bin/cp -f`, never
    committed): a release build has no door to the camera without a sign-in, and no caller exists yet.

  **Owner's steps (pending):**
  1. macOS real camera: `cd src/mobile/apple && xcodebuild -project Veyq.xcodeproj -scheme Veyq -destination
     'platform=macOS' -derivedDataPath ../build/apple-mac CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM=
     CODE_SIGN_ENTITLEMENTS= build`, then `VEYQ_DEV_SCAN=1 ../build/apple-mac/Build/Products/Debug/veyq.app/Contents/MacOS/veyq`;
     allow the camera, hold up a veyq code on another screen (the web's pairing code): the window shows its scheme, and
     `veyq-scan: state Found(veyqpair)` is printed.
  2. Apple `Denied`: the same start after refusing (or in System Settings › Privacy & Security › Camera switching veyq
     off): `veyq-scan: state Denied`.
  3. A real iPhone's camera (the CR's owner question 2), with `VEYQ_DEV_SCAN=1` in the scheme's environment.
- [ ] **5 the bridge, Linux and Windows** — D5's calls with `WireTest` samples and `DispatchTest` (a frame in, a
  dropped frame, `Found` updated, `Close`); the Linux camera (D4, the crates recorded in `Cargo.toml` with their
  check) and the Windows camera (D4). **First measurement, stop and ask if it fails:** that the Camera portal and
  `pipewiresrc` run in the container's session at all (Broadway has no camera, so the test camera is the proof there
  and a real Linux session's camera the owner's step). · Done when: `./gradlew :bridge:jvmTest` and both `run.py
  build`/`test` green; each desktop's test camera settles a scan in its pass; the Windows VM's `NoCamera` seen.
- [ ] **6 docs and harvest** — a new ADR (numbered when written): *QR codes are read and drawn by the core* (D1–D3,
  the rejected alternatives with R2–R4); ADR 0131 decision 1 gains the inbound payload (D5); `native-apps.md` (what is
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

## Retrospective

Phases 1–3 (the implementer, 2026-11-13):
- **The CR's "Kanji left out" could not meet its own bar.** 14 of qrcode-2's 35 images are Japanese, so upstream's
  thresholds need the Kanji mode and a Shift_JIS byte guess; measured in the first corpus run, solved by keeping such
  segments as bytes (phase 1's deviation). Root cause: the refine counted the corpus' images (R5) but did not read the
  expected texts. Next time: a CR that adopts a corpus' thresholds reads what the corpus expects before scoping out a
  feature.
- **A round trip found what the corpus did not.** ZXing does not read its own crisp version-10 code at 3–5 px a module
  (a data run taken for a finder pattern); the corpus is photos and never shows it. The encoder's round trip did, and
  so would every rendered kit page. Next time: hold a port to its upstream corpus *and* to the product's own outputs,
  both directions, before calling it done.
- **Two bites missed first, both for a reason worth keeping:** a capacity check behind a second refusal (equivalent
  mutant, bitten with both off) and a `close` guard hidden by the played decoder's cancellability (the test now plays
  the real decoder's non-cancellable decode). The worktree's write hook forbade the Write tool into a worktree created
  by `git worktree add` until the session entered it (`EnterWorktree` with its path): a brief that has the implementer
  create its own worktree should say so.

Phase 4 (the implementer, 2026-11-13):
- **The device found the bug the build did not.** An emulator started without a camera still claims `camera.any` (the
  feature list is the system image's), and CameraX's start then throws out of `ProcessCameraProvider.get()`: the first
  NoCamera pass crashed the app. Next time: a "no camera" state is reached by the device that lacks one, never assumed
  from a feature flag alone — the CR's "`FEATURE_CAMERA_ANY` absent → `NoCamera`" was only half of it.
- **The emulator's camera pass cost more than the code.** The virtual scene's poster hangs behind the camera's start, a
  1-bit PNG silently does not load, and moving the camera needs the emulator's gRPC (JWT by default). The recipe is in
  phase 4's Met; phase 6 should move it into `src/mobile/CLAUDE.md`'s traps so the next camera pass starts from it.
- **"Measure the release build on a device" had no door**: a release build reaches the camera only through a caller, and
  none exists yet. Measured instead through builds made for the measurement (a Release macOS build with the harness
  condition set, an Android release APK with the harness copied in and restored). A CR that asks for a release-build
  number should name how that build reaches the code.

