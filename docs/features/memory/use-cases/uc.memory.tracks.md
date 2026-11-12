# UC: Memory tracks — GPX tracks on a Memory's map, beside its photos

- **ID:** uc.memory.tracks
- **Status:** **built (web)** ([ADR 0130](../../../decisions/0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md)).
  The backend has nothing of its own here: a track is a medium, stored, listed, trashed, re-keyed, exported and erased as
  every medium is ([en.memory.media](../enablers/en.memory.media.md)). Its browser pass is lane L1's
  `tests/e2e/tests/features/memories/memory-tracks.spec.ts`.
- **Primary actor:** Anyone who may upload into the Memory or chapter on screen (`memoryRights.upload`): a member who may
  write its scope, or a guest of a `READ_WRITE` link. **Secondary:** anyone who opens it — a member, or a guest of a
  `READ` or `READ_WRITE` link, who sees the tracks exactly as a member does.
- **Goal:** Add one or more GPX files to a Memory and see each as a line on the Memory's map beside its photos, with its
  distance, duration, climb and descent, while the server never learns that the Memory holds a track, let alone where it
  leads.
- **Preconditions:** The Memory's `K_node` is held; the scope is not pending deletion and Memories is switched on (to
  add or remove).
- **Postconditions (success):** Each added file is a medium of the node whose original is the file, its `blob-meta`
  typed `application/gpx+xml`; the Map tab lists it and, where the map services are on, draws it.

## Main flow — add a track

1. With the Memory or chapter open, the user opens its **Map** tab and chooses **Add GPX track**.
2. They pick one or more `.gpx` files.
3. Each file is read on the device. A file that is too large, is no GPX track, has fewer than two points, or would take the
   node past its track limit is refused with its own message, and nothing of it is uploaded (E1).
4. Each accepted file is uploaded as a medium through the upload queue, its original the file as picked. It shows in the
   top bar's upload status, and never as a tile in the gallery.
5. Once it is listed, the track appears in the list under the map with its colour, its name and its numbers, and as a line
   on the map with a start and an end marker. The map frames the photos and the tracks shown.

## Alternate / exception flows

- **A1 — show, hide and select:** each row's switch shows or hides its line on this view only (nothing is written).
  Choosing a line or a row selects the track: its line is drawn heavier and the map frames it.
- **A2 — download:** a row's **Download GPX** saves the original as it was picked, under its file name (`download` right).
- **A3 — remove:** a row's **Remove**, where `deleteMedia` grants it — a member's moves the track to the media trash with
  Undo, as a photo's delete does; a guest's removes its own track only, asked first, for good. The Trash tab lists a
  removed track as an item without a picture; restoring it brings it back to the map.
- **A4 — report:** a row's **Report** opens core's report dialog for the track as a medium (`ITEM`), where Report is offered.
- **A5 — no map, or map services off:** on a server without a map the tab says so; with the map services off the tab offers
  to turn them on (the consent prompt of every map, [uc.memory.place](uc.memory.place.md) A5). Either way the list, the
  numbers, the download, the removal and the add stay.
- **A6 — a chapter:** a chapter holds tracks of its own; a Memory's Map tab draws its own tracks only.
- **A7 — photos on the map:** every photo with GPS in its listed tier is a dot at its own position; nothing is placed on a
  line by its time.
- **E1 — a refused file:** more than 25 MiB (*too large*), not a GPX document or one with a document type declaration
  (*not a GPX track*), fewer than two usable points (*too short*), the node already holding 50 tracks (*limit reached*). The
  message names the file; the other files of the pick go on.
- **E2 — a stored track that does not open or parse:** listed by its file name as *could not be read*, never drawn; the
  rest of the tab is unaffected.
- **E3 — vault locked / key not held:** nothing is listed or drawn.

## Quality notes

- **Operator-blind.** The server stores a medium: an original of a size without derivatives, sealed under a fresh
  `K_blob` wrapped under `K_node`. Type, name and every coordinate are sealed; it cannot tell a track from a photo it could
  not derive (ADR 0130 decision 1 and its residual).
- **Parsed on the device only**, with the browser's XML parser; a document type declaration is refused before parsing.
  The bounds are the adding client's: the server cannot see a track to count it (ADR 0130 decision 2).
- **No new consent.** The map is drawn under the one consent per browser every map uses; the tile service learns the
  viewport, never a line (ADR 0130 decision 6).
- **Not a photo.** A track is in no view of photos: gallery, item count, lightbox, contributions, search, the date and
  place reconcile (ADR 0130 decision 7).

## User impact

Your hike or ride shows on the Memory's map beside the photos you took along the way, and the server never sees where
you went.

## Related

- Feature: [Memory](../_index.md)
- Use-cases: [uc.memory.place](uc.memory.place.md) (the map consent, the photos' GPS), [uc.memory.upload](uc.memory.upload.md)
  (the upload queue it rides), [uc.memory.delete-media](uc.memory.delete-media.md) (the trash a removed track goes to)
- Decisions: [ADR 0130](../../../decisions/0130-a-gpx-track-is-a-medium-of-its-memory-drawn-on-its-map.md),
  [ADR 0016](../../../decisions/0016-geocoding-provider-neutrality.md), [ADR 0018](../../../decisions/0018-map-tiles-provider-neutrality.md),
  [ADR 0113](../../../decisions/0113-memories-media-are-blobs-of-cores-blob-service.md)
- End-user view (docs site): [Add GPX tracks](../../../../site/src/content/docs/memories/add-gpx-tracks.mdx)
