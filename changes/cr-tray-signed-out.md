---
id: cr-tray-signed-out
title: The uploads tray on the sign-in and the unlock, over the app's one queue alone
status: in-progress
satisfies: []
adrs: ["0145", "0150", "0154"]
invariants:
  - "the tray signed out is the platform's (core's blob/UploadsTray), names no application and no tenant"
  - "a guest's batch is still spared by a sign-out's clear(); nothing about it is written to disk"
---

## Goal

Inbox #1250 (owner go 2026-11-24): a link guest's uploads left running signed out (ADR 0145 decisions 4–6) show on the
sign-in and the unlock of every client — progress, failures, cancel, retry, a finished batch's close — as the shell's tray
does, over the app's one queue alone.

## Plan

1. Core: `blob/UploadsTray` — the queue's state, the tray's acknowledge (moved out of Depot's `DepotUploads`, which now
   holds one), cancel, retry, cancel all, close; `UploadsTrayTest` (bitten).
2. Bridge: `Watched.Tray` / `Update.Tray` over a process `UploadsTray`; `Upload.Cancel`/`Retry`/`CancelAll`/`Acknowledge`
   answered by it with no tenant open; `ReceivedBridgeTest`, `WireTest`.
3. Clients: Android (the shell's tray row and sheet above the sign-in and the unlock), Apple (a form section with the
   shell's glyph opening the shell's list), Linux (the shell's header-bar button in the way-in pages' header bar),
   Windows (the shell's command in a CommandBar at the page's top right).
4. Harvest into ADR 0145; delete this CR; #1250's row marked built with the owed device passes.

## Decision log

- **The model is core's `blob/UploadsTray`, not the raw `UploadQueue.State` alone.** The raw state has no acknowledge: a
  batch all in would stay on the sign-in for ever (the acknowledge was Depot's `DepotUploads`', a tenant's). The rule moved
  into `UploadsTray` once; `DepotUploads` holds one, so the shell keeps its behaviour and its tests.
- **One `UploadsTray` per process beside the tenant's**: both acknowledge the same clean batch; a second acknowledge of a
  hidden batch changes nothing — cheaper than creating and closing a tray at every gate change on four clients.
- **The bridge adds `Watched.Tray` rather than giving `Watched.Uploads` a no-tenant value**: `Uploads` stays the tenant's
  (what it says needs answering once, by the shell's host only); the hosts' way-in instances watch `Tray`, which never says.
- **Each client's own form of the shell's tray**: Android the shell's row (Material's list row under the top edge) and its
  bottom sheet; Apple a form section (the sign-in has no toolbar; HIG's grouped form row with a chevron) opening the shell's
  list as a sheet; Linux and Windows the shell's own button in the page's header bar / a CommandBar at the top right.
- **No new words**: the tray's own (`upload_title`, `upload_active`, `upload_summary`'s, …) say everything.
- **Not in the share extension** (Apple): it opens no link, so no guest upload runs there.
