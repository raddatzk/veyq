# ADR 0145 — The apps' uploads go on with the app left and show in the gallery; a desktop asks before a quit drops them
- Status: Accepted
- Built: partly (not built: nothing; decision 1 on iOS and decision 2 on macOS are built and compiled, never run on a device — § Consequences)
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
  (iOS 26) when it begins and for `beginBackgroundTask`'s time on leaving (`apple/Veyq/OfflineBackground.swift`); an
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
   closed, since it keeps running. The question counts the tray's active items (`UploadsShown.active` on the bridge,
   `UploadQueue.State.active` on Apple).
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
- **Rejected:** a foreground service started by the app itself rather than WorkManager — WorkManager's typed
  `SystemForegroundService` is already declared for the pin, and the same worker shape keeps both paths alike; a single
  combined notification for pins and uploads — two kinds of work with two progress lines read better as two, and each
  ends on its own; showing the placeholders after the listed media — on a long gallery they would be off screen.
