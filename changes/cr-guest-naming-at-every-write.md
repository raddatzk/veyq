---
id: cr-guest-naming-at-every-write
title: A link guest is asked its name at every write through the core's one naming — a gallery's Add photos, a WRITE_ONLY page's upload
status: in-progress
satisfies: ["uc.sharing.share-contributor", "uc.depot.drop-box"]
adrs: ["0154", "0150", "0031"]
invariants:
  - "operator-blind: the naming makes the same identities as before (a READ_WRITE guest's under the root's key, a WRITE_ONLY uploader's K_up wrapped to the drop key); nothing new is sent"
  - "no write before the guest named itself: a declined naming queues nothing and makes no identity"
  - "no platform and no bridge reads the guest's session or the link's role to offer or gate a write"
---

Inbox #1042 and #1044, together (ADR 0154 decision 1's two named remainders).

## Phase 1 — the core

- `GuestNaming` covers every writing link: `request()` asks on READ_WRITE and WRITE_ONLY; `name()` makes a READ_WRITE
  guest (`nameGuest`) or a WRITE_ONLY uploader (`nameUploader`) by the role — the one place that branches on it.
- `GalleryModel.Door.named()` (a member's: true; a guest's: `GuestAccess.naming`), `GalleryModel.uploadAsking`.
- `GuestDropBox.uploadAsking`, `GuestUploader.uploadAsking`; their inline `name` through `naming.name`.
- Tests: `GuestDoorsTest` (an unnamed guest's gallery offers Add and asks at the upload; a drop box's and a Memory's
  uploader named through the naming as uploaders; declined, nothing queued, no identity).

## Phase 2 — the bridge and the apps

- Bridge: `Received.Upload` and `Memory.Upload` through the asking uploads; `Received.Name` deleted.
- Linux: `received.rs` `write()` and `GuestPage.add_names_first` gone. Windows: `ReceivedView.Write` gone,
  `KindBody.GuestCreate()` takes no writer.
- Android and Apple: `canAdd` gone; `uploadMedia` through `uploadAsking`.
- Tests: `ReceivedBridgeTest` (the drop box asked at the upload, the guest gallery's upload asked and declined).

## Decision log

- **The role branch moves into `GuestNaming.name`**, not into a typed `Received.Name` answer: a write asks, so no host
  needs a separate "name yourself" call; `Received.Name` is deleted rather than kept beside the naming flow.
- **`GuestNaming.offered` stays READ_WRITE-only**: it means "the screen shows the guest line"; a WRITE_ONLY page says who
  sent in its own body (the phones' drop forms), so widening it would draw *Not you?* twice there.
- **The phones' WRITE_ONLY pages keep their inline naming form** (the web's drop forms: name, then pick); its save now
  goes through `GuestAccess.naming.name`, so the identity is still the core's choice. Whether those pages should ask at
  the write instead, as the desktops' do, is a product question — filed as #1090.
- **`GalleryModel.uploadAsking` closes the sources on a decline** (nothing else will: no queue holds them), where
  `GuestLevel.uploadAsking` leaves that to its caller — a gallery's callers (Android's, Apple's, the bridge's) never closed
  on a zero before.
- **A declined naming on the desktops still toasts "failed"** (`Received.Upload` answers `NOT_TAKEN`, as a level's upload
  already did): filed as #1091 rather than reading the session in the bridge to tell the two apart.
