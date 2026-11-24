---
id: cr-guest-uploads-go-on
title: A guest's upload in the app goes on with the app left — the link's keys held in memory while its files go
status: in-progress
satisfies: ["uc.sharing.share-public-viewer"]
adrs: ["0145", "0150"]
invariants:
  - "nothing of a guest's upload is written to disk by this change — no plaintext, no sealed copy, no key (ADR 0140's rule, ADR 0145 decision 1)"
  - "a left link's K_share, its tree's keys and its client live exactly as long as one of its view's uploads waits or runs, then are zeroed and closed"
  - "no contract and no backend change"
---

## Goal

Inbox #1016 (owner 2026-11-24: do it). A WRITE_ONLY (or READ_WRITE) link's guest uploads on the app's one queue while
its guest view is open; leaving the view zeroes `K_share` and the tree's keys and closes the link's client, so a running
upload is cut off. Make a guest's uploads go on as a member's do (ADR 0145): the link's keys, session and client held
**in memory only** past the view for exactly as long as that view's files wait or run, then dropped; the process held
as for a member's; shown in the tray; a desktop's quit question counting them; a process ended anyway fails them as a
cut-off upload.

## Plan

1. **Core** (`sharing/`, the platform): `GuestUploadHold` — the jobs one guest view put on the queue; `GuestView.close()`
   leaves the screen at once and defers the release (trees, gates: `K_share` zeroed, client closed, session reference
   dropped) until none of its jobs waits or runs and no settle hook of its runs; then its failed items are made final
   (no retry with dropped keys). Every guest enqueue goes through `GuestAccess.enqueue`. `UploadQueue.enqueue(…,
   account = false)` marks a batch that is not the signed-in account's: `clear()` (sign-out, switch, erasure) spares it.
   Tests: kept while running, dropped once in, dropped when cut off (cancelled / failed), spared by `clear()`; bite.
2. **Bridge**: `Watched.Underway` → `Update.Underway(files)` from `UploadsUnderway` (tenant-independent), what the desktops'
   quit question reads.
3. **Android / Apple**: nothing to hold beyond the core (the worker and `ContinuedWork` already follow `UploadsUnderway`,
   and the macOS question reads it); check and adjust comments.
4. **Linux / Windows**: the quit question reads `Update.Underway` from an app-level watch instead of the tray's
   `UploadsShown.active`.
5. Harvest into ADR 0145 (dated section), inbox row, delete this CR.

## Decision log

1. **The holder lives in core `sharing/`**, beside `GuestView` (platform, not an application): every application's guest
   upload already closes over core's `GuestAccess`, so one hold there serves Depot's and Memories' alike.
2. **The guest's uploads stay on the app's one queue** (no queue per link): the tray, `UploadsUnderway`, Android's worker,
   iOS's `ContinuedWork` and the desktops' question already count it; a queue per link would need a second tray on every
   client.
3. **A guest upload is not the account's**: a sign-out, a tenant switch, a sign-in or unlock and an erasure clear only the
   account's batches (`UploadQueue.enqueue(account = false)` for a guest's); a lock touches no upload (as before).
4. **The same link opened again while its uploads run is a new view of its own**: the held one keeps its own `K_share`
   copy, client and session until its files are in; both read the same session from the credential store. *Not you?* in
   the new view forgets the stored session; the held uploads go on under the identity they began with.
5. **Release = what `close()` did before**: the trees' keys and `K_share` zeroed, the client closed, the gates' in-memory
   session reference dropped (a `String` cannot be zeroed; the credential store's entry is ADR 0150 decision 3's and
   unchanged — this change writes nothing).
6. **After release a failed item of the link offers no retry** (made final): its keys are gone, a retry could not send.
7. **A settle hook still running holds the release** (a folder upload's take-back of empty folders runs after its batch),
   so the server is not left with a guest's empty folders because the view was left first. After the view is left its
   settles re-read the link no more (`reloadSoon` stops), but the take-back's own re-read runs.
8. **Signed out there is no tray** on any client (the tray is the shell's, built from a tenant): a guest's uploads show
   in the tray once signed in, and in the phone's notification / system progress and the desktops' question signed out.
   A tray on the sign-in screen is filed as inbox #1250.
9. **The desktops' question counts `UploadsUnderway`** (the bridge's new `Watched.Underway`), as the macOS question
   already does: the tray's `UploadsShown.active` is null with no tenant.
