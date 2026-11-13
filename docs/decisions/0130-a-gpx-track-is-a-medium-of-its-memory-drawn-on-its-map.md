# ADR 0130 — A GPX track is a medium of its Memory, drawn on its map

- Status: Accepted
- Built: yes
- Date: 2026-11-13
- Model: [Application: Memories](../apps/memories.md)

## Context

The owner wants GPX tracks on a Memory's map, beside its photos, "like the old veyq, with the same UX — here of course with
E2EE" (inbox #781). The old application (`raddatzk/veyq-no-e2ee`) had two generations. Both kept several tracks per
Memory, a chapter's its own, and drew them on the Memory's own map tab beside the photos' pins, each photo at its own GPS
position. Each track was a coloured line with a start and an end marker, listed under the map with a visibility switch, its
name and its distance, duration, ascent and descent; it could be removed and its GPX downloaded. The first generation
parsed on the server. The second sealed a parsed JSON per track in a table of its own.

Today's veyq stores an application's blobs in one of three ways, and each could hold a track:

- **A node attachment** (core's `veyq_node_attachment`, ADR 0102's banner): exactly one blob per node and slot, which
  `UNIQUE (node_id, slot)` and ADR 0113 decision 10 rely on.
- **An item of its own in core's blob service** (ADR 0112, Expenses' receipt). Memories' kinds already store their media
  there as **owner's content**, and core's upload dispatcher takes exactly one completion per kind (ADR 0113 decision 4).
  A Memories role that is an item of its own would make two completions accept `MEMORY`. Its role token would also tell
  the operator that a Memory holds GPS tracks.
- **A medium** (`veyq_media`, ADR 0113). The server knows only an original blob, its size, optional derivative tiers,
  and the uploader. A medium's type and name are sealed in the original's `blob-meta`, and the media rows already carry
  a PDF or an undecodable photo, neither of which has derivatives.

## Decision

### 1. A track is a medium whose original is the GPX file

A track is a **medium of the Memory or chapter**. Its original is the picked GPX file as it is, sealed and uploaded as
every original is: a fresh `K_blob` under the node's key, chunked AEAD, direct to the store. Its `blob-meta` carries the type
`application/gpx+xml`, the file name and the digest. It has no derivatives and no EXIF, and its listed meta tier holds the
file's date alone.

There is **no new contract, table, role, route or seal purpose**. The server cannot tell a track from a photo it could not
derive: it sees an original of a size with no tiers. A client knows a track by its opened `blob-meta`: the GPX type, or a
generic XML or byte type with a `.gpx` name (`track/gpx.ts` `isTrackMeta`).

### 2. The file is read on the device only, within bounds

The client parses the GPX with the browser's XML parser. It reads track segments (`trk`/`trkseg`/`trkpt`), or the routes
(`rte`/`rtept`) when there are none, with each point's elevation and time. Waypoints are not drawn. A document type
declaration is refused before parsing, so no entity is ever expanded. Coordinates out of range are skipped.

The checks run on add, before anything is uploaded:

- a file of at most **25 MiB**;
- a `gpx` root;
- at least **two** usable points;
- at most **50 tracks** per Memory or chapter.

Each refusal has its own sentence. The server holds none of these bounds. It cannot see a track, so it caps a track as any
medium: by the quota. A stored file that does not parse, such as one from a newer client or an import, is listed as
unreadable and never drawn. Each segment is drawn with at most 2,000 points, picked evenly with its ends kept. The numbers
are computed over every point.

### 3. Several tracks per Memory and per chapter; a page shows its own

As in the old application, a Memory and each chapter hold their own tracks. A page draws its node's tracks only, not its
chapters' tracks.

### 4. The Map tab: the photos and the tracks, the list under them

A Memory's detail gains a **Map** tab after its Chapters tab. It is the slot the detail reserved for it. The tab holds:

- **The map:** the photos' GPS as the place editor's dots, and each shown track as a line in its colour with a start and
  an end marker. Choosing a line or a row selects the track, draws it heavier and frames it.
- **The list under the map:** a switch that shows or hides the track on the map (this view only), its colour, its name, and
  its distance, duration, climb and descent, each left out where the file does not give it.
- **Per track, a menu:** **Download GPX** (the original, as picked), **Report** (where Report is offered) and
  **Remove**.
- **Add GPX track**, which takes one or several files.

A track's name is the file's own track name, else its `metadata` name, else the file name without `.gpx`. There is no
rename and no activity type in this slice (inbox #841).

### 5. The photos sit at their own place, never on the line by time

A photo is drawn at its own GPS position, from the listed capture tier the place's medoid already reads. Nothing is
interpolated from its capture time onto a track. That would need trustworthy clocks on both devices and would put a photo
without GPS at a position nobody took it at. The old application did the same.

### 6. No new consent

The map is drawn under the **one consent per browser** that every map uses (ADR 0016, ADR 0018), with the same three
states as a level's map: no map on the server, the consent prompt, and the map with *Turn map services off*. Drawing a
line sends the tile service nothing a photo's dot does not. The tile requests carry the viewport, never a track.
Without a map or without the consent, the list, the numbers, the download and the removal all still work.

In a shared space each member's own consent decides, as for every other map. A space owner's enabling notice remains
inbox #47's residual.

### 7. A track is no photo

A track is left out of every view of photos: the gallery, its item count, the lightbox, the contributions organizer, the
search's photo documents, and the date and place reconcile. A banner never matches it.

A track is still a medium:

- **The media trash** shows a removed track as an item without a picture, and a restore brings it back to the map.
- **A download, a copy and an export** of the Memory carry its original. An import of that export restores it as a track,
  because the type is restored with it.

### 8. Its lifecycle and rights are a medium's

What a medium does, a track does. There is no second path to keep in step.

- **Rights:**
  - adding a track is the `upload` right: a member, or a `READ_WRITE` guest, since a share view equals a member view;
  - removing one is `deleteMedia`: a member's trash with Undo, or a guest's purge of its own, asked first;
  - downloading one is `download`;
  - a `READ` guest sees the tracks as a member does.
- **Scope rotation and re-key** (ADR 0109): re-wrapped as media.
- **Trash, purge and erasure** (ADR 0061, ADR 0113 decision 5): handled as media.
- **Export and import** (ADR 0082): handled as media.
- **An application switched off** (uc.platform.switch-application-off): read-only, as media are. The tab and the
  download stay; add and remove go.
- **Quota:** counted as media.
- **Reports** (ADR 0093): a track is reportable as an `ITEM`, Memories' medium report.

## Consequences

- **Operator-blind as a photo is.** The operator sees one more original blob with no derivatives. It cannot tell that the
  blob is a track, that the Memory has tracks, or where they lead.
  - **Residual:** a GPX file has no tiers, and neither does an undecodable photo, a PDF or a video whose frame did not
    decode. Its size is no tell either.
- **No server-side bound on a track.** The 25 MiB and 50-track bounds are a client's. A modified client can store a
  larger file, and other members' clients then refuse to draw it (listed as unreadable when it does not parse). It costs
  only the quota, as any medium does.
- **Every open of the Map tab reads and parses each track whole.** Reading starts only when the tab is opened, and a
  parsed track is kept while the page stays on its Memory.
- **A moderator's review** shows a reported track as the medium it is: its file, not drawn.

### Rejected alternatives

- **An item of core's blob service** (`MEMORIES_TRACK`, ownItem): two completions would accept Memories' kinds, so core's
  dispatcher and ADR 0113 decision 4 would have to change. The role token would also name tracks to the operator.
- **A node attachment per track:** one blob per slot means numbered slots and a list in the node's meta. The meta is
  already near its 8,192-character cap (`bannerModel.test.ts`, "the meta budget").
- **A table of tracks** (the old second generation): a parallel path for listing, the trash, reports, the hold, erasure,
  export and the re-key, each of which media already have.
- **Storing a parsed form instead of the file:** the original is what a member downloads and exports. A parsed copy would
  be a second truth with no reader the original cannot serve.
