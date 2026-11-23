# ADR 0136 — An app's logic is the Kotlin core's; each platform draws only its own UI, Windows and Linux among them
- Status: Accepted
- Built: partly — decisions 1 to 3 for Android and Apple (the screens' logic is the core's, the words one catalog);
  decision 4 for Linux's first slice (sign-in, the shell, Home, a Pot read only, Search), run in a Fedora 44 container
  under GTK's Broadway backend, not yet in a desktop session or as a Flatpak; decision 5 for Windows' first slice, the
  same one: the core as a `mingwX64` DLL behind the same bridge, its C# types generated, a WinUI 3 app run unpackaged on
  an ARM64 Windows 11 VM under x64 emulation, not yet on an x64 PC or as an MSIX; on both, the device trust with the
  core reaching the secure store itself (2026-11-11), a node's header with every action on it, its sheets' models
  moved into the core first (2026-11-12), and Expenses whole — the entry editor, receipts, settle-up, an entry's
  history and restore, a Pot's settings and people, New pot and the live poll —, the Pot's screen models moved into
  the core first (2026-11-12; the Consequences). Measured 2026-11-10 to 2026-11-12
  (`src/mobile/linux/run.py` and `src/mobile/windows/run.py`, each `test`, `bridge` and `build`, `./gradlew
  :bridge:jvmTest`, a browser pass on Linux, a UI Automation pass on Windows): ADR 0123's Consequences, the core on
  Linux and on Windows, the bridge, the Linux app and the Windows app
- Date: 2026-11-09 (decision 4 revised 2026-11-10: Linux in Rust over the native core, not on the JVM)
- Model: [native-apps](../crosscutting/native-apps.md)
- Extended by: [ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md) (gates, structure, notices and formats the core's; a core UI per app that its applications plug into)

## Context

[ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md) put a Kotlin Multiplatform core under a
native UI for Android (Compose) and the Apple platforms (SwiftUI). The core holds the suite, the API, the session, the
navigation model and each application's domain; but each app's model (`android/…/AppModel.kt`, `apple/Veyq/Core/AppModel.swift`,
some 1,500 lines each) still holds logic both write again: when the lists are read anew, which outcome raises which
notice, the sign-in, unlock and trust flow, what a page shows and what it may do. Two copies of a rule are two chances
for one platform to be wrong; the upload queue (2026-11-09) was the first piece moved into the core for that reason,
the web's split taken over.

The owner adds Windows and Linux (2026-11-09), each with its own native UI rather than a shared one — four UIs over one
core — and asks for as much as possible in the Kotlin core. With four UIs, every rule left in an app model is written
four times.

## Decision

1. **The line: everything but drawing and the device is the core's.** The core holds the suite, the API, the session,
   the navigation model, every application's domain and flows, the upload queue — and each screen's model: what it
   shows (its state), what a tap does (its actions), the rights that narrow both, what is read anew after a write, and
   which notice an outcome raises (a key with its arguments, never a sentence). A platform holds only:
   - its UI, built to its own guidelines (ADR 0123 decision 9; Fluent on Windows, the GNOME HIG on Linux);
   - what only the device can do: the system's pickers, share sheets, save dialogs and drops; the secure store
     (Android Keystore, the Keychain, Windows Hello and DPAPI, the Secret Service); decoding pictures and video for a
     medium's tiers (`MediaUpload.Deriver`); the share extension, notifications and widgets;
   - the platform's side of a file: reading a picked one at any offset (core's `UploadSource`) and writing a download.
   A rule that does not touch a screen or the device and sits in a platform is a defect to move, as a second copy of
   key-material logic is (root `CLAUDE.md`).

2. **A screen's model is a core class a UI observes.** It exposes its state as a flow (a Kotlin UI collects it) and a
   watch with a callback (Swift and the bridge of decision 5 observe it, as `UploadQueue.watch` does), and its actions as
   plain functions; it is tested in `commonTest` with no UI at all. Moving a screen keeps its behaviour: the slice that
   moves it changes no rule and is proved by the tests the rules gain, then by both apps unchanged on a device.

3. **The words have one source.** The core keeps the catalog (English and German, the keys the screen models raise
   and every label), and the build generates each platform's resources from it — Android's `strings.xml`, Apple's
   String Catalog, Windows' `.resw`, Linux's gettext catalog —, as the theme is generated from the web's colours
   (ADR 0123 decision 7). Today's two catalogs (Android's and Apple's) are merged into it as the screens move.
   The web keeps its own catalog, held to this one (§ The web's words, below).

4. **Linux: GTK 4 and libadwaita in Rust, the core as a Kotlin/Native library** (owner 2026-11-10: no JVM in the
   app, and the way GNOME's own apps are built). The Linux app is Rust with gtk4-rs and libadwaita-rs (both checked
   2026-11-10: maintained, MIT, community-run — gtk-rs and GNOME's World group). The core joins as a `linuxX64` and a
   `linuxArm64` shared library — the suite's primitives libsodium's, built from the pinned release as for Apple (ADR
   0123 decision 3) — through the same narrow bridge as Windows (decision 5), its Rust types generated from the core's
   models. It ships as a Flatpak on the GNOME runtime. Its secure store is the Secret Service.
   **One Linux UI for every desktop** (owner 2026-11-10): KDE Plasma and the others get the same libadwaita app. The
   Flatpak brings GTK and libadwaita with the runtime, the system's dialogs come through the xdg-desktop-portals (KDE's
   own file chooser under Plasma), the colour scheme through the Settings portal, the secure store through any Secret
   Service (gnome-keyring, KWallet, KeePassXC); libadwaita does not take a desktop's theme, so under Plasma it looks
   like a GNOME app. Where no Secret Service runs, the app works without staying signed in. **It is built and tested
   against the runtime it ships on** — the GNOME SDK (`org.gnome.Sdk`) of the Flatpak's runtime version — so the
   distribution a developer or a test VM runs is never what the app is proved against.

5. **Windows: WinUI 3 in C#, the core as a Kotlin/Native library.** The Windows app is C# on the Windows App SDK. The
   core joins as a `mingwX64` DLL — the suite's primitives libsodium's, built from the pinned release as for Apple
   (ADR 0123 decision 3), the API over Ktor's WinHttp engine — held to the same vectors by the same `Conformance`. C#
   reaches it through one narrow bridge rather than Kotlin/Native's whole C export: a call with a serialized request
   and its answer, and one callback for every model's watch, both in the core's own serialization; the C# types are
   generated from the core's models, never written by hand. Its secure store is Windows Hello with DPAPI. **The bridge
   is one, for Windows and Linux alike** (decision 4): its C side, its serialization and its generator are shared, each
   UI language only adding its generated types. Kotlin/Native has no Windows ARM64 target (only `mingw_x64` in 2.4.21's
   platform libraries, measured 2026-11-10), so the Windows app is an x64 process: native on x64 PCs, under Windows'
   x64 emulation on an ARM one.

6. **The order.** The screens move into the core first (decision 2), application by application, Android and Apple
   thinned in the same slice; then the large screens (an Android tablet and window, the iPad, macOS) are run and laid
   out to their guidelines; then the bridge with Linux, which runs in a container on the developer's Mac (GTK's
   Broadway backend draws it into a browser) and in a VM; then Windows over the same bridge, which needs a Windows
   machine or VM. No new feature lands in an app model meanwhile: a feature built during the move is built in
   the core.

## Consequences

- Four UIs over one core. Each feature costs its screen four times, but its rules once; the screen models' tests carry
  what the apps' live passes carried alone so far.
- ADR 0123 decision 1's targets grow by `mingwX64`, `linuxX64` and `linuxArm64`; the JVM target serves Android alone.
  "UI is never shared" stands. The repository gains Rust, for the Linux UI alone.
- The Windows app cannot be built on the developer's Mac: everything Kotlin cross-compiles there, and the C# without a
  Windows API builds and tests there, but the WinUI build, the core's tests and the app run on a Windows host
  (`src/mobile/windows/run.py` drives one over ssh), and the apps' CI job (still open) needs a Windows runner.
- **The bridge carries no key, so on Linux and Windows the core reaches the secure store itself** (found building the
  bridge, 2026-11-10; built 2026-11-11). Decision 1 has a platform hold the secure store, but `DeviceKeys`' calls carry
  the Master Key to seal and the refresh token to keep; forwarded to a host, both would cross the bridge. So the store
  is the core's own code, in `linuxMain` and `mingwMain`, and **the key never crosses the bridge**: the host hands the
  core only its window (Windows' HWND, the owner of Hello's prompt) and the prompt's words.
  - **Linux: the Secret Service through libsecret, reached by `dlopen`** (`core/secret/`, `LinuxDeviceKeys`). A small
    header of our own declares the few functions and `SecretSchema` it calls (`secret_password_store_sync`,
    `lookup_sync`, `clear_sync`, `secret_service_get_sync` for the capability), so `libveyq.so` has no `NEEDED`
    libsecret, no glib or libsecret header enters the Mac's build, and the app runs where libsecret is absent, offering
    nothing (decision 4). Every key is an item of the default collection; the unlock key has no presence check (the
    Secret Service has none), so Linux offers the login level alone. A Flatpak runtime must carry libsecret.
  - **Windows: DPAPI and the Passport key storage provider through cinterop** (`core/wincred/`, `WindowsDeviceKeys`):
    only the header's C helpers are exposed, so windows.h's types stay the platform library's and only integers, bytes
    and UTF-16 strings cross. DPAPI (`CryptProtectData`, the current user, no UI) keeps the bind keys, the secrets and
    the login level's unlock key; the presence level's 32 bytes are encrypted to an RSA key in the Microsoft Passport
    key storage provider (NCrypt), whose decryption Windows refuses without Hello. Hello's availability is cryptngc's
    `NgcGetDefaultDecryptionKeyName` (an undocumented export loaded at run time, as KeePassWinHello checks it), not
    `dsregcmd`, whose `NgcSet` says NO on a local account with a PIN. Rejected: `UserConsentVerifier`, a dialog only,
    which leaves the bytes open without it; `KeyCredentialManager`, WinRT, which Kotlin/Native reaches only through
    raw COM activation.
  - What each store guarantees is [security-model](../crosscutting/security-model.md) § What a trusted device rests on;
    what was built and measured, ADR 0123's Consequences, the device trust.
- **Every primitive on Windows is libsodium's, AES-256-GCM among them** (owner 2026-11-10: one source per platform, as
  Apple's and Linux's). libsodium's AES-GCM needs AES-NI, PCLMUL and AVX. An x64 CPU without AVX, and a Windows-on-ARM
  PC whose x64 emulation does not show AVX, are refused at start, as a CPU without AES instructions is on Linux. CNG for
  AES-GCM alone was rejected: two sources of primitives on one platform. What looked at first like emulation lacking AVX
  was our build's defect: libsodium's `configure` reads XCR0 on Windows x64 only through the `_xgetbv` intrinsic, which
  clang accepts only under `-mxsave`, so without the flag no CPU had AVX, an x64 PC's neither (measured 2026-11-10; ADR
  0123's Consequences, the core on Windows).
- **"The platform's side of a file" (decision 1) is read as: the dialog and the grant are the platform's, the bytes the
  core's** ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)
  decision 3). The platform's save or open dialog chooses the file and hands over its path; the core opens it and reads
  or writes it (`blob/FileSink`, `blob/FileSource`), removing a download that did not finish. The Apple app did so
  first (`AppleDepot`); Linux and Windows do so through the bridge, where nothing of a file's content crosses.
- **An Emergency Kit's read is the core's; drawing its pages is the platform's** (2026-11-13, the kit at sign-in on all
  four apps; `en.account.key-model` § The Emergency Kit). The token's decode, the read order (the raw token in the
  bytes, the PDF's `Keywords`, the QR on the first three pages, the first address field only where the token has no
  server), the tolerant PDF reader (an object scan, not an xref walk; inflate through each platform's zlib) and the QR
  decoder ([ADR 0141](0141-qr-codes-are-read-and-drawn-by-the-core.md)) are one Kotlin implementation
  (`account/KitImport`, `account/KitPdf`, `crypto/EmergencyKit`). A platform picks the file and draws a page or a
  picture to luminance through its own renderer (`PdfRenderer`, PDFKit, poppler, `Windows.Data.Pdf`) behind
  `KitPictures` — rejected: a PDF renderer in the core (a fifth beside four, pdfium and MuPDF C libraries with their own
  CVE stream) and each platform's metadata API for `Keywords` (Android's and Windows' renderers have none). **One
  renderer's width is no rule:** PDFKit's drawing of the photographed kit read at every width from 1600 to 3600 but the
  web's 2400, so a page that reads nothing is drawn once more 2000 wide (`KitImport.FALLBACK_PIXELS`). **The sign-in
  model holds the kit's Secret Key, never a UI**: the gate says only *that* a kit's key is held, and the key lives
  exactly as long as a sign-in gate shows a kit (one setter, `show`, ends it on every other gate), zeroed when it goes.
- **When the members' pictures are read is the core's** (`session/AvatarsModel`, decision 1): Android and Apple had
  each synced them when a tenant opened, a rule in two apps before a third and fourth copy. They now watch the core's
  model, and Linux and Windows read its id set through the bridge (ADR 0137 decision 1).
- **A device link is one core model, and an app opens the account on its sign-in, never on its last step**
  (2026-11-13/15, device linking on all five apps, [ADR 0059](0059-device-linking.md)). `session/DeviceLink` holds the
  ceremony in each of its four roles (shows or scans, for a new device or from one) as decision 2's state: the web's
  steps, the refusals by name, the code reaching a UI only as `matrix()`/`png(px)`, the scanning roles' own `QrScan`, the
  polls, the client's own channel deadline (the server's 10 min, so a relay that stops answering does not leave a link
  waiting for ever). Its crypto, the payload grammar and the grant's JSON are `crypto/Pairing`, held to
  `contracts/crypto/vectors/v1.json` § `pairing_channel` as `@veyq/crypto` is. A platform draws the state and hands back
  the camera's frames, the six digits and the slide's end; the bridge carries the state alone (`Link.*`,
  `Watched.Link`), never the code's text, a key, the grant or a token. **The new device's tail runs whatever the screen
  does**: once the handed-over token is spent its successor must be kept, so a close in `FINISHING` ends the state as
  `ENDED` while the tail still signs the account in. An app keyed to the state's `DONE` therefore never opened that
  account — the bridge first held its close off in `FINISHING`, a rule in one host the phones lacked. Every app opens
  the shell on `DeviceLink.signedIn()` / `SessionModel.signedInBy(link)` instead (true once the tail signed in, false on
  every other end), never on `DONE`. The slide (ADR 0059 §7) is each platform's slider confirming only a gesture that
  began at its start and ends at its end, or the arrow keys to the end then Return; a jump (a tap on the track, a
  Home/End/Page key, one accessibility adjustment) never confirms. A screen reader confirms through a named action of the
  sentence on Android and Apple; GTK and WinUI offer no named action of an app's own, so there the sentence is the
  slider's accessible name and a screen-reader user confirms by the keys (`device_link.rs`, `DeviceLinkDialog.cs`).
- **A node's header and its sheets are core screen models** (2026-11-11/12, before Linux and Windows drew them; decision
  2's shape, each rule a `commonTest` case killed by a mutation; what the desktops built and measured is ADR 0123's
  Consequences, the Linux app and the Windows app). Android and Apple held each of these in their app models and draw
  them from the core now:
  - `navigation/NodeHeaderModel` (built per node by `ShellModel.header`): the rights composed from pinned, links on, the
    identity folder, a kind's doors (`AppKinds.doors`, a Pot's copy and cover only while writable) and whether a report
    is offered — read once per tenant, and asked again by the next header after a failed read, not kept as "not
    offered" as both apps did (a report is a legal duty: the safe side); the plan and its words; pin with its optimistic
    state, its revert and notice; trash with its undo, which the core holds open for `TRASH_UNDO_MS` (6 s) and then
    drops, so a host shows Undo until the state ends it and keeps no clock; rename; and the cover: its frame read when a
    kind that takes one opens, `saveCover`, `removeCover`.
  - `navigation/TrashSheet.purge(row)` takes the row the person confirmed (Apple's confirmation binding had cleared the
    row before the button's action, so "Delete forever" sent nothing).
  - `navigation/MoveSheet`: move and copy through one browser (the place's block, the guest folder's confirm, the copy's
    way by kind — a Pot by its open model, the rest `SubtreeCopy` —, no room as a notice with the copy's size, the
    left-out lines). **A move's undo is the core's 6 s window, as a trash's**: Android had shown it as a long snackbar,
    Apple for 6 s — a technical unification, open to the owner's veto. A move that ended a guest's folder (out of a drop
    box) offers no undo: the server refuses putting it back, which Apple's offered undo always met.
  - `ShellModel.download` writes a node's ZIP into `blob/FileSink`, removing a half-written one.
  - `navigation/ShareSheet`: the links, the form (`LinkForm` held as the sheet's state), mint, edit, renew, off and on,
    delete, the confirms, the fresh address, a folder's "Add files only". A link's end is a local day turned into an
    instant by the core (`session/LocalDays`). Its tests play the server's links at the sheet's door;
    `ShareLinks`' and `ShareLinkChanges`' HTTP and crypto are held by the live tests alone (a played tenant's scope keys
    and tree were judged not worth their size).
  - **The re-key's resume starts in the core** when a tenant is shown (`SessionModel.Door.opened`, never `open`, which
    "Manage servers" also calls for a tenant read only for its spaces); `TenantSession.rekeyed` then refreshes the shell,
    forgets the tree and re-reads an open Pot — not each row's details, as both apps did: a re-key changes keys, not
    what a row shows. No app starts it.
  - `navigation/ReportSheet`: offered or not, the form's rules (`NodeReport`), one send, and the privacy passage's
    address built by the core (`NodeReport.privacyUrl`), no longer by each app.
  - The cover's spec is `NodeCover`'s constants (long edge, quality, thumbnail edge, `encodedSize`) beside its diff
    (`NodeCover.changes`); decoding, cropping by hand and encoding stay the platform's, the crop's math `CoverFrame`'s.
    **Android and Apple still write a cover through `NodeCover` themselves**, not through the header model's cover
    calls, which Linux and Windows use: deliberate — the move took only the spec and the diff, and their cover screens
    had nothing left to decide. Moving them onto the header model is open, never needed for a rule.
  - Where the shell decided differently by accident, the core settled it on every app: an object opened at the top and
    trashed leaves for its application's list (`Shell.leave`, `ShellModel.afterTrash`; it stayed open on a closed model),
    and a renamed open object keeps its place and `top` (`ShellModel.afterRename`).
- **The Pot's screens are core screen models** (2026-11-12, before Linux and Windows drew them; decision 2's shape, each
  rule a `commonTest` case over a Pot the test plays, `PotFixture`, killed by a mutation; what the desktops built and
  measured is ADR 0123's Consequences, the Linux app and the Windows app). Android and Apple held each of these in their
  screens and app models and draw them from the core now:
  - `expenses/EntryEditor` (opened by `PotModel.editor`): the form (core's `EntryForm`; Apple's hand-written
    `FormState` copy gone), tried, the refusal shown only once tried, the preview, the rate's fill (started by a currency
    the base is not; **an answer for a currency changed meanwhile is dropped by a counter, not by cancelling its
    request**, since a cancelled request leaves "dropped" unbiteable and USD → GBP → USD would let the first answer
    fill), the receipts held (`Receipts.READ_LIMIT` is what a device reads of a picked file at most; the count and size
    caps and a refused pick the core's), save, the skipped receipts' notice, delete only once asked. **A stale save now
    does what the web does**: the other writer's version is read into the form (`ED_STALE`), the receipts an earlier try
    uploaded are carried, never uploaded twice, and the next save goes on top — both apps had kept the old revision, so
    every next save was stale again (open to the owner's veto). The delete is asked, with a new word saying it can be
    restored; the web asks nothing.
  - `PotModel`: every write of the Pot's screens is one `PotModel.Op` on the open Pot (`Opened.write`), so a test plays
    it without a session; `record(rows)` dated the core's today; `restore(entryId)` judged on the core's clock (outside
    the window nothing is sent and the message says why); `live()` waits for a Pot still opening and ends when that Pot
    closes or another opens, so a poll never reads a Pot no screen shows. The editor's and the settings' writes leave the
    Pot's message alone (`said = false`): each says its own outcome.
  - `expenses/PotSettings` (the base currency refused once money is in, own categories, a person or member added,
    renamed, (de)activated, erased only once asked, of the settled only) and `expenses/PotCreateSheet` (the space and
    currency of the shell's place and the platform's language, the members read again on a space change, a late answer
    for a space left dropped, the people, `canCreate`, `parentIn` from the place it opened at — Apple's `"REFUSED"`
    literal gone).
  - **Today and the clock are the core's**: a new entry's date, a settle-up's, the rate's day and a restore's window
    come from `PotModel.today()`/`now` (`session/LocalDays`); a platform hands only a day the person picked.
  - Where the apps disagreed, one rule (technical, open to the owner's veto): an amount without a currency is a number
    alone everywhere (Android's locale currency and Apple's `"EUR"` fallback gone; the core never hands one without —
    `PotViewMoneyTest`); shared files fit a Pot by `PotShare.Intake` (each a receipt, at most 100, all within 64 MiB,
    a device reading no more than `room`; Android's 40 MiB per-file cap gone); the editor's delete says `entry_delete`;
    a PDF receipt shows at most `Receipts.PDF_PAGES_SHOWN` (20) pages and says so (`receipts_pages_cut`; Apple's PDFKit
    view pages lazily and shows all, which the constant allows as "at least"); what a receipt is, is its bytes'
    (`Receipts.kindOf`, `mediaTypeOf`; Apple's ImageIO sniff gone). Saving a receipt stays each platform's door.
  - **The bridge answers each write with what a host acts on at once**, never left to a watched state that may arrive
    after: the editor's save and delete `EditorSaved(done, notice)`, a settle-up or restore `Said`, every settings and
    New pot change `Answer.Stored(stored, notice)` (the host empties its box on `stored`, says the notice otherwise).
    The live poll is a watch whose job is the poll (`Watched.PotLive`, delivering nothing of its own): a host's dropped
    watch stops it structurally, where a `Pot.Live`/`Pot.Unlive` pair would leave a page torn down without its stop call
    polling (the bitten alternative, a poll launched beside the watch, kept polling). An entry's history is a call
    (`Pot.History`), restorable judged when asked, not part of every poll's update. Each field of the shown editor is
    one core function's answer (`EditorShown`: the payers' total, the rest, the split's hint and words), so no host
    computes one. Edits (`Editor.Change`, `Editor.DropReceipt`, `PotCreate.Name`) are applied on the host's thread in
    the order called.
- **Depot's screens are core screen models** (2026-11-12, before Linux and Windows drew them; decision 2's shape, each
  rule a `commonTest` case over a file, a level or a tree the test plays, killed by a mutation — `FileModelTest`,
  `DepotUploadsTest`, `DropBoxPageTest`, `FolderUploadTest`, `ShareSheetTest`; what the desktops built and measured is ADR
  0123's Consequences, the bridge, the Linux app and the Windows app). Android and Apple held each of these in their
  screens and draw them from the core now:
  - `depot/FileModel` (`ShellModel.fileModel`): what a file's page shows, decided by its bytes (`FileView.decide`): an
    image (decoded by the platform to at most `FileView.IMAGE_EDGE`, 4096), a text's first megabyte and whether it was
    cut, a PDF (pages rendered as they come into view, at most `FileView.PDF_PAGES`, 1000, said past it), a medium as a
    `RangeReader`, or what the page says instead (`FileModel.words`); the facts and the uploader; `reload` when the shell's
    lists change; the opened file's rights `FileRights` (the rights matrix's opened-file rows, [depot](../apps/depot.md) §
    Guests), from which the header's facts and the file's own actions come — Replace (worded Upload into an empty file),
    Send a copy (where the platform shares and the page shows content). **A member replaces nothing in a guest's identity
    folder**, where both apps offered Replace on every file (technical, open to the owner's veto). The app-held map of every
    file went: the replace offer reads its file when made and when taken.
  - **A FILE's download is the file itself** under its own name (`AppKinds.downloadsItself`/`ownDownload`,
    `ShellModel.downloadAs`), any other node its ZIP, on every app — the desktops had written a file's ZIP. A phone's Send a
    copy is that download into the shared directory, swept at the next start.
  - `blob/FileSave` (`depot/` until cr-apps-core-ui-layer, which Memories uses too): "Save a copy" written a frame at a time where the person chose, its progress and its cancel (which
    returns once the partial file is gone), in a scope of its own, so it outlives its page and stops with its tenant.
  - `depot/DepotUploads` (`ShellModel.depotUploads`, one per open tenant, over the app's one `UploadQueue`): files and a
    folder into a level, a replace **expected over the content shown when Replace was chosen** (`FileModel.chooseReplace`,
    the web's "the blob the member saw"; a replace not chosen first is refused), each Depot item's settle (the lists read
    anew, the replace offer, the item out of the tray), what the uploads say one at a time (`State.said`, `take`, `seen`),
    and the tray's acknowledge on the model's clock for the whole queue, Memories' batches too — a model that knew only
    Depot's jobs would have left that timer in the views. A cancelled upload gives up the file it made
    (`UploadJob.abandon`, every give-up `NonCancellable`); one whose give-up cannot reach the server keeps the node, and
    the next run fills it (the web's `fileUploadJob`).
  - **The folder walk's rule is the core's, the walk the platform's:** a phone enumerates through its own door
    (`DocumentsContract`, security-scoped URLs) and hands `FolderUpload.Found` entries; `FolderUpload.keeps` decides
    (regular files, hidden ones kept, a link never followed, an unreadable entry said — both in words of their own, never
    dropped silently); the root is the picked directory's own name (`rootName`), never a literal. On the desktops the core
    walks the path itself (ADR 0137's Consequences).
  - `depot/DropBoxPage` (`ShellModel.dropBoxPage`): the guests' folders, read anew on each revision of the shell's lists, a
    folder moved out through `MoveSheet`, its delete asked (`dropbox_delete_folder`) and done through a header with the
    core's guest-folder facts (`DropBoxPage.FOLDER_FACTS`). "Add files only" at a space's top level is `ShareSheet.topLevel`
    (the share sheet in a mode, its form, saving, refusal and fresh address the sheet's own), not a sheet of each app's.
    **Where the top level's share is offered is the core's for the desktops** (`ShellModel.offersTopLevelShare`); Android
    and Apple still compute it in their shells (native-apps § Open).
  - **`ShellModel.refresh()` reads the tree anew** (`TenantSession.forgetTree` first): the session caches its tree, and
    every write of this device forgets it, but before this a refresh read the cache, so a desktop saw another device's
    upload or a guest's new folder only after a new sign-in. One tree read per refresh; a separate reload beside `refresh`
    was weighed and left, since `refresh` already promised what another device wrote. Bitten by the live case only
    (`ShellModel` takes a concrete `TenantSession`, which no `commonTest` plays).
  - Where the apps disagreed, one rule (technical, open to the owner's veto): the drop box folder's delete says
    `dropbox_delete_folder`, the tray's close `upload_close`; a folder upload's failure says its own words, never
    `share_failed`.
- **Memories' screens are core screen models** (2026-11-13/15, before Linux and Windows drew them; decision 2's shape,
  each rule a `commonTest` case over a gallery the test plays, killed by a mutation — `GalleryModelTest`,
  `GalleryActionsTest`, `GalleryPageTest`, `MediaUploadsTest`, `MemoryRightsTest`, `MemoryCreateTest`,
  `MediaGridLayoutTest`; what the bridge and the desktops built and measured is ADR 0123's Consequences). Android and
  Apple held these in their app models and draw them from the core now:
  - `memories/GalleryModel` (`ShellModel.galleryModel`, `galleryAt`): a Memory's or a chapter's page — its rows (each
    naming the blob ids of its grid and lightbox tiers, which a platform's decoded-picture cache keys by: the core caches
    no bytes), a Memory's chapters with their covers and each chapter's `page` to open (a chapter is no list's object, so
    the lists' kind mapping could not open one), the rights, what is said. It reads anew on each revision of the shell's
    lists, when an upload of this device lands in **this** node, and when the live poll's revision moved (`startLive()`:
    core's `LivePoll` at the web's `ITEMS` cadence, `listNodeMedia` with the first page's revision as `If-None-Match`,
    nothing read while nothing moved, ended with the page) — nothing else. A GPX track and a music track are no photo
    view's media (`GalleryMedium`, the web's `isGalleryMedium`, ADR 0135 decision 2) and an audio file is no medium to add.
  - **The gallery is the web's justified grid on every app** (`memories/MediaGridLayout`, `mediaGridLayout.ts` ported
    once: aspects from the sealed dimensions bounded 3:4 … 4:3, the 4:3 fallback, the 8 gap, each row filled to the width
    and the last at the target height, 150 up to a width of 720 and 200 beyond; `Layout.window` the rows near the view,
    ADR 0105). A platform places tiles at the frames `GalleryModel.layout` hands it. Rejected: square tiles on the phones
    beside the justified grid elsewhere (two looks for one gallery) and a layout per platform (four copies of a rule).
  - `memories/Lightbox` (`GalleryModel.lightbox(index)`, `lightboxOn(mediaId)`): the index kept by the medium's id across
    a re-read (the one now at its place when it went, the web's rule); a video's original as a `RangeReader`, opened only
    for a video not blocked and **closed by the model** when the lightbox pages away, the player's view leaves (`stop`),
    the lightbox or its gallery closes, or a re-read takes the medium (`RangeReader.close()`: every read after throws);
    the details (`MediaDetails.rows`, the EXIF tier read once per medium, the uploaders once per gallery for every owner
    key it shows); the save of an original (Depot's `FileSave` over the original's frames under
    `MediaDetails.downloadName`, outliving the lightbox); **the slideshow, the web's**: `SLIDESHOW_MS` (5 s, held to
    `MediaLightbox.vue`'s `SLIDESHOW_INTERVAL_MS` by `LightboxPinsTest`), a video's poster held one interval and never
    played, ending on the last medium, any `go` or `play` pausing it, on the model's clock — no view keeps a timer. Zoom
    is the platform's gesture and view; the model knows nothing of it.
  - `memories/MemoryRights` — the web's `rights.ts` ported, fail-closed, for a member and each link role on a MEMORY, a
    CHAPTER, a FOLDER and a space's top ([memories](../apps/memories.md) § the rights matrix), the rights no app draws yet
    among them (edit meta, banner, marks, filter), so the CRs that draw those add no rule. **One model for member and
    guest** (share view = member view, inbox #401): `GalleryModel` takes an access and offers an action only where the
    rights say yes. The apps open a gallery as a member only today (a received link is #435's), so the change that lets
    one open a link adds a source, never a view. Rejected: a member-only model now and a guest's later.
  - **Trash with Undo** (`GalleryModel.trash`, `Lightbox.trash`): the medium out of the rows at once, `deleteMedia`, the
    Undo held for `TRASH_UNDO_MS` (6 s, a node trash's) through `restoreMedia`, which, as on the web, nothing else
    reaches. **An Undo waits for its trash:** pressed while the trash was on its way, the restore went first, found
    nothing, and the trash landing after it took the medium for good (measured on Android under load; a test of an Undo
    needs a door that holds a call open). A guest's own medium is a purge, asked first, without Undo.
  - `memories/MemoryCreate`: a Memory where its placement allows (a space's top, a folder; `AppKinds.Create.MEMORY` in
    every level's New), a chapter only on a Memory's page (`GalleryModel.createChapter`); a sibling whose name folds the
    same refused before anything is sent, the server's refusals said as the web's `MemoryNodeDialog` says them; the new
    node opens.
  - `memories/MediaUploads` (`ShellModel.mediaUploads`, one per open tenant over the app's one queue) holds the settle —
    a medium in tells its node's gallery, a batch's end the lists — and hands each picked file back to the platform once
    the queue is done with it (done, cancelled, or abandoned after a failure; a failed one's kept for its retry; every one
    when the tenant closes), read from the queue's **state**, not its settle callbacks, so a tenant opened again over the
    same queue (`handOver`) loses none. The settle is not the page's: an upload outlives the page it was started from.
    The tier spec is `MediaUpload`'s (`PREVIEW_EDGE` 2048 at 0.85, `THUMBNAIL_EDGE` 512 at 0.8, a poster at
    `posterAtMs(duration)` = min(100 ms, duration / 2); `MediaTierSpecTest` reads the web's `mediaDeriver.ts`);
    decoding and encoding stay the platform's. Rejected: tiers made in the core (a JPEG codec and a video decoder in
    Kotlin/Native, against decision 1). The tray stays `DepotUploads`', which already ran Memories' batches.
  - A Memory's and a chapter's header is shareable: the share sheet offers READ, READ_WRITE and WRITE_ONLY, and a
    WRITE_ONLY link seals to the node's own drop key, made by its first link and reused by every later one
    (`ShareLinks.dropFields`, the web's `share.ts`); an upload-only link is neither edited nor renewed in the apps
    (switched off and minted anew). "Add photos" joins the header's plan where the rights upload. The hero shows only
    once a cover exists, on every app (owner 2026-11-13; the web always frames one).
  - Where the phones disagreed, one rule (technical, open to the owner's veto): the uploaders read once per gallery
    (Android's), the slideshow the web's, the Undo the node trash's 6 s, a Mac's saved original through the save panel
    into `FileSink` (iOS keeps `fileMover`, ADR 0047's Consequences).
- The repository's Rust (`src/mobile/linux/`) is held to the no-deprecated rule by reading: cargo has no deprecation
  gate, as the JavaScript install has.
  The repository's C# (`src/mobile/windows/`) has one: `run.py build` is red on any NuGet package `dotnet package list`
  calls deprecated or vulnerable, transitive ones included.
- What is still open per platform is [native-apps](../crosscutting/native-apps.md)' to keep.

## The web's words (inbox #1060, built 2026-11-22)

The web's messages (`src/web/src/i18n/index.ts`, each application's `features/<owner>/i18n.ts`, vue-i18n) and
`words.json` were two catalogs nothing compared. Measured 2026-11-22 (a node `eval` of each web catalog's blocks, then a
Python comparison): the web 2,529 keys in 4 files, the apps 1,503; 1,134 app keys and 1,242 web keys say a meaning the
other says (English equal once placeholders are read alike); 42 such pairs worded it apart (15 "pot" against the web's
and ADR 0086's "Pot", 13 German wordings — "Spaces" against "Bereiche", "Titelbild" against "Cover" —, quote style,
punctuation, case); only 427 web keys derive an app key's name.

1. **The two catalogs are paired, and the pairs are held equal.** `src/mobile/words/web.json` names, per app key, the
   web keys that say the same (`twins`), the pairs worded apart on purpose with why (`apart`: a different meaning, or a
   platform's convention — the ellipsis on a command that opens a picker), and the pairs waiting on the owner (`open`).
   `scripts/validate_web_words.py` holds every twin equal in English and German (placeholders matched by first use in
   the English, plurals form by form, Apple's `apple_en` / `apple_de` not compared), and demands that any sentence of
   three words or more both catalogs say be paired — so a new shared sentence is decided the day it lands. A separate
   file and not a field on each entry: ~820 entries would gain a line while other work adds words, and lines beside an
   edited `de` conflict; one line per pair merges. Seeded from the identical pairs of three words or more, the pairs
   whose names agree and the resolved conflicts: 819 app keys, 860 pairs, 10 apart, 2 open.
2. **Where they differed, the web's wording won** (ADR 0154; owner mandate 2026-11-21), in `words.json`: 49 field changes
   over 44 keys — "Pot" capitalised in all 18 of the apps' English uses, "Bereich(e)" for "Space(s)" in all 6 German ones,
   "Cover" for "Titelbild" on the three cover actions, the web's typographic apostrophe, its punctuation and case, and
   seven German wordings (`offer_not_now`, `pot_unreadable`, `search_words_password`, `search_words_profile`,
   `signin_working`, `share_link_share`, `bookmark_delete_confirm`). Apple's title case stays in `apple_en`
   (`cover_choose_image` gained one). Owner questions left `open`: `kit_save_title` (the apps' own step title against
   the web's) and `link_sign_in_scan_hint` (the web quotes a screen's title in lower case); and, worded alike on both
   but split within each, "Cover" / "Titelbild", "Titel" / "Track", and "Tenant" left English in German.
   **All answered by the owner 2026-11-22** (recorded in ADR 0086): `kit_save_title` is the web's "Notfall-Kit
   sichern"; the web quotes "Add a device" as the screen is named; "Cover" everywhere, "Titel" for a piece of music
   (a GPX track stays "Track"), "Tenant" stays English. Both pairs moved to `twins`; `open` is empty.
   The changes, each `words.json` field as it was and as the web words it:

   | key | field | before | after |
   |---|---|---|---|
   | `pot_loading` | en | 'Loading the pot…' | 'Loading the Pot…' |
   | `pot_ledger_unknown_entry` | en | 'An entry this pot no longer shows' | 'An entry this Pot no longer shows' |
   | `entry_stale` | en | 'Someone else changed the pot meanwhile. It was read again; save once more.' | 'Someone else changed the Pot meanwhile. It was read again; save once more.' |
   | `people_remove_confirm` | en | 'No entry names them, so they disappear from the pot. This can’t be undone.' | 'No entry names them, so they disappear from the Pot. This can’t be undone.' |
   | `pot_busy` | en | 'The pot’s key is being renewed. Try again in a moment.' | 'The Pot’s key is being renewed. Try again in a moment.' |
   | `pot_blocked` | en | 'The operator blocked this pot.' | 'The operator blocked this Pot.' |
   | `settings_missing` | en | 'This pot has no base currency yet. Pick one to record entries.' | 'This Pot has no base currency yet. Pick one to record entries.' |
   | `settings_missing_read_only` | en | 'This pot has no base currency yet, so it shows no entries.' | 'This Pot has no base currency yet, so it shows no entries.' |
   | `settings_currency_fixed` | en | 'Fixed: the pot holds entries.' | 'Fixed: the Pot holds entries.' |
   | `settings_categories_hint` | en | 'Added here, they can be picked for every entry of this pot. They can be renamed, not removed.' | 'Added here, they can be picked for every entry of this Pot. They can be renamed, not removed.' |
   | `create_pot` | en | 'New pot' | 'New Pot' |
   | `create_pot_name_taken` | en | 'There is already a pot with this name here.' | 'There is already a Pot with this name here.' |
   | `create_pot_people_failed` | en | 'The pot was created, its people were not. Add them in its settings.' | 'The Pot was created, its people were not. Add them in its settings.' |
   | `create_pot_failed` | en | 'The pot was not created. Please try again.' | 'The Pot was not created. Please try again.' |
   | `ed_blocked` | en | 'Blocked by the operator: this pot or entry takes no changes. Nothing was saved.' | 'Blocked by the operator: this Pot or entry takes no changes. Nothing was saved.' |
   | `problem_unknown_participant` | en | 'The entry names someone who is not in this pot.' | 'The entry names someone who is not in this Pot.' |
   | `history_unreadable` | en | 'A version this pot cannot read' | 'A version this Pot cannot read' |
   | `pot_open_failed` | en | 'This pot did not open' | 'This Pot did not open' |
   | `memory_date_invalid_range` | en | "The end can't be before the start." | 'The end can’t be before the start.' |
   | `memory_place_name_from_map` | en | "Use the map's name" | 'Use the map’s name' |
   | `settings_maps_receive` | en | "The map service receives the part of the map you view, the place-name service the point of a place, and both this device's IP address." | 'The map service receives the part of the map you view, the place-name service the point of a place, and both this device’s IP address.' |
   | `cover_choose_image` | en | 'Choose an Image' | 'Choose an image' |
   | `cover_choose_image` | apple_en | None | 'Choose an Image' |
   | `pot_expense_by` | en | 'Expense by {1}' | 'expense by {1}' |
   | `pot_refund_to` | en | 'Refund to {1}' | 'refund to {1}' |
   | `ed_paid_column` | en | 'paid' | 'Paid' |
   | `ed_paid_column` | de | 'bezahlt' | 'Bezahlt' |
   | `ed_received_column` | en | 'received' | 'Received' |
   | `ed_received_column` | de | 'erhalten' | 'Erhalten' |
   | `list_empty` | en | 'Nothing here yet' | 'Nothing here yet.' |
   | `list_empty` | de | 'Hier ist noch nichts' | 'Hier ist noch nichts.' |
   | `memory_upload_switched_off` | en | 'Memories is switched off here, so nothing can be added.' | 'Memories is switched off here, so nothing can be added' |
   | `memory_upload_switched_off` | de | 'Erinnerungen ist hier abgeschaltet, deshalb kann nichts dazukommen.' | 'Erinnerungen ist hier abgeschaltet, deshalb kann nichts dazukommen' |
   | `share_link_share` | de | 'Teilen …' | 'Teilen…' |
   | `cover_change` | de | 'Titelbild ändern' | 'Cover ändern' |
   | `cover_remove` | de | 'Titelbild entfernen' | 'Cover entfernen' |
   | `cover_set` | de | 'Titelbild festlegen' | 'Cover festlegen' |
   | `drawer_spaces` | de | 'Spaces' | 'Bereiche' |
   | `space_switch` | de | 'Space wechseln' | 'Bereich wechseln' |
   | `switcher_open` | de | 'Space, Tenant oder Server wechseln' | 'Bereich, Tenant oder Server wechseln' |
   | `manage_hint` | de | 'Ausgeblendete Tenants und Spaces bleiben auf dem Server und werden nur auf diesem Gerät weggelassen.' | 'Ausgeblendete Tenants und Bereiche bleiben auf dem Server und werden nur auf diesem Gerät weggelassen.' |
   | `unlock_remembered_hint` | de | 'Bestätige, dass du es bist, um deine Spaces zu öffnen.' | 'Bestätige, dass du es bist, um deine Bereiche zu öffnen.' |
   | `device_unlock_prompt` | de | 'Deine Spaces öffnen' | 'Deine Bereiche öffnen' |
   | `offer_not_now` | de | 'Nicht jetzt' | 'Jetzt nicht' |
   | `pot_unreadable` | de | 'nicht lesbar' | 'unlesbar' |
   | `search_words_password` | de | 'Passwort, Passphrase, Anmeldung' | 'Kennwort, Passwort, Anmeldung' |
   | `search_words_profile` | de | 'Benutzername, echter Name, Profilbild, Avatar' | 'Benutzername, Name, Profilbild, Avatar' |
   | `signin_working` | de | 'Anmeldung läuft…' | 'Wird angemeldet…' |
   | `bookmark_delete_confirm` | de | '„{1}“ wird in den Papierkorb verschoben. Von dort kannst du es wiederherstellen, bis es endgültig gelöscht wird.' | '„{1}“ kommt in den Papierkorb. Dort lässt es sich wiederherstellen, bis es endgültig entfernt wird.' |

3. **The notices' words are generated for the web** — the mechanism ADR 0155 decision 7.2 waited on ("notices after one
   word catalog"). `generate_words.py --web` writes `src/web/src/i18n/words.generated.json` (committed): the keys
   `web.json`'s `read` names, in vue-i18n's syntax with list placeholders (`{n}` → `{n-1}`, a `Notice`'s arguments
   arrive positional), plurals as ` | ` forms, syntax characters as literal interpolations. The guard holds the file to
   the generator's output and every `read` key to a `Msg` the `*Notices.kt` tables name; `words.test.ts` renders every
   word through vue-i18n in both languages against the catalog's own text. Until the notices' move (§ The notices'
   move, below) `read` was all 113 of those `Msg`s and nothing registered them; the move registers `words` and narrows
   `read` to the 76 the web says.
4. **One source for all of it is staged, not built.** Generating every web message from `words.json` would add the
   1,287 web-only keys to it (the apps generating resources for words they never show) and rename or map the web's
   5,092 `t('…')` calls in 202 files (`grep -rhoE "\bt\(['\"\`]" src/web/src --include='*.vue' --include='*.ts'`);
   that churn would not merge beside the work that adds words to both. The order: (1) the notices' move registers
   `words` and deletes the web's twin keys of those words (**built** 2026-11-23, § The notices' move); (2) each application, as ADR 0155 decision 7.3 moves it onto
   the core, joins its shared words to `read` and deletes its web twins; (3) the web-only words stay the web's until an
   app needs one.

The guard's limits (its docstring): a pair is only as true as `web.json` says; a meaning worded apart in English
entirely, or in one or two words, is not offered for pairing (the homographs live there — "Open" the state and the
verb); a notice raised outside a `*Notices.kt` cannot be in `read`. `validate_guard_gaps.py` cases 126–132 pin its rules
and the short-text limit.

### The notices' move (inbox #1170, built 2026-11-23, branch `claude/notices-words`)

Step (1) of item 4. `src/web/src/i18n/index.ts` registers `words.generated.json` as core's `words` namespace (so no
application can take it, `addApplicationMessages`); every call site of a web key `web.json` twinned with a notice's word
calls `t('words.<key>', [args…])` instead — the named arguments become the list the apps' `{1}`, `{2}` are, in the
order the twin-equal rule already held them to — and the 80 such keys of `index.ts` and the applications' `i18n.ts`
are deleted in both languages, with Memories' `media.uploadStatus.*` (six one-word labels the pairing never offered,
the same states as core's `upload.status.*`: both now read `UPLOAD_STATUS_TEXT`). A refusal or a reason built at run
time (`node.move.refused.${code}`, `expenses.entry.problem.${…}`, the upload state) reads a typed table instead
(`MOVE_REFUSAL_TEXT`, `DELETE_REFUSAL_TEXT`, `PROBLEM_WORDS`, `UPLOAD_STATUS_TEXT`, `MEDIA_REFUSAL_TEXT`, and the copy and
export dialogs' reason tables): its codes the apps' notices say point into `words`, the rest stay the web's keys, and
`Record<Code, string>` makes a new code a type error rather than a raw key. No text a user sees changed: a twin was
held equal in both languages before it was deleted (twin-equal), and `words.test.ts` renders each word as the catalog
writes it.

Its checks: `validate_web_words` **read-has-no-twin** (a `read` word stands in no `twins` / `open` pair, and no web
message says what a `read` word says, of any length, unless the pair is `apart` — guard-gaps case 132 re-adds
`Queued`); `i18n.test.ts` **names only words it registered** (every `words.<key>` a source quotes is a registered word,
no `words.*` key is built at run time) beside the existing **uses every key it carries** (a registered word no source
names is red). Measured eager set (`vite build`, `index.html`'s entry and modulepreloads, gzip -9): 658,043 B before,
659,341 B after, **+1,298 B gz**; the generated words file alone is 3,956 B gz for the 76 words and would be 5,501 B gz
for all 113.

Decision log (question, choice and why, evidence, cost):
- *Which words does the web register?* Only those it says: `read` narrows from the 113 `Msg`s of the notices tables to
  the 76 with a web twin, and **read-is-notices** turns from equality into `read` ⊆ the notices' `Msg`s. Registering
  all 113 would put 37 words no web source names on the eager set (+1,545 B gz on the file alone, measured; with the move's own +1,298 B an estimated
  ~2.8 KB gz, over the 2 KB the move was allowed) and redden `i18n.test.ts`' unused-key rule. Cost: a notice's word no longer becomes the web's by being
  raised; ADR 0155 decision 7.2's notices slice, which draws the web's notices from the core, adds its words to `read`
  then, and step (2) widens the subset rule to an application's shared words.
- *A twin's return:* a `read` word's copy coming back as a web key is red by text, of any length, because `unpaired`
  asks for three words and the upload states are one ("Queued", "Done"). Two web keys say a `read` word's text with
  another meaning and are `apart`: `common.done` (the button) and `memory.banner.preparing` (a banner being made ready).
- *Twins that only paired through a deleted web key:* `memory_refused_scope_deleting` and `trash_name_taken` were twinned
  with web keys that were also a `read` word's twin (`memory.buckets.deleteRefusedReason.scope_deleting`,
  `node.slugTaken`); those pairs went with the keys. Nothing holds those two app keys equal to `delete_refused_scope_deleting`
  / `node_slug_taken` now — the apps' own catalog, not the web's, is where that duplication lives.
- *Near-twins left alone:* notice words the web says in other words (`copy_done` ~ `node.copy.copied`, `create_pot_*` ~
  `expenses.create.*`, `share_reissue_hint` ~ `depot.dropBox.shareReissueHint`) and short exact ones the pairing does not
  offer (`import_imported` ~ `export.imported`, `left_out_line` ~ `node.copy.leftOutLine`, `left_out_unsupported` ~
  `node.copy.reason.unsupported_kind`) stay the web's: moving the first changes what a user reads (the owner's copy
  call), the second are inbox #1171's to pair first.
- *A summary's counts (inbox #1173, 2026-11-23):* a download's and an import's summary took their counts in one form
  ("1 items", "1 Einträge"). The import's summary is a plural on both sides (`import_summary` `one`/`other` ~
  `export.importSummary` `one | other`, still twins); the apps needed no call-site change, since the core's
  `Notice(Msg.IMPORT_SUMMARY, nodes, …)` already passes the count first, which every platform's generated words choose
  the form by. The web passes `plan.nodes` as the plural count. A download's summary (web only — the apps' download has
  none) is built from two counted parts, `export.itemCount` and `export.fileCount` (`summary: '{items}, {files},
  {size}'`) rather than one plural of two counts, which vue-i18n's one choice per message cannot express. Pairing:
  `export.fileCount` twins `dropbox_guest_files` (Datei/Dateien, the same count); `export.itemCount` is `apart` from
  `memory_items`, since a download counts entries (Eintrag) and a Memory its items (Objekt). User-visible: only the
  singular forms are new (en "1 item, 2 kB, will be added to …", de "1 Eintrag, 2 kB, wird zu … hinzugefügt"; "1 item,
  1 file, …", "1 Eintrag, 1 Datei, …"); the plural forms read as before. Note: vue-i18n also takes a named `n` or
  `count` as the plural choice implicitly, so the tests bite on the catalog's forms, not only on the call's count.
- *The short notice words (inbox #1171, 2026-11-23):* the three the pairing could not offer (two words or fewer) are
  decided. Twins, so the web reads them from `words` and its copies are deleted: `import_imported` (was
  `export.imported`), `left_out_line` (was `node.copy.leftOutLine`) and `left_out_unsupported` (was
  `export.reason.unsupported_kind` and `node.copy.reason.unsupported_kind` / `unknown_application` / `newer_content`, which
  all said "not supported"); the copy and download dialogs' reason tables point there. Each was equal in both languages
  before the move, so no text a user reads changed. Apart: `left_out_line` from `node.deleteMany.reason` and
  `node.move.many.reason` — the same "“name”: reason" shape, but a selection's node that was not deleted or moved,
  which the apps say with their own `select_reason` (raised by `SelectionTrash` and `MoveSheet`, not a notices table, so
  it cannot be read); those two now twin `select_reason`, held equal. `words.json`'s notes on `import_imported` and
  `import_imported_left_out` no longer name deleted web keys. Eager set (`vite build`, the entry and its modulepreloads,
  gzip -9): 659,430 B before, 659,361 B after, **−69 B gz** (three words added to `read`, six web keys deleted in
  both languages).
- *The notice words worded apart (inbox #1172; the owner's copy decision 2026-11-23, "as proposed"):* one wording per
  pair, then the web reads it from `words`. The apps take the web's wording for `copy_done` (en “{1}” copied to “{2}”.,
  de „{1}“ nach „{2}“ kopiert.; was “{1}” is copied to “{2}”. / „{1}“ ist nach „{2}“ kopiert.), `create_pot_failed`
  (The Pot could not be created. Please try again. / Die Kasse konnte nicht angelegt werden. Bitte versuche es erneut.;
  was The Pot was not created. … / Die Kasse wurde nicht erstellt. …) and `share_reissue_hint`'s ending (… Create a new
  link instead. / … Erstelle stattdessen einen neuen Link.; was Renew it instead. / Erneuere ihn stattdessen.). The web
  takes the apps' `download_failed`, which names the file (“{1}” could not be downloaded. Please try again. / „{1}“
  konnte nicht heruntergeladen werden. Bitte versuche es noch einmal.; was The file could not be downloaded. Please try
  again. / Die Datei konnte nicht heruntergeladen werden. Bitte versuche es erneut.) — Depot's row and file view pass the
  node's name. These four join `read`; the web's `node.copy.copied`, `expenses.create.failed`,
  `depot.dropBox.shareReissueHint` and `depot.downloadFailed` are deleted. `create_pot_people_failed` ~
  `expenses.create.peopleFailed` is `apart` by decision (the apps add a Pot's people in its settings, the web on its
  page). **Not moved, for the owner:** `upload_name_taken` ~ `memory.slugTaken`. The web says `memory.slugTaken` only
  where a Memory is created or renamed (`MemoryNodeDialog.vue`), and there the apps say `memory_slug_taken` (raised by
  `MemoryCreate`), which already twins it word for word ("A folder or Memory with a similar name already exists here.");
  the apps' `upload_name_taken` is an upload's refusal, which the web words inside `depot.upload…slug_taken` ("“{name}”
  was not uploaded: a folder, Memory or file …"). Moving the Memory dialog to `upload_name_taken` would change what it
  says and break its true twin, so it stays; the question left (inbox #1180) is whether a Memory's name conflict should also name
  files (then `memory_slug_taken` and `memory.slugTaken` change together). Eager set (measured as above): 659,361 B
  before, 659,380 B after, **+19 B gz**.

## Alternatives considered

- **Compose Multiplatform for Android, Windows and Linux.** One Compose UI for three platforms, the cheapest by far.
  Rejected by the owner (2026-11-09): each platform's own UI.
- **A desktop shell around the web client (Tauri).** Parity at once, and a client the operator does not serve (ADR
  0026). Rejected: the web's look and navigation instead of the app's (ADR 0122), and the TypeScript suite instead of
  the core.
- **Logic left in each app model.** Rejected: four copies of every rule.
- **Windows through Kotlin/Native's whole C export, or the core on a JVM beside a C# UI.** The first exposes every
  class as handles and function tables a C# hand-maintains; the second runs a second runtime and process for a UI.
  The narrow bridge (decision 5) keeps the surface to one call and one callback.
- **A second Linux UI for KDE Plasma (Qt with Kirigami, over the same bridge).** Plasma's own look and theme. Rejected
  by the owner (2026-11-10): every screen a fifth time; the libadwaita app works under Plasma through the portals and
  the Secret Service, and only its look is GNOME's.
- **Linux on the JVM, in Kotlin through java-gi** (decision 4 as first taken, 2026-11-09). No bridge, maintained
  bindings, one language, and a spike ran (2026-11-10: GTK 4.22 and libadwaita 1.9 from an ARM container, java-gi
  1.0.0-RC3 on JDK 25). Replaced by the owner (2026-11-10): a Java runtime inside every Linux install, where the
  bridge decision 5 builds anyway serves a native UI in GNOME's own way.
- **Linux on Kotlin/Native with GTK through cinterop.** No bridge either, but GObject's signals and lifetimes written
  by hand with no maintained bindings, and Kotlin's collector beside GObject's reference counts where a lifetime goes
  wrong.
