# ADR 0145 — The apps' uploads go on with the app left and show in the gallery; a desktop asks before a quit drops them
- Status: Accepted
- Built: yes; decisions 4–6 (a guest's uploads) and decision 7 (the tray signed out) 2026-11-24 on every app, by the
  core's and the bridge's tests and each app's build — their device passes are the owner's (§ Consequences)
- Date: 2026-11-18
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The apps upload through the core's one queue (`blob/UploadQueue`, the web's `queue.ts`): one job at a time, in this
process, from files the platform's picker handed over. What happened to it when the person left the app was nobody's
decision (read 2026-11-18, the block's refine):

- **Android** keeps a backgrounded process only while it is in no danger of being reclaimed; nothing held it for an
  upload, so a long upload stopped when the system took the process, and the queue — kept in memory only — was gone
  with it. A pin's fetch already holds the process for exactly this case: WorkManager's worker promoted to a `dataSync`
  foreground service with its notification while a fetch runs ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
  decision 9, `android/…/OfflineWork.kt`).
- **iOS** suspends the process within seconds of leaving the app. The pin's fetch asks for a `BGContinuedProcessingTask`
  (iOS 26) when it begins and for `beginBackgroundTask`'s time on leaving (then `OfflineBackground.swift`, now `apple/Veyq/ContinuedWork.swift`); an
  upload asked for neither.
- **The desktops** keep running while their window is minimized or hidden. **Closing the window** ended the Linux and
  Windows apps (GTK's application quits with its last window; `App.xaml.cs` shuts the core down on `Closed`), and with
  them every upload under way — silently. The macOS app keeps running with its windows closed (a SwiftUI `WindowGroup`
  app does not quit with its last window); ⌘Q ended it as silently.
- **The gallery** showed nothing of an upload until its medium listed: the tray (`DepotUploads`) said how far the queue
  was, the grid did not. The web draws each file under way as a tile in the grid at once, a local preview with its
  progress, reconciled into the real tile when the medium lists (`optimisticUpload.ts`, uc.memory.upload step 5).

## Decision

1. **An upload holds the process while it runs, the way a pin's fetch does.** The core names what is under way
   (`blob/UploadsUnderway`: per queue, its files waiting or running and the bytes done of the bytes set out — counts and
   sizes, never a name), and each phone holds the process for it:
   - **Android:** a WorkManager worker of its own (`UploadWorker`, unique work `veyq-uploads`) promoted to a `dataSync`
     foreground service with a notification on a channel of its own (*Uploads*: "Uploading 3 files", "40 % done"),
     ended once nothing is under way. It uploads nothing and opens no account: the queue runs in the process as before,
     the worker only keeps the process alive. Where the system refuses the foreground service (started from the
     background), it runs as a plain worker for the time granted. **No new permission:** `FOREGROUND_SERVICE_DATA_SYNC`,
     `POST_NOTIFICATIONS` and WorkManager's `dataSync`-typed service are the pin's.
   - **iOS:** a `BGContinuedProcessingTask` submitted when an upload begins with the app in front (the person's own act),
     its title and progress the upload's, and `beginBackgroundTask`'s time on leaving the app where the system takes no
     such task. The identifiers are `<prefix>.uploads.<uuid>`, permitted by `Info.plist`'s
     `BGTaskSchedulerPermittedIdentifiers` beside the pin's `.offline.*`. The pin's class becomes one class with two
     instances (`ContinuedWork`), one per kind of work: each shows its own progress.
   - **What is not done:** no transfer by the system's own background session (`URLSession` background, Android's
     `DownloadManager`-like uploaders): every frame is sealed in the process before it is sent, and a system session would
     need the plaintext or the sealed file written to disk first — ADR 0140's rule keeps no plaintext at rest, and a
     sealed copy of every picked file would double the disk an upload needs. An upload the system stops anyway (iOS's
     time ends, Android's process is taken despite the service) **fails as an upload cut off does today**: the queue is
     in memory, so the person picks the files again. Persisting the queue across a process end is not part of this
     decision.
2. **A desktop asks before a quit drops what is under way.** Closing the Linux or Windows window, and quitting the macOS
   app (⌘Q, its menu), while files still upload asks first — "Uploads are still running" / "If you quit now, 3 files will
   not be uploaded." with **Quit anyway** and **Keep uploading** (the default) —; with nothing under way it closes as
   before. A minimized or hidden window keeps uploading, as it always did; the macOS app keeps uploading with its windows
   closed, since it keeps running. The question counts every file under way in the process, `UploadsUnderway` (the
   bridge's `Watched.Underway` on Linux and Windows, `ContinuedWork.uploads` on the Mac; decision 6).
3. **A Memory's gallery shows what this device sends into it as placeholder tiles.** `GalleryModel.State.uploading`
   (`memories/UploadTile`, from `MediaUploads.tiles(nodeId)`): every photo and video queued into the node, in the order
   queued, **first in the grid** (the grid is newest first, and what was just added is what the person looks for) —
   waiting, preparing, uploading or failed —, each a square tile with its kind's glyph, its progress, or that it failed
   (the tray retries and cancels it; the tile has no action of its own). A tile gives way once its medium lists (the
   gallery reads anew when a medium of this device is in), so there is no gap and no duplicate; a cancelled one is none.
   Music and GPX tracks are not grid tiles and show none. The layout is the core's (`GalleryModel.layout(rows, uploading,
   width)`, a tile's id `upload:<item>`); the bridge carries them as `GalleryShown.uploading` and lays them out in
   `Gallery.Layout`.
   - **Not as the web:** no local preview and no capture-time slot. The web decodes each picked file a second time, at
     tile size, for its preview, and sorts it into the grid by the capture time it will seal; the apps' deriver already
     decodes each file once for its tiers, and a second decode per tile on a phone is the cost the web measured at 1 GiB
     before it bounded it (inbox #785). The glyph and the progress answer "is it coming?", which is what the tile is for.

### A guest's uploads (2026-11-24, inbox #1016)

A link's guest uploads on the app's one queue while its guest view is open (ADR 0150 decision 5); leaving the view zeroed
`K_share` and the tree's keys and closed the link's client at once, so a guest's upload under way was cut off. Owner
2026-11-24: a guest's upload goes on with the link and the app left, exactly as a member's.

4. **The link's keys and client are held in memory past the view exactly as long as one of its uploads waits or runs.**
   `GuestView.close()` ends the pages and the live poll at once; the release — the trees' keys and `K_share` zeroed, the
   client closed, the gates' in-memory session dropped — waits in core's `sharing/GuestUploadHold` (the platform's, as every
   application's guest upload closes over core's `GuestAccess`) until none of the
   view's jobs waits or runs and no settle hook of the view runs (a folder upload's take-back of its empty folders), then
   runs at once where nothing ever was under way. Nothing is written for it: no plaintext, no sealed copy, no key (the
   session's credential-store entry is ADR 0150 decision 3's, unchanged). Released, the view's failed items offer no
   retry (`UploadQueue.endRetries`): what would send them is gone. A process ended meanwhile fails them as decision 1's
   cut-off upload. Every application's guest upload queues through `GuestAccess.enqueue`, never on the queue itself.
5. **A guest's batch is no account's.** It is queued by `UploadQueue.enqueueSpared`: `clear()` — a sign-out, a sign-in or
   unlock that opens the first tenant, a switch, an erasure, the bridge's `closeTenant` — cancels only the account's
   batches. A lock touches no upload, as before. The same link opened again while its uploads run is a view of its own
   (its own key copy and client); both read the one stored session, and *Not you?* in the new one leaves the held uploads
   under the identity they began with.
6. **The process is held and the question asked as for a member's**, since both read `UploadsUnderway`: Android's
   `UploadWorker`, iOS's `ContinuedWork` uploads instance, the Mac's quit question — and now Linux's and Windows', which
   count the bridge's `Watched.Underway` (`Update.Underway(files)`, from the core's start, a tenant open or not) instead of
   the tray's `UploadsShown.active`, null with no tenant. **The shell's tray** is built from a tenant (`DepotUploads`
   mirrors the whole queue): a guest's uploads show there once signed in; signed out decision 7's tray shows them.
   - **Rejected:** a queue per link — the tray, the worker, the continued task and the question already follow the one
     queue; a second would need a second tray on every client. Persisting a left link's key or session to resume after a
     process end — against ADR 0150 decision 3's key in memory only.

7. **The sign-in and the unlock carry the tray over the queue alone** (inbox #1250, owner 2026-11-24). Core's
   `blob/UploadsTray` — the platform's, no application's and no tenant's — is the queue's state with the tray's acknowledge
   (a batch all in, none failed, leaves by itself after `UploadQueue.ACKNOWLEDGE_AFTER_MS`; one with a failure stays until it
   is closed) and its cancel, retry, cancel all and close. The acknowledge lived in Depot's `DepotUploads`, a tenant's, so a
   batch all in would have stayed on the sign-in for ever; it moved into `UploadsTray` once, and `DepotUploads` holds one.
   Every app holds one more for its process (Android's `AppModel.signedOutTray`, Apple's `AppModel.signedOutTray`, the
   bridge's `Watched.Tray` / `Update.Tray` — `Watched.Uploads` stays the tenant's, since what it says is answered once, by
   the shell); both acknowledging the same batch is harmless. With no tenant open the bridge's `Upload.Cancel`, `Retry`,
   `CancelAll` and `Acknowledge` reach it. Each client draws the shell's own tray, in its own place on the way-in screens,
   and only while the queue lists something: **Android** the shell's row (progress ring, the queue's one line, a chevron)
   above the sign-in and the unlock, under the status bar, opening the shell's bottom sheet; **Apple** (iOS and macOS) a
   section under the heading of the sign-in's and the unlock's form — the shell's glyph, the queue's one line
   (`Notices.uploadSummary`), a chevron — opening the shell's list as a sheet (the way-in screens have no toolbar for the
   shell's button; not in the share extension, which opens no link); **Linux** the shell's header-bar button in the
   way-in page's header bar (`Uploads::signed_out`); **Windows** the shell's command in a CommandBar at the page's top
   right (`Uploads.WithTray`). Nothing is said there (no folder is prepared and no replace offered signed out), no writes
   made offline are listed (they are an account's), and no new words were needed: the tray's own say it.
   - **Rejected:** drawing the raw `UploadQueue.State` alone (no acknowledge, above); giving `Watched.Uploads` a value with no
     tenant (two host instances would each answer what the shell's uploads say); a tray made and closed at every gate change
     (four clients' bookkeeping for a double acknowledge that changes nothing).

## Consequences

- **What the operator sees: nothing new.** The same uploads, at the same times; holding the process changes when bytes
  are sent, not what.
- **What the person sees:** on Android a silent, ongoing notification while files upload (shown where notifications are
  allowed — asked where a pin is made; the service runs either way); on iOS the system's progress for a continued task
  where it grants one. On a desktop, the quit question only while something is under way.
- **Android 15 bounds a `dataSync` foreground service** to about six hours in 24 (remembered from the platform's docs,
  not measured): an upload that long is cut off as decision 1 says, and fails.
- **iOS is the owner's to prove** on a real iPhone: whether the continued task is granted for an upload, how long
  `beginBackgroundTask` lasts, what an upload cut off looks like. The Simulator has no background processing
  (`BGTaskScheduler.h`), and no simulator boots on the build Mac. The macOS quit question is built and compiled; its
  click is the owner's (inbox #925).
- **The passes (2026-11-18).** **Android** (a throwaway API 36 emulator, deleted after): a 99.6 MB video and a photo
  picked into a new Memory, both drawn as placeholders (*Preparing c1-big.mp4*), the app left with Home — WorkManager's
  `SystemForegroundService` foreground (`isForeground=true`, `types=0x1`, oom adj 50), the notification on *Uploads*
  saying *Uploading 2 files*, *2 % done* — and both in after 379 s with the app still in the background, the service
  gone, the placeholders given way to the two media. **Linux** (the container under Broadway, driven from headless
  Chrome): the placeholders; closing the window while the video prepared asked (*1 file will not be uploaded*), *Keep
  uploading* kept the upload going, and a close once it was in quit without asking. **Windows** (the VM, German): two
  files picked through the open dialog, two placeholders (*c1-big.mp4 wird vorbereitet*), closing the window asked
  (*Uploads laufen noch*, *2 Dateien*), *Weiter hochladen* kept them going to *2 Objekte*, and a close with nothing under
  way closed. **Apple:** built for macOS and the iOS Simulator SDK, never run — its passes are the owner's (above).
- **A guest's uploads (decisions 4–6, 2026-11-24)** are proved by `GuestUploadHoldTest` — held while running, zeroed once
  in, cancelled or failed, no retry after, a guest's batch spared by `clear()`; each rule bitten: a release at once, none at
  all, a `clear()` of every batch, no `endRetries` each turned it red — and the bridge's `ReceivedBridgeTest` (`Watched.Underway`
  with no tenant open). Android and Apple needed no code: their holds and the Mac's question already read
  `UploadsUnderway`. Owed, the owner's: a guest's upload with the link and the app left on Android and an iPhone, the Mac's
  ⌘Q, Linux's and Windows' close signed out, and a sign-in over a running guest upload.
- **The tray signed out (decision 7, 2026-11-24)** is proved by `UploadsTrayTest` (a guest's batch spared by a sign-out's
  `clear()` shown with its progress and acknowledged by itself once in, with no tenant's model; a failed one kept until it
  is closed; the actions reaching the queue; a closed tray acknowledging nothing) — bitten: the acknowledge taken out of
  `UploadsTray` turned it red —, `DepotUploadsTest` unchanged over the moved rule, and the bridge's `ReceivedBridgeTest`
  (`Watched.Tray` lists a drop box guest's upload with no tenant open, then empties) and `WireTest`. Each app builds.
  Owed, the owner's: on each of Android, an iPhone, the Mac, Linux and Windows, a link guest's upload left running, the
  link left and the app on its sign-in or unlock — the tray shows it with its progress, leaves once it is in, keeps and
  closes a failure, and cancels one under way.
- **What the docs site says:** [Add photos and videos](../../site/src/content/docs/memories/add-photos-and-videos.mdx)
  (EN and DE) § In the app — the placeholders, a phone's uploads with the app left, a desktop's question.
- **Rejected:** a foreground service started by the app itself rather than WorkManager — WorkManager's typed
  `SystemForegroundService` is already declared for the pin, and the same worker shape keeps both paths alike; a single
  combined notification for pins and uploads — two kinds of work with two progress lines read better as two, and each
  ends on its own; showing the placeholders after the listed media — on a long gallery they would be off screen.
