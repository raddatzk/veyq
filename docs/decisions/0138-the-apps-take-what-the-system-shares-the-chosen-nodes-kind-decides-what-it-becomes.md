# ADR 0138 — The apps take what the system shares; the chosen node's kind decides what it becomes

- Status: Accepted
- Built: yes on Android and Apple (the share target, the share extension, the pick mode, a Pot, a Memory and a chapter as targets, a drop onto the app and onto a Memory's gallery, the app group and the shared keychain group; 2026-11-19 for a Memory; a folder and a space's top as targets, every file type offered and a drop onto a level, 2026-11-19); the macOS drop onto a Memory's gallery passed 2026-11-19; open: Apple's device passes of a Memory's and a folder's share and the iPad's drop (#1005), the macOS drop onto a level
- Date: 2026-11-08

## Context

The owner wants to share from outside into veyq (2026-11-08): a photo of a receipt taken with the camera, shared from the
gallery to veyq, the app's own navigation walked to the Pot it belongs to, and the new expense started there. On Android a
share target is an activity of the app itself; on iOS it is a share extension — another process, another bundle, with
no access to what the app keeps unless the two share it. The native apps are [ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
and [ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md).

## Decision

1. **One flow on both platforms.** The system's share sheet hands veyq what was shared; veyq opens its own navigation
   (ADR 0122: the spaces, the folders, the objects) in a pick mode, in which a node can be chosen only where its kind
   takes what is shared and this member may write there; anything else is shown and cannot be chosen. The pick mode
   starts at the space's Home, never in the object last open (where the app otherwise opens, ADR 0122), so the person
   chooses the place; a target's flow closed without its save gives the files back to the pick mode, for this node or
   another, and only the pick mode's own cancel ends the share. **veyq is offered for every share** (owner 2026-11-19):
   a folder takes any file (decision 2), so the system offers veyq whatever is shared — on Android every type, on iOS
   every attachment that is data. A share that carries no file (a text, a web page's link) waits in the pick mode
   saying that nothing could be taken, until a shared link is given a meaning (inbox #1015).

2. **The chosen node's kind decides what the shared thing becomes** — each application's answer, as a kind's slug token
   input is (core never knows an application's rules). Core asks `AppKinds.share(kind)`: null takes nothing; an answer
   (`navigation/ShareAnswer`) names the files it takes, its bounds for one share, where a member may choose it, and **how
   it takes them** — into its **editor** (bytes held in memory, within the bounds) or into its **upload queue** (each
   file a source read a frame at a time). The pick mode dims a node whose kind has no answer or where the answer says
   this member may not put anything. **Expenses** (owner 2026-11-08, `expenses/PotShare`): a Pot takes images and PDFs
   within its receipts' rules (`receipts.ts`: at most 10 MiB each, 100 an entry) and opens the editor of a new expense
   with them as its receipts — the app's one editor, nothing of its own. **Memories** (`memories/MemoryShare`): a Memory
   and a chapter take photos and videos (`MediaUpload.isMedia`), with no bound beyond the upload's own, into the app's
   one upload queue, where the member may upload there (`MemoryRights`' `upload`: Memories switched on for the
   tenant) and the account is online. **Depot** (`depot/FolderShare`): a folder and a space's top — which asks as a
   folder, as the web treats a level without a node and a folder alike — take **any file**, with no type and no bound
   but the upload's own and the quota (the web's level picker has no `accept`), into the upload queue, where the member
   may upload there: online, not in a guest's identity folder, Depot not switched off for the tenant (the web's
   `features/depot/rights.ts`). The folder stays core's kind; what it takes is Depot's, as on the web, where Depot
   contributes the level's upload actions.
   - Rejected: **a Memory branch beside the Pot's in core** — core would know two applications' rules; likewise a Depot
     branch in `ShareInbox`.

3. **Nothing is stored until the target's own save.** Choosing a node opens its flow; the editor's save uploads the
   receipts and writes the entry as it does in the app; cancelling leaves nothing behind. A Memory can be entered in the
   pick mode: its page, and each chapter's, shows the waiting files' count in the banner with **Add here**, the
   Memory's own save, which queues them into that node (its gallery's placeholder tiles show them, ADR 0145) and ends
   the share; leaving the page without it gives the files back to the pick mode. **A level** — a folder or a space's
   top — likewise: a folder's row in the pick mode is navigation (it is how the person walks to the place), and the open
   level shows *Add here* wherever Depot's answer may be chosen there, queueing the files into Depot's upload queue as
   its picker does — a name a file already holds is offered that file's replace — and ending the share; a level where
   Depot may put nothing keeps the banner without *Add here*. Core decides which open page offers *Add here*
   (`navigation/AddHere`, asked through `ShellModel.addHere` over the open gallery or the level shown), so no app
   branches on kinds. **Words** (owner 2026-11-19): the
   banner says "Choose where these files go" / "Wähle, wohin diese Dateien gehören" and the action "Add here" / "Hier
   hinzufügen".
   - Rejected: **queueing on choosing a Memory's row** — a chapter could not be chosen (it lives inside the Memory's
     page), and nothing would wait for the target's save.

4. **iOS shares what the app keeps with its share extension, and nothing more.** The app group `group.app.veyq`
   holds the device's defaults (its trusted-device records and trust marks, the recent and hidden lists): on iOS the app
   reads them from the group's suite, and an earlier installation's own defaults are moved there once. The Keychain items
   live in a shared access group (`<team>.app.veyq.shared`), the entitlements' first, so every new item goes there;
   an item an earlier installation kept in the app's own group is moved there when the app reads it. Both are bound to
   the developer team: no other vendor's app reaches them. macOS keeps the app's own until it has an extension.

5. **The extension signs in only from what the app left.** It resumes an account the person stays signed in to (its unlock
   key behind `.userPresence`: Face ID, Touch ID or the passcode) or unlocks a password-trusted one with the password —
   never a first sign-in, a Secret Key or an enrolment: with no trusted account it says to open veyq first. Its keys are
   in memory only, as the app's are (ADR 0123 decision 8); the Master Key is never stored.

6. **What a share extension holds whole is bounded by its target.** A share extension has a fixed memory limit (on the
   order of 120 MB): a receipt is at most 10 MiB, so a Pot's flow holds its files in memory as the app does, at most
   64 MiB of them in one share — a file beyond that is left out, as a too large one is, and the banner counts it. A
   target with larger files (a folder's, a Memory's) seals and uploads them in frames from disk: no file of a share is
   held whole but a Pot's bounded receipts. **iOS's extension hands a Memory's or a folder's files to the app through
   the app group and closes** (owner 2026-11-19): it takes each file with `loadFileRepresentation`, copying it inside the callback
   (the system's URL is valid only there) into the app group's container, `share-in/<random>/` — a JPEG where the sender
   offers one, as decision 7 says —, and *Add here* writes beside the copies where they go — the target's kind (a Memory, a chapter, a
   folder) and node, none for a space's top; a handoff without a kind is a Memory's — and completes the request at once.
   The app, the next time it opens that account's tenant, queues them into that place through its one upload queue —
   Memories' or Depot's —, and removes each copy once the queue lets it go (`MediaUploads`' `left`, `DepotUploads`'
   `left`: an upload done or cancelled, or abandoned failed, and a same-name file's replace offer answered); a cancel in
   the extension removes them at once, and a sign-out or a hand-off the account no longer has removes them unsent. The
   owner's reason: the files are not uploaded yet, and they are the person's own plaintext already on the device —
   a copy in the app's own container until the upload is no exposure the device does not already hold. The apps
   behave as the web does: the place is chosen and *Add here* pressed in the extension, the upload runs in the app.
   - Rejected: **the extension running the upload queue itself, open until the upload is done** — the person would
     wait in the share sheet through a large video's upload, and closing it would cancel the upload (the owner's
     no, 2026-11-19).
   - Rejected: **a background `URLSession`** — the core's client is not one, and its presigned frame uploads would need
     a second upload path.

7. **An image is taken in a representation every client draws.** iOS offers a photo as HEIC and as JPEG; the share
   takes the JPEG, so a receipt reads in every browser, as the web's own uploads do — a photo shared into a folder too:
   the share is read before its place is chosen, and one read for every target beats holding two copies until the
   choice.

8. **Android's share target is an activity of the app**, in its process, for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
   of any type (`*/*`, decision 1; each target still takes only what its answer takes): the session, the navigation and the editor are the app's own. A shared
   file is read where the system put it, a frame at a time (`ContentSource`, a pick's): the share's read grant is the
   receiving activity's and outlives the app being left, so the upload's worker reads it to its end (measured,
   Consequences). The one copy the app writes is a HEIC re-encoded as a JPEG (decision 7), under
   `no_backup/share-in/`, removed once nothing reads it and swept at start.

9. **A drop onto the app is a share** (owner 2026-11-09: a screenshot's thumbnail dragged onto veyq). Where it lands
   decides: an open Pot that takes it — writable, with people — opens its new expense with the files at once; a Memory's
   or a chapter's gallery the member may upload into queues the photos and videos into it at once, as its picker's do
   (Android's `dragAndDropTarget`, Apple's `dropDestination`: iPadOS, macOS, an iPhone where the system offers a drag
   between apps); an open level — a folder or a space's top — where Depot may put files uploads any file dropped onto
   it there at once, as the web's level and the desktops' take a drop, and on macOS a dropped folder uploads as a folder
   (`DepotUploads.uploadFolder`, as the desktops' do); anywhere else they wait in the pick mode, as a share's do. The
   files are read as a share's are (decisions 6 and 7).

## Consequences

- **Built:** decision 4 — the app group's defaults (`apple/Veyq/Shared.swift`, the move of an earlier installation's)
  and the shared keychain group (`apple/Veyq/KeychainStore.swift`, the move of an item read from the app's own group;
  `apple/Veyq/Veyq-iOS.entitlements`); core's `UserDefaultsStore` takes the defaults it is handed. An installation from
  before keeps its trusted account across the move (measured 2026-11-08 in the iOS Simulator).
- **Built on Android:** decisions 1–3, 7 and 8 — the share target (`MainActivity`, `singleTask`, so a share reaches the
  app's own task and session), the files read at once (`android/ShareIn.kt`; a HEIC re-encoded as a JPEG), the pick
  mode's banner with the kinds that take nothing dimmed, and a writable Pot with people opening its new expense with
  them as receipts; what a Pot takes is Expenses' answer in core (`expenses/PotShare.kt`, `PotShareTest`). A photo
  shared from the Files app was saved as a receipt of a new expense and opened again (measured 2026-11-08).
- **Built on iOS:** decisions 1–3 and 5–7 — the share extension `VeyqShare` (`apple/VeyqShare/`): the app's own views
  and model (`AppModel(inExtension: true)`) under its `ShareViewController`, which reads the attachments once (a JPEG
  where the sender offers one, a HEIC re-encoded, a PDF as it is) and ends the request on the save or the cancel. With
  no trusted account it says to open veyq first (`.needsApp`); it never moves or removes what the app keeps. A photo
  shared from the Photos app was placed in a Pot after Face ID, saved as a new expense's receipt, and opened on the web
  (measured 2026-11-08 in the iOS Simulator); the editor closed unsaved returned to the pick mode, and the pick mode's
  cancel ended the request — on Android likewise (measured the same day on the emulator).
- **Built on Apple, decision 9:** the shell takes images and PDFs dropped onto it (`apple/Veyq/IncomingFiles.swift`, the
  one reader the share extension uses too, re-encoding a HEIC through ImageIO so macOS reads the same way;
  `AppModel.receiveDrop`). The reader is measured through the extension; a drop itself is not, since the Simulator's one
  scripted finger cannot hold a thumbnail and switch apps. Android's drag and drop is not built.
- **Built on Android, decisions 2, 3, 8 and 9 for a Memory** (cr-app-share-into-a-memory): `video/*` in both filters,
  `ShareIn` handing sources read in place, the banner's *Add here* on a Memory's and a chapter's page, a drop onto the
  gallery (`Modifier.dragAndDropTarget`, `ShareDrop.kt`) and onto the shell (a share). Measured 2026-11-19 on a throwaway
  API 36 emulator as a throwaway account: a photo and a 303 MB video shared from the Files app into a chapter, *Add
  here* pressed and the app left at once (Home) — the upload's worker read the video to its end in the background
  (09:02:56 to 09:22:24, about 329 MB sent, no `SecurityException` in the app's log), and after the app was force-stopped
  and opened anew the chapter listed both from the server; `run-as app.veyq find . -type f` then showed no share copy
  (WorkManager's records, the offline store's opaque files and the known preferences only). A photo dragged from the
  Files app in a second (freeform) window onto the chapter's gallery was uploaded into it.
- **Built on Apple, decisions 2, 3, 6 and 9 for a Memory** (cr-app-share-into-a-memory): `public.movie` in the
  extension's activation rule; `IncomingFiles` copying each file inside `loadFileRepresentation`'s callback (a HEIC
  re-encoded file to file) and handing sources on; the banner's *Add here*; in the extension the handoff through the app
  group (`apple/Veyq/ShareHandoff.swift`: `share-in/<id>/` with `target.json`, written under a dot name and renamed
  whole, queued by the app on its next open, each copy removed by the queue's `left`, cleared at a sign-out, swept at
  start); a drop onto a Memory's gallery (`MemoryScreen`'s `.onDrop`). Built for macOS and the iOS simulator SDK. **The
  macOS pass proved the drop** (2026-11-19, the coordinator's main session with full-screen control, a throwaway): three
  photos dragged from Finder onto a new Memory's gallery were uploaded into it (*3 items*). The device passes are the
  owner's (inbox #1005), and a handoff for a space the app does not list waits until a sign-out (inbox #1007).
- **Decision 2's Memory, built 2026-11-19** (cr-app-share-into-a-memory, inbox #893): each kind's answer is its
  application's (`navigation/ShareAnswer`, `AppKinds.share`; Expenses' `PotShare.answer`, Memories'
  `memories/MemoryShare`), the files waiting as sources in core's `navigation/ShareInbox` — the Pot reads its bounded
  receipts from them when it is chosen, a Memory's *Add here* queues them; held by core's `ShareInboxTest` (each kind
  answering in its own application, a Memory taking a video and refusing a PDF, a chapter taking, a member who may not
  upload dimmed, *Add here* queuing once) and `PotShareTest` (unchanged by the move). Open after it: Apple's device
  passes — a long video handed over from Photos and the extension cancelled, an iPad's drop (inbox #1005; the macOS drop
  from Finder passed 2026-11-19); a
  handoff for a space the app does not list waits unsaid until a sign-out (#1007). A folder as a share target is Depot's answer (inbox #987); the desktops have no
  share target (GNOME has none; Windows' needs the packaged app's Share Target contract) and take a drop onto a
  Memory's gallery instead. The Use page: [Share into veyq](../../site/src/content/docs/getting-started/share-into-veyq.mdx).
- **Android's Pot share after the move to sources** (measured 2026-11-19 on a throwaway API 36 emulator as a throwaway
  account): a PDF and a photo shared from the Files app, the pick mode's Pot chosen, its new expense opened with both as
  receipts, closed unsaved (the pick mode back), the Pot chosen again and the expense saved; opened anew in the app and
  on the web it lists both, at the originals' sizes. A
  HEIC shared likewise opened as `cat.jpg`, its JPEG copy under `no_backup/share-in/` while the editor held it and gone
  after the save; one left by a closed app was swept at its next start. The first try opened no editor: the Pot page's
  effect was keyed on the waiting files, which its own take emptied, so the read was cancelled (`PotScreen`, fixed).
- **Android's drop grant outlives the activity** (measured 2026-11-19 on a throwaway API 36 emulator as a throwaway
  account): a 253.5 MB video dragged from the Files app in a freeform window onto a Memory's gallery (11:02:44), its
  upload's worker started (11:02:47), the app left to Home (11:03:06) and its activity finished by Back (11:03:42, no
  `MainActivity` left in `dumpsys activity activities`, the process kept by the upload's foreground service); the worker
  read the file to its end and ended `SUCCESS` at 11:20:10, no `SecurityException` in the log, and the web lists the
  video at 253.5 MB with its poster. So a drop is read in place as a share is, and needs no copy. A process killed
  meanwhile is not measured.
- App groups and keychain groups need the developer team's signing: a Simulator build is signed ad hoc with them; a device
  build needs the team's provisioning profile with the group.
