# ADR 0124 — The apps take what the system shares; the chosen node's kind decides what it becomes

- Status: Accepted
- Built: partly (Android's share target, iOS's share extension, the pick mode on both, a drop onto the Apple app, the app group and the shared keychain group; not built: a drop onto the Android app)
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
   another, and only the pick mode's own cancel ends the share.

2. **The chosen node's kind decides what the shared thing becomes** — each application's answer, as a kind's slug token
   input is (core never knows an application's rules). **Expenses** (owner 2026-11-08): a Pot takes images and PDFs within
   its receipts' rules (`receipts.ts`: at most 10 MiB each, 100 an entry) and opens the editor of a new expense with them
   as its receipts — the app's one editor, nothing of its own. A folder (as Depot's files) and a Memory (as its photos)
   take theirs when those applications are built in the apps; a kind with no answer takes nothing.

3. **Nothing is stored until the target's own save.** Choosing a node opens its flow; the editor's save uploads the
   receipts and writes the entry as it does in the app; cancelling leaves nothing behind.

4. **iOS shares what the app keeps with its share extension, and nothing more.** The app group `group.me.raddatz.veyq`
   holds the device's defaults (its trusted-device records and trust marks, the recent and hidden lists): on iOS the app
   reads them from the group's suite, and an earlier installation's own defaults are moved there once. The Keychain items
   live in a shared access group (`<team>.me.raddatz.veyq.shared`), the entitlements' first, so every new item goes there;
   an item an earlier installation kept in the app's own group is moved there when the app reads it. Both are bound to
   the developer team: no other vendor's app reaches them. macOS keeps the app's own until it has an extension.

5. **The extension signs in only from what the app left.** It resumes an account the person stays signed in to (its unlock
   key behind `.userPresence`: Face ID, Touch ID or the passcode) or unlocks a password-trusted one with the password —
   never a first sign-in, a Secret Key or an enrolment: with no trusted account it says to open veyq first. Its keys are
   in memory only, as the app's are (ADR 0123 decision 8); the Master Key is never stored.

6. **What a share extension holds whole is bounded by its target.** A share extension has a fixed memory limit (on the
   order of 120 MB): a receipt is at most 10 MiB, so a Pot's flow holds its files in memory as the app does, at most
   64 MiB of them in one share — a file beyond that is left out, as a too large one is, and the banner counts it. A
   target with larger files (a folder's, a Memory's) seals and uploads them in frames from disk.

7. **An image is taken in a representation every client draws.** iOS offers a photo as HEIC and as JPEG; the share
   takes the JPEG, so a receipt reads in every browser, as the web's own uploads do.

8. **Android's share target is an activity of the app**, in its process, for `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
   of `image/*` and `application/pdf`: the session, the navigation and the editor are the app's own.

9. **A drop onto the app is a share** (owner 2026-11-09: a screenshot's thumbnail dragged onto veyq). Where it lands
   decides: an open Pot that takes it — writable, with people — opens its new expense with the files at once; anywhere
   else they wait in the pick mode, as a share's do. The files are read as a share's are (decisions 6 and 7).

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
- App groups and keychain groups need the developer team's signing: a Simulator build is signed ad hoc with them; a device
  build needs the team's provisioning profile with the group.
