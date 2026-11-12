---
id: cr-app-kit-sign-in
title: Sign in with the Emergency Kit on every app — the kit read in the core, its page drawn by the platform, its QR scanned on a phone
status: planned
satisfies: ["en.account.key-model", "uc.account.unlock"]
adrs: ["0129", "0130", "0047", "0123", "0059"]
invariants:
  - operator-blind E2EE — the kit is read on the device only; nothing of it (file, page, frame, token, Secret Key) reaches the server or any other host; the sign-in sends what a typed sign-in sends
  - no plaintext at rest but where the person saves it (ADR 0047, carried to the apps) — no app copies the kit file, a drawn page or a camera frame to a cache, temp or log; the bytes are read into memory, capped, and zeroed where the language lets us
  - every rule is the core's (ADR 0129 decision 1) — the token's decode, the read order, steps 1, 2 and 4 and the QR decoder are one Kotlin implementation; a platform only picks, draws a page or a picture, and runs a camera
  - the bridge only carries (ADR 0130; SecretFreeSchemaTest) — no Secret Key, token or kit byte travels outward; the host's drawn pixels ride inward beside a reply, never in JSON
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
  Depot's PDF renderers on all four platforms and the bridge's path-based file reads (ADR 0130 decision 3) are this CR's
  floor.
- **Read first:**
  - `docs/features/account/enablers/en.account.key-model.md` § The Emergency Kit (the token, "the server is a hint,
    never a check", the web's fallback chain) and § The Secret Key's text form;
  - `docs/features/account/use-cases/uc.account.unlock.md` main flow step 1 and Quality notes (*One Secret-Key control*,
    *A kit from the wrong account warns*, *A kit made at another address says where*);
  - ADR 0129 (decisions 1, 2, 4, 5), ADR 0130 (all four decisions), ADR 0047's Consequences (*The apps hold the same
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
  dependency. What this CR assumes of it, *until `cr-core-qr-decoder` lands*:
  - a decode function callable on one still picture, apart from `QrScan`, that takes a luminance plane **2400 px wide**
    (a kit page; `QrScan`'s 1280 px cap is the scan model's, not the decoder's) and answers the text or null;
  - `QrScan`'s `accepts` is where the kit's parser goes, and its `Found(T)` carries the read `Kit` to the sign-in model
    without the text crossing the bridge;
  - its fixtures include a `veyqkit:` code; the web's three kit PDFs are this CR's (phase 5).
  Phases 5 and 7 re-read every sentence carrying the marker *"until `cr-core-qr-decoder` lands"* against the merged
  tree before their first edit (`grep -n "until .cr-core-qr-decoder. lands" changes/cr-app-kit-sign-in.md`).
  **Shared files:** both CRs extend ADR 0130 (that CR decision 1 with a call's inbound payload, this one decision 3 with a
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
- `suspend fun pdfPage(page: Int, width: Int): Luma?` — page `page` drawn `width` px wide, as 8-bit luminance;
- `suspend fun picture(longSide: Int): Luma?` — the file decoded as an image, scaled so its longer side is `longSide`.

The order, for a file of at most `KIT_FILE_CAP` (32 MiB — a photographed kit at 200 dpi is 230 KB, a scan app's
multi-page PDF a few MB; a larger file is refused unread, never read whole):
1. the bytes read as Latin-1 through `EmergencyKit.read` — a hit ends the read **without opening a page**;
2. if the bytes start with `%PDF-`: the `Keywords` of the document information through the core's PDF reader (D3);
3. the QR: pages 1..min(n, 3) drawn 2400 px wide (the web's `KIT_PDF_PAGES`, `KIT_PICTURE_PIXELS`), each decoded by the
   sibling's decoder (*until `cr-core-qr-decoder` lands*: the function Context assumes); a file that is not a PDF
   goes to `picture(2400)` and its decode instead;
4. **only where the token found carries no `srv`**: the first address field (D3), read as a typed server
   (`SessionModel.origin`, then the same origin check as D1); empty or unreadable → no server.

`Found` = the `Kit` and where its server came from (`TOKEN`, `FIELD`, `NONE`). Steps 2 and 4 never throw out: a PDF the
core's reader cannot follow answers "nothing here" and the order continues.
- **Why the platform draws and the core decides:** drawing a PDF needs a full renderer (fonts, images, transforms), and
  each platform already ships one (R3); everything else — where to look, in which order, how wide, how many pages, what
  counts — is a rule and so the core's (ADR 0129 decision 1). Rejected: a PDF renderer in the core (a second renderer
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
- **Why the model holds it:** the bridge may carry nothing secret outward (ADR 0130, `SecretFreeSchemaTest`), and the
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

### D6 — The desktops hand a path; the core asks the host to draw (ADR 0130 extended inward)

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
  page number. No kit byte, token or key crosses outward. This is new for ADR 0130 decision 3 ("nothing of a file's
  content crosses the bridge"): a drawn page's pixels do, inward, because only the host can draw — recorded there in
  phase 10.
- **Why the core asks rather than the host looping:** the order (D2) stays one function on all four apps; the phones
  implement `KitPictures` in-process, the bridge over host requests, and the same `KitImport` runs on both.

### D7 — The scan is the sibling's scanner with the kit's parser; the sign-in only opens it and takes what it found

The sign-in's "Scan Emergency Kit" opens `cr-core-qr-decoder`'s scanner (*until `cr-core-qr-decoder` lands*: `QrScan`
with its camera view per app) with `accepts = EmergencyKit.read`: a code that is not a v3 kit is skipped and scanning
goes on (the web's `accepts`); `Found(kit)` closes the scanner and hands the kit to `SessionModel` exactly as a file's
kit (D4: email and server filled, the key held in the model; a QR carries no form, so step 4 never applies). On the
desktops the bridge's `Scan.*` calls carry it; `Update.Scan`'s `Found` says only that the sign-in took a kit.
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

- [ ] **1 docs — the refined state first.** `en.account.key-model` § The Emergency Kit: the kit has two writers and
  **two readers** — the web and the apps, the apps' read in the core; the apps' order with step (d) (the first address
  field, only where the token has no `srv`; fields 2 and 3 never read; the printed text never read); the scan wherever an
  app has a camera. `uc.account.unlock`'s step 1 names the apps' sign-in beside the web's screens as a place the one control holds.
  No site page changes (R10: none speaks for the apps; owner question 3).
  · Done when: the two docs say it; `for f in scripts/validate_*.py; do /usr/bin/python3 "$f" || echo "RED $f"; done`
  (guard_gaps skipped) shows no new red against its run before the edit on a detached worktree of `HEAD`.

- [ ] **2 core — the token codec.** D1: `EmergencyKit.read`, the origin check, `Conformance` reading
  `emergency_kit_token`'s cases and refused tokens on the JVM (BouncyCastle) and over libsodium.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green, `python3 linux/run.py test --tests '*Conformance*'`
  green. **Bites:** (B1) the origin check made to accept anything → the refused `srv` vectors red; (B2) `v == 3`
  dropped → "version 2" red; (B3) a valid token whose `sk` has a wrong check symbol, a case of its own, red when
  `SecretKeyText.read` is bypassed. Each bite by a one-line edit in a detached worktree, reverted by snapshot.

- [ ] **3 core — the PDF reader and the order (steps 1, 2, 4).** D2 with a `KitPictures` and a decoder function both
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

- [ ] **4 core — the sign-in holds the kit.** D4 in `SessionModel`: `readKit`, `clearKit`, `Gate.SignIn.kit`, the held
  key used, replaced, cleared and zeroed; a pasted token; the other-server notice. `SessionModelTest` over its played
  door.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green; with `VEYQ_LIVE` a new `LiveSignInTest` case signs
  the dev principal in through `readKit` over an in-memory synthetic kit PDF (built in the test from `VEYQ_ENV_FILE`'s
  credentials, `Keywords` only) with an empty typed Secret Key, and reaches the shell with its tenants. **Bites:** (B11)
  the held key put into `Gate.SignIn` → a test asserting the gate's serialized JSON never contains the key's text red;
  (B12) Cancel no longer clearing → a sign-in after Cancel with an empty key red (it must fail as a missing key); (B13) a
  typed key no longer winning → the door's received key red.

- [ ] **5 core — step 3 over the sibling's decoder.** **Entry condition:** `cr-core-qr-decoder` has landed on the
  branch (its decoder in the core, green); before the first edit, re-read every *"until `cr-core-qr-decoder` lands"*
  sentence here against the merged tree and correct the CR where it differs. Wire the decoder into D2's step 3 and the
  picture path. A `jvmTest` draws the fixtures' pages with **Apache PDFBox** as a test-only renderer (Apache-2.0,
  maintained 3.x, JVM; `jvmTest` only, never shipped — checked and recorded in `libs.versions.toml`'s comment) so the
  whole order runs on the printed and photographed kits.
  · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green with `kit-printed.pdf` and `kit-photo.pdf` read to
  the fixture kit through step 3. **Bite:** (B14) pages drawn 600 px wide instead of 2400 → `kit-photo.pdf` red (the
  lever is real: it shows the width matters for the photographed kit; if 600 still reads, the bite names the width that
  fails, measured, and the CR's 2400 is kept as the web's).

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

- [ ] **7 every app — scan the kit.** **Entry condition:** `cr-core-qr-decoder`'s scan model, its cameras and its
  `Scan.*` calls have landed (its phases 3–5); re-read the marked sentences first. D7 on all four sign-ins: the entry,
  `accepts = EmergencyKit.read`, `Found` into D4, `NoCamera` hiding the entry, `Denied` said. A `SessionModelTest` case
  over a played `QrScan`: a foreign code skipped, a v2 kit skipped, a v3 kit filling the form. Phase 8's bridge calls come
  first where the desktops need `SignInKit`'s model; the order of phases 7 and 8 is the implementer's.
  · Done when: core, `:bridge:jvmTest`, `:android:assembleDebug`, the `xcodebuild` builds and both `run.py build` green;
  **pass** with each app's camera or its debug test camera (the sibling's) showing the printed fixture's page: the
  scanner opens, skips a non-veyq code, reads the kit, closes, fills the form; on the Windows VM (no camera) the entry is
  absent. The iPhone pass on a real camera is the owner step (or carried open). No frame on disk: the same measurement as
  phases 6 and 9. **Bite:** (B18) `accepts` made to take any text → the foreign-code case red.

- [ ] **8 bridge — the kit by path, pages drawn by the host.** D6: `Call.SignInKit`, `Call.SignInKitClear`,
  `HostRequest.Pdf.*`/`Picture.Luma`, `HostReply.Pixels` with its payload through `Host.kt`, the generated Rust and C#.
  `DispatchTest` over a played host: a PDF read by step 1 with no host request, a printed fixture asked page by page, a
  path that does not open, a file over the cap read no further, `Refused(NO_KIT)`; `WireTest`'s samples by derivation.
  · Done when: `./gradlew :bridge:jvmTest` green and, with `VEYQ_LIVE`, a `LiveBridgeTest` case `aKitSignsIn*` signs in by
  `Call.SignInKit` over a synthetic kit written under `build/` and removed in `finally`. **Bites:** (B16) a `secretKey`
  field added to `Gate.SignIn`'s `kit` → `SecretFreeSchemaTest` red; (B17) the step-1 short-circuit removed in the bridge's
  `KitPictures` path → the "no host request" case red.

- [ ] **9 Linux and Windows — pick the kit.** D6's hosts and D8: the open dialog (PDF and images filtered), the host's
  drawing from the path into memory, the Secret Key row's state. **First measurement:** on Windows,
  `StorageFile.GetFileFromPathAsync` on a picked path in the unpackaged app.
  · Done when: `python3 linux/run.py build` and `python3 windows/run.py build` green (each with a test that draws
  `kit-printed.pdf`'s first page through the host's own drawing and hands it to the core over the bridge, where its test
  project links the core — otherwise the pass alone); **pass** on Broadway and on the VM (`run.py drive`): each fixture
  picked, the form filled, a dev kit signs in. **No copy left:** `find / -xdev -newer <marker>` in the container and
  `%TEMP%` plus the app's LocalState on the VM show nothing of the kit.

- [ ] **10 docs and harvest.** `docs/crosscutting/native-apps.md` § Built (Account and session: the kit, by file on all
  four, by camera wherever the app has one, each measured pass with its date); ADR 0130 decision 3 (a drawn page's pixels ride inward
  beside a host reply, and why); ADR 0129's Consequences (the kit's read order is the core's, the drawing the platform's);
  ADR 0047's apps bullet (a picked kit is read in memory and never copied); `src/mobile/CLAUDE.md` § Prove your work (the
  new tests and live cases); every code comment that names this CR repointed at the ADR or doc that states the decision;
  the retrospective; the CR deleted.
  · Done when: `git grep -n "cr-app-kit-sign-in"` → nothing but the CR; the guard loop green; the CR deleted.

## Definition of done

Every app's sign-in takes a kit file (all four: Android, the Apple app on iPhone, iPad and Mac, Linux, Windows) and,
wherever the app has a camera, a scanned kit, and fills email, server and a held Secret Key so only the password is
typed; the read order, the token's decode and steps 1, 2 and 4 are core code held to the vectors' `emergency_kit_token`
and the web's three fixtures on every `:core:jvmTest`; each bite B1–B18 was seen red once; no pass found a kit's copy on
any app's disk; `en.account.key-model`, ADR 0129/0130/0047 and `native-apps.md` say what was built. No site page changes
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

## Retrospective

(filled at the end)
