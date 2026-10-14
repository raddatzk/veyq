# UC: Take a node and everything under it out as a file, and bring such a file back in

- **ID:** uc.platform.export-import
- **Status:** built 2026-10-13 ([ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)), web, plus one small backend operation, the quota preflight (`checkTenantStorageFit`); the file format is [`contracts/export/veyq-subtree.schema.json`](../../../../contracts/export/veyq-subtree.schema.json). Seen working in a real browser with a Memory of two photos: exported, imported again as "Urlaub (2)" with both photos, the date and the place. The quota preflight was tried against the real backend with a real quota: at 20 000 bytes the dialog said there was no room and kept Import shut, at 100 MB it let the import start, and past ten questions in a short time the server answered `429`. The export dialog was also seen in dark mode at 375 px (no overflow, readable); the import dialog was only measured there (same width, no overflow), not seen. **Not yet seen:** a multi-GB export, a Memory with GPS-tagged photos, a Files folder tree, an uploaded cover, the failure messages and the progress bar, and the dialog's own look while a `429` leaves the question unanswered (unit-tested, not read in the browser).
- **Primary actor:** A member whose keys are unlocked.
- **Goal:** Get a folder tree, a file, a Memory with its chapters out as one ZIP that outlives veyq and can be handed to someone, and get such a file back in, as new content that belongs to the importer.
- **Preconditions:** Signed in and unlocked. Export: any node of a kind that can be exported (a folder, a file, a Memory, a chapter). Import: a place where the member may create (not a read-only scope, not a guest's view).
- **Postconditions (success):** Export: one ZIP on the member's disk, written by the browser while the content was decrypted on the device. Import: a new node tree under the folder on screen, sealed under the importer's keys, uploaded by the importer.

## Main flow — download (formerly "export")

1. The member chooses **Download** on a node: in its row's ⋯ menu, in the folder's header or on the node's own page. A
   file downloads as itself; a folder, a Memory, a chapter or a drop box as a ZIP (owner decision 2026-10-14: there is no
   separate "Export"). The ZIP asks no question: no plaintext warning, no location option (ADR 0082 decision 4).
2. The client walks the subtree with the keys the member already holds and runs each kind's export hook. The dialog then says how many items and files and how many bytes, and lists what a hook left out.
3. **Save as ZIP…** asks the browser where to save (the first thing the click does, because the picker needs the click) and streams the content into the file: the archive is never held in memory.
4. The ZIP unpacks as the tree — a directory per node, each file under its own name, names made safe for every file
   system — with `.veyq/manifest.json` beside them listing the tree, each node's path and a SHA-256 for every file
   (ADR 0082 decision 3).

## Main flow — import

1. The member uploads a ZIP or picks a directory (or drops either onto the level). It is recognised **by its content** — a
   ZIP or a directory holding `.veyq/manifest.json` that says `veyq-subtree` — never by its name, and the member is offered
   **Import** or **Upload as a file**. Anything else is an ordinary upload (a directory: a folder upload, inbox #424).
2. The dialog reads the manifest and says what would be created, how big it is and where it goes; what this version cannot
   import is listed — a kind of an application this instance does not have, or newer data of one it has — with **Import the
   rest** or **Cancel** (ADR 0082 decision 9).
3. Before anything is written the dialog asks the server one question - would this many bytes still fit the tenant's quota? - and a **no** says so and keeps **Import** shut (E7). **Import** creates the nodes and uploads the files through the ordinary upload. Every file is checked against its SHA-256 and the ZIP's own checksum before it is uploaded. A name that is taken among the siblings becomes `Name (2)`.
4. The importer is the owner and uploader of everything imported; the tree reloads.

## Alternate / exception flows

- **E1 — a file that is not an export, or is from a newer veyq:** the dialog says so and imports nothing.
- **E2 — a damaged archive** (a checksum does not match, a payload is missing): the import stops, what it created is moved to the trash, and the dialog says how far it got.
- **E3 — storage or any other stop part-way:** the same: the top-level node created is moved to the trash (its subtree with it), the message says "N of M".
- **E4 — a browser that cannot save a file this large** (no file picker, no download route): the dialog says so before anything is written; use a Chromium-based browser on a computer.
- **E5 — a node a kind cannot export** (a drop box, a kind this build does not know): left out with its subtree and listed.
- **E6 — a share guest:** a `READ` or `READ_WRITE` guest **downloads** what it sees, a ZIP for a folder or a Memory, as a
  member does (ADR 0082 decision 5); no guest imports, and a recognised archive is an ordinary upload for a guest.
- **E8 — a tampered or malformed archive:** a manifest that fails its schema, a path or name outside the rules, an offset
  outside the file, overlapping entries, a declared size that is not the actual one, a field over the API's limit: refused
  before anything is written, with the reason (ADR 0082 decision 10).
- **E7 — not enough room:** the preflight (`POST /api/tenants/{tenantId}/storage-fit`, any member, yes or no only) says no: the dialog says there is not enough storage space and offers no import. A question the server could not answer (a rate limit, an outage, an older server) never blocks: the upload that follows decides, and a full quota still ends the import with a rollback (E3).

## What an import does not carry, by design

Ids, keys and wraps (all fresh), the server-observed times, favourites, shares, members and the trash. The original contributor of a medium and a Memory's participants stay in the file as **names only**. A coordinate comes back only if the photo file still carries it; "Include places and coordinates" off drops what veyq adds to the manifest but **cannot remove the location tags inside a photo file** ([ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md) addendum).

## Quality notes

- **Operator-blind.** The server sees nothing of an export: the client decrypts, walks and writes. There is no export endpoint. An import is a sequence of ordinary node and blob writes.
- **No memory wall.** One decrypted chunk window at a time on export; the import reads the ZIP by slice. Checked with a 4.5 GiB entry (ZIP64) against `unzip` and Python's `zipfile`.
- **Not atomic.** The server has no multi-node transaction; the rollback is best effort and says so when it fails.
- **Quota, without exposing it.** The preflight is the rule an upload start applies, asked without a lock: the tenant's usage (trash and open uploads included) plus the declared bytes against the quota the Server Owner set; a tenant with no quota fits anything. It answers **yes or no, never a number**, so a member learns no more than a refused upload already tells; because a long run of questions could still be bisected towards the free space, it is rate-limited per member and per tenant (`veyq.ratelimit.storage-fit.*`, default 10 and 60 per minute, `429` with `Retry-After`). It is advisory: another upload may take the room first, and the start stays the one decision.

## What this means for you / Was das für dich bedeutet

**EN —** You can take any folder, file or memory out of veyq as one ZIP file — the photos, the names, the dates and places — and bring such a file back in, into the same or another veyq. The file is **not encrypted**: whoever has it can read everything in it, so keep it somewhere you trust. Everything is prepared on your own device; the server never sees the contents. Coming back in, everything becomes yours; shares and members do not travel with it.

**DE —** Du kannst jeden Ordner, jede Datei und jede Erinnerung als eine ZIP-Datei aus veyq herausnehmen — mit Fotos, Namen, Daten und Orten — und eine solche Datei wieder hineinladen, in dasselbe oder ein anderes veyq. Die Datei ist **nicht verschlüsselt**: Wer sie hat, kann alles darin lesen, bewahre sie also an einem Ort auf, dem du vertraust. Alles wird auf deinem eigenen Gerät vorbereitet; der Server sieht die Inhalte nie. Beim Hineinladen wird alles deins; Freigaben und Mitglieder reisen nicht mit.

## Related

- Feature: [Platform](../_index.md)
- End-user view (wiki): [Export and import](../../../../wiki/en/export-and-import.md) · [Export und Import](../../../../wiki/de/export-und-import.md)
- Use-cases: [uc.platform.node-cover](uc.platform.node-cover.md), [uc.memory.participants](../../memory/use-cases/uc.memory.participants.md), [uc.memory.upload](../../memory/use-cases/uc.memory.upload.md)
- Decisions: [ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md), [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md), [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)
