# Native apps — where they stand

The current picture of the V2 native apps in `src/mobile/` (inbox #438): what each platform runs, what is built
against the web client, and what is open. The decisions are [ADR 0122](../decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
(one app, its navigation), [ADR 0123](../decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
(the shared Kotlin core under a native UI; its Consequences record each slice with what was measured, and are this
page's source for every "measured") and [ADR 0138](../decisions/0138-the-apps-take-what-the-system-shares-the-chosen-nodes-kind-decides-what-it-becomes.md) (the
share target). This page is kept current as slices land; a row moves from open to built in the commit that builds it.

## The split

Everything that is not drawing a screen or talking to the device belongs in the Kotlin core (owner 2026-11-09,
[ADR 0136](../decisions/0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)), and it is there
(2026-11-10): the suite, the API, every application's domain and its flows, the upload queue, and each screen's state and
rules as a core model both apps draw — the way in (`session/SessionModel`), the shell and its lists (`navigation/ShellModel`,
`RowDetails`), the trash sheet (`navigation/TrashSheet`), the open Pot (`expenses/PotModel`) with its entry editor, settings
and New pot (`EntryEditor`, `PotSettings`, `PotCreateSheet`), shared files
(`expenses/ShareInbox`), the link form and a link's row (`session/LinkForm`, `LinkRow`), a header's plan and words
(`navigation/NodeHeader`), an open node's header with pin, trash, rename and cover (`navigation/NodeHeaderModel`) and the
sheets behind it (`MoveSheet`, `ShareSheet`, `ReportSheet`; ADR 0136's Consequences), a file's page and its Save a copy
(`depot/FileModel`, `FileSave`), Depot's uploads (`depot/DepotUploads`, the walk's rule `FolderUpload.keeps`) and a drop
box's page (`depot/DropBoxPage`), a Memory's page and its lightbox (`memories/GalleryModel`, `Lightbox`, the grid's layout
`MediaGridLayout`, the rights `MemoryRights`, `MemoryCreate`) and Memories' uploads' settle (`memories/MediaUploads`); which words an outcome raises (`words/Notices`) and what a row or a page says, piece by piece
(`words/Piece`). A platform keeps only its UI, built to its own guidelines, and what only it can do: the system pickers
and share sheets, the secure store, decoding and framing pictures, writing a date, a size or an amount its own way, the
device's calendar, and the order of a view's own steps.

Where the two apps had drifted, one answer was chosen (each pinned by a core test): after the open node is trashed the
shell goes to its parent, read anew (one opened at the top: its application's list); a move's undo is offered for the
core's 6 s, as a trash's; shared files fit a Pot by one rule (`PotShare`'s count and byte budget); a file's
page is decided and read by the core, decoded and drawn by the platform; a Pot created from a level lands in the level
shown; a currency's minor units are one table measured from the web's `Intl` (`expenses/Currencies`, not
`java.util.Currency` or `NumberFormatter`); a drop onto an open Pot runs the core's state machine; receipts a save left out
for want of room close the editor and the shell says which (the web's toast); a report's contact is counted as the web's
`maxlength` counts it; the lists' covers are read again after every change on both; an amount the core hands without a
currency is a number alone (no locale's or `"EUR"` fallback); a stale save reads the other writer's version into the
editor and saves on top, as the web; the editor's delete is asked and says `entry_delete`; a PDF receipt shows at most
20 pages and says so; today and a restore's window are the core's clock; a file downloads as itself, any other node as
its ZIP; a replace expects the content shown when Replace was chosen; a member replaces nothing in a guest's identity
folder; a gallery is the web's justified grid on every app, its uploaders read once per gallery, its slideshow the
web's and a medium's Undo the node trash's 6 s; nothing decrypted is written to show or play a file ([ADR 0047](../decisions/0047-inline-video-playback-range-streaming.md)'s
Consequences).

## Platforms

| Platform | Builds | UI | Large-screen layout | Seen running |
|---|---|---|---|---|
| Android phone | yes (minSdk 31) | Compose, Material 3 | — | yes, the emulator, every slice |
| Android tablet | the same APK | Compose | Material 3's window classes by the window's width: from 600 dp a rail at the side takes the bar's place; from 840 dp the drawer stays open as the sidebar (ADR 0122; an object chosen there has no back, decision 7) and an application's list stands beside its open object (list-detail; a choice there replaces the open one, `Shell.openBeside`); sign-in and unlock read at a form's width | the emulator at 1280 × 800 dp and 800 × 1280 dp (`wm size`), 2026-11-10 |
| iPhone | yes (iOS 26) | SwiftUI | — | yes, the Simulator, every slice |
| iPad | yes (the iOS target, no device family set) | SwiftUI | `NavigationSplitView` with its sidebar open in a regular size class; the detail is one page whose bar's places are a segmented control in its toolbar, not a tab bar, Search always the toolbar's field at its trailing end; Settings pinned at the sidebar's foot; an object chosen in the sidebar has no back (ADR 0122 decision 7); sign-in and unlock read at a form's width | the Simulator, 2026-11-10 |
| macOS | yes (macOS 26) | SwiftUI | always the split view, as the iPad's; the sidebar 240–360 pt wide | an ad-hoc signed build, 2026-11-10 (Home, a Pot's entries and overview); without a team's signing the device trust is never enrolled; no share extension there |
| Windows | the core and the bridge for x64 (`veyq.dll`), cross-compiled on the developer's Mac; the app on a Windows host (`src/mobile/windows/run.py build`), unpackaged and self-contained | WinUI 3 in C#, Fluent, over the core as `veyq.dll` through the bridge | `NavigationView` with its own thresholds: Left from 1008 epx, LeftCompact (icons) from 641, LeftMinimal below; the switcher its pane header, Search its `AutoSuggestBox` (Ctrl+E, Ctrl+F), Settings at its foot, New the first command of the content's `CommandBar` | an ARM64 Windows 11 VM under x64 emulation, driven through UI Automation, 2026-11-11 (the first slice: sign-in, the shell, Home, a Pot read only, Search; maximized, 800 and 500 wide); the members' pictures and a Pot's download through the system's save picker, its ZIP imported by the web, 2026-11-11; the device trust, the owner answering Windows Hello, 2026-11-11; a node's header with every action on it, 2026-11-12; Expenses whole (the entry editor, receipts with a PDF through `Windows.Data.Pdf`, settle-up, history and restore, settings and people, New pot, the live poll), 2026-11-12; Depot whole (a file's page with
media played, Save a copy with its cancel, uploads up to 2 GiB + 1, drop boxes and the top level's share), 2026-11-12; Explorer's drop with UAC on, 2026-11-13; Memories (New Memory and
chapters, the justified grid with the live poll, the lightbox with zoom, the slideshow, a video played, Save and trash
with Undo, uploads with this host's tiers and a drop from Explorer, a Memory's links, the row covers), 2026-11-15; no x64 PC |
| Linux | the core and the bridge for arm64 and x86-64, cross-compiled on the developer's Mac; the app for arm64, proved and built in the GNOME 51 SDK it ships on (`src/mobile/linux/run.py build`) and drawn in a Fedora 44 container for a pass (`run.py run`), and as an aarch64 Flatpak bundle on `org.gnome.Platform//51` (`run.py flatpak`, [ADR 0139](../decisions/0139-the-desktop-apps-ship-as-a-flatpak-bundle-and-an-msix-built-in-checks-and-promoted-with-the-release.md)) | GTK 4 and libadwaita in Rust, over the core as `libveyq.so` through the bridge | `AdwNavigationSplitView` with libadwaita's `AdwSidebar` (240–360 sp), collapsing below 600 sp to a navigation stack, the sidebar in its page mode; the switcher the sidebar's title menu, Search a search bar under it (Ctrl+F), Settings at its foot | Broadway in Fedora 44 arm64, drawn into a browser, 2026-11-10 (the first slice: sign-in, the shell, Home, a Pot read only, Search; 1100 × 750 and 500 × 750); the members' pictures and a Pot's download through GTK's save dialog, its ZIP imported by the web, 2026-11-11; the device trust over gnome-keyring in the container, 2026-11-11; a node's header with every action on it, 2026-11-12; Expenses whole (the entry editor, receipts with a PDF through poppler-glib, settle-up, history and restore, settings and people, New pot, the live poll), 2026-11-12; Depot whole (a file's page with
media played, Save a copy with its cancel, uploads up to 2 GiB + 1, drop boxes and the top level's share), 2026-11-12; a
real GNOME session in the Fedora 45 VM for the drop, the portal's dialogs and the sound, 2026-11-12; the Flatpak installed
there from its bundle (the runtime from Flathub), started to its sign-in in English and German, the FileChooser portal,
the secure store's calls, an update and an uninstall, 2026-11-13 (ADR 0123's Consequences, the Flatpak); signed in inside
the installed Flatpak's sandbox — by an Emergency Kit picked through the portal, the members' pictures, the exchange
rates, a receipt's PDF uploaded, drawn and saved at document-portal paths, a Depot upload and a drop from Files, H.264
and VP9 played and heard, the account's Settings from the sidebar (rename, another session signed out, sign-out) in
English/light and German/dark — 2026-11-13; Memories (as Windows', a drop from Files and the sound in the VM's session),
2026-11-13/14 |

Windows and Linux are wanted (owner 2026-11-09), each with its own native UI over the same core
([ADR 0136](../decisions/0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)): Linux GTK 4 and
libadwaita in Rust, Windows WinUI 3 in C#, both over the core as a Kotlin/Native library through one bridge (owner
2026-11-10). Each has its first slice built (ADR 0123's Consequences: the core on Linux and on Windows, the bridge, the
Linux app and the Windows app), and the bridge's channels for shown bytes, a blob's ranges and files by path ([ADR 0137](../decisions/0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)),
proved by the members' pictures and a Pot's download on both. On Windows that core is x64 only: Kotlin/Native has no Windows ARM64 target.
The desktop apps' packages, a preview, are described to a person on the docs site's
[Install the app](../../site/src/content/docs/getting-started/install-the-app.mdx).

## Built, by area

"Measured" means ADR 0123 records it run against a dev instance (and, where it says so, read back by the web); "built"
means the code is there and compiles, nothing recorded beyond. Both apps hold everything listed unless a row says
otherwise; the Linux and Windows apps hold only their first slice, the members' pictures, a Pot's download, the
device trust with "Manage servers" (their rows above), the kit at sign-in, the account's Settings and device linking, a folder's and a Pot's header with every action on it: pin,
trash with undo and the space's trash, rename, New folder, move and copy with the storage check, a folder's download,
share links, a report and a cover with its crop, Expenses whole as its paragraph's but the share sheet's files,
Depot whole as its paragraph's but Send a copy (measured 2026-11-12), and Memories as its paragraph's (measured
2026-11-13/15; ADR 0123's Consequences, the Linux app and the Windows app).

**Account and session.** Sign-in with password and Secret Key behind the `/api/setup` major gate, each grant judged as
the web judges it (measured, `LiveSignInTest`, `GrantTrustTest`); several servers and tenants with their switcher,
hiding tenants and spaces. **The Emergency Kit at sign-in, on all four apps** (`en.account.key-model` § The
Emergency Kit; [ADR 0136](../decisions/0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)'s
Consequences): "Open Emergency Kit" takes the kit's PDF (saved, printed, photographed) or a picture of its QR from the
system's picker, "Scan Emergency Kit" its QR wherever the app has a camera (first on a phone or tablet, after Open on a
computer; hidden where the scan says `NoCamera`); email and server are filled, the Secret Key held by the core's
sign-in model, so only the password is typed — the web's three fixture kits read and a dev kit signed in to the shell,
measured 2026-11-13 on Android (a throwaway AVD), iOS (a throwaway simulator), macOS, Linux (the Fedora VM's GNOME, the
portal's chooser, inside the installed Flatpak's sandbox) and Windows (the VM, the open picker; the packaged app too);
the scan to the shell on Android (the emulator's virtual scene), iOS and macOS (their test cameras). No copy of a kit
or a frame on any app's disk ([ADR 0047](../decisions/0047-inline-video-playback-range-streaming.md)'s Consequences).
**The account's Settings page** from the drawer, on all four apps (`uc.account.sessions`): the address, this device (its
name, the trust level as Manage servers' — one core state —, Stop trusting), devices and sessions named from the
device register (sign one out, "Sign out and remove saved key", every other), the devices with a saved key, Sign out;
"Appearance and language" on the phones opens the system's per-app page (the apps follow the system's theme and
language; the desktops have no row); "Add a device" where the instance has `core.device-linking`. Measured
2026-11-13 against the dev backend on Android, iOS, macOS, Linux (the Fedora VM, inside the Flatpak's sandbox) and
Windows (the VM), the desktops in English/light and German/dark: this device renamed, a second session of the account signed out from
the list, "Sign out" taken — on macOS a session's own menu not driven (§ Open).
**Device linking, on all four apps** ([ADR 0059](../decisions/0059-device-linking.md); the core's model, ADR 0136's
Consequences; `uc.account.onboard-device` § In the apps): both directions in both roles — Settings' "Add a device" shows
a code and, where the device has a camera, scans one; the sign-in's "Scan a code from another device" (camera only) and
"Sign in with a QR code" use the form's server, and say "This server does not link devices." where it has
`core.device-linking` off (Settings' entry is offered only where it is on). The new device spends the grant's token at
refresh (ADR 0059 §2) and is offered only its `DEVICE_KEY` levels, "Not now" saying the password and Secret Key or the
kit will be needed next time. The shown code and the scanner are a secret on screen (`src/mobile/CLAUDE.md` § Secrets
on screen). Measured 2026-11-13/15 against the web in headless Chrome (its camera a `.y4m` of the app's code), no
password typed: Android all four runs (a throwaway AVD's virtual scene as its camera), Linux all four (the container,
its test camera), macOS three of four (its test camera), Windows the two showing ones (the VM has no camera, so
`NoCamera` offers only those) and a linked Windows device showing again; a linked Linux device staying signed in through
the Secret Service (2026-11-15: `run.py run --home` a volume of its own, the web's code scanned, "Stay signed in" at the
Login level, the app quit and started twice straight to its shell; a copy of that home without its keyring started to the
sign-in; Settings' "Sign out" left no session and no saved key of it on the web's list); the slide by pointer and keys on Android, Linux
and Windows; Android's task snapshot and Windows' capture bitten with the code shown.
The Use page that says this to a person: [Add another device](../../site/src/content/docs/account/adding-another-device.mdx).

**Device trust, on all four apps** (ADR 0123 decision 8 as changed 2026-11-11; measured 2026-11-11, `DeviceTrustLiveTest`,
`LiveBridgeTest` and a pass per app, ADR 0123's Consequences, the device trust; the two live tests again 2026-11-14,
once the principal had wrap slots free again, and `LiveRunTest` with the case's device trusted — families 20 → 21 →
20, wraps 14 → 15 → 14): every sign-in trusts the device with
the password; an offer after it to stay signed in at each level the device's store has, or "Not now"; "Manage servers"
changes the level, goes back to the password trust or stops trusting; the unlock screen. What each store guarantees is
[security-model](security-model.md) § What a trusted device rests on.

| Platform | The store | Presence level | Login level |
|---|---|---|---|
| Android | Keystore (`KeystoreDeviceKeys`) | a strong biometric or the screen lock (BiometricPrompt) | a Keystore key without user authentication |
| Apple | Keychain (`AppleDeviceKeys`, `KeychainStore`) | Face ID, Touch ID or the passcode (`.userPresence`) | an item bound to the passcode |
| Windows | DPAPI and the Passport key storage provider (`WindowsDeviceKeys`) | Windows Hello, where it is set up | DPAPI |
| Linux | the Secret Service through libsecret, `dlopen`ed (`LinuxDeviceKeys`) | not offered | the collection the desktop sign-in unlocks |

Neither level is offered on Android or Apple without a screen lock or passcode, nor on Linux without a Secret Service
(where no bind key can be kept either, so every start asks for the Secret Key).

**Spaces and navigation.** The drawer (switcher, New, Home, Favorites, Recent, the applications), the bar per
application ending in Search (names only), Home's tree with its filters; favorites, the trash (delete with undo,
restore, purge), move, copy (Pot, folder, file, drop box, with the storage check), rename (its links' names re-sealed),
a node's cover with its crop, core's header for every kind, a report to the moderators — each measured 2026-11-08/09.

**Sharing.** Links on a Pot, a folder, a file, a Memory or a chapter (READ, READ_WRITE, "Add files only" drop boxes,
at a space's top level too; a Memory's or chapter's upload-only link sealing to the node's own drop key, neither edited
nor renewed): create, edit, renew, switch off and on, delete; closing a link re-keys the subtree, an interrupted re-key
is resumed — measured 2026-11-09. Taking shared images and PDFs into a Pot from the system's share sheet (Android, iOS;
ADR 0138), measured 2026-11-08.

**Expenses.** Every web use case: create a Pot, record entries, receipts, balances, an entry's history, exchange
rates, participants, share, verify the ledger; the live poll; export and copy of a Pot — measured 2026-11-08/09. On
Linux and Windows too (2026-11-12): the entry editor, receipts attached through the system's open dialog, shown from
memory (a PDF through poppler-glib / `Windows.Data.Pdf`) and saved through its save dialog, settle-up, an entry's
history and restore, settings and people, New pot, the live poll while the window is active and Refresh (F5); not the
system's share-in (GNOME has no share target, Windows' needs a packaged app) nor drag and drop.

**Depot.** Upload, replace, download a file; upload a folder; view a file (image, PDF, text, media) and send a copy;
drop boxes (read, create, move a guest's folder out); uploader names of members — measured 2026-11-09. Since 2026-11-12
the file page, the uploads and the drop box page are the core's (ADR 0136's Consequences), and Android and Apple were
passed on them again. On Linux and Windows too (2026-11-12): a file's page (an image, a text, a PDF, the rest said), a
medium **played** through the platform's own player over the core's reader (GTK's `MediaFile`, `MediaPlayerElement`),
never decrypted whole or to disk; Save a copy with its progress and Cancel; Replace; uploads of files and a folder from
the system's dialogs and from New, a file of any size read at offsets (2 GiB + 1 measured); drag and drop onto a level or a
folder's row (Linux; Windows from Explorer with UAC on, measured 2026-11-13: a file and a folder onto a level and onto a
folder's row, read back at their SHA-256); the drop box page (a guest's folder moved out or deleted
after asking, with Undo); "Add files only" at a space's top level; Refresh (F5) reading what another device or a guest
wrote. Not Send a copy (GNOME has no share target, Windows' share needs a decrypted file another app reads: Save a copy is
the desktops' way out).

**Memories.** The gallery with chapters, the lightbox with details, video and saving the original; adding photos and
videos, their metadata read on the device as the web reads it (EXIF, the container's time, position), Android asking
once for full media access so a photo keeps its place and name — measured 2026-11-09. Since 2026-11-13 the page, the
lightbox and the uploads' settle are the core's (ADR 0136's Consequences), and every app draws New Memory and Add
chapter, the chapters with their covers, the web's justified grid, the live poll, zoom, the slideshow, a medium trashed
with Undo (a guest's own purged after asking, held by the core's tests: the apps open a gallery as a member only), a
Memory's links, and the hero once a cover exists; GPX tracks and music are left out of the gallery, as the web's photo
views leave them. Measured on Android (2026-11-15), on Linux (the container and the Fedora VM, 2026-11-13/14) and on
Windows (the VM, 2026-11-15), each with a WRITE_ONLY guest's upload seen through the poll; on Apple the moved rules were
passed on iOS and macOS (2026-11-13), the new functions only built (§ Open). The desktops make a medium's tiers through
the host (`HostRequest.MediaTiers`: gdk-pixbuf and GStreamer, WIC and `Windows.Media.Editing`), take a drop of files onto
the gallery and say a dropped folder not taken. On a Mac the lightbox opens in the window, a window of its own only when
asked (ADR 0123's Consequences).

**Uploads.** One queue in the core, the web's rules (`UploadQueueTest`); Android's row and sheet under the top bar,
Apple's toolbar button and list — measured 2026-11-09 (the list behind Apple's button not seen open); Linux's header bar
button with its popover and Windows' `AppBarButton` with its flyout, each with Cancel, Retry and Cancel all, measured
2026-11-12. What the tray shows and when it clears is the core's (`DepotUploads`, Memories' batches among them); what a
medium's upload settles and when a picked file is let go, `MediaUploads`'.

**Out and in.** The ZIP of a Pot, a folder, a file or a drop box, as the web writes it — measured 2026-11-09.

**QR codes** ([ADR 0141](../decisions/0141-qr-codes-are-read-and-drawn-by-the-core.md)). One decoder and one encoder in
the core, a scan model (`qr/QrScan`) and a camera on all four apps — CameraX, AVFoundation, the Camera portal over
PipeWire, `MediaCapture` — the desktops' through the bridge's `Scan.*` with frames as the call's payload, and a code
drawn for the desktops as the core's PNG. Measured 2026-11-13 through each app's debug test camera to `Found` (Android a
throwaway AVD's virtual scene, the iOS Simulator, the macOS build, Linux in the container and the Fedora VM, Windows on
the ARM VM); `NoCamera` on each machine without one, `Denied` on Android, and on Linux by a virtual camera and GNOME's
dialog. Callers: the kit's scan at sign-in, device linking.

## Open

**Platforms and form factors**
- Windows beyond its first slice (the next slice is Linux's and Windows' together, owner 2026-11-10):
  - **Packaging — built** ([ADR 0139](../decisions/0139-the-desktop-apps-ship-as-a-flatpak-bundle-and-an-msix-built-in-checks-and-promoted-with-the-release.md)): an MSIX, x64, self-contained, built `-c Release` through the
    Windows App SDK's own MSBuild tooling, unsigned in Checks and signed at promotion with its block map unchanged; no
    package dependency on the WebP or media extensions (a sideloaded MSIX can depend only on framework packages, and
    Windows 11 provisions them), so where a person removed one the members' pictures fall back to initials (measured
    2026-11-11, the decode test's bite) and a format Media Foundation does not decode is said and offered for saving (the
    VM decoded VP9/Opus and H.264/AAC and refused ProRes, `C00D5212`, said `file_unsupported`; the app ships no codec:
    patents, the owner's answer of the Depot slice, 2026-11-12). A HEIC photo's tiers need the HEIF and HEVC extensions likewise
    (not measured; where WIC does not decode it, it goes up alone with its icon, as on a phone that cannot). **Built 2026-11-13** (`windows/run.py package`, ADR
    0123's Consequences, the MSIX): installs, starts to its German sign-in with UAC off (elevated, like every process
    there), updates in place keeping its data and uninstalls leaving nothing on the VM; a signed copy's block map is the
    unsigned one's. With **UAC on** (2026-11-13): installs, starts to its German sign-in at medium integrity (`0x2000`, not
    elevated) and uninstalls leaving nothing; a picked path opens in the core (ADR 0137: the kit picked at its sign-in,
    `kit picked: Done`); `NoCamera` (no scan entry, the VM has no camera). Still to measure in the package, each needing a
    signed-in packaged app — a Release build has no dev sign-in, so **the owner signs it in** (the password is never typed
    by an agent): the members' pictures, Save a copy, a medium played, a drop (the unpackaged app takes one with UAC on),
    an update keeping the device trust (which needs a trust slot: the principal is at the server's limit, `409
    too_many_devices`) and Hello's enrolment (the owner's PIN); the camera's consent prompt on a PC with a camera (inbox
    #915, with the MSIX opened by double-click through App Installer and removed through Settings → Apps, which the
    site's *Install the app* names and no pass walked yet). **The released MSIX is
    signed by the project's own self-signed certificate, `CN=veyq`** (the owner, 2026-11-13; ADR 0139 decisions 7 and
    12): each person trusts its `veyq.cer` once, elevated, in LocalMachine\TrustedPeople before the first install —
    without it the install is refused `0x800B0109`, unelevated the import is refused, and the install itself needs no
    elevation (measured with UAC on, ADR 0123's Consequences, the MSIX). Their Windows then trusts every package that
    certificate's key signs under `CN=veyq`, not only the one they checked, and nothing revokes it (remembered from the
    MSIX trust model, not measured). The release attaching `veyq.cer` under `apps` with a cosign bundle, and signing the
    MSIX with that certificate only, is written and has not run (ADR 0139 decision 7): the certificate, the signing
    environment and the first Publish dispatch are the owner's, and so is the decision whether the signature stays
    without a timestamp (inbox #913).
  - **Idle memory** is 359–459 MiB (WinUI, x64 under Prism emulation on the ARM VM, measured 2026-11-12 by
    `PeakWorkingSet64`); playing a medium and a 2 GiB upload add an amount that does not scale with the file. What it is on
    an x64 PC, and whether de's +243 MiB over a 2 GiB upload (en's +86) is the GC's slack, is unmeasured.
  - **The level's New shows on a file's and a drop box's page** (as on every non-Pot page); one switch-off of a link took
    30 s once (de/dark; 4 s in en/light), not seen again.
  - **Memories' unseen steps on Windows** (2026-11-15, each machine-doable, inbox #926): a video's sound (the Depot pass's was the
    owner's ear), a chapter's cover drawn (none was set in the pass), a HEIC picked (the HEIF and HEVC extensions; an
    undecodable file goes up alone, `MediaTiersTest`), and two fixes built and unit-checked but not driven again: the
    lightbox's ←/→ after the save picker (the keys on the window's content now) and a grid tile in its `Border`.
  - **Windows' share sheet left a ghost** once: after it was used, screenshots taken while a later `ContentDialog`
    opened showed its window again (stale, the first link), while UI Automation drove the dialog beneath; gone after a
    restart. Whether a person sees it on a real display is checked in the next Windows pass.
  - **The cover's pan** (a manipulation) was never driven: `Veyq.Drive` has no drag, so its passes framed by zoom.
  - **An x64 PC.** The app has run only on the ARM64 VM under x64 emulation (`ssh kevin@192.168.64.8`, key
    `~/.ssh/veyq_vms_ed25519`; Windows 11 26H2, its owner's session driven through two scheduled tasks), never on an x64
    PC.
  - **Windows without Hello set up** offers the login level alone (DPAPI): held by the tests over a played Hello, never
    seen on a machine without a PIN (the VM has one).
  - **Windows ARM64 native** waits for a Kotlin/Native `mingw` ARM64 target.
  - **A proxy.** WinHTTP takes it from `netsh winhttp`, not from the user's settings (remembered); not tried.
- Linux beyond its first slice:
  - **The desktop session** (the Fedora ARM VM). The device trust is built and ran over gnome-keyring in the Fedora 44
    container only (the Depot slice ran the app in the VM's session with the password trust). Untried: the app over oo7
    in a real session, a collection that stays locked on a real desktop (the
    enrolment then fails and falls back to the password trust, as designed, not seen), and libsecret's ABI on another
    distribution than Fedora (the `dlopen`ed functions are few and stable, remembered). Measured there 2026-11-12 (ADR
    0123's Consequences, the Linux app): drag and drop from Files, the sound, and GNOME's portal file chooser — taken by a
    non-sandboxed app only under `GDK_DEBUG=portals`, which returns real paths; whether the document portal's path opens
    in the core stays the Flatpak's question. The test VM is Fedora 45 Workstation, aarch64, in UTM (`ssh kevin@192.168.64.9`, key
    `~/.ssh/veyq_vms_ed25519`): GTK 4.24.1, libadwaita 1.10.0, and no gnome-keyring — `org.freedesktop.secrets` is
    answered by **oo7** (`oo7-daemon` 0.7.0 beta, GNOME's Secret Service in Rust), measured 2026-11-10 (`busctl --user
    status org.freedesktop.secrets`, `rpm -qa`). The core is tested against oo7 there and against KWallet under Plasma.
    Measured there the same day: a libsecret client stores, reads and clears an item with no prompt (`secret-tool
    store`/`lookup`/`clear` over ssh into the session bus) — `pam_oo7` unlocks the `login` collection at sign-in; the
    session is Wayland; Flathub offers `org.gnome.Platform`/`Sdk` 49, 50 and 51 for aarch64 (`flatpak remote-ls
    --runtime --arch=aarch64 flathub`); and Fedora 45 lacks `libcrypt.so.1` as Fedora 44 does (`ldd` of the app).
  - **The Flatpak — built** ([ADR 0139](../decisions/0139-the-desktop-apps-ship-as-a-flatpak-bundle-and-an-msix-built-in-checks-and-promoted-with-the-release.md);
    what was measured: ADR 0123's Consequences, the Flatpak; signed in inside its sandbox, § Platforms). Still open:
    staying signed in through a restart (the account needs a free trust slot; inbox #915) and the x86_64 bundle (CI's
    first run, inbox #914). **The
    `org.freedesktop.secrets` name** the manifest grants exists only for the core's first question
    (`secret_service_get_sync` in `LinuxDeviceKeys.secretServiceAvailable`): asking libsecret's backend instead
    (`secret_backend_get_sync`, which a sandbox answers with the portal's keyring) would let the manifest drop the name —
    a core change with its probe cases in `run.py test`, not taken here. **A desktop whose system language is not the
    person's** has no runtime locale for it until `flatpak config --set extra-languages <lang>` (the VM's case; a German
    desktop has its own).
  - **Other desktops** (ADR 0136 decision 4: one libadwaita app for all). Not measured yet, remembered only: KWallet
    answers the Secret Service, KDE's portal the file chooser and the colour scheme. The desktop-session slice checks
    them under Fedora's KDE Plasma edition beside GNOME — staying signed in through KWallet (and through KeePassXC's
    Secret Service, also untried), a file picked and saved through KDE's dialogs, dark mode following Plasma's. That
    the app runs where no Secret Service answers, signing in at every start, is measured in the container
    (`run.py run --no-secret-service`, `run.py test`'s `no-bus` and `absent` probes).
  - **A fingerprint reader** (fprintd, polkit) has no standard way to a key, so the presence level is not planned on
    Linux.
  - **HEIC on Linux**: the Fedora container's glycin-heif has no HEVC decoder (libde265), so a HEIC goes up alone with
    its icon; in the GNOME 51 runtime not measured (the build sandbox's glycin starts no loader). Whether the installed
    Flatpak decodes one is its next pass's question.
  - **A Memory's WebM sought far** took about a minute to land under Broadway (16 Mb/s read a range at a time through
    the reader; Windows' took 2 s): a larger read-ahead for a seek is untried.
  - **What the uploads say waits for a save** (2026-11-12): an `AdwToastOverlay` shows one toast at a time and a save's
    progress toast never times out, so an upload's offer or outcome shows only after the save ends. Unexplained, seen
    once each: the first 2 GiB upload failed at 30 % and its automatic retry failed at once (no code was logged; three
    later ones went through), and a Retry of a 5-byte file stood at 0 % for 10 s until Cancel all took it.
  - **A foreign currency's spelling.** A currency other than the locale's own is written by its code (`EUR 1,234.56`
    under en_US) where Android and Apple write `€1,234.56`. glibc's locale data has only the locale's own symbol.
  - **A release build warns** of `dev_login.rs`' `AtomicBool`/`Ordering` imports, used only under `debug_assertions`
    (cosmetic, seen in the Flatpak's build 2026-11-13).
  - **x86-64.** The app has not been built and run on x86-64; only arm64 was. CI's `flatpak` job on an x86-64 runner is
    its first build and start (inbox #914).
- **A CPU libsodium's AES-256-GCM cannot run on is refused at start** (owner 2026-11-10, accepted): it needs AES
  instructions, and on x86-64 also PCLMUL and AVX. Refused: a Raspberry Pi 4 (no AES instructions), an x64 PC or
  x86-64 Linux PC without AVX, and a Windows-on-ARM PC whose x64 emulation hides AVX (it shows it by default). A
  software AES-GCM, or a second source such as CNG, would be a second implementation of a primitive on one platform;
  it is revisited if someone asks.
- **A cover on the desktops is shown on a Pot's and a Memory's page** (its head, cut by `Cover.Cut`), in its dialog and
  on a Memory's row in Memories' list (`Shell.RowCover`), but no folder's page draws a hero and no other row a thumbnail,
  where Android and Apple draw both. The core has what they need (`NodeCover.row`, `HeaderShown.cover`,
  `Header.CoverPicture`, `Cover.Cut`); the other kinds bring it in their slices. A cover set from another device shows on the page's next open, as on every platform.
- **An image past `FileView.IMAGE_EDGE` (4096) on the desktops** was not on a device (the passes' JPEG is 3000 × 2000):
  the scale-down (`file_page::within_edge`, Windows' `DecodePixelWidth`) is built, not seen.
- **Apple's Depot passes left gaps** (2026-11-12): the macOS build was compiled, not run, so its download through the
  save panel is unproved by hand; on iOS no seek near a medium's end, no Download and no Send a copy (both a system sheet
  the simulator tool does not drive), no drop box made from the top level (it types no name) and no cancel or retry (it
  takes no network away). Android's passes and the core's tests hold them.
- **Apple's Undo was never tapped on a device**: the simulator tool's screenshots lag 2–3 s behind a 6 s window, so
  the tap lands after the toast is gone. The trash's, the move's and a medium's undo are held by the core's tests and
  measured on Android and both desktops.
- **Apple's Memories functions of 2026-11-15 were never tapped** (the owner's, inbox #925): New Memory, Add chapter, the
  chapter covers, the live poll, zoom with its pan, the slideshow, a medium trashed with Undo and a Memory's WRITE_ONLY
  link — built for macOS and the iOS simulator and launched signed in, but the simulator tool's access was not granted,
  so no tap ran; the core's tests hold their rules. Nor were the Mac's Escape in the inline lightbox, its File menu's
  "Open in New Window", a tile's context menu and a ⌘-click driven (the background tools send none), nor a seek near a
  medium's end on iOS and macOS (the simulator's screenshots lag the player's controls; AVKit's on the Mac expose
  nothing to accessibility).
- **Memories' phone passes left two steps** (machine-doable, inbox #926): a member's upload from the web seen through
  the poll (the passes' web upload was a WRITE_ONLY guest's, since Vite's `?devlogin` reads a `.run/.env` the serving
  worktree lacked), and a GPX track left out of a phone's gallery (the dev account holds none; music was, 52 of 54
  media shown).
- **Apple's Expenses screens were never typed into** since their move into the core (2026-11-12): the simulator tool
  types no text, so a new entry saved, an item split, a person or category added or renamed, New pot created and a
  share from Photos were driven on Android and the desktops and held by `commonTest`, not on an iPhone; on Apple they
  were seen opened, refused, recorded, restored and deleted only.
- **The apps' CI jobs** (Checks' `app-inputs`, `flatpak`, `msix`, `apps-stage`; ADR 0139 decision 10) are written and have
  never run on GitHub: the first push to `main` proves them (inbox #914). Android's and Apple's apps have none.

**Cameras** ([ADR 0141](../decisions/0141-qr-codes-are-read-and-drawn-by-the-core.md); inbox #912)
- **No real camera has scanned yet** — every `Found` came through a debug test camera. Owed, each the owner's (a
  camera's first use asks the person, and no test machine but the developer's Mac has one): the macOS build's camera
  (`VEYQ_DEV_SCAN=1 <build>/Debug/veyq.app/Contents/MacOS/veyq`, a veyq code held up on another screen →
  `veyq-scan: state Found(…)`; then the sign-in's kit scan with `VEYQ_DEV_KIT_SCAN=1` and a printed kit → `veyq-kit:
  kit held`), Apple's `Denied` (the camera refused, or switched off in System Settings › Privacy & Security › Camera →
  `state Denied`), a real iPhone (`VEYQ_DEV_SCAN=1` in the scheme, and "Notfall-Kit scannen" over a printed kit), a
  Linux desktop's camera through the portal (on the Fedora VM the virtual camera of `src/mobile/CLAUDE.md` § Traps and
  **Allow** in GNOME's dialog → `state Found(…)`), and a Windows laptop's (`VEYQ_DEV_SCAN=1 windows/run.py run`, a
  code held up; Settings › Privacy › Camera off → `state Denied`), plus the sign-in's kit scan on each of the desktops.
- **The desktops' kit scan through a test camera** was not run (machine-doable): the sign-in's "Scan Emergency Kit" with
  `VEYQ_DEV_CAMERA=<a frame of a kit's code>` on Linux (container or VM) and Windows (the VM), a non-veyq code skipped,
  the dialog closing with the form filled — the bridge's `KIT` scan is held by `KitDispatchTest` only.
- The Android camera's frames on the emulator are 640×480 at most; 720p is measured on Apple's test camera only.

**The bridge** ([ADR 0137](../decisions/0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md))
- **A picture replaced under the same user id sends no signal.** The id set stays the same, so no update tells Linux
  or Windows to ask again. Nothing reads the people anew yet; the first caller of `AvatarsModel.peopleRead` owes the
  hosts that signal (a revision per id, say).

**Account**
- **Device linking's passes left gaps** ([ADR 0059](../decisions/0059-device-linking.md); § Built, by area):
  - The iOS Simulator's four runs against the web, Apple's snapshot bite (`.secretOnScreen()` removed → the
    SplashBoard snapshot shows the code) and the Mac's fourth run (the Mac new device showing, the web scanning) were
    not run — CoreSimulator wedged under load, and the Mac's fourth try met the web's camera reopening and then the dev
    backend restarting (machine-doable, inbox #928). Apple's runs were driven by debug hooks (`VEYQ_DEV_LINK`,
    `VEYQ_DEV_LINK_DIR`), so the Apple slide itself — drag, arrows and Return, VoiceOver's named action — waits for a
    person (the owner's, #928).
  - No app has linked with another app yet (phone ↔ phone, phone ↔ desktop): every run had the web on the other side
    (machine-doable through the test cameras, #928).
  - Windows has never scanned a link's code: the VM has no camera, so `QrCamera.OpenLink` is proven by its build and
    Linux's twin only; a real camera's link scan on every app is the owner's (inbox #912).
  - On GTK and WinUI a screen reader confirms the slide by the keys, with no named action of its own (ADR 0136's
    Consequences); no screen reader has driven the slide on any platform.
  - `DeviceLinkTest`'s confirmation race in its final form (its racers on their own threads behind one gate) was
    measured red against check-then-act 20/20 and green at rest, never yet under load (#928).
  - Windows' Settings logged "the settings did not draw: COMException 0x800F1000" twice on a sign-out from Settings;
    caught, the sign-out completed, the cause not looked into (inbox #929).
  - Every pass leaves a device-register entry per device it linked (no screen removes one): an orphan once a reconcile drops its rows, and orphans are what eviction takes first ([en.account.device-register](../features/account/enablers/en.account.device-register.md) § Growth).
- The device register's own screen (the devices an account trusts, ADR 0058): every app writes it at each unlock and
  enrolment, only the web shows it.
- Invites and members; the rest of the web's settings — password, email, replacing the keys, recovery, erasure, terms,
  profile (inbox #863), and an in-app choice of appearance and language (the apps follow the system's; the desktops have
  no per-app language of their own).
- **On macOS a session's menu in Settings was never driven** ("Weitere" → "Dieses Gerät abmelden" on another session:
  the background tools open no menu) — the owner's click (inbox #916); **and its rename alert shows the current name
  only the first time** — later openings are empty (the binding set before presenting; AppKit's alert keeps its field),
  not fixed (inbox #917).
- Several instances gated on their major (inbox #436): the apps speak only their own major today.

**Navigation and sharing**
- Search beyond names: the web's search in the client over what it can open, across every space ([ADR 0125](../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)), taken over once the apps reach the web's features (owner 2026-11-09); listing a kind across scopes.
- Opening a received link in the app, as its guest, and the handover from the browser that must not carry a link's
  `#k=` key unguarded (#435); a kept link (`SHARE_BOOKMARK`) has no page yet.
- Dropping files onto the app (Android; Apple's drop is built, not measured).
- Quick capture: widgets, shortcuts; notifications, among them the reminder of what one owes in a Pot (#550).

**Expenses**: reading a receipt's text on the device (#549); what [expenses.md](../apps/expenses.md) keeps out of the
first cut.

**Depot**: a READ_WRITE guest's own name as uploader; importing a download (a ZIP, or one unpacked as a folder; a dropped
or picked veyq download is uploaded as the file it is until then); multi-select and dragging a node onto a folder to move
it (inbox #421); a PDF's text layer and password prompt, a text's copy and wrap toggle (the web's pdf.js extras); Send a
copy and "Open with" on the desktops (not offered, see Built). **Android and Apple still decide where the top level's
share is offered** in their shells (`folder == null && rules.on && rules.dropBox`), where the desktops ask the core
(`ShellModel.offersTopLevelShare`): the phones' next slice moves them onto it. **A guest's new folder shows only after a
refresh**: nothing polls a drop box; `ShellModel.refresh` reads the tree anew (the desktops' F5, the uploads' settle,
each app's lists read after a write). A guest's deleted folder is restorable only by Undo (inbox #904).

**Memories**: curating a Memory — its date, place and map, participants, a medium's capture time, the banner (inbox
#890); item marks, uploader buckets with guests' names and the gallery's filter (#891); export, import, copy and the
re-key of a Memory or chapter (#892); a Memory as a share target and a drop onto a phone's gallery (#893); its music
(#906) and GPX tracks (#907); a guest's view of a received link (#435, a source for the same model).

**Uploads**: an upload that goes on while the app is in the background (Android a foreground service, iOS a
background task); placeholder tiles in the gallery for what is under way; the list behind Apple's button seen open.
