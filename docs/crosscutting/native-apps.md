# Native apps — where they stand

The current picture of the V2 native apps in `src/mobile/` (inbox #438): what each platform runs, what is built
against the web client, and what is open. The decisions are [ADR 0122](../decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
(one app, its navigation), [ADR 0123](../decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
(the shared Kotlin core under a native UI; its Consequences record each slice with what was measured, and are this
page's source for every "measured") and [ADR 0126](../decisions/0126-the-apps-take-what-the-system-shares-the-chosen-nodes-kind-decides-what-it-becomes.md) (the
share target). This page is kept current as slices land; a row moves from open to built in the commit that builds it.

## The split

Everything that is not drawing a screen or talking to the device belongs in the Kotlin core (owner 2026-11-09,
[ADR 0127](../decisions/0127-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)), and it is there
(2026-11-10): the suite, the API, every application's domain and its flows, the upload queue, and each screen's state and
rules as a core model both apps draw — the way in (`session/SessionModel`), the shell and its lists (`navigation/ShellModel`,
`RowDetails`), the trash sheet (`navigation/TrashSheet`), the open Pot (`expenses/PotModel`), shared files
(`expenses/ShareInbox`), the link form and a link's row (`session/LinkForm`, `LinkRow`), a header's rights and words
(`navigation/NodeHeader`); which words an outcome raises (`words/Notices`) and what a row or a page says, piece by piece
(`words/Piece`). A platform keeps only its UI, built to its own guidelines, and what only it can do: the system pickers
and share sheets, the secure store, decoding and framing pictures, writing a date, a size or an amount its own way, the
device's calendar, and the order of a view's own steps.

Where the two apps had drifted, one answer was chosen (each pinned by a core test): after the open node is trashed the
shell goes to its parent, read anew; shared files fit a Pot by one rule (`PotShare`'s count and byte budget); a file's
page is decided and read by the core, decoded and drawn by the platform; a Pot created from a level lands in the level
shown; a currency's minor units are one table measured from the web's `Intl` (`expenses/Currencies`, not
`java.util.Currency` or `NumberFormatter`); a drop onto an open Pot runs the core's state machine; receipts a save left out
for want of room close the editor and the shell says which (the web's toast); a report's contact is counted as the web's
`maxlength` counts it; the lists' covers are read again after every change on both.

## Platforms

| Platform | Builds | UI | Large-screen layout | Seen running |
|---|---|---|---|---|
| Android phone | yes (minSdk 31) | Compose, Material 3 | — | yes, the emulator, every slice |
| Android tablet | the same APK | Compose | Material 3's window classes by the window's width: from 600 dp a rail at the side takes the bar's place; from 840 dp the drawer stays open as the sidebar (ADR 0122) and an application's list stands beside its open object (list-detail; a choice there replaces the open one, `Shell.openBeside`); sign-in and unlock read at a form's width | the emulator at 1280 × 800 dp and 800 × 1280 dp (`wm size`), 2026-11-10 |
| iPhone | yes (iOS 26) | SwiftUI | — | yes, the Simulator, every slice |
| iPad | yes (the iOS target, no device family set) | SwiftUI | `NavigationSplitView` with its sidebar open in a regular size class | no |
| macOS | yes (macOS 26) | SwiftUI | always the split view | the core's tests only (`:core:macosArm64Test`); the app itself never; no share extension there |
| Windows | no | — | — | — |
| Linux | no | — | — | — |

Windows and Linux are wanted (owner 2026-11-09), each with its own native UI over the same core
([ADR 0127](../decisions/0127-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)): Linux GTK 4 and
libadwaita in Kotlin on the JVM, Windows WinUI 3 in C# over the core as a Kotlin/Native library.

## Built, by area

"Measured" means ADR 0123 records it run against a dev instance (and, where it says so, read back by the web); "built"
means the code is there and compiles, nothing recorded beyond. Both apps hold everything listed unless a row says
otherwise.

**Account and session.** Sign-in with password and Secret Key behind the `/api/setup` major gate, each grant judged as
the web judges it (measured, `LiveSignInTest`, `GrantTrustTest`); the device trusted with the password, staying signed
in behind biometrics, unlock, sign-out and "Stop trusting this device" (measured, `DeviceTrustLiveTest`); several
servers and tenants with their switcher, hiding tenants and spaces.

**Spaces and navigation.** The drawer (switcher, New, Home, Favorites, Recent, the applications), the bar per
application ending in Search (names only), Home's tree with its filters; favorites, the trash (delete with undo,
restore, purge), move, copy (Pot, folder, file, drop box, with the storage check), rename (its links' names re-sealed),
a node's cover with its crop, core's header for every kind, a report to the moderators — each measured 2026-11-08/09.

**Sharing.** Links on a Pot, a folder or a file (READ, READ_WRITE, "Add files only" drop boxes, at a space's top
level too): create, edit, renew, switch off and on, delete; closing a link re-keys the subtree, an interrupted re-key
is resumed — measured 2026-11-09. Taking shared images and PDFs into a Pot from the system's share sheet (Android, iOS;
ADR 0126), measured 2026-11-08.

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
- iPad and macOS: run, look at and fix the split view; macOS has no share extension.
- Windows (WinUI) and Linux (GTK/libadwaita): the apps (ADR 0127 decisions 4 and 5).
- A CI job for the apps.

**Account**
- Linking a device on a phone ([ADR 0059](../decisions/0059-device-linking.md)): the contract gives a client without
  cookies no adoption (`POST /api/token/adopt` answers only with a cookie).
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
