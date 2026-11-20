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
(`navigation/ShareInbox`, each kind's `ShareAnswer`), the link form and a link's row (`session/LinkForm`, `LinkRow`), a header's plan and words
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
with Undo, uploads with this host's tiers and a drop from Explorer, a Memory's links, the row covers), 2026-11-15; and
the steps that pass left: a video's sound (the default output's peak meter up to 0.15 while an AAC tone played, 0 at
rest), a chapter's cover set and drawn on its row, a HEIC's tiers and an undecodable one's glyph, the lightbox's ←/→
after the save picker, the grid's tiles in their `Border`s, 2026-11-15; no x64 PC |
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
the scan to the shell on Android (the emulator's virtual scene), iOS and macOS (their test cameras); on Linux (the
container) and Windows (the VM) the scan through each test camera (2026-11-18: a non-veyq code read and skipped, the
dialog open after 50 and 100 frames; the dev kit's code `Found`, the dialog closed, email and server filled and the
Secret Key held "From your Emergency Kit" — no sign-in sent). No copy of a kit
or a frame on any app's disk ([ADR 0047](../decisions/0047-inline-video-playback-range-streaming.md)'s Consequences).
**The account's Settings page** from the drawer, on all four apps (`uc.account.sessions`): the address, this device (its
name, the trust level as Manage servers' — one core state —, Stop trusting), devices and sessions named from the
device register (sign one out, "Sign out and remove saved key", every other), the devices with a saved key, Sign out;
"Appearance and language" on the phones opens the system's per-app page (the apps follow the system's theme and
language; the desktops have no row); "Add a device" where the instance has `core.device-linking`. Measured
2026-11-13 against the dev backend on Android, iOS, macOS, Linux (the Fedora VM, inside the Flatpak's sandbox) and
Windows (the VM), the desktops in English/light and German/dark: this device renamed, a second session of the account signed out from
the list, "Sign out" taken — on macOS a session's own menu not driven (§ Open).
**The account's own flows, on all four apps** ([ADR 0148](../decisions/0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md), built 2026-11-19): the web's flows ported into the core and
held to the shared vectors (`secret_key_proof`, `auth_attestation`, `rotation_stage`, `verification`, `fingerprint`'s
digits and chain code, `safety_code`, on the JVM and libsodium) — the rename of any device a row names
(`AccountSettings.renameFamily`); *Your profile* in Settings and *Edit profile* on one's own person page over one
`ProfileModel`, the picture cropped square by the platform and encoded as WebP by the core (libwebp on every native
slice, `Bitmap.compress` on Android); the safety number below a person's name (own: the digits, the chain code and the
core's QR; another's: the state and *Compare* by a `veyqid:` scan or by digits, a verification sealed for the tenant);
*Change password*, the address change (the request in the app, the confirm on the web's page, the new address taken at
the next sign-in), *Secure your account* (the whole rotation, `finishRotation` at every unlock and every 45 s, the
offline store wiped and refilled) and *Erase your account* (the device forgetting the account after). The Emergency Kit
is written by the core straight into the file the person chose in the system's save dialog and never shown; a
moderator is sent to the web for the rotation and the erasure. Passed on Android (an API 36 emulator) and Linux
(Broadway) as throwaways with a member, every kit save followed by a no-copy check of the app's files; the core's live
tests ran every flow but the address change's confirm as throwaways (ADR 0148's Consequences say what each pass proved
and what is open). **The flows' Secret Key and the breached-password check** ([ADR 0151](../decisions/0151-the-apps-account-rest-the-sign-ins-secret-key-control-in-every-flow-that-asks-for-the-key-and-the-breached-password-check-straight-from-the-device.md), built 2026-11-19
on every app): the password change, the address change (its request and its kit step) and the rotation's gate take the
Secret Key as the sign-in does — typed, a pasted `veyqkit:` token, the kit file from the system's picker or a scan where
the app has a camera, held by the flow's own `account/KeyIntake` and zeroed after; a kit of another account warns,
never blocks. The new password of the password change and of the rotation is checked as the web checks it
(`account/BreachCheck`): when the field is left and again at submit if edited, a 5-hex SHA-1 prefix with
`Add-Padding` straight from the device to the address the instance's `core.breached-password` serves, through
`session/OutsideHttp` (the app's `User-Agent` naming the instance, no bearer, no cookie), a hit waiting for *Use it
anyway*, a check that could not run going ahead with one line. Passed on Android, Linux and Windows as throwaways, the
range address blocked on each so no live query was sent (the kit file, a pasted token, another account's kit, *could
not be checked*; Windows also a scan and the rotation's gate with the kit file); a hit is held by `BreachCheckTest`,
never drawn live (ADR 0151's Consequences). The Use pages: [Your profile](../../site/src/content/docs/account/your-profile.mdx),
[Verify a person](../../site/src/content/docs/account/verify-a-person.mdx),
[Change your password](../../site/src/content/docs/account/change-password.mdx),
[Your email address](../../site/src/content/docs/account/change-email.mdx),
[Secure your account](../../site/src/content/docs/account/replace-your-keys.mdx),
[Erase your account](../../site/src/content/docs/account/erase-your-account.mdx).
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
sign-in; Settings' "Sign out" left no session and no saved key of it on the web's list). **App with app** (2026-11-18,
through the test cameras, no password typed, every new device answering "Not now" and every family signed out after):
Android ↔ Android (two throwaway AVDs: one showing for the other's scan, and a QR sign-in the other way), Android ↔
Linux and Linux ↔ Windows each in all four roles — so Windows scanned a link's code for the first time (its test
camera, `VEYQ_DEV_CAMERA` on the VM) and a device linked a moment before served as the trusted one for the next run;
the check numbers matched on both screens each time, and a code of the other direction was hinted
(`OTHER_DIRECTION`) before the right one was read. The slide by pointer and keys on Android, Linux
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
application ending in Search, Home's tree with its filters; favorites, the trash (delete with undo,
restore, purge), move, copy (Pot, folder, file, drop box, with the storage check), rename (its links' names re-sealed),
a node's cover with its crop, core's header for every kind, a report to the moderators — each measured 2026-11-08/09.
The drawer shows only a section with something in it, and every switcher row names its server (and the account where
two signed-in accounts list the same tenant) — the owner's rules of 2026-11-19, core's `Lists.drawerSections` and
`ShellWords.switcher` on every app ([src/mobile/CLAUDE.md](../../src/mobile/CLAUDE.md), the drawer and the switcher).

**Search** ([ADR 0146](../decisions/0146-the-apps-search-with-the-cores-port-of-the-webs-search-both-answer-to-one-set-of-vectors.md)): the web's search ported into the core (`search/`) and held to the web by
`contracts/platform/search/v1-vectors.json` — every visible space of the current tenant, chips, one ordered list, every
space's photos through `listScopeMedia`, people and the app's own settings sections; each platform in its own field
(Android's `SearchBar`, Apple's `.searchable` with tokens, Linux's sidebar `SearchBar`, Windows' `AutoSuggestBox`), the
Linux and Windows apps over the bridge's `Search.*`. Measured 2026-11-18 on the Android emulator (`uiautomator`,
TalkBack's verbose log), the macOS build (its accessibility tree), Linux under Broadway (the announcement on X11
through `dbus-monitor`) and the Windows VM (UI Automation and its notification events); the first focus's timing and
each platform's keys are ADR 0146's Consequences and decision 12.

**Sharing.** Links on a Pot, a folder, a file, a Memory or a chapter (READ, READ_WRITE, "Add files only" drop boxes,
at a space's top level too; a Memory's or chapter's upload-only link sealing to the node's own drop key, neither edited
nor renewed): create, edit, renew, switch off and on, delete; closing a link re-keys the subtree, an interrupted re-key
is resumed — measured 2026-11-09. Taking shared images and PDFs into a Pot from the system's share sheet (Android, iOS;
ADR 0138), measured 2026-11-08; photos and videos into a Memory or a chapter, and a drop onto its gallery, since
2026-11-19 (below).

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
the desktops' way out). Since V2 block B (2026-11-18) every app asks the core where the top level's share stands
(`ShellModel.offersTopLevelShare`, never on a person's page), and an open drop box follows the scope's tree while it shows
(`DropBoxPage.live`, the gallery's follow): a guest's new folder showed without a refresh on Linux (4 s after the web
guest's drop) and Android (under 7 s). Every row on the desktops draws its node's cover, and a folder's level and a drop
box's page start with its head (Linux, measured: a Pot's row and a folder's hero). A person's refresh (F5, the phones'
pull) reads the members anew, and a picture replaced under its id reaches the desktops through `picturesRound`.

**Memories.** The gallery with chapters, the lightbox with details, video and saving the original; adding photos and
videos, their metadata read on the device as the web reads it (EXIF, the container's time, position), Android asking
once for full media access so a photo keeps its place and name — measured 2026-11-09. Since 2026-11-13 the page, the
lightbox and the uploads' settle are the core's (ADR 0136's Consequences), and every app draws New Memory and Add
chapter, the chapters with their covers, the web's justified grid, the live poll, zoom, the slideshow, a medium trashed
with Undo (a guest's own purged after asking, held by the core's tests: the apps open a gallery as a member only), a
Memory's links, and the hero once a cover exists; GPX tracks and music are left out of the gallery, as the web's photo
views leave them (music has its own view, below). Measured on Android (2026-11-15; a member's upload through the web seen through the poll and a GPX
track left out of the gallery the same day, a throwaway emulator), on Linux (the container and the Fedora VM, 2026-11-13/14) and on
Windows (the VM, 2026-11-15), each with a WRITE_ONLY guest's upload seen through the poll; on Apple the moved rules were
passed on iOS and macOS (2026-11-13), the new functions only built (§ Open). The desktops make a medium's tiers through
the host (`HostRequest.MediaTiers`: gdk-pixbuf and GStreamer, WIC and `Windows.Media.Editing`), take a drop of files onto
the gallery and say a dropped folder not taken. On a Mac the lightbox opens in the window, a window of its own only when
asked (ADR 0123's Consequences).

**Uploads.** One queue in the core, the web's rules (`UploadQueueTest`); Android's row and sheet under the top bar,
Apple's toolbar button and list — measured 2026-11-09 (the list behind Apple's button not seen open); Linux's header bar
button with its popover and Windows' `AppBarButton` with its flyout, each with Cancel, Retry and Cancel all, measured
2026-11-12. What the tray shows and when it clears is the core's (`DepotUploads`, Memories' batches among them); what a
medium's upload settles and when a picked file is let go, `MediaUploads`'. **In the background and in the grid**
([ADR 0145](../decisions/0145-the-apps-uploads-go-on-with-the-app-left-and-show-in-the-gallery-a-desktop-asks-before-a-quit-drops-them.md)):
the core names what is under way (`UploadsUnderway`); Android holds the process with `UploadWorker`, a `dataSync`
foreground service on its own *Uploads* channel ("Uploading 2 files", "2 % done"), and iOS with `ContinuedWork.uploads`
(a continued-processing task, `beginBackgroundTask`'s time); a Memory's grid leads with a placeholder tile per photo or
video under way (`GalleryModel.State.uploading`, `UploadTile`), its glyph and progress, or that it failed; Linux and
Windows ask before closing the window drops what still uploads, the Mac before ⌘Q. Measured 2026-11-18: on Android (a
throwaway emulator) a 99.6 MB video and a photo picked, both placeholders drawn (*Preparing c1-big.mp4*), the app left
with Home — the service foreground (`isForeground=true`, oom adj 50) and its notification counting — and both in after
379 s with the app still in the background, the service gone, the placeholders given way to the two media; on Linux
(the container, under Broadway in headless Chrome) the placeholders, and closing the window mid-upload asked (*Uploads
are still running*, *1 file*), *Keep uploading* kept it uploading and a close after it quit; on Windows (the VM, German)
two placeholders, closing asked (*Uploads laufen noch*, *2 Dateien*) and *Weiter hochladen* kept them going; Apple's built
for macOS and the iOS Simulator SDK, never run (§ Open).

**Out and in.** The ZIP of a Pot, a folder, a file or a drop box, as the web writes it — measured 2026-11-09; a Memory's
and a chapter's since 2026-11-19 (Memories' rest, below). Import of a download on the desktops, built 2026-11-20 ([ADR 0152](../decisions/0152-the-apps-import-a-download-as-the-web-does-the-webs-reader-and-walk-in-the-core-each-kinds-import-its-applications-recognised-by-every-depot-upload.md)):
*Upload files*, *Upload folder* and a drop on macOS, Linux and Windows offer a veyq download as an import, run by the
core's port of the web's reader and walk in each platform's dialog — passed on Linux and Windows as throwaways (the
web's download imported and each kind read back, *Upload as a file*, *Cancel* mid-run leaving nothing, the unpacked
folder); the phones upload one as a file (owner 2026-11-19).

**QR codes** ([ADR 0141](../decisions/0141-qr-codes-are-read-and-drawn-by-the-core.md)). One decoder and one encoder in
the core, a scan model (`qr/QrScan`) and a camera on all four apps — CameraX, AVFoundation, the Camera portal over
PipeWire, `MediaCapture` — the desktops' through the bridge's `Scan.*` with frames as the call's payload, and a code
drawn for the desktops as the core's PNG. Measured 2026-11-13 through each app's debug test camera to `Found` (Android a
throwaway AVD's virtual scene, the iOS Simulator, the macOS build, Linux in the container and the Fedora VM, Windows on
the ARM VM); `NoCamera` on each machine without one, `Denied` on Android, and on Linux by a virtual camera and GNOME's
dialog. Callers: the kit's scan at sign-in, device linking.

**Offline content, on all four apps** ([ADR 0140](../decisions/0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)). One store per account on this device in the core's
`offline/`, ciphertext only, every entry sealed again under a key of this account on this device: what a person viewed,
within a limit they set on the account's Settings page ("Storage on this device"; 2 GB on the phones, 10 GB on the
desktops by default, any size, Off), and what they made "Available offline" from a Memory's, a chapter's, a folder's or a
file's header menu, kept until undone and synced at each online open and on a moved revision. An account on a trusted
device unlocks offline at its trust level; offline the shell says "Offline — content as of …", every write is refused,
and what is not kept says "Not available offline". Sign-out, Stop trusting, removing the device, a refused refresh and a
wrap the server no longer serves wipe the store; Android fetches a pin in a WorkManager worker promoted to a `dataSync`
foreground service, iOS in the time `beginBackgroundTask` and `BGContinuedProcessingTask` grant. Measured 2026-11-15 on
Android (a throwaway AVD), Linux (the container) and Windows (the VM) against the dev backend: a Chapter pinned, the
network cut, the app closed, started and unlocked offline, the pinned video played from the store, the store grepped for
a plaintext marker and the media's names (no hit), and the store empty after sign-out (ADR 0140's Consequences, each
pass); the manifest's load at 100,000 entries 173–869 ms on the Windows VM, 199–277 ms cold on ART. The Use page that
says this to a person: [Use content offline](../../site/src/content/docs/getting-started/use-content-offline.mdx).
**Pots and search offline** ([ADR 0153](../decisions/0153-the-apps-queue-a-persons-own-additions-made-offline-sealed-to-the-device-and-send-them-through-the-online-write-path-expenses-joins-the-store.md) decisions 6 and 7): a Pot's
whole ledger is a kept read (`KeptRead.POT_REVISIONS`), replaced — never merged — at each online open, each pin sync and
after each own write (#1038), its receipts' frames kept when shown; a Pot is pinnable (its ledger, every receipt, its
cover) and opens offline read-only under the banner with its own kept time, a receipt never shown saying "Not available
offline". Each space's `listScopeMedia` is a kept read in the automatic tier (`KeptRead.SCOPE_MEDIA`, never pinned,
swept with its scope), so search offline finds the photos of a Memory never opened. Passed on Android, Linux and Windows
(ADR 0153's Consequences).

**Music, on all four apps** ([ADR 0142](../decisions/0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md)). The web's player ported into the core's
`audio/` (`MusicPlayer`, its order, fades and the lightbox video's interruption) and Memories' music into
`memories/music/` (the queue, the facts, the WAV trim, the upload's refusals, the rename, the Music view's model), each
web test with a Kotlin twin; each platform plays bytes on two decks behind the core's `Deck` port (Android `MediaPlayer`
with a `VolumeShaper`, Apple `AVPlayer`, Windows `MediaPlayer`, Linux `gtk::MediaFile`; the desktops' through the bridge's
`Audio.*` host requests) and lends its system controls — Android a `mediaPlayback` foreground service with its
`MediaSession` and notification, iOS a `.playback` session with Now Playing, macOS Now Playing, Windows the window's SMTC,
Linux MPRIS. Every app draws the header's Play/Stop music, the Photos | Music view switch, the Music view (add, rename,
download, remove with Undo, an `ITEM` report), the bar and the lightbox's compact controls; a pinned Memory's tracks are
kept like a video's original and play offline. The format matrices and the passes are ADR 0142's Consequences: Android
(a throwaway AVD: 5 min with the screen off, a headset's next, the notification without `POST_NOTIFICATIONS`, offline),
Windows (the VM: the SMTC minimised and the media keys), Linux (the container's MPRIS with `playerctl`; the Flatpak's
MPRIS name) and the web's video interruption, measured 2026-11-16; Apple's are open (below). The Use page:
[Add background music](../../site/src/content/docs/memories/add-background-music.mdx).

**A Memory's date, place and maps, on all four apps** ([ADR 0143](../decisions/0143-the-apps-draw-a-memorys-places-and-maps-mapkit-on-apple-the-cores-raster-map-elsewhere-one-consent-per-device.md)).
The web's map services ported into the core's `map/` (the consent per device and its hosts, the tile fetch with its
memory-only cache, the geocoder with its cache and queue, the viewport, clustering), a Memory's date and place into
`memories/place/` (the four date modes, the place editor, the `auto` range and position kept current at the settle
points, the Memories map, a photo's place) and its GPX tracks into `memories/tracks/` (the core's own XML reader, the
bounds, the Map view's list), each web test with a Kotlin twin. Every app draws the facts line under a Memory's header
and the date as a row's second line, the date and place editors, the Memories bar's **Map** tab with its clusters (Linux
and Windows gained Memories' bar tabs for it), a photo's map in its details with its name looked up once and sealed, the
Photos | Music | Map view switch with the tracks (add with a name and an activity, edit, download, remove with Undo, an
`ITEM` report), *Turn map services off* on every map and Settings → Map services. Android, Linux and Windows draw the
core's raster map over the operator's tile host (a Compose `Canvas`, a `gtk::Widget`, a XAML `Canvas`); iOS and macOS
draw MapKit, Apple Maps named in the consent. Every map request carries `User-Agent: veyq/<version> (+https://veyq.app;
<instance origin>)` and no `Referer` or token; no tile is written to disk; offline, and from the moment an account loses
its server, no map is drawn and a pinned Memory's Map view lists its kept tracks. Passed on Android (a throwaway
emulator, with the debug build's request log `DevNetLog`), Linux (the container) and Windows (the VM) against the dev
instance with real OSM tiles, measured 2026-11-16 (ADR 0143's Consequences); Apple's are open (below). The Use pages:
[Set the location](../../site/src/content/docs/memories/set-the-location.mdx),
[Add GPX tracks](../../site/src/content/docs/memories/add-gpx-tracks.mdx).

**Curating a Memory, and a person's profile, on all four apps** ([ADR 0144](../decisions/0144-the-apps-curate-a-memorys-participants-a-persons-profile-a-mediums-capture-time-and-the-banner-the-webs-rules-in-the-core.md)).
The web's rules ported into the core: a Memory's participants (`memories/Participants.kt`: normalized, at most 100,
merged on a `409` so a peer's addition survives; *I was there* sealed at birth by `MemoryCreate`), a medium's corrected
capture time (`memories/CaptureTime.kt` over `ListedTierWrite`, the one listed-tier loop a track's rename and a photo's
place name share), the banner (`memories/banner/`: its shape, `BannerFrame`, the write through the generated
`MemoryBannerApi`, the equal-file match over core's first shared row `blob/SharedBlob.kt`, held to a vector the web's
wrap produced) and a person's profile (core's frame on `Shell.person`, Memories' section over the device-side
`ParticipantIndex`, never the personal scope), each web test with a Kotlin twin; the bridge carries it on `GalleryShown`
and a person's page. Every app draws the people line under the facts line with its list and picker, *I was there* in New
Memory, the capture-time editor in a medium's details, the banner above the header on every view (contained over its
32 px surround, no blur API; a video's poster played on a tap) with its dialog and the trash's question, and the profile
("Memories with {name}", newest first, List | Map; Tiles since ADR 0149, below). A pin keeps the banner's blobs; offline the participants
are named from each scope's kept member list (`KeptRead.MEMBERS`, ADR 0140 decision 5), read-only — measured on Android
2026-11-18: the app stopped, the emulator's network off, unlocked from the store, the line named *Kevin Raddatz* with
the letter and no edit. New Memory offers *Determine location automatically* where the map services are shown (uc.memory.place
A1, the web's opt-in): the Memory is born with a pending `auto` place (*Location pending…*, on the desktops through
`GalleryShown.placePending`) that its first geotagged photo names — measured 2026-11-18 on Android and Linux, each
*Westerland* from a photo tagged on Sylt. No permission, entitlement, capability or dependency was added. Passed on Android (throwaway
emulators), Linux (the container, under Broadway) and Windows (the VM) against the web in headless system Chrome, the
equal file's count read from the store's own request log, measured 2026-11-17/18 (ADR 0144's Consequences); Apple's is
open (below). The Use pages: [People in your Memories](../../site/src/content/docs/memories/people-in-your-memories.md),
[Add a banner](../../site/src/content/docs/memories/add-a-banner.mdx),
[Look through the gallery](../../site/src/content/docs/memories/the-gallery.mdx).

**Memories' rest, on all four apps** ([ADR 0149](../decisions/0149-the-apps-take-memories-rest-a-memory-re-keyed-downloaded-and-copied-its-marks-filter-and-contributions-and-tiles-the-webs-rules-in-the-core.md), built 2026-11-19): the web's rules ported into the core, each web test
with a Kotlin twin. A Memory's and a chapter's **re-key** (`memories/MemoryRekey.kt`: the banner's wraps moved in the
one meta core seals, every medium's sealed tiers live and trashed, the bucket names), so an apps-only member is no longer
held behind a rotation; its **download** and **copy** (`MemoryExport.kt`, `MemoryCopy.kt`: the web's archive, held to
the web's importer by the committed `app-memory-download.zip`; a folder's download carries its Memories; a copy reads
every original and tier by frame and decodes nothing); **item marks** (`ItemMarks.kt`: a badge on the tile, the heart in
the lightbox's toolbar and, on Windows and Apple, a tile's menu), the **gallery's filter** (`GalleryFilter.kt` over
core search's chips, the day chip in the viewer's calendar, the marks' mode), the **Contributions** as the view
switch's fourth segment (`Buckets.kt`, `Contributions.kt`: the uploader picker, sub-buckets made, renamed, deleted and
filled as the server allows — any member, any root, never across roots), a guest named from its bucket, a photo's or
video's **Report** from the lightbox; the marks and buckets kept reads offline. **Tiles** on Home, a folder, an
application's list and a person's Memories (`navigation/ViewMode.kt`: List | Tiles, a profile's Map too; one choice per
account on the device; `TileGrid`'s square covers, two columns on a phone). The bridge carries it to the desktops under
`SecretFreeSchemaTest`. No permission, entitlement or dependency was added. Passed on Android (a throwaway API 36
emulator) and Linux (Broadway) as throwaways, and by the core's live tests (`MemoryRekeyLiveTest`,
`MemoryExportLiveTest`, `MarksLiveTest`); Apple and Windows built, their passes open (below; ADR 0149's Consequences say
what each pass proved). The Use pages: [Mark the best photos](../../site/src/content/docs/memories/marked-photos.mdx),
[See who added what](../../site/src/content/docs/memories/contributions.mdx),
[Look through the gallery](../../site/src/content/docs/memories/the-gallery.mdx),
[Views, tabs and favorites](../../site/src/content/docs/spaces/views-and-favorites.mdx),
[Download and import](../../site/src/content/docs/spaces/download-and-import.md),
[Copy things](../../site/src/content/docs/spaces/copy-things.mdx).

**A received link, on all four apps** ([ADR 0150](../decisions/0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md), built 2026-11-19): the web's share page ported into the core
(`sharing/`: the link held to the web's parse by `contracts/platform/share-links/cases.v1.json`, the link instance's
`/api/setup`, the gates — password, terms, note — in the web's order, the guest's tree and its write session), each kind's
guest door its application's (a folder's level, Depot's file and drop box, Memories' gallery and uploader, Expenses' Pot
over a door). *Open a link* on the sign-in and unlock screens (a paste control that reads the clipboard on the tap only;
`PasteButton` on Apple) opens the link as its guest in a screen above the shell, the member's own pages with a guest's
rights; at the share's root *Back* asks first, since the key lives only in memory. A write link's guest session is in
the platform's credential store under a hashed name (the Keystore-sealed store, the Keychain, the Secret Service, the
Credential Locker), the record's notice before every guest write. A kept link (`SHARE_BOOKMARK`) is listed, created
(*New → Bookmark*), opened in place while `core.share-embedding` is on or on its own page, reported (its share to the
share's operator, its name to this one), and *Keep* turns an opened link into one for a signed-in person, the personal
space first. The bridge carries it as `Received.*` and `Bookmark.*`, no update holding the link or its key
(`ReceivedBridgeTest`). No permission, entitlement or dependency was added. Passed on Android (a throwaway API 36
emulator), Linux (Broadway) and Windows (the VM through UI Automation) as throwaways, with scans of each app's files and
logs finding no key, share id or password, and by the core's live tests (`ReceivedLinkLiveTest`, `GuestGapsLiveTest`,
`GuestRemainderLiveTest`); the macOS pass and the owner's iPhone and iPad are open (below; ADR 0150's Consequences say what
each pass proved). The Use pages: [Open a link in the app](../../site/src/content/docs/sharing/open-a-link-in-the-app.mdx),
[Keep a link someone sent you](../../site/src/content/docs/sharing/keep-a-link.mdx).

**The app opened by a `veyq:` link** ([ADR 0150](../decisions/0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md) § O, built 2026-11-19 on every app and the web): `veyq://link`
opens *Open a link* — over the sign-in, the unlock or the open tenant, what was typed kept when it already shows —, and
`veyq://open` (any other form too) only brings the app to the front; the core's `sharing/DeepLink` reads the host alone,
the bridge answers `Call.DeepLink` with the screen. Claimed by Android's VIEW filter on `MainActivity` (`singleTask`),
Apple's `CFBundleURLTypes` with `.onOpenURL` (the Mac's window takes it, the lightbox's opens none), the Flatpak's desktop
entry (`Exec=veyq %u`, `MimeType=x-scheme-handler/veyq;`; `HANDLES_OPEN`, a second launch hands the URI over D-Bus and
exits) and the MSIX's `windows.protocol` (`Program.cs`: with package identity, `AppInstance` redirects a second activation
to the running one); the unpackaged Windows loop and a Linux run outside the Flatpak claim nothing. The web's banner
(`src/web/src/core/openInApp/`) is on every page: *Copy link and open the app* on a share page copies the link and opens
`veyq://link`, *Open in the veyq app* (`veyq://open`) elsewhere and on the four mailed pages after their step. Passed from
a browser on the web (headless Chrome), Android (the emulator's Chrome), Linux (the Fedora VM's Firefox, the Flatpak)
and Windows (the VM's Edge, the MSIX) as throwaways; the Mac's `open` and the iPhone are open (below; ADR 0150's Consequences say what each pass
proved). The Use page: [What a guest sees](../../site/src/content/docs/sharing/what-guests-see.mdx) § Open in the app; the
residual in [Known limitations](../../site/src/content/docs/security/known-limitations.md).

**A share into a Memory** ([ADR 0138](../decisions/0138-the-apps-take-what-the-system-shares-the-chosen-nodes-kind-decides-what-it-becomes.md) decisions 2, 3, 6, 8 and 9, built 2026-11-19 on Android and Apple): each kind
answers a share in its application's code (`navigation/ShareAnswer`: Expenses' `PotShare`, Memories' `MemoryShare`),
the files wait as sources (`navigation/ShareInbox`); a Memory and a chapter take photos and videos through *Add here*
into the one upload queue, and a drop onto the gallery uploads at once (Android `ShareDrop.kt`, Apple `MemoryScreen`'s
`.onDrop`). Android reads a shared file in place; iOS's extension hands a Memory's files to the app through the app
group (`ShareHandoff.swift`) and closes. Passed on Android as a throwaway (a 303 MB video shared into a chapter and
uploaded with the app left; a drop from a freeform window); Apple built, not clicked (§ Open). The Use page:
[Share into veyq](../../site/src/content/docs/getting-started/share-into-veyq.mdx).

**A share into a folder** (ADR 0138 decisions 1–3, 6, 8 and 9, built 2026-11-19 on Android and Apple): veyq is offered
for every share (Android `*/*`, iOS's extension `public.data`; a text or a link says nothing could be taken, until
#1015). Depot answers for a folder and a space's top (`depot/FolderShare`: any file, where Depot may upload); core's
`navigation/AddHere` picks what *Add here* goes into — the open gallery or the level shown — and where a drop onto a
level uploads at once, so no app branches on kinds; a share's file goes into Depot's queue as a picked one does (a
same-name file offers its replace) and is handed back once the queue lets it go. iOS's handoff names its kind; a folder
dropped on a Mac uploads as a folder. Passed on Android as a throwaway (a `.docx`, a ZIP, a photo, a PDF and a file of
no known type into a folder and a space's top; a replace; a text; drops onto both levels; a HEIC's copy removed after
its upload); Apple built, not clicked (§ Open). The Use pages: [Share into veyq](../../site/src/content/docs/getting-started/share-into-veyq.mdx),
[Upload files and folders](../../site/src/content/docs/depot/upload-files-and-folders.mdx).

## Open

**Platforms and form factors**
- Windows beyond its first slice (the next slice is Linux's and Windows' together, owner 2026-11-10):
  - **Packaging — built** ([ADR 0139](../decisions/0139-the-desktop-apps-ship-as-a-flatpak-bundle-and-an-msix-built-in-checks-and-promoted-with-the-release.md)): an MSIX, x64, self-contained, built `-c Release` through the
    Windows App SDK's own MSBuild tooling, unsigned in Checks and signed at promotion with its block map unchanged; no
    package dependency on the WebP or media extensions (a sideloaded MSIX can depend only on framework packages, and
    Windows 11 provisions them). Where a person removed the WebP one, the core decodes the members' pictures (libwebp's
    decoder from ADR 0127's pin, `Picture.Decode`; built 2026-11-18, the owner's "decode it ourselves" — before, every member
    showed initials, measured 2026-11-11); the same gap is still open (#954) for a WebP a person uploaded as content (a photo's
    original, a receipt, a Depot file, a picture picked for tiers, a cover or a banner), which the same call could close.
    A format Media Foundation does not decode is said and offered for saving (the
    VM decoded VP9/Opus and H.264/AAC and refused ProRes, `C00D5212`, said `file_unsupported`; the app ships no codec:
    patents, the owner's answer of the Depot slice, 2026-11-12). A HEIC photo's tiers need the HEIF and HEVC extensions likewise:
    with both on the VM a HEIC decoded to its tiers, and one WIC does not decode went up alone and shows its kind's glyph
    in the grid (measured 2026-11-15); without them it is the same path, not measured (the VM has them). **Built 2026-11-13** (`windows/run.py package`, ADR
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
  - **Uploads, unexplained, seen once each** (2026-11-12): the first 2 GiB upload failed at 30 % and its automatic retry
    failed at once (no code was logged; three later ones went through), and a Retry of a 5-byte file stood at 0 % for
    10 s until Cancel all took it. (A save's progress is an `AdwBanner` of its own since V2 block B, so no toast waits
    behind it any more; a long save beside an upload was not driven in a pass.)
  - **A release build warns** of `dev_login.rs`' `AtomicBool`/`Ordering` imports, used only under `debug_assertions`
    (cosmetic, seen in the Flatpak's build 2026-11-13).
  - **x86-64.** The app has not been built and run on x86-64; only arm64 was. CI's `flatpak` job on an x86-64 runner is
    its first build and start (inbox #914).
- **A CPU libsodium's AES-256-GCM cannot run on is refused at start** (owner 2026-11-10, accepted): it needs AES
  instructions, and on x86-64 also PCLMUL and AVX. Refused: a Raspberry Pi 4 (no AES instructions), an x64 PC or
  x86-64 Linux PC without AVX, and a Windows-on-ARM PC whose x64 emulation hides AVX (it shows it by default). A
  software AES-GCM, or a second source such as CNG, would be a second implementation of a primitive on one platform;
  it is revisited if someone asks.
- **A cover set from another device** shows on the page's next open, as on every platform.
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
- **Memories' iPhone pass left two steps** (machine-doable once a simulator boots, inbox #926): a member's upload from the
  web seen through the poll, and a GPX track left out of the gallery (the dev account's Memory "Leftovers 926" holds one
  track and one photo). Android passed both (2026-11-15); the simulators were unavailable on the developer's Mac.
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
- The Android camera's frames on the emulator are 640×480 at most; 720p is measured on Apple's test camera only.

**Account**
- **Device linking's passes left gaps** ([ADR 0059](../decisions/0059-device-linking.md); § Built, by area):
  - The iOS Simulator's four runs against the web, Apple's snapshot bite (`.secretOnScreen()` removed → the
    SplashBoard snapshot shows the code) and the Mac's fourth run (the Mac new device showing, the web scanning) were
    not run — CoreSimulator wedged under load, and the Mac's fourth try met the web's camera reopening and then the dev
    backend restarting (machine-doable, inbox #928). Apple's runs were driven by debug hooks (`VEYQ_DEV_LINK`,
    `VEYQ_DEV_LINK_DIR`), so the Apple slide itself — drag, arrows and Return, VoiceOver's named action — waits for a
    person (the owner's, #928).
  - No Apple app has linked with another app (the other pairs passed 2026-11-18, § Built, by area); Windows' link scan
    ran only through its test camera — a real camera's link scan on every app is the owner's (inbox #912).
  - On GTK and WinUI a screen reader confirms the slide by the keys, with no named action of its own (ADR 0136's
    Consequences); no screen reader has driven the slide on any platform.
  - Every pass leaves a device-register entry per device it linked (no screen removes one): an orphan once a reconcile drops its rows, and orphans are what eviction takes first ([en.account.device-register](../features/account/enablers/en.account.device-register.md) § Growth).
- The account's flows ([ADR 0148](../decisions/0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md), built): the agent's macOS click-through and kit no-copy check (inbox #976, with #917),
  the Windows UIA pass and no-copy check (#983, once the VM resolves a throwaway's tenant), whether iOS's
  `fileExporter` stages the kit in the container (#975); the address change's confirm and re-addressed sign-in live (no
  mailing backend on the Mac the passes ran on); of [ADR 0151](../decisions/0151-the-apps-account-rest-the-sign-ins-secret-key-control-in-every-flow-that-asks-for-the-key-and-the-breached-password-check-straight-from-the-device.md) (built): the macOS pass
  (the coordinator's), a breach hit drawn live on any app (no played range service reaches one; `BreachCheckTest` holds
  it), a flow's kit scan on Android and Linux, the rotation's gate with a kit on Android and Linux, and an address change
  sent with a kit; a live pass that sees #979's and #980's fixes, and the fixes of #1009, #1010, #977 and #984
  on screen. The owner's: iPhone and
  iPad (a flow's kit scanned with the real camera, the breach notice too), Touch ID and Windows Hello for real, a real
  mailbox, a safety code scanned with real cameras (#912).
- Not in the apps: invites and members, recovery, terms, *Who's who* (#967), the moderation key (#965), the task
  queue's jobs (#966), and an in-app choice of appearance and language (the apps follow the system's; the desktops
  have no per-app language of their own).
- **On macOS a session's menu in Settings was never driven** ("Weitere" → "Dieses Gerät abmelden" on another session:
  the background tools open no menu) — the owner's click (inbox #916); **and its rename alert shows the current name
  only the first time** — later openings were empty (the binding set before presenting; AppKit's alert keeps its field);
  the name is now set once the alert shows, built but not yet checked on the Mac (inbox #917).
- Several instances gated on their major (inbox #436): the apps speak only their own major today.

**Navigation and sharing**
- Search ([ADR 0146](../decisions/0146-the-apps-search-with-the-cores-port-of-the-webs-search-both-answer-to-one-set-of-vectors.md), built above): **the owner's passes** — the iPhone's search tab and
  the iPad's field with tokens and VoiceOver's count on a device (no simulator boots here, #928), Orca reading the count
  and a chip in a real GNOME session, and ⌘F, Return and Esc with the Mac app frontmost. Open findings: #957–#962.
  Not built: listing a kind across scopes.
- A received link opened in the app ([ADR 0150](../decisions/0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md), built above): **the macOS pass** (`PasteButton`, the guest
  screen, the record's notice before a guest's write, the Keychain entry and *Not you?*, a bookmark in place — the coordinator's, from the main session) and **the
  owner's** iPhone and iPad (#928); on no platform a link of another instance, a live link with terms and a note, or a
  screen reader over the guest screens; Android's re-pass of the kept link's page and the guests' reports
  (#1032–#1036), Linux's signed-out Replace and a guest Pot's Settings, Windows' guest Download and a row's context-menu
  report; the `veyq:` link (§ O): the Mac's `open 'veyq://link'` (the coordinator's) and the iPhone from Safari (the
  owner's). Not built: *Keep* on the web's share page (#1013); a moderator's review link (#1014); a link shared in from another app (#1015); a guest's
  upload with the app left (#1016); a guest's pages following the link live, and a guest medium's EXIF (#1017).
- Dropping files onto the app (Android; Apple's drop is built, not measured).
- Quick capture: widgets, shortcuts; notifications, among them the reminder of what one owes in a Pot (#550).

**Expenses**: reading a receipt's text on the device (#549); what [expenses.md](../apps/expenses.md) keeps out of the
first cut.

**Depot**: a READ_WRITE guest's own name as uploader; a download's import on the phones (not built, owner 2026-11-19),
its macOS click pass and a drop from Files or Explorer ([ADR 0152](../decisions/0152-the-apps-import-a-download-as-the-web-does-the-webs-reader-and-walk-in-the-core-each-kinds-import-its-applications-recognised-by-every-depot-upload.md)'s
Consequences, inbox #985); multi-select and dragging a node onto a folder to move
it (inbox #421); Send a copy and "Open with" on the desktops (not offered, see Built). A password-protected PDF's text
selected and copied on Windows (drawn by `Windows.Data.Pdf`, which has no text API). Built 2026-11-18 (inbox #955): on
Linux an overlay of its own over poppler's rendered pages (`src/mobile/linux/src/pdf_text.rs`; GTK has no view that
selects a PDF's text) — poppler's `selected_region` for the highlight and `selected_text` for the copy, a drag across
pages, a double click a word and a triple a line, Ctrl+A every page shown, Ctrl+C and the context menu's *Copy*; nothing
of the text logged (a count of characters) or written. Passed in Broadway against the dev instance the same day: a
heading of the 25-page report highlighted by a drag (25 characters), Ctrl+C, a word by a double click and the menu's
*Copy* (10), Ctrl+A over the 25 pages (43,115), each read back from the clipboard in the app's debug build as the
selection; `jpx.pdf` (a picture, no text layer) highlighted nothing and offered no Copy. On Windows a PDF opens in WebView2's own PDF viewer, served from memory, InPrivate, in a
user-data folder deleted after it, offline by its arguments — taken after a measurement found no byte of the PDF or its
text on disk and nothing off the device, and held to it by `PdfWebTest` ([ADR 0123](../decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)'s
Windows Depot; [src/mobile/CLAUDE.md](../../src/mobile/CLAUDE.md) § Nothing on disk the app did not choose). Passed on the
Windows VM the same day against the dev instance (`run.py drive`): a 25-page report opened in the viewer without Save,
Print or the More menu, a line selected by a mouse drag and Ctrl+C read back from the clipboard in the logged-on session,
Ctrl+S and Ctrl+P opening nothing, `jpx.pdf` shown (a picture, no text to select); after the page was left no browser of
the viewer ran, its folder was gone, and no file written under `%TEMP%`, `%LOCALAPPDATA%` or `%APPDATA%` during the pass
held the selected words (ASCII, UTF-16). Built 2026-11-18 (V2 block C): a
text's *Copy* and *Wrap lines* on every app; a PDF's password asked in the page on every app (Android from Android 15,
`LoadParams`; below it said with the download); its text selectable and copied on Apple (PDFKit's `PDFView`) and Android 15
(`PdfRenderer.Page.selectContent`, a long press and a drag). Passed on a throwaway API 36 emulator the same day: a text's
wrap toggled (its lines unwrapped, then wrapped again) and Copy pressed (what reached the clipboard not read back: the
emulator's shell has no clipboard command); a protected PDF asking for its password, a wrong one said,
the right one showing its page, and its line selected by a long press and a drag with Copy offered. The desktops' and
Apple's were built and their tests run, not clicked (a Linux pass under a load average of 25 lost its clicks and its
server; Apple's is the owner's, #925). The Use page that says this to a person: [Open and look at a
file](../../site/src/content/docs/depot/view-a-file.mdx) (§ In the app).

**Memories** ([ADR 0149](../decisions/0149-the-apps-take-memories-rest-a-memory-re-keyed-downloaded-and-copied-its-marks-filter-and-contributions-and-tiles-the-webs-rules-in-the-core.md), built above): the passes not run — on Android a report seen in the moderation
inbox and an upload pulling a due Memory's re-key forward (#992; an app open across another device's rotation keeps the
old key, #1021); on Linux a report seen in the moderation inbox (#998), and Orca not told the Contributions' chosen
bucket (#1031); **Apple's pass**, the
agent's on the unlocked Mac (#994) and the owner's on an iPhone and an iPad (#925, #928), with the filter's place on an
iPad and a Mac (#995); **Windows' UIA pass** (#1000, once the VM resolves a throwaway's tenant, #983). The fixes of
#990, #991, #996, #997 and #1001 are in, checked by tests; #990's seen on Android, #996's on Android and Linux; #989's calendars are handed on every app,
Apple's unclicked. A copy that a rotation interrupts fails (#988). The owner answered its four questions 2026-11-19 as built (ADR 0149's Consequences).
A guest's gallery of a received link is ADR 0150's (built above). The web's own heart in its lightbox (#986) and its Contributions' "You" (#1003) are
built (2026-11-19).
A share into a Memory and into a folder (built above): Apple's passes — the iOS handoff, which types a Safari page and a
Files share offer the extension, the iPad drop and the macOS drop onto a level (#1005); an iOS handoff for a space the app does not list
(#1007).

**Music** ([ADR 0142](../decisions/0142-the-apps-play-a-nodes-music-through-the-cores-player-each-platform-plays-bytes-and-lends-its-system-controls.md), built above): **Apple's passes are the owner's** — on a real iPhone the background play,
the lock screen, Control Center and a call's interruption, and iOS's format matrix (no simulator boots on this Mac); on
the unlocked Mac the click-through, Now Playing and the media keys (the screen was locked through every pass). Not run
elsewhere: Android's lock-screen controls (the AVD has no keyguard) and a real phone's decoders (the matrix is the
emulator's); the signed-in Flatpak's own MPRIS on a desktop (`playerctl`; a release build has no devlogin); the MSIX's
SMTC source name (the unpackaged build has no package identity, so the flyout names no app). A decoded format added from
an app is trimmed from its two ends as the platform decodes them since 2026-11-18 (#932, ADR 0142 decision 7 as amended);
Apple's `AVAudioFile` path was built, not run (the owner's pass).

**Maps** ([ADR 0143](../decisions/0143-the-apps-draw-a-memorys-places-and-maps-mapkit-on-apple-the-cores-raster-map-elsewhere-one-consent-per-device.md), built above): **Apple's passes are the owner's** — on the
unlocked Mac and a real iPhone, the pass the other three ran: the consent naming Apple Maps, a place set and named, the
Map tab's pins and a cluster, a photo's map and its name sealed once, a track added, dashed, hidden and selected, *Turn
map services off*, offline, and what MapKit keeps on an iPhone (the Mac's screen locked during both attempts; no iOS
simulator boots on this Mac). Not run elsewhere: Windows offline (the VM's backend is reached through a standing tunnel),
Android's *Download GPX* and *Report* of a track, a phone's 60 Hz frame budget. Built 2026-11-18 (V2 block C): a viewed
tile kept sealed across launches in the store's automatic tier, never shown offline (#939, ADR 0143 decision 4 as
amended); the maps open on their framing, never on the world first (#945); a request log in the Linux and Windows debug
builds (#947).

**Curation** ([ADR 0144](../decisions/0144-the-apps-curate-a-memorys-participants-a-persons-profile-a-mediums-capture-time-and-the-banner-the-webs-rules-in-the-core.md), built above): **Apple's pass is the owner's** (inbox #925) — built and compiled for macOS and
iOS, never clicked (the Mac's screen is the owner's; no iOS simulator boots on this Mac): the people line, its popover and
picker, a profile from a participant and from an uploader and its map, *I was there*, a capture time set and reset, an
image and a video banner across a 720 pt window, the trash's question, Remove banner, and on an iPhone whether
`PhotosPicker`'s copy of a gallery photo is still shared. Not run elsewhere: Windows offline (the standing tunnel).
One's own profile and the safety numbers on a person's page are built by [ADR 0148](../decisions/0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md)
(above), tiles by ADR 0149 (above). Not built: a people search or overview.

**Uploads** ([ADR 0145](../decisions/0145-the-apps-uploads-go-on-with-the-app-left-and-show-in-the-gallery-a-desktop-asks-before-a-quit-drops-them.md), built above): **iOS is the owner's** — on a real iPhone whether the
continued-processing task is granted for an upload (`app.veyq.uploads.*`), how long `beginBackgroundTask` lasts and what
an upload cut off by the system looks like (the Simulator has no background processing; no simulator boots on this
Mac); the Mac's quit question and the placeholders on both Apple platforms are built, never clicked (#925). An upload
the system stops anyway fails, and its files are picked again: the queue is in memory, not persisted across a process
end. Not measured: Android 15's six-hour bound on a `dataSync` service. The list behind Apple's button was never seen
open.

**Offline content** ([ADR 0140](../decisions/0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)): **Apple's passes are the owner's** — on a real iPhone and a signed macOS
build, the pass the other three ran, and whether `BGContinuedProcessingTask`'s submit is accepted with the wildcard
identifier and how long `beginBackgroundTask` lasts (the Simulator has no background processing, and an ad-hoc macOS
build keeps no trust and so no store). Not run on any app: a real phone's release build and the system killing the app,
the presence level's offline unlock (a PIN or Windows Hello typed), the Flatpak's and the MSIX's own data folders, a
pinned Depot file or folder (built, run by no test). **Pots and search offline** (ADR 0153 decisions 6 and 7): the Mac's
click pass is the coordinator's and the iPhone's the owner's (ADR 0153's Consequences hold the click list); not run on
any app: the Flatpak's and the MSIX's, sign-out offline on Linux, a never-kept Pot opened offline on Linux and Windows.
Not built: writes made offline and sent later, a sync of pins while the app is not running
([ADR 0153](../decisions/0153-the-apps-queue-a-persons-own-additions-made-offline-sealed-to-the-device-and-send-them-through-the-online-write-path-expenses-joins-the-store.md) decisions 1–5 and 8; #897, #896).
