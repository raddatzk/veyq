---
id: cr-app-kit-sign-in
title: Sign in with the Emergency Kit on every app — the kit read in the core, its page drawn by the platform, its QR scanned on a phone
status: planned
satisfies: ["en.account.key-model", "uc.account.unlock"]
adrs: ["0130", "0131", "0047", "0123", "0059"]
invariants:
  - operator-blind E2EE — the kit is read on the device only; nothing of it (file, page, frame, token, Secret Key) reaches the server or any other host; the sign-in sends what a typed sign-in sends
  - no plaintext at rest but where the person saves it (ADR 0047, carried to the apps) — no app copies the kit file, a drawn page or a camera frame to a cache, temp or log; the bytes are read into memory, capped, and zeroed where the language lets us
  - every rule is the core's (ADR 0130 decision 1) — the token's decode, the read order, steps 1, 2 and 4 and the QR decoder are one Kotlin implementation; a platform only picks, draws a page or a picture, and runs a camera
  - the bridge only carries (ADR 0131; SecretFreeSchemaTest) — no Secret Key, token or kit byte travels outward; the host's drawn pixels ride inward beside a reply, never in JSON
  - no new dependency that is deprecated, vendor-locked or closed (root CLAUDE.md), each checked and recorded where it is added
  - the web's kit import and the Android/Apple/desktop sign-ins without a kit behave as before; `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` and both desktops' `run.py build` stay green in every phase
---

## Goal

Inbox #842 (owner 2026-11-12: after Depot, before Memories). Today only the web reads an Emergency Kit; on the apps a
person types the server, the email and 54 characters of Secret Key. This CR lets every app — Android, iOS/iPadOS and
macOS (the Apple app), Linux (GTK 4/Rust over the bridge) and Windows (WinUI 3/C# over the bridge) — take the kit
instead:
- **a kit file picked from the files** (iCloud Drive and Files on Apple, the system's document picker on Android, the
  open dialog on the desktops): the PDF, also one re-saved, printed to PDF or photographed into a PDF, or a picture of
  its QR — as the web's one control takes;
- **its QR scanned with the camera** wherever the app has one, through the scanner `cr-core-qr-decoder` builds (the
  phones and tablets, the Mac, and the desktops' cameras where that CR's owner question 1 keeps them);
- the sign-in form then holds **email, Secret Key and server** (the server editable), and asks only for the password.
  The sign-in opens the account's tenants on that server as a typed sign-in does.

A PDF is read in the owner's final order (2026-11-12): **(1)** the raw `veyqkit:` token in the file's bytes; **(2)** the
token in the document information's `Keywords`; **(3)** the QR on the first pages, drawn to pixels by the platform and
decoded by the core; **(4)** the first address field of the PDF's form, **only** where the token carries no `srv`. The
printed email and Secret Key are never read as text; address fields 2 and 3 are never read (a kit is one instance's; they
are the person's notes).

## Context & constraints

- **Substrate:** branch `claude/windows-cr-f6c73c` at `d313c1f4f` (the Depot CR harvested and deleted, tree clean).
  Depot's PDF renderers on all four platforms and the bridge's path-based file reads (ADR 0131 decision 3) are this CR's
  floor.
- **Read first:**
  - `docs/features/account/enablers/en.account.key-model.md` § The Emergency Kit (the token, "the server is a hint,
    never a check", the web's fallback chain) and § The Secret Key's text form;
  - `docs/features/account/use-cases/uc.account.unlock.md` main flow step 1 and Quality notes (*One Secret-Key control*,
    *A kit from the wrong account warns*, *A kit made at another address says where*);
  - ADR 0130 (decisions 1, 2, 4, 5), ADR 0131 (all four decisions), ADR 0047's Consequences (*The apps hold the same
    rule*), ADR 0123 decision 9 (each platform's guideline), ADR 0059 (device linking — the other user of a QR);
  - `src/mobile/CLAUDE.md` whole: Prove your work, each platform's guidelines, the traps;
  - the web as the reference: `src/web/src/features/account/emergencyKit/emergencyKit.ts` (`importKitFromFile`,
    `kitFromPdf`, `KIT_PICTURE_PIXELS` 2400, `KIT_PDF_PAGES` 3, both polarities), `emergencyKit.import.test.ts` (what
    each fixture is and what was measured), `KitScanner.vue` (the camera loop that keeps scanning past a QR that is not
    ours), `src/packages/crypto/src/emergencyKit.ts` (`parseKit`: the first `veyqkit:` match, the checks in order).
- **The sibling CR `cr-core-qr-decoder` (planned, written in parallel; read at refine as `changes/cr-core-qr-decoder.md`)
  owns everything QR:** the decoder (a Kotlin port of ZXing's QR reader in `core/.../qr/`, trying the inverted image too,
  its D1), the scan model `QrScan<T>(accepts)` (its D3: greyscale frames of at most 1280 px, one decode in flight, foreign
  codes skipped, `Found(T)`, `NoCamera`, `Denied`), a camera view per app (its D4: CameraX, AVFoundation on iOS and macOS,
  `MediaCapture` on Windows, the Camera portal and PipeWire on Linux, each with a debug test camera) and the bridge's
  `Scan.*` calls with frames as `veyq_call`'s inbound payload (its D5). This CR specifies none of it and adds no camera
  dependency. What this CR takes from it — its phases 1–3 have landed (re-read at phase 5, 2026-11-13):
  - `QrDecoder.decode(QrFrame, tryHarder = true)` reads one still picture apart from `QrScan`, at any size (a
    2400×3394 kit page measured there); `QrFrame(width, height, rowStride, luminance)` is the drawn page's type, so
    `KitPictures` answers `QrFrame`s; `QrScan`'s 1280 px cap is the scan model's, not the decoder's;
  - `QrScan`'s `accepts` is where the kit's parser goes, and its `Found(T)` carries the read `Kit` to the sign-in model
    without the text crossing the bridge — the text stays in the core; for a file the same holds: `KitImport` hands the
    decoded text straight to `EmergencyKit.read`, and only the `Kit` leaves;
  - its fixtures include a `veyqkit:` code; the web's three kit PDFs are this CR's (phase 5).
  Its phases 4–5 have landed too (re-read at phase 7, 2026-11-13): Android's `QrCamera` (CameraX) and Apple's
  `QrCamera`/`QrCameraView` (AVFoundation) over a `QrScan`, each with its debug test camera, and the bridge's `Scan.*`
  calls with one kind, `SCHEME` — a `kit` kind was left to this CR, and joins in phase 9 with the desktops' file entry.
  **Shared files:** both CRs extend ADR 0131 (that CR decision 1 with a call's inbound payload, this one decision 3 with a
  host reply's drawn page, D6), `docs/crosscutting/native-apps.md` § Built and `src/mobile/CLAUDE.md`; each writes its own sentence or bullet, and
  the second to implement starts from the first's commit.
- **Where what runs** (as in the Depot CR): the Kotlin on this Mac; Linux in the container (`linux/run.py run --port
  8097 --devlogin --no-secret-service`, Broadway, driven from headless system Chrome when the browser pane is hidden);
  Windows on the ARM64 VM (`windows/run.py run --devlogin`, `run.py drive`); Android on a throwaway AVD (never
  `veyq_pixel`); Apple on a throwaway iPhone simulator and the ad-hoc macOS build. The dev backend
  (`veyq.localhost:8080`) is never started twice or stopped.
- **Test kits carry fake or dev data only.** The web's fixtures are fake (`alex.beispiel@example.org`, the Secret Key of
  the bytes 0..31). A live sign-in uses a kit built in the test from the dev principal's credentials
  (`VEYQ_ENV_FILE`), held in memory; where a path is needed (the bridge, a desktop pass) it is written under the test's
  own `build/` directory and removed in a `finally`; it is never committed and never printed.

## Measured at refine (2026-11-12)

From an Explore pass over `src/mobile/` and the web, the load-bearing rows checked again here. Commands from the
repository root unless named:
- `grep -rniE 'veyqkit|EmergencyKit|emergency_kit' src/mobile` → no hit
- `grep -noE 'v\.(obj|arr|list)\("[a-z_0-9-]+"\)' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Conformance.kt` (no `emergency_kit_token`)
- `grep -n emergency_kit_token -A20 contracts/crypto/vectors/v1.json`
- `grep -rniE 'camera|AVCapture|CameraX|\bQR|zxing|barcode|VisionKit|DataScanner|mlkit' src/mobile --exclude-dir=build`; `grep -n -i camera src/mobile/apple/project.yml src/mobile/android/src/main/AndroidManifest.xml` (none)
- `grep -rniE 'inflate|zlib|deflate' src/mobile/core/src` (none); `ls ~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.21/klib/platform/{mingw_x64,linux_x64,linux_arm64,macos_arm64,ios_arm64} | grep zlib`
- `for f in src/web/src/features/account/emergencyKit/__fixtures__/*.pdf; do LC_ALL=C grep -a -o -E '%PDF-[0-9.]+|/Keywords|/AcroForm|/T ?\(address[0-9]\)|/V ?\([^)]*\)|/Type ?/(XRef|ObjStm)|veyqkit:' "$f" | sort | uniq -c; done`
- `sed -n 95,115p src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/SessionModel.kt`; `sed -n 1420,1450p src/mobile/bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt`; `sed -n 25,40p src/mobile/bridge/bridge.h`

| # | What | Where |
|---|---|---|
| R1 | **No app reads a kit; the Secret Key's text form is ported.** `SecretKeyText.read/format/fold` (Crockford base32, 54 symbols); the conformance runner reads `secret_key_text` and 25 other sections, **not** `emergency_kit_token` (3 cases with and without `srv`, 9 refused). | `core/.../crypto/SecretKey.kt`, `Conformance.kt` |
| R2 | **The sign-in already has every field.** `SessionModel.signIn(server, email, password, secretKey)`; the state `Gate.SignIn(working, error, adding, server?, email?)` — the Secret Key and password never in it; `origin(typed)` turns a typed host into an origin. All four UIs draw server, email, password and Secret Key: Android `AppRoot.kt` `SignInScreen`, Apple `SignInView.swift`, Linux `sign_in.rs`, Windows `SignInView.cs` → `Call.SessionSignIn(server, email, password, secretKey)`. No other app screen asks for a Secret Key. | `core/.../session/SessionModel.kt:104-110, 192, 475`; `Account.kt:589` |
| R3 | **Every platform already draws a PDF from memory**: Android `PdfRenderer` (Depot, from a `memfd`), Apple PDFKit (`PDFPages.swift`), Linux `poppler-rs` 0.26 over poppler-glib (`Document::from_bytes`; checked and recorded in ADR 0123's Consequences: GNOME World/Rust, MIT), Windows `Windows.Data.Pdf` (`PdfPages.cs`). All four are the platform's own or already vetted; this CR adds none. | `FileScreen.kt`, `PDFPages.swift`, `linux/Cargo.toml:17-20`, `windows/Veyq/PdfPages.cs` |
| R4 | **No camera and no QR anywhere**; no camera permission in the Android manifest or `project.yml`. | — |
| R5 | **The bridge carries bytes both ways already in its C signature, not yet on the host's way back.** `veyq_call(request, bytes, len, …)` and `veyq_reply(token, json, bytes, len)` take a payload; `HostRequest` has only `Store.Read/Write/Remove`, `HostReply` only `Value/Done/Failed`. A picked file crosses as a path the core opens (`blob/FileSource.kt`, `Bridge.kt` reads a whole file under a cap for a cover and receipts). | `bridge.h`, `Wire.kt:1423-1449`, `Host.kt` |
| R6 | **No inflate in the core; zlib is in Kotlin/Native's platform libraries for every native target** the core builds (mingw_x64, linux_x64/arm64, macos_arm64, ios_arm64 in 2.4.21); the JVM and Android have `java.util.zip.Inflater`. | `~/.konan/...`, `core/build.gradle.kts` |
| R7 | **The fixtures:** `kit-pdfkit-saved.pdf` (PDF 1.3, `veyqkit:` raw twice, `/Keywords`, an `/AcroForm` with `address1` = `familie.example.org` and 2/3 filled, 16 Flate streams, no object or xref streams); `kit-printed.pdf` and `kit-photo.pdf` (PDF 1.3, no token, no `/Keywords`, no form). All three carry `srv` = `https://familie.example.org` — **no fixture exercises step 4**, and none an object stream, so phase 3 builds synthetic PDFs in the test, as the web's `pdfWithKeywords` does. | `src/web/src/features/account/emergencyKit/__fixtures__/` |
| R8 | **A core test reads the web's fixtures already**: `MediaMetadataFixtureTest` reads `src/web/src/features/memories/__fixtures__/` from `veyq.repoRoot` (JVM) / `VEYQ_REPO_ROOT` (native); `inputs.file` is declared for the vectors only. | `core/build.gradle.kts:282-300`, `core/src/jvmTest/.../MediaMetadataFixtureTest.kt` |
| R9 | **The web's words for the kit exist in both languages**: `secretKeyHint`, `upload` ("Upload Emergency Kit"), `scanHint`, `scanError`, `kitUnreadable`, `kitOtherServer`. The apps' catalog has `signin_*` keys and no kit key. | `src/web/src/i18n/index.ts:1360-1373, 3553-3561`; `src/mobile/words/words.json` |
| R10 | **No published page speaks for the native apps** (`git grep -li "linux\|windows" -- site/src/content/docs` → `run/operations.md` only). The kit page (`getting-started/emergency-kit.mdx`, EN+DE) declares `en.account.key-model` in `sources:` and describes the web. | `site/` |
| R11 | **The state says only the web reads a kit** ("two writers and one reader … only `web` reads it, at unlock"), and has no step (d). | `en.account.key-model.md` § The Emergency Kit |

Remembered, not measured at refine (each a phase's first measurement): `StorageFile.GetFileFromPathAsync` opens a
picked path in an unpackaged WinUI app; poppler-rs has `Document::from_file`; Kotlin/Native's `platform.zlib` links into
the mingw DLL without a separate `zlib1.dll`. The camera facts are `cr-core-qr-decoder`'s.

## Decisions

### D1 — The token's decode is a core codec, held to the vectors' reader half

`crypto/EmergencyKit.kt`: `EmergencyKit.read(text): Kit?` finds the **first** `veyqkit:` followed by base64url symbols
(as `parseKit` does), decodes it and checks, in `parseKit`'s order: `v == 3`; `email` a non-empty string; `srv`, where
present, a string that equals the http or https origin it spells; `sk` read by `SecretKeyText.read`. A `Kit` holds the
email, the Secret Key's 32 bytes and the server (null where the token has none). Any failure answers null — one answer,
as on the web; the UI says one thing (R9's `kitUnreadable`).
- **The origin check needs no URL library.** A WHATWG-serialized http(s) origin is ASCII, lower-case in its host, has no
  path, no trailing slash, no userinfo and no default port. So the check parses scheme, host and port by hand and
  compares the re-serialization with the input; any non-ASCII `srv` cannot equal its own serialization and is refused
  (a punycode host passes as the ASCII it is). The refused vectors are the bite.
- **Only the reader half of the vectors.** The apps write no kit (the web and admin-web do), so `Conformance` reads each
  case's token back to its three values and refuses each refused token; the writer half stays the web's. Stated in the
  runner's comment so a later writer adds it.
- The decoded key bytes are handed on, never formatted to a `String` in the core beyond what `SecretKeyText` does;
  intermediate buffers (`ByteArray`) are zeroed after use. A JVM or Kotlin/Native `String` cannot be wiped — the same
  residual the typed Secret Key has today, stated, not new.

### D2 — The read order is one core function over bytes, a page drawer and the decoder

`account/KitImport.kt` (or beside `SessionModel`, the implementer's choice): `KitImport.read(bytes, pictures): Found?`
where `pictures` is a platform interface `KitPictures`:
- `suspend fun pdfPages(): Int?` — the PDF's page count as the platform's renderer opens it (null: it does not open);
- `suspend fun pdfPage(page: Int, width: Int): QrFrame?` — page `page` drawn `width` px wide, as 8-bit luminance;
- `suspend fun picture(longSide: Int): QrFrame?` — the file decoded as an image, scaled so its longer side is `longSide`.

The order, for a file of at most `KIT_FILE_CAP` (32 MiB — a photographed kit at 200 dpi is 230 KB, a scan app's
multi-page PDF a few MB; a larger file is refused unread, never read whole):
1. the bytes read as Latin-1 through `EmergencyKit.read` — a hit ends the read **without opening a page**;
2. if the bytes start with `%PDF-`: the `Keywords` of the document information through the core's PDF reader (D3);
3. the QR: pages 1..min(n, 3) drawn 2400 px wide (the web's `KIT_PDF_PAGES`, `KIT_PICTURE_PIXELS`), each decoded by the
   sibling's decoder (`QrDecoder.decode(frame, tryHarder = true)`, the text straight to `EmergencyKit.read`); a file
   that is not a PDF goes to `picture(2400)` and its decode instead;
4. **only where the token found carries no `srv`**: the first address field (D3), read as a typed server
   (`SessionModel.origin`, then the same origin check as D1); empty or unreadable → no server.

`Found` = the `Kit` and where its server came from (`TOKEN`, `FIELD`, `NONE`). Steps 2 and 4 never throw out: a PDF the
core's reader cannot follow answers "nothing here" and the order continues.
- **Why the platform draws and the core decides:** drawing a PDF needs a full renderer (fonts, images, transforms), and
  each platform already ships one (R3); everything else — where to look, in which order, how wide, how many pages, what
  counts — is a rule and so the core's (ADR 0130 decision 1). Rejected: a PDF renderer in the core (a second renderer
  beside four that exist, and pdfium/MuPDF are C libraries with their own CVE stream); the platform's own metadata API for
  step 2 (`PDFDocument.documentAttributes`, poppler's `keywords()`) — Android's `PdfRenderer` and `Windows.Data.Pdf` have
  none, so step 2 would be two implementations and two missing.
- **Why Apple uses the core's decoder too**, though AVFoundation/Vision decode QR: one decoder held to one test set reads
  the same kits everywhere; a second decoder on one platform would answer a damaged photo differently with no shared test
  to say so (owner 2026-11-12: one decoder in the core).

### D3 — The core's PDF reader is tolerant and small: an object scan, not an xref walk

`account/KitPdf.kt` reads only what steps 2 and 4 need, the way a tolerant reader recovers a broken file:
- **Objects:** every `N G obj … endobj` in the bytes, and every object of every object stream (`/Type /ObjStm`,
  `/FlateDecode` only) — a later definition of the same number wins (an incremental update appends). The xref table and
  xref streams are **not** followed: a scan finds the same objects in a well-formed file and still finds them in one whose
  offsets a re-save broke.
- **The document information:** the `/Info` reference of the **last** trailer (`trailer << … >>`) or xref-stream dictionary
  that names one; its `/Keywords`, resolved if indirect.
- **The first address field:** the catalog's `/AcroForm` `/Fields`, walked through `/Kids`, the field whose fully
  qualified name is `address1` (the name both writers give it — `emergencyKit.ts` `field.fieldName`, admin-web's twin);
  its `/V`. No other field is read.
- **Strings:** literal strings with every escape (`\n \r \t \b \f \( \) \\`, octal `\ddd`, a backslash line continuation,
  balanced parentheses), hex strings (odd length padded), then UTF-16BE with its BOM, UTF-8 with its BOM (PDF 2.0), else
  PDFDocEncoding (Latin-1 for the ASCII a token is made of).
- **Inflate** is the platform's zlib: `java.util.zip.Inflater` on the JVM and Android, `platform.zlib` on every native
  target (R6), behind one `expect fun inflate(bytes, cap): ByteArray?`. An object stream inflating past 8 MiB is skipped
  (a bomb costs nothing). Rejected: a hand-written inflate (a second implementation of a format zlib has carried for
  thirty years); a third-party Kotlin PDF library (none maintained for Kotlin Multiplatform; PDFBox is JVM-only).
- **An encrypted PDF** (`/Encrypt` in a trailer) skips steps 2 and 4 — its strings are ciphertext — and goes to step 3,
  where a renderer that opens it without a password (an owner-password-only file) still draws the QR. Stated as the
  residual it is; neither writer encrypts a kit.

### D4 — The sign-in model holds the kit's Secret Key; no UI ever does

`SessionModel` gains `readKit(bytes, pictures)` (the phones) and the bridge's path variant (D6), and `clearKit()`:
- **State:** `Gate.SignIn` gains `kit: KitShown?` = the kit's email, its server and where the server came from. Reading a
  kit fills the form's **email** and **server** in the state (the server editable as any typed one; a kit with no server
  and no field keeps what the form had) and **holds the Secret Key inside the model** — the state, the bridge and every UI
  only know *that* a kit's key is held, and draw the Secret Key row as "from your Emergency Kit" with a way to clear it.
- **The sign-in:** `signIn(server, email, password, secretKey)` with an empty `secretKey` and a held kit signs in with the
  held key; a typed `secretKey` wins over a held kit (the person changed their mind) and clears it.
- **A pasted token in the Secret Key field is read as a kit's key** at sign-in (uc.account.unlock step 1: "pasting its
  token in place of the key"): the key taken, its email and server not applied (the person is already typing).
- **The held key is cleared** on `clearKit()`, on a new kit read (replaced), on Cancel and on leaving the sign-in, and
  after a sign-in that succeeded; a failed one keeps it (the person retypes the password, not the kit). Its bytes
  are zeroed when cleared.
- **The other-server notice** (uc.account.unlock's *A kit made at another address says where*): where the kit's server
  replaced a server the form already held that was different, the state says so as information (R9's `kitOtherServer`
  shape: made at, typed); never a refusal.
- **Why the model holds it:** the bridge may carry nothing secret outward (ADR 0131, `SecretFreeSchemaTest`), and the
  phones' UIs have no more reason to hold the key than the desktops' — the person never needs to see it. One rule on all
  four keeps one test.

### D5 — The phones read the file in memory and draw from memory

- **Android:** `ActivityResultContracts.OpenDocument` with `application/pdf` and `image/*`; the URI read through
  `ContentResolver.openInputStream` into a capped `ByteArray` (never `cacheDir`, never a copied file); `KitPictures` over
  `PdfRenderer` from a `memfd` holding those bytes (Depot's way, ADR 0047) and `BitmapFactory`/`ImageDecoder` for a
  picture, the luminance taken from the bitmap's pixels.
- **Apple:** `.fileImporter(allowedContentTypes: [.pdf, .image])` (iCloud Drive and Files on iOS/iPadOS, the open panel
  on macOS); the security-scoped URL read with an `NSFileCoordinator` read (it brings an iCloud file down into the
  person's own iCloud container, not ours) into `Data`; **never** a document picker `asCopy` (it copies into the app's
  `tmp/Inbox`). `KitPictures` over `PDFDocument(data:)` and `CGImageSource`, drawn into an 8-bit grey `CGContext`.
- On both, the `ByteArray`/`Data` is let go after the read, the Kotlin side zeroed.

### D6 — The desktops hand a path; the core asks the host to draw (ADR 0131 extended inward)

- `Call.SignInKit(path)` → the core reads the file by path under `KIT_FILE_CAP` (`FileSource`, as `Editor.AddReceipts`
  does) and runs D2 with a `KitPictures` the bridge implements as **host requests**: `HostRequest.Pdf.Pages(path)`,
  `Pdf.Page(path, page, width)`, `Picture.Luma(path, longSide)`, answered `HostReply.Pixels(width, height)` with the
  luminance as the reply's payload (`veyq_reply`'s `bytes`, R5), `Value` for the page count, or `Failed`. The answer to
  `SignInKit` is `Done` (the gate's update carries `kit`) or `Refused(NO_KIT)`. `Call.SignInKitClear` clears it.
- **Each host draws from the path the core names**, with what it already uses: Linux `poppler-rs` (`Document::from_file`,
  or `from_bytes` over a read into memory) and `gdk::Texture::from_filename`/`gdk_pixbuf` for a picture; Windows
  `StorageFile.GetFileFromPathAsync` → `PdfDocument.LoadFromFileAsync` / `BitmapDecoder`, rendered into an
  `InMemoryRandomAccessStream`, never a file.
- **What crosses:** inward a path (as every picked file does) and pixels in a reply's payload; outward a path and a
  page number. No kit byte, token or key crosses outward. This is new for ADR 0131 decision 3 ("nothing of a file's
  content crosses the bridge"): a drawn page's pixels do, inward, because only the host can draw — recorded there in
  phase 10.
- **Why the core asks rather than the host looping:** the order (D2) stays one function on all four apps; the phones
  implement `KitPictures` in-process, the bridge over host requests, and the same `KitImport` runs on both.

### D7 — The scan is the sibling's scanner with the kit's parser; the sign-in only opens it and takes what it found

The sign-in's "Scan Emergency Kit" opens `cr-core-qr-decoder`'s scanner (`QrScan` and each app's camera view, landed)
over `SessionModel.scanKit`, whose `accepts` is `EmergencyKit.read`: a code that is not a v3 kit is skipped and scanning
goes on (the web's `accepts`); the kit it reads is taken by the model inside `accepts` exactly as a file's kit
(`takeKit`; D4: email and server filled, the key held in the model; a QR carries no form, so step 4 never applies), and
`Found` carries only where the server came from — so neither the code's text nor the key reaches a UI. The scanner
closes on `Found`. On the desktops the bridge's `Scan.*` calls carry it with a `kit` kind (phase 9); `Update.Scan`'s
`Found` says only that the sign-in took a kit.
- **Where the scanner says `NoCamera`**, the sign-in does not show the scan entry (the file is the way there); `Denied`
  says so with R9's `scanError` shape and keeps the file offered.
- **Nothing of this CR's own touches a camera:** the permission strings, the camera dependencies and the frame handling
  are the sibling's; this CR adds the kit's words (D8) and the sign-in's entry.

### D8 — The words come from the web's

New `signin_kit_*` keys in `src/mobile/words/words.json`, English and German, worded from the web's (R9) with the apps'
verbs ("Open Emergency Kit" for the web's "Upload": nothing is uploaded; owner question 2 holds the final copy). Each
platform's control follows its guideline (ADR 0123 decision 9): a button under the Secret Key row on every app; the
scanner's entry beside it wherever the app has a camera (D7).

## Owner steps

- **A camera pass on a real iPhone** (the simulator has no camera; `cr-core-qr-decoder`'s owner question 2): the owner
  scans a printed fixture kit once phase 7 is built, or the pass is carried as open.

## Inventory (locators — run at implement time)

From `src/mobile` unless named:
- the sign-in's sites: `grep -rnE 'SignIn|signIn|secretKey|secret_key|SecretKey' core/src/commonMain bridge/src android/src apple/Veyq linux/src windows/Veyq --include='*.kt' --include='*.swift' --include='*.rs' --include='*.cs' | grep -v '/build/'` — and the **tests**: the same over `core/src/*Test bridge/src/jvmTest linux/src windows/Veyq.Tests windows/Veyq.Bridge.Tests`
- the gate's readers (a new field on `Gate.SignIn`): `grep -rn 'Gate.SignIn\|GateSignIn\|Gate::SignIn' core bridge android apple linux windows --exclude-dir=build`
- the host requests' plumbing: `grep -rn 'HostRequest\|HostReply\|veyq_reply\|host_request' bridge linux/src windows --exclude-dir=build`
- each platform's PDF drawing to reuse: `grep -rln 'PdfRenderer\|PDFDocument\|poppler::\|PdfDocument' android/src apple/Veyq linux/src windows/Veyq`
- the temp/cache writers a kit must not join: `grep -rnE 'cacheDir|temporaryDirectory|NSTemporaryDirectory|GetTempPath|g_get_tmp_dir|tmp' android/src apple/Veyq linux/src windows/Veyq`
- the web's kit words: `grep -n -E "secretKeyHint|upload:|scanHint|scanError|kitUnreadable|kitOtherServer" ../../src/web/src/i18n/index.ts`
- the docs that say only the web reads a kit: `git grep -n -iE "one reader|only .web. reads|reads it, at unlock" -- docs site`

## Phases

- [x] **1 docs — the refined state first.** `en.account.key-model` § The Emergency Kit: the kit has two writers and
  **two readers** — the web and the apps, the apps' read in the core; the apps' order with step (d) (the first address
  field, only where the token has no `srv`; fields 2 and 3 never read; the printed text never read); the scan wherever an
  app has a camera. `uc.account.unlock`'s step 1 names the apps' sign-in beside the web's screens as a place the one control holds.
  No site page changes (R10: none speaks for the apps; owner question 3).
  · Done when: the two docs say it; `for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done`
  (guard_gaps skipped) shows no new red against its run before the edit on a detached worktree of `HEAD`.

  **Status: done (2026-11-13).**
  **Built:** `en.account.key-model` § The Emergency Kit — two writers and **two readers** (the web; the apps, their read
  in the core, ADR 0130 decision 1), and a bullet with the apps' order (a)–(d): step (d) only where the token has no
  `srv`, fields 2 and 3 never read, the printed text never read, the scan wherever an app has a camera, the platform only
  picks, draws and runs the camera. `uc.account.unlock` step 1 names the apps' sign-in as a place the one control holds
  (file and scan, email and server filled, the key held) and *One Secret-Key control* lists it. Beside the two, from the
  inventory's last locator: `docs/crosscutting/keys-grants-and-trust.md`'s birth row ("two writers and two readers") and
  the header comment of `src/packages/crypto/src/emergencyKit.ts` ("the only reader" was no longer true). No site page.
  **Met:** the guard loop (`for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done`, guard_gaps
  skipped) showed no RED before the edit (this worktree at `089980f3a`, clean) and none after;
  `git grep -n -iE "one reader|only .web. reads|reads it, at unlock|is the only reader" -- docs site src/packages` → only
  ADR 0132's unrelated "one reader" (a picture reader).

- [x] **2 core — the token codec.** D1: `EmergencyKit.read`, the origin check, `Conformance` reading
  `emergency_kit_token`'s cases and refused tokens on the JVM (BouncyCastle) and over libsodium.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green, `python3 linux/run.py test --tests '*Conformance*'`
  green. **Bites:** (B1) the origin check made to accept anything → the refused `srv` vectors red; (B2) `v == 3`
  dropped → "version 2" red; (B3) a valid token whose `sk` has a wrong check symbol, a case of its own, red when
  `SecretKeyText.read` is bypassed. Each bite by a one-line edit in a detached worktree, reverted by snapshot.

  **Status: done (2026-11-13).**
  **Built:** core `crypto/EmergencyKit.kt`: `Kit(email, secretKey, server)` (the key's bytes, `wipe()`, a `toString`
  without them) and `EmergencyKit.read(p, text)` — the first `veyqkit:` token, base64url read as `atob` reads it, UTF-8
  read strictly, then `parseKit`'s checks in order (`v` 3, a non-empty string `email`, `srv` an origin where present —
  JSON `null` refused —, `sk` through `SecretKeyText.read`); the decoded buffer zeroed. `EmergencyKit.isServer`: the
  origin check by hand (scheme, host, port re-checked against their serialization: lower-case ASCII, no path/slash/
  userinfo/query, no default port or leading zero, IPv4 dotted-decimal, IPv6 compressed as the standard writes it).
  **Deviation:** a domain label is held to `a-z 0-9 - _` (the URL standard lets a few more symbols through that no
  server name carries) and an `xn--` label is not punycode-validated — both stricter or equal on every real origin; said
  in the function's comment. `Conformance.emergencyKitToken()`: the reader half (each case read back to its three
  values, each refused token refused) plus one case of its own, B3's — the third case rebuilt with its key's last check
  symbol changed (and, as its control, rebuilt unchanged and read). `commonTest` `EmergencyKitTest`: the first token in
  any text and only the first, the JSON's shapes, 9 origins taken and 23 refused.
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test` green (`ConformanceTest` 3/3, `EmergencyKitTest` 4/4 on both;
  `LibsodiumConformanceTest` 2/2); `python3 linux/run.py test --tests '*Conformance*:*EmergencyKit*'` 6/6 passed.
  **Bites** (scratchpad `bite.py`, one edit at a time, `/bin/cp -f` restore, `:core:jvmTest --tests
  '*ConformanceTest.bouncyCastle*'`): B1 `isServer` answering true for any non-empty text → red, "refuses a server with a
  path / trailing slash / default port / upper case / without a scheme / of another scheme" (the empty one still refused
  by the mutant itself); B2 the `v` check removed → red, "refuses version 2"; B3 `SecretKeyText.read(p, sk) ?: ByteArray(32)`
  → red, "refuses a Secret Key with a wrong check symbol". The file compared equal to its copy after the run.

- [x] **3 core — the PDF reader and the order (steps 1, 2, 4).** D2 with a `KitPictures` and a decoder function both
  played in the test; D3's `KitPdf` and `inflate` (`expect`/`actual`). **First measurement:** `inflate` over a known
  stream in `mingwX64Test` (`./gradlew windows:coreTest` from the repo root) — if `platform.zlib` does not link into the
  DLL, stop and hand back with the measured error.
  Tests (`commonTest` over synthetic PDFs built in the test, `jvmTest` and the native tests over the web's fixtures via
  `veyq.repoRoot`/`VEYQ_REPO_ROOT`, `inputs.file` declared for them):
  - `kit-pdfkit-saved.pdf` → the kit by step 1, server `TOKEN`, **no page drawn** (the played `KitPictures` counts zero
    calls);
  - `kit-printed.pdf` and `kit-photo.pdf` → steps 1, 2 and 4 find nothing (premise asserted as the web's test does), the
    order asks for pages 1..n ≤ 3 at width 2400;
  - synthetic: `Keywords` as a UTF-16BE hex string, as a literal with octal escapes, inside a Flate object stream, and
    redefined by an incremental update (the later wins); a token without `srv` + `address1` = `familie.example.org` →
    server `https://familie.example.org` from `FIELD`; the same with `srv` = A and `address1` = B → A (`TOKEN`);
    `address2` filled and `address1` empty → no server; an `/Encrypt` trailer → steps 2 and 4 skipped; a file over
    `KIT_FILE_CAP` → refused unread; an object stream that inflates past the cap → skipped.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test`, `python3 linux/run.py test`, `./gradlew windows:coreTest`
  green. **Bites:** (B4) step 4 run regardless of `srv` → the A/B case red; (B5) the early return after step 1 removed →
  the zero-pages assertion red; (B6) hex/UTF-16 decoding removed → its case red; (B7) object streams not scanned → the
  compressed case red; (B8) "later wins" reversed → the incremental case red; (B9) the inflate cap removed → the bomb
  case red (the cap a parameter, the bomb small); (B10) the page limit raised to 4 with a played QR on page 4 → found,
  red.

  **Status: done (2026-11-13; step 3's decoder played, as planned).**
  **Built:** core `blob/Inflate.kt` (`expect fun inflate(bytes, cap): ByteArray?`; `jvmMain` over `java.util.zip.Inflater`,
  `nativeMain` over `platform.zlib` — commonized for all six native targets, so one actual —, null on a broken or
  truncated stream and as soon as the output would pass the cap). `account/KitPdf.kt` (D3): one linear pass over the top
  level (`N G obj` by the last two integers, a stream skipped by a `/Length` that lands on `endstream`, else by searching
  it; `trailer` dictionaries), then each `/Type /ObjStm` `/FlateDecode` stream in file order under `INFLATE_CAP` (8 MiB),
  an object inside one losing to a later plain definition; xref streams count as trailers; `encrypted`, `keywords()`,
  `field(name)` (fully qualified through `/Kids`, cycles and 10 000 nodes bounded); strings with every escape, hex
  (odd padded), UTF-16BE/UTF-8 BOMs, else Latin-1. `account/KitImport.kt` (D2): `Luma`, `KitPictures`,
  `KitImport.read(p, bytes, pictures, qr)` with `FILE_CAP` 32 MiB, `PICTURE_PIXELS` 2400, `PDF_PAGES` 3, `Found(kit,
  serverFrom)`; step 1 scans the bytes for the first `veyqkit:` with symbols (no whole-file string), the PDF parsed lazily
  only after step 1 misses, each drawn picture zeroed after its decode, a foreign QR passed. Tests: `commonTest`
  `KitImportTest` (16, synthetic PDFs built in the test; the Flate streams written as Python-`zlib` hex, commonTest having
  no deflate) and `InflateTest` (3); `KitFixtureCases` over the web's three fixtures, run by `jvmTest` `KitFixtureTest`
  and `appleTest` `KitFixtureNativeTest`; `core/build.gradle.kts` declares the fixtures directory an input of every test
  task (`kitFixtures`).
  **Deviations:** (1) `qr` was a parameter `(Luma) -> String?` until the decoder landed; phase 5 replaced it with the
  decoder and `Luma` with its `QrFrame`. (2) The fixture cases run on the JVM and Apple, not in the Linux and
  Windows test binaries: their runners mount/copy only the vectors and set no `VEYQ_REPO_ROOT`; every synthetic case, the
  object stream through each platform's zlib included, runs on all four. (3) Step 4 lower-cases the typed field before
  `SessionModel.origin` (a host is case-blind; "Familie.example.org" would otherwise fail the origin check). (4) The files
  sit in a new package `account/` (D3's name) and `blob/` (inflate, not kit-specific).
  **Met:** first measurement — `python3 windows/run.py test --tests '*InflateTest*:*KitImportTest*'` 19/19 passed on the VM;
  the mingw test binary imports no zlib DLL (`objdump -p core/build/bin/mingwX64/debugTest/test.exe | grep "DLL Name"`:
  ADVAPI32, CRYPT32, KERNEL32, WINHTTP, msvcrt, ncrypt, ole32; `inflateInit_` in its symbols) — `platform.zlib` links
  statically (its klib's `linkerOpts=-Wl,-Bstatic -lz`). `./gradlew :core:jvmTest :core:macosArm64Test` green
  (`KitImportTest` 16, `KitFixtureTest`/`KitFixtureNativeTest` 2, `InflateTest` 3 on each), `python3 linux/run.py test`
  344 passed (+ both Secret Service probes), `python3 windows/run.py test` 345 passed. The fixture case also holds the
  core's reader to a real viewer's save: `kit-pdfkit-saved.pdf`'s `Keywords` carry the token and its `address1` reads
  `familie.example.org`. **Bites** (`bite.py`, one edit each, `/bin/cp -f` restore, files compared equal after; jvmTest
  unless said): B4 the `srv` early return removed → `aTokensServerWinsOverTheField` red; B5 `fromPictures` called before
  step 1 → `aRawTokenEndsTheReadWithoutAPageDrawn` and the PDFKit fixture case red; B6 the UTF-16 branch removed →
  `keywordsAsAUtf16HexString` red, hex strings refused → it, the object stream and incremental cases red; B7 object
  streams not scanned → `keywordsInsideAFlateObjectStream` red; B8 the first definition kept → `anIncrementalUpdates…` red;
  B9 the object stream inflated uncapped → `anObjectStreamThatInflatesPastTheCapIsSkipped` red, and the native inflate's
  cap check removed → `InflateTest.aStreamPastTheCapIsRefused` red on macosArm64; B10 `PDF_PAGES` 4 →
  `theQrIsLookedForOnTheFirstThreePagesAtTheWebsWidth` red.
  **For phase 4+:** `KitImport.read` takes a `Primitives` (the key's check is `SecretKeyText.read`'s) and answers a `Kit`
  whose `secretKey` the caller owns and must `wipe()`; `veyq.dll`/`libveyq.so` reference no inflate yet, so phase 8's
  `run.py bridge` is the first link of it into the libraries (Linux's klib links `-lz` dynamically: expect a `NEEDED
  libz.so.1`, remembered from the klib manifest, not measured).

- [ ] **4 core — the sign-in holds the kit.** D4 in `SessionModel`: `readKit`, `clearKit`, `Gate.SignIn.kit`, the held
  key used, replaced, cleared and zeroed; a pasted token; the other-server notice. `SessionModelTest` over its played
  door.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green; with `VEYQ_LIVE` a new `LiveSignInTest` case signs
  the dev principal in through `readKit` over an in-memory synthetic kit PDF (built in the test from `VEYQ_ENV_FILE`'s
  credentials, `Keywords` only) with an empty typed Secret Key, and reaches the shell with its tenants. **Bites:** (B11)
  the held key put into `Gate.SignIn` → a test asserting the gate's serialized JSON never contains the key's text red;
  (B12) Cancel no longer clearing → a sign-in after Cancel with an empty key red (it must fail as a missing key); (B13) a
  typed key no longer winning → the door's received key red.

  **Status: built and unit-proven (2026-11-13); the live run is pending (the dev backend was down and not to be
  started by this phase) — the box stays open until it is green.**
  **Built:** core `session/SessionModel.kt` — `readKit(bytes, pictures, typedServer?)` (`KitImport.read` with the door's
  suite; `bytes` zeroed once read; no kit → `Msg.SIGNIN_KIT_UNREADABLE` on the gate, a kit held before kept; a sign-in
  left during the read wipes what it found), `takeKit(found, typedServer?)` (the entry phase 7's scan hands its `Found`
  to; the model owns the kit from there), `clearKit()`. The held key is a private `ByteArray` in the model, held
  **exactly as long as the gate shows a kit**: every gate change goes through one `show(gate)`, which zeroes and drops it
  for any gate that is not a `SignIn` with a `kit` — so Cancel, `otherAccount`, every way out of the sign-in, a typed
  key (its gates carry `kit = null`) and a sign-in that succeeded (`Signed`) all end it by one rule; a new kit replaces
  (zeroes) the one before; a failed sign-in's error gate carries the kit on, so the key stays for the retry. The sign-in
  hands the door a **copy** (`signInWithKey`), zeroed in a `finally`. A pasted token (any non-blank Secret Key text is
  tried through `EmergencyKit.read`) signs in with that kit's key, its email and server not applied; a token that does
  not read goes on as typed text and fails as a wrong Secret Key does. The other-server notice is data:
  `KitShown.typedServer` = the form's server (typed now, else the gate's) as an origin, where the kit's server replaced
  a different one (compared case-blind); the words for it are phase 6's. `KitImport.ServerFrom` became `@Serializable`.
  `Door` gained `primitives` and `signInWithKey(server, email, password, secretKey: ByteArray)`; `AccountDoor` over the
  new `VeyqAccount.signInWithKey` (the string `signIn` and it share one private body — the key still asked for after the
  login and the wrap, so a typed sign-in fails in the same order as before; the byte key zeroed in a `finally`). One
  word: `signin_kit_unreadable` (EN+DE, from the web's `kitUnreadable`, "paste" said as "enter"). The bridge's two test
  doors gained the two members (`signInWithKey` unreachable there until phase 8).
  **For `cr-app-device-linking` phase 3 — what changed on the `SignIn` gate:** one field, `kit: KitShown? = null`, last
  in `Gate.SignIn(working, error, adding, server, email, kit)`; nothing else of its shape. Behaviour a linking entry must
  keep: any `SignIn` gate shown **without** `kit` ends a held kit key (the `show` rule), so a linking state that stays on
  the sign-in carries `kit` over with `copy(...)` where the kit should survive, and builds a fresh `SignIn(...)` only
  where it should not; set the gate through `show`, never `_gate.value`.
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64
  :core:compileKotlinMingwX64` green (from `src/mobile`; `SessionModelTest` 27 on the JVM and on macosArm64, 8 of them
  new); the guards (`scripts/validate_*.py` but guard_gaps) green. `LiveSignInTest`'s new case
  `aKitPdfSignsThePrincipalInWithAnEmptySecretKey` (the kit PDF built in memory from the env file's credentials, the token
  only in `Keywords` as UTF-16 hex so the read takes step 2; the trust it enrols forgotten and the session signed out in
  a `finally`; nothing of the kit printed or written) compiles and **reports as skipped** without `VEYQ_LIVE` (JUnit's
  `Assume`; the test XML: `tests="2" skipped="2"`) — the file's older case, which returned early and showed as passed,
  now skips the same way. **Bites** (scratchpad `k4/bite.py`, one edit each, `/bin/cp -f` restore, compared equal after;
  `:core:jvmTest --tests '*SessionModelTest*'`): B11 a body property on `KitShown` carrying `SecretKeyText.format` of
  the key (outside `equals`, inside the JSON) → `a_kit_read_fills…` red by the JSON check alone; B12 `show` no longer
  dropping the key on a non-`SignIn` gate → `cancel_leaving_and_the_rows_clear…` and `a_failed_sign_in_keeps…` red; B13
  the held key used when a key was typed → `a_typed_key_wins…` red; and beyond the CR's three: the pasted token not read
  → `a_pasted_token…` red; the notice never set → `a_kit_from_another_server…` red; a failed sign-in dropping the kit →
  `a_failed_sign_in_keeps…` red; the door's copy not zeroed → `a_kit_read_fills…` and `a_pasted_token…` red; the file's
  bytes not zeroed → `a_kit_read_fills…` and `a_file_without_a_kit…` red; an unreadable file dropping the held kit →
  `a_file_without_a_kit…` red; a kit taken outside the sign-in → `outside_the_sign_in…` red. **Pending, live:**
  `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main checkout>/.run/.env ./gradlew :core:jvmTest --tests
  '*LiveSignInTest*'` (from `src/mobile`; `VEYQ_ENV_FILE` only from a worktree).
  **Deviations:** (1) `takeKit` is public beside `readKit` — phase 7's scan needs the same entry, and the tests hand it
  a `Kit` whose zeroing they can watch. (2) `readKit` takes the form's server as typed (`typedServer`), since the
  phones' text field is the UI's until the sign-in; the bridge's `SignInKit` (phase 8) may pass it the same way. (3)
  Not measured here: both desktops' `run.py build` (the invariant's) — `Gate.SignIn` gained a field their generated
  types read; phase 8/9 builds them.

- [x] **5 core — step 3 over the sibling's decoder.** **Entry condition:** `cr-core-qr-decoder` has landed on the
  branch (its decoder in the core, green); before the first edit, re-read every *"until `cr-core-qr-decoder` lands"*
  sentence here against the merged tree and correct the CR where it differs. Wire the decoder into D2's step 3 and the
  picture path. A `jvmTest` draws the fixtures' pages with **Apache PDFBox** as a test-only renderer (Apache-2.0,
  maintained 3.x, JVM; `jvmTest` only, never shipped — checked and recorded in `libs.versions.toml`'s comment) so the
  whole order runs on the printed and photographed kits.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green with `kit-printed.pdf` and `kit-photo.pdf` read to
  the fixture kit through step 3. **Bite:** (B14) pages drawn 600 px wide instead of 2400 → `kit-photo.pdf` red (the
  lever is real: it shows the width matters for the photographed kit; if 600 still reads, the bite names the width that
  fails, measured, and the CR's 2400 is kept as the web's).

  **Status: done (2026-11-13; it needed nothing of phase 4, which stays open).**
  **Built:** core `account/KitImport.kt` — the stand-in `qr: (Luma) -> String?` parameter and `Luma` are gone:
  `KitPictures` answers the decoder's `QrFrame`, and `KitImport.read(p, bytes, pictures)` reads each drawn page (and a
  picture file) with `QrDecoder.decode(frame, tryHarder = true)`, its text straight into `EmergencyKit.read` — only the
  `Kit` leaves, as only `QrScan`'s `Found` leaves a scan; the frame's luminance zeroed after its decode (the decoder's own
  working copies are let go, not zeroed: said in the comment). `commonTest`'s `PlayedPictures` no longer plays a decoder:
  it draws real codes with the core's `QrEncoder` (3 px a module; blank white pages without one), so every
  `KitImportTest` case runs the real decoder on every target. `jvmTest` `KitFixtureTest` gains `PdfBoxPictures` (a page
  rendered `width` px wide in 8-bit grey, `ImageType.GRAY`, the scale from the crop box, a quarter-turned page's height
  taken) and one case per fixture: `kit-printed.pdf` and `kit-photo.pdf` read to the fixture kit (email, key, server from
  `TOKEN`) through step 3 at 2400 px. Apache PDFBox 3.0.8 (`libs.pdfbox`, `jvmTest` only; Apache-2.0, released
  2026-07-08 — checked on Maven Central and recorded in `libs.versions.toml`); `verification-metadata.xml` written from an
  empty Gradle home (+47 lines: pdfbox, pdfbox-io, fontbox, commons-logging 1.4.0 and their parent POMs, nothing else
  changed).
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64
  :core:compileKotlinMingwX64` green (`KitFixtureTest` 4 and `KitImportTest` 16 on the JVM, the PDFBox cases about 2 s;
  `KitImportTest` 16 and `KitFixtureNativeTest` 2 on macosArm64). **Bites** (scratchpad `bite5/bite.py`, one edit each,
  `/bin/cp -f` restore, compared equal after): B14 `PICTURE_PIXELS` 600 → both fixtures "no kit read" (and the premise
  case's widths); **measured further: at 1200 the printed kit reads and the photographed one does not** — the width
  matters for the photo; 2400 kept as the web's. The decoder bypassed (its text taken as null) → both PDFBox cases,
  `aPictureGoesStraightToItsQr`, `anEncryptedPdf…GoesToItsQr` and `aForeignQrIsPassed…` red; the frame's zeroing removed
  → `aForeignQrIsPassed…` red.
  **Deviations:** the fixtures' drawings run on the JVM only (as planned); the native targets run the order over
  `QrEncoder`-drawn pages. `PdfBoxPictures.picture` answers null (every fixture is a PDF); the picture path's real decode
  is `aPictureGoesStraightToItsQr`'s.

- [ ] **6 Android + Apple — pick the kit.** D5 and D8: the button on both sign-ins (iPhone, iPad, macOS, Android phone
  and tablet), `KitPictures` per platform, the Secret Key row's "from your Emergency Kit" state with its clear, the
  notices. Neither app has a unit-test target that draws a page today (`src/mobile/CLAUDE.md` § Prove your work names
  none), so the pass below is the platform drawing's proof; the order itself is phase 3's and 5's.
  · Done when: `./gradlew :android:assembleDebug` and the `xcodebuild` builds (iphonesimulator, macosx) green; **pass**
  on a throwaway AVD and a throwaway iPhone simulator and the macOS build: each fixture (`adb push` to Downloads;
  `xcrun simctl` into Files / iCloud Drive's local folder; Finder on macOS) picked and the form filled (`familie.example.org`,
  `alex.beispiel@example.org`, the key held); then a dev kit (built from the principal's credentials, as phase 4's live case builds it) signs in and
  reaches the shell. **No copy left:** `adb shell run-as me.raddatz.veyq find cache files -newer <marker>` and the
  simulator's app container `tmp`/`Library/Caches` listed after the picks show nothing of the kit; that measurement
  bitten once by a planted file (B15).

  **Status: built and passed up to the sign-in (2026-11-13); the signing in with a dev kit is pending (the dev backend
  was down and not to be started by this phase) — the box stays open until it is green.**
  **Built:** words `signin_kit_open` ("Open Emergency Kit" / "Notfall-Kit öffnen"), `signin_kit_held` ("From your
  Emergency Kit" / "Aus deinem Notfall-Kit"), `signin_kit_clear` ("Enter it instead" / "Stattdessen eingeben", Apple
  Title Case) and `signin_kit_other_server` (the web's `kitOtherServer` turned to the app's case: the kit's server
  replaced the one the form held). **Android** `KitFile.kt`: `KitFile.read` — the `OpenDocument` URI through
  `ContentResolver` into one array of the size the provider says (+1 to see the end without growing), at most
  `KitImport.FILE_CAP`, an over-cap file read no further and answered empty (the core then says `signin_kit_unreadable`),
  every array let go zeroed; `AndroidKitPictures` — `PdfRenderer` over Depot's `OpenPdf` `memfd` of the same bytes, a
  picture through `ImageDecoder` scaled to the long side, BT.601 luminance taken a row at a time, each bitmap cleared and
  recycled. `AppModel.readKit(uri, typedServer)`/`clearKit()`, `Screen.SignIn.kit`; `SignInScreen`: an outlined
  "Open Emergency Kit" button under the Secret Key row (`application/pdf`, `image/*`), the row read-only "From your
  Emergency Kit" with a trailing clear while a kit is held, the other-server notice under the button, email and server
  taken from the gate when a kit arrives; the sign-in then sends an empty key. **Apple** `KitFile.swift`: `KitBytes.read`
  — the `.fileImporter` URL (opened in place, never `asCopy`) read under its security scope inside an
  `NSFileCoordinator` read, straight into one `NSMutableData` with POSIX `read` (no intermediate `Data`), over-cap
  refused unread, zeroed by `clear()` after the core's read; `KitDrawer` — core's new `KitDrawing` over PDFKit
  (`PDFDocument(data:)` over the same memory, no copy) and ImageIO (`CGImageSourceCreateThumbnailAtIndex`, drawn up
  where ImageIO only scales down), each into an 8-bit grey `CGContext`. Core `appleMain` `account/AppleKit.kt`:
  `KitDrawing`/`KitDrawn` (Swift implements no suspending call, so blocking calls run on `Dispatchers.Default`),
  `AppleKit.bytes` (one `memcpy`) and `pictures(drawing)`, the drawn buffer zeroed once copied. `AppModel.readKit`/
  `clearKit`, `ScreenState.signIn(…, kit:)`; `SignInView`: the Secret Key row a `LabeledContent` "From your Emergency
  Kit" with an `xmark.circle.fill` clear while held, the notice as the section's footer, a "Open Emergency Kit" row
  (`folder`) under it, `.onChange(of: kit)` filling email and server. The debug build's `DevLogin` reads
  `VEYQ_DEV_KIT=<path>` through the same `readKit` and logs only where the server came from (`veyq-kit:`).
  **Deviation (core):** `KitImport.FALLBACK_PIXELS` = 2000 — a page (or picture) that reads nothing at 2400 is drawn
  once more 2000 wide. Measured first on macOS: PDFKit's drawing of `kit-photo.pdf` read nothing at exactly 2400 (8-bit
  grey, generic gray 2.2 and sRGB-to-luminance alike) and read at every width tried from 1600 to 3600 but 2400 (decoded on
  macosArm64; 1000-1400 and a linear-gray drawing never). The resampling at one width decides, so the order tries a
  second width; PDFBox and Android's `PdfRenderer` read the photo at 2400 already. Tests: `KitImportTest`
  `aPageThatReadsNothingAtTheWebsWidthIsDrawnOnceMoreAtTheFallback` (page and picture) and the drawing orders in it
  and `KitFixtureCases` (17 cases). A measurement trap on the way: a gray PNG read back through Java's
  `BufferedImage.getRGB` is gamma-converted, so a frame that failed in the app decoded on the JVM — compare raw bytes.
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug` green (from
  `src/mobile`; `KitImportTest` 17 on both targets); `xcodebuild -project Veyq.xcodeproj -scheme Veyq -destination
  'platform=macOS' … build` and the scheme for a throwaway iPhone 18 Pro simulator (iOS 27) green; the guards
  (`scripts/validate_*.py` but guard_gaps) green. **Pass, Android** (throwaway AVD `veyq_kit6`, Pixel 8, API 36; deleted
  after): the three fixtures `adb push`ed to Downloads, each picked in DocumentsUI after a fresh start (server
  `veyq.localhost:8080`): the form showed `https://familie.example.org`, `alex.beispiel@example.org`, the row "From your
  Emergency Kit", and the notice, each within 3 s (read with `uiautomator dump`); the clear brought the typed row back;
  the same at 2560x1600/320 dpi (the form a centred column). **Pass, macOS and iOS:** the computer-use grant for the app
  and the iOS Simulator tool's access were not given in this session, so no panel could be clicked: the built apps read
  each fixture through `VEYQ_DEV_KIT` (macOS: the fixture in the scratchpad; iOS: the fixture in the simulator's Files
  "On My iPhone" storage) — `veyq-kit: kit held, server from TOKEN, other server said: true` for all three on both, and a
  `simctl io` screenshot of the German form filled. **The pickers themselves (`.fileImporter`, the open panel and the
  Files sheet) were not clicked — open.** **No copy left** (B15 bitten each time by a planted copy, which each listing
  then named, removed after): Android `run-as me.raddatz.veyq find . -newer kit-marker` → only ART's
  `files/profileInstalled`; iOS the app's data container `find . -newer kit-marker -type f` → only iOS's
  `Saved Application State` scene files (1 KB, no kit text: grep for the email, host and key groups found none), and no
  file carrying `veyqkit:`; macOS every file newer than the marker in the user temp and cache folders and the app's
  `Library` folders, held to "carries `veyqkit:` or a fixture's first 2 KiB or is a PDF" → only two build artifacts with
  the word in source text (a cinterop `protocols*.m`, a Kotlin daemon log; neither has the fixture's token).
  **Pending, live:** with the dev backend up — `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main
  checkout>/.run/.env ./gradlew :core:jvmTest --tests '*LiveSignInTest*'` (phase 4's) and, per app, a kit built from the
  principal's credentials (as `LiveSignInTest.aKitPdfSignsThePrincipalInWithAnEmptySecretKey` builds it, written under
  `build/` and removed after) picked, the password entered by `devlogin.py`'s way, Sign in → the shell with its tenants;
  and the three pickers clicked once by hand (Android's was).
  **Bites:** B15 as above (three platforms); B19 `WIDTHS` without the fallback → `aPageThatReadsNothing…`,
  `theQrIsLookedFor…`, `aForeignQrIsPassed…` and the fixture premise red, restored with `/bin/cp -f`, compared equal.

- [ ] **7 the core, Android and Apple — scan the kit.** **Split (the coordinator, 2026-11-13):** this phase is the core
  and the phones' and the Mac's scan; the desktops' (Linux and Windows scanning a kit through the bridge's `Scan.*` with
  a `kit` kind, the entry hidden on the Windows VM, which has no camera) moved to phase 9 beside their file entry, which
  needs phase 8's model first. **Entry condition:** `cr-core-qr-decoder`'s scan model and the Android and Apple cameras
  have landed (its phases 3–4); re-read the marked sentences first. D7 on the Android and Apple sign-ins: the entry,
  `accepts = EmergencyKit.read`, `Found` into D4, `NoCamera` hiding the entry, `Denied` said. A `SessionModelTest` case
  over a played `QrScan`: a foreign code skipped, a v2 kit skipped, a v3 kit filling the form.
  · Done when: core, `:bridge:jvmTest`, `:android:assembleDebug` and the `xcodebuild` builds green; **pass** with each
  app's camera or its debug test camera (the sibling's) showing the printed fixture's page: the scanner opens, skips a
  non-veyq code, reads the kit, closes, fills the form. The iPhone pass on a real camera is the owner step (or carried
  open). No frame on disk: the same measurement as phase 6. **Bite:** (B18) `accepts` made to take any text → the
  foreign-code case red.

  **Status: built and passed (2026-11-13); the box stays open for the owner's real-camera passes (an iPhone, this Mac's
  camera — its first use asks the person through macOS's privacy prompt) and the live sign-in pending since phase 4.**
  **Re-read first:** the two *"until `cr-core-qr-decoder` lands"* sentences (§ Context, D7) said its cameras and `Scan.*`
  were still to come; both had landed — rewritten to what is there (`Scan.*` has one kind, `SCHEME`; `kit` joins in
  phase 9). D7's "`Found(kit)` closes the scanner and hands the kit to `SessionModel`" rewritten to what was built (below).
  **Built:** core `SessionModel.scanKit(typedServer?, decode, scope)`: a `QrScan<KitImport.ServerFrom>` whose `accepts`
  reads the code with `EmergencyKit.read` and **takes the kit inside `accepts`** (`takeKit`, `TOKEN` where the token has a
  server, else `NONE`), so `Found` carries only where the server came from — no `Kit`, no key, no text ever reaches a UI or
  waits in a scan's state for a UI to hand it on; a kit read outside the sign-in is wiped by `takeKit` and the code
  skipped. `appleMain` `AppleKit.scan(session, typedServer, timing)` for Swift (no defaults, no suspending decoder; the
  scan as `QrScan<AnyObject>`). Words `signin_kit_scan` ("Scan Emergency Kit" / "Notfall-Kit scannen"),
  `signin_kit_scan_hint` and `signin_kit_scan_denied` (the web's `scanHint`/`scanError`, the apps' verbs). **Android**
  `KitScanner.kt`: a Material 3 full-screen dialog (top app bar, close) over `QrCamera`, the hint under it, `Denied`'s
  words in its place; closes on `Found`; `NoCamera` drops the entry for the screen. The sign-in's outlined "Scan
  Emergency Kit" (`QrCodeScanner`) sits **above** "Open Emergency Kit", shown only where `FEATURE_CAMERA_ANY` is (and no
  scan said `NoCamera`). **Apple** `KitScannerView` (in `SignInView.swift`): a sheet with a `NavigationStack`, Cancel
  as its cancellation action, the hint as a bottom inset; the scan entry (`qrcode.viewfinder`) **first on iOS/iPadOS,
  after Open on the Mac** (owner question 1), shown where `QrCamera.available` (a video device, or a debug test camera).
  Debug only: `VEYQ_DEV_KIT_SCAN=1` opens the sheet once the sign-in shows and `DevLogin` logs `veyq-kit:` what it took.
  **No frame in a system snapshot** — found on the way, not in the CR: an app's switcher snapshot is a file on disk, and
  the scanner's shows the kit's code (= the Secret Key). Android: the dialog is `FLAG_SECURE`
  (`SecureFlagPolicy.SecureOn`); iOS: the camera is covered with the ground colour while the scene is not active.
  **Two camera fixes the pass found** (the sibling's files): Android's `QrCamera` held the permission's grant in no state,
  so after "Allow" nothing recomposed and the scan stayed `Starting` (measured: no `Scanning` within 30 s; fixed, the
  grant a `mutableStateOf`); Apple's `QrCameraView` drew its states in a bare `Group`, so a caller drawing nothing for
  `Found` made it vanish and reappear — a new camera every ~6 ms (1 296 starts in 9 s, measured; fixed with a `ZStack`
  over a clear ground).
  **Met:** `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :android:assembleDebug` green (from
  `src/mobile`, `ANDROID_HOME` set; `SessionModelTest` 29 on the JVM and on macosArm64, 2 new:
  `a_scanned_kit_skips_codes_that_are_no_v3_kit_and_fills_the_form_with_its_key_held` — a URL, a v2 kit and a
  `veyqpair:` code skipped, the form untouched, then the kit read with the other-server notice and its held key signing
  in — and `a_kit_scanned_outside_the_sign_in_is_not_taken_and_the_scan_goes_on`); the macOS build (ad hoc) and the
  iOS-simulator build `BUILD SUCCEEDED`; the guards (`scripts/validate_*.py` but guard_gaps) green.
  **Pass, Android** (throwaway AVD `veyq_kit7`, Pixel 8, API 36, deleted after): virtual scene with the printed fixture's
  page as the wall poster — section 3 of `kit-printed.pdf`'s page 1 (Quick Look at 2000 px, cropped 1280×720 around the
  code), the scene camera moved over gRPC (`-grpc 8590 -grpc-use-token`: **bound to 127.0.0.1** by `lsof`, the token from
  the emulator's discovery file `grpc.token`, a wrong token refused `grpc-status 16`) to POSITION -0.15 0.3 3.8, ROTATION
  0 180 0. The entry tapped (`input tap` at its `uiautomator` bounds), "While using the app" → `state Scanning` →
  `decoded 640x480 in 75.7 ms, a code read`, `state Found(value=TOKEN)`; the dialog closed, the form showed
  `https://familie.example.org`, `alex.beispiel@example.org`, "From your Emergency Kit" and the notice (emulator
  framebuffer `adb emu screenrecord screenshot`; `screencap` is black under `FLAG_SECURE`). A second boot with a
  non-veyq code (`https://example.org/menu`) as the poster: 26 frames `a code read`, the scan stayed `Scanning`.
  "Don't allow" → `state Denied`, the dialog saying it; close → the form. **Pass, iOS** (throwaway iPhone 18 Pro, iOS 27,
  deleted after; the simulator has no camera): without `VEYQ_DEV_CAMERA` the entry is absent (shot); with the foreign
  code as the test camera 88 frames `a code read`, `Scanning` throughout; with the page's crop `decoded 1280x720 in
  13.0 ms, a code read`, `Found(value=TOKEN)`, `veyq-kit: kit held, server from TOKEN, other server said: true`, the
  sheet closed and the German form filled (shot). **Pass, macOS** (the debug build, `VEYQ_DEV_CAMERA` +
  `VEYQ_DEV_KIT_SCAN`): the foreign code 94 frames read, `Scanning`; the crop `Found(value=TOKEN)` and `veyq-kit: kit
  held, server from TOKEN`. **Measured on the way:** the whole page as one frame (1414×2000 fitted to 707×1000, the code
  about 2 px a module) read nothing in 9 s of frames — a camera reads the kit when aimed at its code, as the hint says.
  **No frame on disk:** Android `run-as me.raddatz.veyq find . -newer kit-marker -type f` → only `files/profileInstalled`
  (bitten: a planted `cache/frame-planted` listed, removed); the task snapshot `/data/system_ce/0/snapshots/<task>.jpg`
  (`adb root`) taken on HOME mid-scan is blank — **bitten**: a build without `SecureOn` wrote the camera's code into it
  (pulled and seen). iOS: the app container's files newer than the marker are the install's SplashBoard launch images
  only; the scene snapshot written on backgrounding mid-scan (`Library/SplashBoard/Snapshots/sceneID:…/*.ktx`, read with
  `sips`) shows the sheet with the camera covered — **bitten**: without the cover it holds the code. macOS: no file
  newer than the marker in the user temp folder, `Library/Caches/me.raddatz.veyq`, `HTTPStorages` (scratchpad
  `nocopy_mac.py`, bitten by a planted PNG it named).
  **Bites** (scratchpad `k7/bite.py`, one edit each, `/bin/cp -f` restore, compared equal; `:core:jvmTest --tests
  '*SessionModelTest*'`): B18 `accepts` answering `NONE` for any text → red; the kit not taken (`accepts` answering its
  `ServerFrom` without `takeKit`) → red; outside the sign-in settling anyway → red
  (`a_kit_scanned_outside_the_sign_in…`). The snapshot covers bitten on the devices as above.
  **Deviations:** (1) the kit is taken inside `accepts`, not after `Found` (D7 said `Found(kit)` hands it on): a UI that
  watched for `Found` to take the kit would hold the key, and a scan closed between the two would leave it unzeroed. A
  code read after the view closed the scan is still taken (stated in `scanKit`'s comment). (2) the poster is the page's
  crop around the code, not the whole page: 640×480 cannot resolve a code a sixth of an A4 page wide (measured on iOS
  above). (3) No tap on iOS or macOS: the sheet was opened by `VEYQ_DEV_KIT_SCAN` (the button's same call), the
  simulator tool and computer use not used; the Android entry was tapped. (4) The live sign-in is phase 4's pending step;
  a scan's held key signs in as a file's (the unit case proves the hand-over).
  **Owner steps (pending):** a real iPhone (the sign-in's "Notfall-Kit scannen" over a printed fixture kit → the form
  filled); this Mac's camera (`VEYQ_DEV_KIT_SCAN=1 <build>/Debug/veyq.app/
  Contents/MacOS/veyq`, allow the camera, a printed kit held up → `veyq-kit: kit held`).

- [ ] **8 bridge — the kit by path, pages drawn by the host.** D6: `Call.SignInKit`, `Call.SignInKitClear`,
  `HostRequest.Pdf.*`/`Picture.Luma`, `HostReply.Pixels` with its payload through `Host.kt`, the generated Rust and C#.
  `DispatchTest` over a played host: a PDF read by step 1 with no host request, a printed fixture asked page by page, a
  path that does not open, a file over the cap read no further, `Refused(NO_KIT)`; `WireTest`'s samples by derivation.
  · Done when: `./gradlew :bridge:jvmTest` green and, with `VEYQ_LIVE`, a `LiveBridgeTest` case `aKitSignsIn*` signs in by
  `Call.SignInKit` over a synthetic kit written under `build/` and removed in `finally`. **Bites:** (B16) a `secretKey`
  field added to `Gate.SignIn`'s `kit` → `SecretFreeSchemaTest` red; (B17) the step-1 short-circuit removed in the bridge's
  `KitPictures` path → the "no host request" case red.

- [ ] **9 Linux and Windows — pick and scan the kit.** D6's hosts and D8: the open dialog (PDF and images filtered), the host's
  drawing from the path into memory, the Secret Key row's state. **And D7 on the desktops** (moved here from phase 7):
  a `kit` kind of `Scan.Open` over `SessionModel.scanKit` (its `Found` only the `ServerFrom`), the scan entry after Open
  (the file first on a desktop), hidden where the scan says `NoCamera` (the Windows VM), each app's test camera showing
  the page's code (`VEYQ_DEV_CAMERA`) in its pass, a non-veyq code skipped. **First measurement:** on Windows,
  `StorageFile.GetFileFromPathAsync` on a picked path in the unpackaged app.
  · Done when: `python3 linux/run.py build` and `python3 windows/run.py build` green (each with a test that draws
  `kit-printed.pdf`'s first page through the host's own drawing and hands it to the core over the bridge, where its test
  project links the core — otherwise the pass alone); **pass** on Broadway and on the VM (`run.py drive`): each fixture
  picked, the form filled, a dev kit signs in. **No copy left:** `find / -xdev -newer <marker>` in the container and
  `%TEMP%` plus the app's LocalState on the VM show nothing of the kit.

- [ ] **10 docs and harvest.** `docs/crosscutting/native-apps.md` § Built (Account and session: the kit, by file on all
  four, by camera wherever the app has one, each measured pass with its date); ADR 0131 decision 3 (a drawn page's pixels ride inward
  beside a host reply, and why); ADR 0130's Consequences (the kit's read order is the core's, the drawing the platform's);
  ADR 0047's apps bullet (a picked kit is read in memory and never copied); `src/mobile/CLAUDE.md` § Prove your work (the
  new tests and live cases); every code comment that names this CR repointed at the ADR or doc that states the decision;
  the retrospective; the CR deleted.
  · Done when: `git grep -n "cr-app-kit-sign-in"` → nothing but the CR; the guard loop green; the CR deleted.

## Definition of done

Every app's sign-in takes a kit file (all four: Android, the Apple app on iPhone, iPad and Mac, Linux, Windows) and,
wherever the app has a camera, a scanned kit, and fills email, server and a held Secret Key so only the password is
typed; the read order, the token's decode and steps 1, 2 and 4 are core code held to the vectors' `emergency_kit_token`
and the web's three fixtures on every `:core:jvmTest`; each bite B1–B18 was seen red once; no pass found a kit's copy on
any app's disk; `en.account.key-model`, ADR 0130/0131/0047 and `native-apps.md` say what was built. No site page changes
(R10, owner question 3); `docs/coverage/` has no app surface (`SURFACES` in `scripts/validate_coverage.py`), so the apps'
proof is the tests and passes `src/mobile/CLAUDE.md` § Prove your work names — said there in phase 10.

## Not in this CR

- **The QR decoder** — `cr-core-qr-decoder`.
- **The camera itself, on any app** — `cr-core-qr-decoder` (whether the Linux and Windows desktops get one is its owner
  question 1; this CR's entry follows whatever it builds, hidden where it says `NoCamera`).
- **The photo library** as a kit source (a screenshot of the QR in the camera roll) — owner question 4; the files picker
  takes a picture file already.
- **Writing a kit on the apps** (an account born or secured on an app) — no app births or secures an account today.
- **Other app screens that ask for a Secret Key** (recovery, a password change, a rotation, an invite accept) — none is
  built (R2); each takes `KitImport` and D4's rule when it is.

## Owner questions

1. **The sign-in's two entries.** The file and the scan sit side by side under the Secret Key row on every app with a
   camera; on a phone the scan could lead instead (it is the faster way there). *Recommendation:* the file first on the
   desktops, the scan first on a phone or tablet.
2. **The words.** The apps' button "Open Emergency Kit" / "Notfall-Kit öffnen" (the web's "Upload" is wrong where
   nothing is uploaded), "Scan Emergency Kit" / "Notfall-Kit scannen", the Secret Key row "From your Emergency Kit" /
   "Aus deinem Notfall-Kit", the "not found" and camera texts taken from the web, and the camera's
   reason — one per app, added by `cr-core-qr-decoder`, so its text names QR codes in general, the kit among them. *Recommendation:* as
   written; the implementer uses them unless answered otherwise.
3. **Published pages.** No site page speaks for the apps (R10), so this CR changes none; the kit page's "Signing in on a
   new device" stays the web's. *Recommendation:* the apps' pages come with their packaging, and the kit page gains "or
   scan it with the app" then.
4. **The photo library as a source** (iOS Photos, Android's photo picker) for a screenshot or photo of the kit's QR.
   *Recommendation:* not now — the files picker takes a picture file, and the camera reads the paper; a third entry on a
   sign-in screen is one more thing to explain.


**Decided** (the coordinator, 2026-11-12, under the owner's standing mandate to decide and report; the owner may overturn any):
- 1–4: as recommended — the file first on the desktops, the scan first on a phone or tablet; the words as written; no site
  page until the apps' packaging; no photo library now.

## Retrospective

(filled at the end)

Phase 7 (the implementer, 2026-11-13):
- **"No frame on disk" has a writer the app does not own.** The CR's measurement listed the app's own folders; the
  system's app-switcher snapshot (Android `/data/system_ce/0/snapshots`, iOS `SplashBoard/Snapshots`) is a file too, and
  a scanner's holds the Secret Key as a code. Found by asking where else a frame lands, closed by `FLAG_SECURE` and an
  inactive-scene cover, each bitten by a build without it. Next time: a screen that shows a secret names the OS
  snapshot in its no-copy check.
- **The sibling's harness passed what the first real caller did not.** Both camera bugs (Android's preview never binding
  after "Allow", Apple's camera restarting when the caller draws nothing) were invisible to the debug harnesses, whose
  callers happened to avoid them. A camera CR's pass should include a caller that draws nothing for a state and a first
  permission grant in the same process.
- **Take the secret where it is read.** Taking the kit inside `accepts` rather than on `Found` kept the key out of every
  UI and closed a race the CR's wording would have built. A decision that says "X hands Y to the model" names who holds Y
  in between.
- **A whole page is no camera frame.** The printed page as one frame read nothing; the realistic frame is the code
  aimed at. A pass fixture for a camera says what the camera sees, not what the paper holds.

Phase 6 (the implementer, 2026-11-13):
- **One renderer's width is not a rule.** 2400 came from the web and held for PDFBox and pdfium; PDFKit's drawing of the
  photographed kit read at every width from 1600 to 3600 except 2400. Only the macOS pass showed it — a played or
  JVM-drawn page never would. Next time: a platform drawing's pass runs every fixture, not the easiest.
- **Compare raw bytes, never a picture read back.** A PNG of the failing frame decoded on the JVM because
  `getRGB` gamma-converts grey; that sent the first fix (1200) the wrong way. The frame as the decoder gets it is the
  only evidence.
- **A pass without a hand on the picker.** Neither the app nor the simulator was granted to this session; a debug path
  through the same read (`VEYQ_DEV_KIT`) proved everything but the picker's own sheet, and said so.

Phase 5 (the implementer, 2026-11-13):
- **A fixture loop hides the second fixture from a bite.** B14's first run stopped at `kit-printed.pdf`'s failure and
  could not say whether the photo — the file the bite is about — failed too; one test case per fixture named both, and
  a second width (1200) then showed which one the width matters for. Next time: a bite's target file gets its own case.
- **Playing the drawing, not the decoder.** With the real decoder landed, the test seam moved from "a decoder that
  answers a text" to "a renderer that draws a real code" (`QrEncoder`); no public parameter remains that a caller could
  use to read a code's text past the core.

Phase 4 (the implementer, 2026-11-13):
- **One gate setter carries the key's lifetime.** "Cleared on Cancel, on leaving, after a success, on a typed key" was
  four rules at four call sites; routing every gate change through `show()` and tying the key to "the gate shows a kit"
  made it one rule — and B12's mutation of that rule turned two tests red, not one site's.
- **An early `return@runTest` reads as a pass.** Two live test files showed their live cases green on every plain run;
  a JUnit `Assume` before `runTest` is what makes "not run" visible in the report.
