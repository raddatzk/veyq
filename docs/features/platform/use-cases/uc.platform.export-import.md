# UC: Take a node and everything under it out as a file, and bring such a file back in

- **ID:** uc.platform.export-import
- **Status:** built 2026-10-13, Download and the tree layout, Upload folder and the drop box's download 2026-10-14 ([ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md)), web, plus one small backend operation, the quota preflight (`checkTenantStorageFit`); the file format is [`contracts/export/veyq-subtree.schema.json`](../../../../contracts/export/veyq-subtree.schema.json). The quota preflight was tried against the real backend with a real quota: at 20 000 bytes the dialog said there was no room and kept Import shut, at 100 MB it let the import start, and past ten questions in a short time the server answered `429`. Download and import (format version 2) were seen in a real browser on 2026-10-14: a folder holding a Memory of six photos and a file downloaded as a ZIP that `unzip` unpacks as the tree with every SHA-256 matching; that ZIP dropped back offered Import / Upload as a file and imported as "Archiv (2)", the unpacked directory dropped back imported as "Archiv (3)" with the same tree, Cancel wrote nothing, "Upload as a file" uploaded it as a file, a ZIP with an unknown application imported the rest and named what it left out, and a tampered one was refused with its reason. Upload folder and the drop box were seen in a real browser on 2026-10-14: a directory picked through **New → Upload folder…** (the picker driven with `File`s carrying `webkitRelativePath`) was uploaded as its tree with safe names (`a:b.txt` as `a_b.txt`, `img.TXT` beside `IMG.txt` as `img (2).TXT`) and its top folder numbered among the siblings (`Reisen (2)`), after the quota preflight; a picked unpacked download opened the import (Import only) and imported; a dropped directory without the manifest was uploaded as its tree; a `READ_WRITE` guest's picked directory holding `.veyq/manifest.json` was uploaded as folders and files, never imported; a drop box with two guests both named "Dr. Anna" downloaded as `Postfach/Dr. Anna/` and `Postfach/Dr. Anna (2)/` and re-imported as plain folders of those names; and a `READ` guest of a folder link holding a Memory, files and that drop box downloaded a ZIP that `unzip` unpacks as the tree (downloaded again once the Memory held a photo, its listing showed it under `Lissabon/`). Lane L1 drives the rest in Chromium, green in its local runs at the V1 heavy stage (`tests/e2e/tests/features/platform/export-import.spec.ts`): a Memory with an uploaded cover and two GPS-tagged photos and a Files tree three levels deep round trip, five altered downloads are refused with their reasons, a payload whose SHA-256 no longer matches stops the import, and both progress bars move. **Not yet seen:** a directory chosen in the operating system's own picker (the browser pane cannot drive it), a multi-GB export, a folder upload refused by the preflight (unit-tested), and the dialog's own look while a `429` leaves the question unanswered (unit-tested, not read in the browser).
- **Primary actor:** A member whose keys are unlocked; for the download also a `READ` / `READ_WRITE` share guest (E6).
- **Goal:** Get a folder tree, a file, a Memory with its chapters out as one ZIP that outlives veyq and can be handed to someone, and get such a file back in, as new content that belongs to the importer.
- **Preconditions:** Signed in and unlocked. Download: a folder, a Memory, a chapter or a drop box as a ZIP; a file downloads as itself. Import: a place where the member may create (not a read-only scope, not a guest's view).
- **Postconditions (success):** Download: one ZIP on the member's disk, written by the browser while the content was decrypted on the device. Import: a new node tree under the folder on screen, sealed under the importer's keys, uploaded by the importer.

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

1. The member uploads a ZIP (picked for "Upload files", or dropped onto the level) or a directory (picked for
   **New → Upload folder…**, or dropped onto the level). It is
   recognised **by its content** — a ZIP holding a veyq manifest (`.veyq/manifest.json`, or a version 1 archive's
   `manifest.json`), or a directory holding `.veyq/manifest.json` at its root — never by its name. A ZIP offers **Import** or
   **Upload as a file**, a directory **Import**; one import per pick or drop, every other file is uploaded at once. Anything
   else is an ordinary upload; a directory without the manifest is uploaded as its tree, a folder per directory and a file
   per file, every name made safe as an archive's paths are and a taken one numbered `Name (2)`, after the same quota
   preflight for the tree's bytes (inbox #424; a folder that cannot be created is named and nothing below it is sent, a
   file that fails is the upload's per-file failure). There is no separate "Import…" entry: upload recognises an archive
   itself (owner decision 2026-10-14).
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
- **E5 — a drop box:** downloads as a directory with each guest's folder under it, under the name the drop box shows it by
  (a repeated name numbered, `Anna (2)`), and imports as a **plain folder** with plain folders inside: no drop key, no
  links, no guest identities (nothing can rebuild a guest's folder or identity; owner decision 2026-10-14). Its own guest
  (`WRITE_ONLY`) downloads nothing. A node a kind leaves out of its download is listed in the dialog.
- **E6 — a share guest:** a `READ` or `READ_WRITE` guest **downloads** what it sees, a ZIP for a folder, a Memory or a drop
  box, as a member does (ADR 0082 decision 5); no guest imports: a recognised archive is an ordinary upload for a guest,
  and a `READ_WRITE` guest's directory is always uploaded as its tree, its `.veyq/` included as files.
- **E8 — a tampered or malformed archive:** a manifest that fails its schema, a path or name outside the rules, an offset
  outside the file, overlapping entries, a declared size that is not the actual one, a field over the API's limit: refused
  before anything is written, with the reason (ADR 0082 decision 10).
- **E7 — not enough room:** the preflight (`POST /api/tenants/{tenantId}/storage-fit`, any member, yes or no only) says no: the dialog says there is not enough storage space and offers no import. A question the server could not answer (a rate limit, an outage, an older server) never blocks: the upload that follows decides, and a full quota still ends the import with a rollback (E3).
- **E9 — a download that fails while it is written** (the browser fails it, or the Service Worker's download route refuses
  a stream that is not the announced length): the dialog says the download failed and offers **Save as ZIP…** again; it
  never stays at "Writing the file…" and never reports the file saved. Without the file picker the browser's own download
  is told the ZIP's exact length up front, its `Content-Length`, so it can show progress against the whole (ADR 0082, ADR
  0047 § The download route; F11, unit-tested; the stack lane's `export-import` spec is its browser proof).
- **E10 — another member rotates the space while the import runs:** what the import already created stays at the epoch
  it started with, so its next write there - a node below it, a medium, a cover, a Pot's records or a receipt, a file's
  content - is refused until that node is re-keyed. Each such write brings the node forward on the importer's own device
  and is sealed again under the node's new key, and the import goes on; nothing is created twice. Below a drop box it
  waits for an owner's device, and only if none comes is the import rolled back (E3)
  ([uc.spaces.rekey-after-rotation](../../spaces/use-cases/uc.spaces.rekey-after-rotation.md) E9, ADR 0111 decision 11).

## What an import does not carry, by design

Ids, keys and wraps (all fresh), the server-observed times, favourites, shares, members and the trash. The original contributor of a medium and a Memory's participants stay in the file as **names only** (a guest's download carries neither: a guest never reads the member directory). A coordinate comes back only if the photo file still carries it; the archive keeps each original's bytes, its own location tags included ([ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md) decisions 4 and 6).

## Quality notes

- **Operator-blind.** The server sees nothing of an export: the client decrypts, walks and writes. There is no export endpoint. An import is a sequence of ordinary node and blob writes.
- **No memory wall.** One decrypted chunk window at a time on export; the import reads the ZIP by slice. Checked with a 4.5 GiB entry (ZIP64) against `unzip` and Python's `zipfile`.
- **The check reads each payload once, off the page, one payload ahead.** A payload's SHA-256 and a ZIP entry's CRC-32
  are one pass over its bytes in core's digest worker, and the next payload's pass runs while the current one uploads,
  never two at once (inbox #557). Read in the profile's S4 (`scripts/profile_hot_paths.py`: a download of 50 photos of
  5.2 MB imported, three runs each without the CPU profiler, 2026-10-29): 15.8 s against 20.2 s at throttle 1 and 17.2 s
  against 35.1 s at throttle 4. The main thread had spent 3.5 s (14.6 s at throttle 4) on the SHA-256 and 0.5 s (1.9 s)
  on the CRC-32; its tasks fell from 5.5 s to 1.3 s (21.0 s to 3.3 s), and the worker spends 4.5 s on both. What is
  left between two uploads is the original's own preparation (its tiers, 154 ms a photo), which the import does not yet
  overlap with the previous upload as a member's upload does (inbox #784).
- **Not atomic.** The server has no multi-node transaction; the rollback is best effort and says so when it fails.
- **Quota, without exposing it.** The preflight is the rule an upload start applies, asked without a lock: the tenant's usage (trash and open uploads included) plus the declared bytes against the quota the Server Owner set; a tenant with no quota fits anything. It answers **yes or no, never a number**, so a member learns no more than a refused upload already tells; because a long run of questions could still be bisected towards the free space, it is rate-limited per member and per tenant (`veyq.ratelimit.storage-fit.*`, default 10 and 60 per minute, `429` with `Retry-After`). It is advisory: another upload may take the room first, and the start stays the one decision.

## User impact

You can take anything out of veyq as one ordinary ZIP that outlives veyq, and bring such a file back into this or
another veyq as your own content.

## Related

- Feature: [Platform](../_index.md)
- End-user view (docs site): [Download and import](../../../../site/src/content/docs/spaces/download-and-import.md) · [Herunterladen und Importieren](../../../../site/src/content/docs/de/spaces/download-and-import.md)
- Use-cases: [uc.platform.node-cover](uc.platform.node-cover.md), [uc.memory.participants](../../memory/use-cases/uc.memory.participants.md), [uc.memory.upload](../../memory/use-cases/uc.memory.upload.md)
- Decisions: [ADR 0082](../../../decisions/0082-a-node-and-everything-under-it-exports-as-a-plaintext-zip-and-imports-under-the-importer.md), [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md), [ADR 0083](../../../decisions/0083-memory-participants-are-a-sealed-list-in-the-memorys-metadata.md)
