# ADR 0082 — A node and everything under it exports as a plaintext ZIP and imports under the importer

- Status: Accepted
- Date: 2026-10-13

## Context

A member wants their data **out** in a form that outlives veyq and that they can hand to someone, and wants such a file
back **in** — a folder tree, a Files folder, a Memory with its chapters. Inbox #379 asked it for a Memory alone ("a
Memory's export is a file; dropping it into a Files folder imports it"). The owner widened it on 2026-10-13: any node
and everything under it, as a **core** feature, because the tree, names, covers and metadata are core's and only the
*content* differs per kind.

Three constraints shape it. **Operator-blind:** the server holds only ciphertext, so a readable export can only be
produced where the keys are — the web client, on decrypted data. **Applications stay apart** ([ADR 0079](0079-veyq-is-a-platform-memories-is-its-first-application.md)):
a Files folder may contain a Memory, but Files must not import `features/memory`; core reaches each kind through the
registry (`KindDescriptor`, as `createEntry` already does). **No dependency, no memory wall:** a subtree can be gigabytes;
the client already streams downloads through `saveStream` (`web/src/core/blob/stream.ts`) and reads big files by
`Blob.slice`, and the project takes no dependency it can avoid.

Not this: a **tenant or instance** export that stays encrypted for moving a tenant between instances. That is a different
object (never decrypted, operator-side keys, [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) already
covers the instance backup) and is left to its own inbox row (#413).

## Decision

Decided by the owner, 2026-10-13.

**1. Scope.** Export and import apply to **any node and everything under it** — folder tree, Files, drop boxes, Memories
with chapters. It is a **core** feature. Core knows only the tree, names, cover and plaintext metadata; each kind supplies
and consumes its **content** through a hook on `KindDescriptor` (beside `createEntry`): `exportContent` yields a content
object plus its payload streams, `importContent` creates the kind's content under a freshly created node. Files supplies
the file bytes; Memories the media originals with EXIF, GPS, place and `capturedAt`. A Memory export (#379) is simply a
subtree rooted at a `MEMORY` node. Files reaches Memories only through the registry; a kind the importing build lacks is
skipped with its subtree and reported, never guessed.

**2. Produced in the client, on decrypted data.** The server never sees plaintext and gets no export endpoint: the client
walks the tree with the keys the member already holds, decrypts, and writes the archive through `saveStream`.

**3. The format.** A **store-only** (no compression — media is already compressed, and it keeps the writer trivial and
streamable) ZIP that **reads as the tree when unpacked** (owner decision 2026-10-14): each node is a directory named after
the node, its files under their own names (`Trip 2025/Lisbon/IMG_1.jpg`), and the manifest beside them at
**`.veyq/manifest.json`**, so the same archive is an ordinary download and an importable export. Names are made safe for
every file system before they become paths: no `/` or `\`, no `.`/`..`, no control characters, none of Windows'
reserved names (`CON`, `NUL`, …) or trailing dots and spaces, a bounded length, and a numeric suffix where two names of
one directory fold to the same name ignoring case (`Name (2)`, before the extension). A node that holds others (a folder,
a Memory, a chapter) is a directory; a node of a kind that IS one file (a Files file) is that file. The manifest maps every
node to its path; the path is a label, never an identity. The manifest — `format: "veyq-subtree"`, `version`,
`exportedAt`, `appVersion`, the `applications` it carries (decision 9), and the tree (each node: archive-local `id`,
`kind`, `name`, `path`, `cover`, `metadata`, the kind's `content` reference, `children`) — is specified by
[`contracts/export/veyq-subtree.schema.json`](../../contracts/export/veyq-subtree.schema.json); a worked example is
below. The writer and reader are **hand-written and streaming**, with **no new dependency**: the writer emits local
headers with the data-descriptor flag and a central directory at the end; the reader finds the central directory from the
end and reads entries with `Blob.slice`, so an import never holds the file in memory. Payload paths come from the manifest
only, lie at or under their node's path, and are refused when a segment is not a safe name or the path reaches into
`.veyq/`; each payload's `sha256` is verified while streaming. ZIP64 is required from the start (a subtree exceeds 4 GiB).
This layout is format `version` 2; version 1 kept the manifest at the root and every payload under `data/<node id>/`, and
a reader reads both while a writer writes only 2.

**4. It is a download, and is called one** (owner decision 2026-10-14). There is no "Export": a node offers
**Download** — a file as itself, a folder, a Memory, a chapter or a drop box as this ZIP. No plaintext warning and no
location option: a downloaded file is as plaintext as a downloaded archive, and a photo's coordinates sit in its own
EXIF, which the archive keeps byte for byte (Addendum 2), so an option that dropped only the manifest's copies promised
more than it did.

**5. Rights.** Export needs **READ** on the node. Import is **a member flow only** (decisions 6 and 7): a guest — whatever
the share role — never imports. A guest is otherwise the member view with rights removed (the principle of inbox #401): a
`READ` or `READ_WRITE` guest of a shared folder or Memory may **export** what they can see, a `WRITE_ONLY` drop-box guest
neither exports nor imports.

**6. Import is a member flow, and the result belongs to the importer.** The restored subtree lands **under the importing
member**: they become the uploader and owner; media go into the **importer's bucket** (the simplest option; the owner
agreed). **Not carried over:** ids, keys and wraps (everything is created fresh and sealed under the target's keys), the
server-observed timestamps (uploaded/updated), item favorites, shares, members, trash. The **original contributor** of a
medium stays in the manifest as **text only** ("originally by Anna"), never mapped to an identity. A name clash in the
target parent gets a numeric suffix, `Name (2)`. An unknown newer `version` is **refused**; unknown fields are ignored.

**7. Detected by content, offered only to members.** An archive is recognised by its **content** — the ZIP signature plus a
`.veyq/manifest.json` whose `format` matches — never by file name. **A picked directory** that holds `.veyq/manifest.json`
(the archive, unpacked) is recognised the same way and imported from its files; one without it is an ordinary folder
upload (inbox #424). Recognised, a member is offered **Import** or **Upload as a file**. A dropped archive in a Files folder offers **Import** to
members only: never on a guest view (any role), so for a guest — a `WRITE_ONLY` one included — it stays an ordinary upload.

**8. Failure and atomicity.** The server offers no multi-node atomicity. An import is a sequence of ordinary writes; one
that aborts or runs out of quota **rolls back what it created** (to the trash) and reports "N of M". A **dry-run** step
reads the manifest and shows total size against the member's quota **before anything is written**.

**9. Unknown applications and newer data are reported, never guessed** (owner decision 2026-10-14). The manifest names, per
application whose kinds it carries, that application's id and the version of its `content` schema. The dry-run (decision 8)
lists, before anything is written, every node this build cannot import: a kind of an application it does not have (an
"Expenses" export into an instance without it), or a known application's content newer than it reads. The member then
**imports the rest**, with those subtrees left out and named in the report, or **cancels**. Nothing unknown is dropped
silently, and nothing newer is read as if it were older.

**10. The archive is untrusted input.** The import replays the ordinary writes, so the server checks what it always
checks; the client additionally checks the archive before and while it reads it: the manifest against its schema; caps on
node count, depth, name length and every metadata field at the API's own limits; the ZIP structure itself (every offset
inside the file, no overlapping entries, declared sizes equal to actual sizes, ZIP64 fields in range); each payload's
`sha256`; and text that is rendered (a note, a description) only through the safe markdown renderer. Being store-only, an
archive cannot be a compression bomb.

## Worked example

A folder "Trip 2025" holding a file and a Memory "Lisbon" with a chapter "Alfama: day 1" and two photos. The ZIP holds:

```
.veyq/manifest.json
Trip 2025/
Trip 2025/itinerary.pdf
Trip 2025/Lisbon/
Trip 2025/Lisbon/IMG_0412.jpg
Trip 2025/Lisbon/IMG_0413.jpg
Trip 2025/Lisbon/Alfama_ day 1/
```

The chapter's directory is its name made safe (`:` is not allowed on every file system); the manifest keeps the name as veyq
shows it:

```json
{
  "format": "veyq-subtree",
  "version": 2,
  "exportedAt": "2026-10-14T09:30:00Z",
  "appVersion": "0.9",
  "applications": [
    { "id": "files", "contentSchema": 1 },
    { "id": "memories", "contentSchema": 1 }
  ],
  "root": {
    "id": "n1", "kind": "FOLDER", "name": "Trip 2025", "path": "Trip 2025",
    "children": [
      {
        "id": "n2", "kind": "FILE", "name": "itinerary.pdf", "path": "Trip 2025/itinerary.pdf",
        "content": {
          "type": "files.file", "schema": 1,
          "file": { "path": "Trip 2025/itinerary.pdf", "size": 48213, "mediaType": "application/pdf",
                    "sha256": "9f2c0c4e1b7a5d3e8a6f4b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e" }
        }
      },
      {
        "id": "n3", "kind": "MEMORY", "name": "Lisbon", "path": "Trip 2025/Lisbon",
        "content": {
          "type": "memories.memory", "schema": 1,
          "date": { "mode": "range", "from": "2025-09-14", "to": "2025-09-17" },
          "participants": ["Anna", "Kevin"],
          "cover": { "mediaIndex": 0, "crop": { "x": 0, "y": 0, "width": 1, "height": 1 } },
          "media": [
            { "file": { "path": "Trip 2025/Lisbon/IMG_0412.jpg", "size": 3811920, "mediaType": "image/jpeg",
                        "sha256": "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809" },
              "name": "IMG_0412.jpg", "capturedAt": "2025-09-14T16:02:11Z",
              "location": { "lat": 38.7139, "lon": -9.1394, "place": "Lisbon" },
              "exif": { "Make": "Fujifilm", "Model": "X-T5", "FNumber": 4 },
              "contributor": "Anna" },
            { "file": { "path": "Trip 2025/Lisbon/IMG_0413.jpg", "size": 4120377, "mediaType": "image/jpeg",
                        "sha256": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f" },
              "name": "IMG_0413.jpg", "capturedAt": "2025-09-14T16:05:40Z",
              "contributor": "Kevin" }
          ]
        },
        "children": [
          { "id": "n4", "kind": "CHAPTER", "name": "Alfama: day 1", "path": "Trip 2025/Lisbon/Alfama_ day 1",
            "content": { "type": "memories.chapter", "schema": 1, "media": [] } }
        ]
      }
    ]
  }
}
```

## Addendum (2026-10-13, while building the Memories hooks)

Three things the build found that the decision above did not know, each decided here so the code and the contract agree.

1. **Core carries the tree and the payload bytes; a kind's content carries everything else.** Core's walker neither reads
   nor writes a node's `cover` or `metadata`. A Memory's date, place, cover and participants therefore travel inside its
   `content` (`memories.memory`), and **a chapter's media sit in the chapter's own content** (`memories.chapter`), not in the
   Memory's list: the chapter is a node of its own with its own key, and the Memory's hook returns before the chapter
   exists. The per-medium `chapter` field is gone from the schema.
2. **The archive keeps each original's bytes, its own GPS tags included.** Re-encoding the originals to remove them is
   deliberately not done: it would change the bytes the download promises to keep. This is why decision 4 offers no location
   option: one could drop only the manifest's copies, never the file's own EXIF.
3. **An import restores a coordinate only if the file still carries it.** The upload reads GPS and camera data from the
   file's bytes, and there is no way to seal a coordinate the file lacks. What an import restores beyond the file: the Memory's
   date and place, the capture time (via the payload's modified time, or as a correction when the member had corrected it),
   the per-medium place name, the cover (a photo of the Memory by its position in the list, or an uploaded image, which is
   re-encoded by the cover upload). Participants are exported as display names and not restored.

## Consequences

- **A new core surface in the web**: the zip writer/reader, the walk, the dialogs (download, which asks nothing for a
  single file; import with the dry-run), the `KindDescriptor` hook, and the drop detection in the Files level.
  Nothing in the backend or `contracts/api/` — the client uses the existing node, blob and quota endpoints. The schema
  under `contracts/export/` is the one new contract artefact.
- **The format is a public promise.** `version` bumps only for a change an old reader would misread; kinds version their
  own `content.schema`, named per application in `applications`. A reader that tolerates additions keeps old files
  importable, and a reader keeps every older `version` it ever wrote: the tree layout (2) replaced `data/<id>/` (1), and
  archives of version 1 still import.
- **The plaintext file is outside the E2EE guarantee by design**, exactly as a downloaded file is; the operator never sees
  it, but the member's disk, mail or cloud does. An encrypted variant is deliberately not built.
- **Rollback is best-effort**, not a transaction: a crash mid-import leaves a partial subtree in the trash-able state it
  reached, and the report says how far it got. Accepted because the alternative needs server-side atomicity across nodes
  and blobs that does not exist.
- **Contributors flatten to text, and media land in the importer's bucket**, so a restored shared Memory loses who owns
  which medium for quota and deletion purposes. Accepted for simplicity; revisit if members ask.
- **Rejected:** a per-kind export (Memories only) — duplicates the tree, naming and clash logic and leaves a Files folder
  with a Memory in it unexportable; a server-side export — breaks operator-blindness; a compressed ZIP or a library such
  as a zip package — a dependency for no gain on already-compressed media; carrying ids and keys — turns an import into
  a restore that must fight the live tree.
- **Open (not decided here):** the exact ZIP64/data-descriptor details and the `exportContent`/`importContent` signatures
  belong to the build.
