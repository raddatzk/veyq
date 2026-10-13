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
streamable) ZIP holding `manifest.json` and `data/<node-id>/<file>` payloads. The manifest — `format: "veyq-subtree"`,
`version`, `exportedAt`, `appVersion`, the options chosen, and the tree (each node: archive-local `id`, `kind`, `name`,
`cover`, `metadata`, the kind's `content` reference, `children`) — is specified by
[`contracts/export/veyq-subtree.schema.json`](../../contracts/export/veyq-subtree.schema.json); a worked example is
below. The writer and reader are **hand-written and streaming**, with **no new dependency**: the writer emits local
headers with the data-descriptor flag and a central directory at the end; the reader finds the central directory from the
end and reads entries with `Blob.slice`, so an import never holds the file in memory. Payload paths come from the manifest
only and are refused if they leave `data/` (no `..`, no absolute path); each payload's `sha256` is verified while
streaming. ZIP64 is required from the start (a subtree exceeds 4 GiB).

**4. The file is plaintext, and says so.** The export dialog warns that anyone holding the file can read it. GPS and EXIF
are **kept by default** and an **opt-out at export time** (`options.includeLocation: false` drops location fields and GPS
tags; the fields are then absent).

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
`manifest.json` whose `format` matches — never by file name. A dropped archive in a Files folder offers **Import** to
members only: never on a guest view (any role), so for a guest — a `WRITE_ONLY` one included — it stays an ordinary upload.

**8. Failure and atomicity.** The server offers no multi-node atomicity. An import is a sequence of ordinary writes; one
that aborts or runs out of quota **rolls back what it created** (to the trash) and reports "N of M". A **dry-run** step
reads the manifest and shows total size against the member's quota **before anything is written**.

## Worked example

A folder "Trip 2025" holding a file and a Memory "Lisbon" with a chapter and two photos (the location of the second photo
was opted out, so it has none):

```json
{
  "format": "veyq-subtree",
  "version": 1,
  "exportedAt": "2026-10-13T09:30:00Z",
  "appVersion": "0.9.0",
  "options": { "includeLocation": true },
  "root": {
    "id": "n1", "kind": "FOLDER", "name": "Trip 2025", "cover": null, "metadata": {},
    "children": [
      {
        "id": "n2", "kind": "FILE", "name": "itinerary.pdf", "metadata": {},
        "content": {
          "type": "files.file", "schema": 1,
          "file": { "path": "data/n2/itinerary.pdf", "size": 48213, "mediaType": "application/pdf",
                    "sha256": "9f2c0c4e1b7a5d3e8a6f4b2c1d0e9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e" }
        }
      },
      {
        "id": "n3", "kind": "MEMORY", "name": "Lisbon",
        "cover": { "path": "data/n3/cover.jpg", "size": 90211, "mediaType": "image/jpeg" },
        "metadata": { "description": "Four days in September" },
        "content": {
          "type": "memories.memory", "schema": 1,
          "media": [
            { "file": { "path": "data/n3/IMG_0412.jpg", "size": 3811920, "mediaType": "image/jpeg",
                        "sha256": "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809" },
              "name": "IMG_0412.jpg", "chapter": "n4", "capturedAt": "2025-09-14T16:02:11Z",
              "location": { "lat": 38.7139, "lon": -9.1394, "place": "Lisbon" },
              "exif": { "Make": "Fujifilm", "Model": "X-T5", "FNumber": 4 },
              "contributor": "Anna" },
            { "file": { "path": "data/n3/IMG_0413.jpg", "size": 4120377, "mediaType": "image/jpeg",
                        "sha256": "f0e1d2c3b4a5968778695a4b3c2d1e0ff0e1d2c3b4a5968778695a4b3c2d1e0f" },
              "name": "IMG_0413.jpg", "chapter": "n4", "capturedAt": "2025-09-14T16:05:40Z",
              "contributor": "Kevin" }
          ]
        },
        "children": [
          { "id": "n4", "kind": "CHAPTER", "name": "Alfama", "metadata": {}, "children": [] }
        ]
      }
    ]
  }
}
```

## Consequences

- **A new core surface in the web**: the zip writer/reader, the walk, the dialogs (export with the plaintext warning and
  the location opt-out; import with the dry-run), the `KindDescriptor` hook, and the drop detection in the Files level.
  Nothing in the backend or `contracts/api/` — the client uses the existing node, blob and quota endpoints. The schema
  under `contracts/export/` is the one new contract artefact.
- **The format is a public promise.** `version` bumps only for a change an old reader would misread; kinds version their
  own `content.schema`. A reader that tolerates additions keeps old files importable.
- **The plaintext file is outside the E2EE guarantee by design**; the operator never sees it, but the member's disk,
  mail or cloud does. The warning is the only mitigation; an encrypted variant is deliberately not built.
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
  belong to the CR; the manifest's `name` for payload files on a name clash inside one node (a suffix is expected).
