# UC: Member files — upload, download, replace, rename and delete a file

- **ID:** uc.files.member-files
- **Primary actor:** A **member** of a scope with write access, signed in, the scope open (so its client holds the
  keys of the folder on screen).
- **Goal:** Keep files in the scope's folder tree beside Memories — upload them, get them back byte for byte, put a
  new version in place, rename them, and throw them away — with the server holding only ciphertext.
- **Preconditions:** A scope the member can write in; a folder (or the scope root) open in the folder level view.
- **Postconditions (success):** each picked file is a `FILE` node under that folder holding one sealed blob in its
  `FILE_CONTENT` attachment slot, with its sealed metadata (MIME type, plaintext size, modified time); a download
  returns the original bytes. The mechanism — node, blob, contract — is
  [docs/apps/files.md](../../../apps/files.md) § How a file is stored and § The contract.

## Main flow

1. The member picks one or more files (the section's upload button, or a drop onto Files' section of the level view).
   Each file becomes one job of core's upload queue; the status menu in the top bar shows its progress.
2. Per file the client creates a `FILE` node under the folder (its name and slug sealed under the folder's key, the
   slug folded as a file name: `Foto.JPG` and `foto.jpg` are the same name), plans its blob under the file node's own
   key, and pumps the chunks straight to the store.
3. The client completes the upload (`completeFile`, no expected blob: the slot is empty) — the server attaches the
   blob to the node in one transaction — and then writes the file's sealed metadata on the node.
4. The section lists the folder's files with name, size and modified time.
5. **Download:** the client presigns the blob's chunks, opens them under the file node's key and saves the file under
   its name.
6. **Replace:** a row's replace (or a picked name a file already holds, after the member confirms) uploads the new
   bytes into the same node, completing with the blob the member saw (`expectedBlobId`); the old blob is freed in the
   same transaction.
7. **Rename** opens core's name dialog; **delete** asks, then moves the node to core's trash, from which it is
   restored like any node.

## Alternate / exception flows

- **E1 — a peer replaced the file first:** the completion is `409 stale_file` with the current blob; nothing changes,
  the uploaded bytes are freed, and the section offers "replace anyway" on the blob now there.
- **E2 — the name is taken or the place illegal:** the create is refused (`slug_taken`, `illegal_structure`); no
  upload starts and the queue's automatic retry does not repeat it.
- **E3 — no space left:** the upload is refused for the quota and never retried; the section says why.
- **E4 — the metadata write fails after the bytes landed:** the file is there; the section says its metadata is
  missing and shows the plaintext size the blob gives. The two writes are not atomic (docs/apps/files.md § Open
  questions).
- **E5 — the node is not a live file of this scope** (a folder, a Memory, a trashed file, another scope's node) or
  the caller cannot write the scope: the completion is refused and nothing is attached.
- **A6 — the scope is being deleted:** the level is read-only; download only.

## Quality notes

- **Operator-blind.** The server sees ids, the opaque sizes, the `K_blob` wrap and the sealed name, slug and
  metadata — never a file name, a MIME type or a key ([docs/apps/files.md](../../../apps/files.md) § Operator-visible).
- **A replace carries what it read** ([ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md)): a
  compare-and-set on the blob id, never a blind overwrite.
- **A file is no container:** nothing is placed under a `FILE`. It is shared through a folder above it
  ([uc.files.share-folder-files](uc.files.share-folder-files.md)) or on its own
  ([uc.files.share-file](uc.files.share-file.md)).
- **The download streams** into a picked file or the Service Worker's download route, and only falls back to an
  in-memory `Blob` up to 512 MiB; a multi-gigabyte download in a real browser is unverified (inbox #371).

## What this means for you / Was das für dich bedeutet

**EN —** You can keep ordinary files — documents, PDFs, anything — in the same folders as your memories. Upload
them, download them again exactly as they were, put a newer version in place, rename them or move them to the trash.
Each file is encrypted on your device before it leaves it: the person running the server stores it but can read
neither its contents nor its name. If someone else in your space replaced the same file a moment before you, veyq
tells you instead of silently overwriting their version.

**DE —** Du kannst ganz normale Dateien — Dokumente, PDFs, alles Mögliche — in denselben Ordnern ablegen wie deine
Erinnerungen. Lade sie hoch, lade sie genau so wieder herunter, ersetze sie durch eine neuere Fassung, benenne sie um
oder lege sie in den Papierkorb. Jede Datei wird auf deinem Gerät verschlüsselt, bevor sie es verlässt: Wer den Server
betreibt, speichert sie, kann aber weder ihren Inhalt noch ihren Namen lesen. Hat jemand anderes in deinem Bereich
dieselbe Datei kurz vor dir ersetzt, sagt dir veyq das, statt seine Fassung stillschweigend zu überschreiben.

## Related

- **Feature:** [files](../_index.md); the application's description [docs/apps/files.md](../../../apps/files.md).
- **Use-cases:** [uc.files.share-folder-files](uc.files.share-folder-files.md) (the same files through a folder
  link), [uc.files.drop-box](uc.files.drop-box.md).
- **Decisions:** [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md),
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) (the node key
  and the file-name folding), [ADR 0077](../../../decisions/0077-a-write-carries-the-version-it-read.md),
  [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) (the blob key bound to node and role).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
