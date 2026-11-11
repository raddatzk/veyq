# Native apps — where they stand

The current picture of the V2 native apps in `src/mobile/` (inbox #438): what each platform runs, what is built
against the web client, and what is open. The decisions are [ADR 0122](../decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
(one app, its navigation), [ADR 0123](../decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
(the shared Kotlin core under a native UI; its Consequences record each slice with what was measured, and are this
page's source for every "measured") and [ADR 0129](../decisions/0129-the-apps-take-what-the-system-shares-the-chosen-nodes-kind-decides-what-it-becomes.md) (the
share target). This page is kept current as slices land; a row moves from open to built in the commit that builds it.

## The split

Everything that is not drawing a screen or talking to the device belongs in the Kotlin core (owner 2026-11-09,
[ADR 0127](../decisions/0127-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)), and it is there
(2026-11-10): the suite, the API, every application's domain and its flows, the upload queue, and each screen's state and
rules as a core model both apps draw — the way in (`session/SessionModel`), the shell and its lists (`navigation/ShellModel`,
`RowDetails`), the trash sheet (`navigation/TrashSheet`), the open Pot (`expenses/PotModel`), shared files
(`expenses/ShareInbox`), the link form and a link's row (`session/LinkForm`, `LinkRow`), a header's plan and words
(`navigation/NodeHeader`), an open node's header with pin, trash, rename and cover (`navigation/NodeHeaderModel`) and the
sheets behind it (`MoveSheet`, `ShareSheet`, `ReportSheet`; ADR 0127's Consequences); which words an outcome raises (`words/Notices`) and what a row or a page says, piece by piece
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
`maxlength` counts it; the lists' covers are read again after every change on both.

## Platforms

| Platform | Builds | UI | Large-screen layout | Seen running |
|---|---|---|---|---|
| Android phone | yes (minSdk 31) | Compose, Material 3 | — | yes, the emulator, every slice |
| Android tablet | the same APK | Compose | Material 3's window classes by the window's width: from 600 dp a rail at the side takes the bar's place; from 840 dp the drawer stays open as the sidebar (ADR 0122; an object chosen there has no back, decision 7) and an application's list stands beside its open object (list-detail; a choice there replaces the open one, `Shell.openBeside`); sign-in and unlock read at a form's width | the emulator at 1280 × 800 dp and 800 × 1280 dp (`wm size`), 2026-11-10 |
| iPhone | yes (iOS 26) | SwiftUI | — | yes, the Simulator, every slice |
| iPad | yes (the iOS target, no device family set) | SwiftUI | `NavigationSplitView` with its sidebar open in a regular size class; the detail is one page whose bar's places are a segmented control in its toolbar, not a tab bar, Search always the toolbar's field at its trailing end; Settings pinned at the sidebar's foot; an object chosen in the sidebar has no back (ADR 0122 decision 7); sign-in and unlock read at a form's width | the Simulator, 2026-11-10 |
| macOS | yes (macOS 26) | SwiftUI | always the split view, as the iPad's; the sidebar 240–360 pt wide | an ad-hoc signed build, 2026-11-10 (Home, a Pot's entries and overview); without a team's signing the device trust is never enrolled; no share extension there |
| Windows | the core and the bridge for x64 (`veyq.dll`), cross-compiled on the developer's Mac; the app on a Windows host (`src/mobile/windows/run.py build`), unpackaged and self-contained | WinUI 3 in C#, Fluent, over the core as `veyq.dll` through the bridge | `NavigationView` with its own thresholds: Left from 1008 epx, LeftCompact (icons) from 641, LeftMinimal below; the switcher its pane header, Search its `AutoSuggestBox` (Ctrl+E, Ctrl+F), Settings at its foot, New the first command of the content's `CommandBar` | an ARM64 Windows 11 VM under x64 emulation, driven through UI Automation, 2026-11-11 (the first slice: sign-in, the shell, Home, a Pot read only, Search; maximized, 800 and 500 wide); the members' pictures and a Pot's download through the system's save picker, its ZIP imported by the web, 2026-11-11; the device trust, the owner answering Windows Hello, 2026-11-11; a node's header with every action on it, 2026-11-12; no x64 PC |
| Linux | the core and the bridge for arm64 and x86-64, cross-compiled on the developer's Mac; the app for arm64, in a Fedora 44 container (`src/mobile/linux/run.py build`) | GTK 4 and libadwaita in Rust, over the core as `libveyq.so` through the bridge | `AdwNavigationSplitView` with libadwaita's `AdwSidebar` (240–360 sp), collapsing below 600 sp to a navigation stack, the sidebar in its page mode; the switcher the sidebar's title menu, Search a search bar under it (Ctrl+F), Settings at its foot | Broadway in Fedora 44 arm64, drawn into a browser, 2026-11-10 (the first slice: sign-in, the shell, Home, a Pot read only, Search; 1100 × 750 and 500 × 750); the members' pictures and a Pot's download through GTK's save dialog, its ZIP imported by the web, 2026-11-11; the device trust over gnome-keyring in the container, 2026-11-11; a node's header with every action on it, 2026-11-12; no desktop session yet |

Windows and Linux are wanted (owner 2026-11-09), each with its own native UI over the same core
([ADR 0127](../decisions/0127-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)): Linux GTK 4 and
libadwaita in Rust, Windows WinUI 3 in C#, both over the core as a Kotlin/Native library through one bridge (owner
2026-11-10). Each has its first slice built (ADR 0123's Consequences: the core on Linux and on Windows, the bridge, the
Linux app and the Windows app), and the bridge's channels for shown bytes, a blob's ranges and files by path ([ADR 0128](../decisions/0128-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)),
proved by the members' pictures and a Pot's download on both. On Windows that core is x64 only: Kotlin/Native has no Windows ARM64 target.

## Built, by area

"Measured" means ADR 0123 records it run against a dev instance (and, where it says so, read back by the web); "built"
means the code is there and compiles, nothing recorded beyond. Both apps hold everything listed unless a row says
otherwise; the Linux and Windows apps hold only their first slice, the members' pictures, a Pot's download, the
device trust with "Manage servers" (their rows above), and a folder's and a Pot's header with every action on it: pin,
trash with undo and the space's trash, rename, New folder, move and copy with the storage check, a folder's download,
share links, a report and a cover with its crop (measured 2026-11-12, ADR 0123's Consequences, the Linux app and the
Windows app).

**Account and session.** Sign-in with password and Secret Key behind the `/api/setup` major gate, each grant judged as
the web judges it (measured, `LiveSignInTest`, `GrantTrustTest`); several servers and tenants with their switcher,
hiding tenants and spaces.

**Device trust, on all four apps** (ADR 0123 decision 8 as changed 2026-11-11; measured 2026-11-11, `DeviceTrustLiveTest`,
`LiveBridgeTest` and a pass per app, ADR 0123's Consequences, the device trust): every sign-in trusts the device with
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

**Sharing.** Links on a Pot, a folder or a file (READ, READ_WRITE, "Add files only" drop boxes, at a space's top
level too): create, edit, renew, switch off and on, delete; closing a link re-keys the subtree, an interrupted re-key
is resumed — measured 2026-11-09. Taking shared images and PDFs into a Pot from the system's share sheet (Android, iOS;
ADR 0129), measured 2026-11-08.

**Expenses.** Every web use case: create a Pot, record entries, receipts, balances, an entry's history, exchange
rates, participants, share, verify the ledger; the live poll; export and copy of a Pot — measured 2026-11-08/09.

**Depot.** Upload, replace, download a file; upload a folder; view a file (image, PDF, text, media) and send a copy;
drop boxes (read, create, move a guest's folder out); uploader names of members — measured 2026-11-09.

**Memories.** The gallery with chapters, the lightbox with details, video and saving the original; adding photos and
videos, their metadata read on the device as the web reads it (EXIF, the container's time, position), Android asking
once for full media access so a photo keeps its place and name — measured 2026-11-09.

**Uploads.** One queue in the core, the web's rules (`UploadQueueTest`); Android's row and sheet under the top bar,
Apple's toolbar button and list — measured 2026-11-09 (the list behind Apple's button not seen open).

**Out and in.** The ZIP of a Pot, a folder, a file or a drop box, as the web writes it — measured 2026-11-09.

## Open

**Platforms and form factors**
- Windows beyond its first slice (the next slice is Linux's and Windows' together, owner 2026-11-10):
  - **Packaging:** MSIX with its signing and its PNG assets (the app's `.ico` is the build's already: `generate_theme.py --winui`). It must depend on the Store's Webp
    Image Extensions, or the app decodes WebP itself: without the package every member's picture is drawn as initials
    (measured 2026-11-11, the decode test's bite). Whether a packaged app's picked path still opens in the core is
    checked there (ADR 0128).
  - **Drag and drop.** The save picker (a download) and the open picker (a cover's picture, encoded by the app and read
    by the core by path, `Header.CoverSave`) are built; Depot's uploads bring drag and drop.
  - **The other screens.** Every other application's screens, uploads and a Pot's writes are not built. New offers a
    folder (its Pot entry disabled); Settings offers nothing yet.
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
    container only. Untried: the app over oo7 in a real session, a collection that stays locked on a real desktop (the
    enrolment then fails and falls back to the password trust, as designed, not seen), and libsecret's ABI on another
    distribution than Fedora (the `dlopen`ed functions are few and stable, remembered). Also open there: the portals
    (the save and open dialogs are built and ran as GTK's own in the container, never through a portal) and drag and
    drop. The test VM is Fedora 45 Workstation, aarch64, in UTM (`ssh kevin@192.168.64.9`, key
    `~/.ssh/veyq_vms_ed25519`): GTK 4.24.1, libadwaita 1.10.0, and no gnome-keyring — `org.freedesktop.secrets` is
    answered by **oo7** (`oo7-daemon` 0.7.0 beta, GNOME's Secret Service in Rust), measured 2026-11-10 (`busctl --user
    status org.freedesktop.secrets`, `rpm -qa`). The core is tested against oo7 there and against KWallet under Plasma.
    Measured there the same day: a libsecret client stores, reads and clears an item with no prompt (`secret-tool
    store`/`lookup`/`clear` over ssh into the session bus) — `pam_oo7` unlocks the `login` collection at sign-in; the
    session is Wayland; Flathub offers `org.gnome.Platform`/`Sdk` 49, 50 and 51 for aarch64 (`flatpak remote-ls
    --runtime --arch=aarch64 flathub`); and Fedora 45 lacks `libcrypt.so.1` as Fedora 44 does (`ldd` of the app).
  - **The Flatpak** on the GNOME runtime. It installs the generated icon and desktop entry (`generate_theme.py --gtk`) to hicolor and `applications/`. It must carry GTK 4.22 and libadwaita 1.9, `libcrypt.so.1`, a CA bundle and
    the zoneinfo, libsecret (for the device trust; the host's Secret Service or its portal answers over D-Bus), and
    glycin's loaders, through which GTK 4.22 decodes WebP (without them every member's picture is
    drawn as initials, measured 2026-11-11). Whether the document portal's path opens in the core is checked there
    (ADR 0128). The app's build and tests move into that runtime's SDK (`org.gnome.Sdk`), replacing the Fedora 44
    container as what the app is proved against (ADR 0127 decision 4); Fedora stays only as a test VM's system.
  - **Other desktops** (ADR 0127 decision 4: one libadwaita app for all). Not measured yet, remembered only: KWallet
    answers the Secret Service, KDE's portal the file chooser and the colour scheme. The desktop-session slice checks
    them under Fedora's KDE Plasma edition beside GNOME — staying signed in through KWallet (and through KeePassXC's
    Secret Service, also untried), a file picked and saved through KDE's dialogs, dark mode following Plasma's. That
    the app runs where no Secret Service answers, signing in at every start, is measured in the container
    (`run.py run --no-secret-service`, `run.py test`'s `no-bus` and `absent` probes).
  - **A fingerprint reader** (fprintd, polkit) has no standard way to a key, so the presence level is not planned on
    Linux.
  - **The other screens.** Every other application's screens (Depot, Memories), uploads and a Pot's writes are not
    built. New offers a folder (its Pot entry disabled); Settings offers nothing yet.
  - **A foreign currency's spelling.** A currency other than the locale's own is written by its code (`EUR 1,234.56`
    under en_US) where Android and Apple write `€1,234.56`. glibc's locale data has only the locale's own symbol.
  - **x86-64.** The app has not been built and run on x86-64; only arm64 was.
- **A CPU libsodium's AES-256-GCM cannot run on is refused at start** (owner 2026-11-10, accepted): it needs AES
  instructions, and on x86-64 also PCLMUL and AVX. Refused: a Raspberry Pi 4 (no AES instructions), an x64 PC or
  x86-64 Linux PC without AVX, and a Windows-on-ARM PC whose x64 emulation hides AVX (it shows it by default). A
  software AES-GCM, or a second source such as CNG, would be a second implementation of a primitive on one platform;
  it is revisited if someone asks.
- **A cover on the desktops is shown only in its dialog**: no hero on a node's page and no thumbnail in a row, where
  Android and Apple draw both. The core has what they need (`NodeCover.THUMBNAIL_EDGE`, `NodeCover.changes`, the
  header's `HeaderShown.cover` and `Header.CoverPicture`); a Pot's page draws it with the core's head in the Expenses
  slice, the other kinds in theirs.
- **Apple's Undo was never tapped on a device**: the simulator tool's screenshots lag 2–3 s behind a 6 s window, so
  the tap lands after the toast is gone. The trash's and the move's undo are held by the core's tests and measured on
  Android and both desktops.
- A CI job for the apps (Windows needs a Windows runner).

**The bridge** ([ADR 0128](../decisions/0128-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md))
- **A picture replaced under the same user id sends no signal.** The id set stays the same, so no update tells Linux
  or Windows to ask again. Nothing reads the people anew yet; the first caller of `AvatarsModel.peopleRead` owes the
  hosts that signal (a revision per id, say).
- **Cancelling a write.** A download runs to its end or fails, and the partial file is removed; cancelling comes with
  the first consumer that needs it (Depot's large files).
- **The calls that open a reader** (a Depot file, a Memory's medium) and the video player, the upload queue in the
  bridge, and tiles and receipts as UI (covers outside their dialog above) are their applications' slices: the channels are there (ADR 0128
  decisions 1 and 2).

**Account**
- Linking a device on a phone ([ADR 0059](../decisions/0059-device-linking.md)): the contract gives a client without
  cookies no adoption (`POST /api/token/adopt` answers only with a cookie).
- The device register's own screen (the devices an account trusts, ADR 0058): every app writes it at each unlock and
  enrolment, only the web shows it.
- Invites and members; settings (the drawer's row does nothing yet): password, recovery, erasure, sessions, terms,
  profile, appearance and language.
- Several instances gated on their major (inbox #436): the apps speak only their own major today.

**Navigation and sharing**
- Search beyond names: the web's search in the client over what it can open, across every space ([ADR 0125](../decisions/0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md)), taken over once the apps reach the web's features (owner 2026-11-09); listing a kind across scopes.
- Opening a received link in the app, as its guest, and the handover from the browser that must not carry a link's
  `#k=` key unguarded (#435); a kept link (`SHARE_BOOKMARK`) has no page yet.
- Dropping files onto the app (Android; Apple's drop is built, not measured).
- Quick capture: widgets, shortcuts; notifications, among them the reminder of what one owes in a Pot (#550).

**Expenses**: reading a receipt's text on the device (#549); what [expenses.md](../apps/expenses.md) keeps out of the
first cut.

**Depot**: a READ_WRITE guest's own name as uploader; importing a download (a ZIP, or one unpacked as a folder).

**Memories**: creating a Memory and a chapter; the live poll; zoom and the slideshow; editing the date, the place,
the participants and a medium's capture time; trashing and restoring media; item marks, buckets and guests' uploader
names; the map; sharing and its guest views; the re-key of a Memory or chapter; export, import and copy; the banner.

**Uploads**: an upload that goes on while the app is in the background (Android a foreground service, iOS a
background task); placeholder tiles in the gallery for what is under way; the list behind Apple's button seen open.
