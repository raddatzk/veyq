# UC: Files on a shared folder — guests download, contributors add and replace

- **ID:** uc.depot.share-folder-files
- **Primary actor:** A **guest** holding a folder's public link — `READ` or `READ_WRITE` — with no account.
- **Secondary actor:** The **member** who shared the folder and sees what contributors added.
- **Goal:** Hand someone a folder's files through one link; with a contributor link, let them add files and put new
  versions in place.
- **Preconditions:** A member shared a `FOLDER` (`READ` or `READ_WRITE`, core's folder share dialog); the guest opened
  the link and passed its password and terms if any.
- **Postconditions (success):** the guest sees every live file of the shared subtree with name, size and date, and
  downloads it byte for byte; a contributor's new or replaced file is a `FILE` in the subtree, its blob and its sealed
  metadata written together, counted against the link's storage limit. The role matrix and the contract are
  [docs/apps/depot.md](../../../apps/depot.md) § Guests.

## Main flow

1. The guest opens the link; core's folder page renders Depot's section at every folder of the subtree — the member's
   own section, with what the guest's role does not allow removed (inbox #401): its files with name, size and date,
   opened under the keys the link's chain gives.
2. **Download:** the client presigns through the link (`resolveShareBlob`, the share's subtree gate) and decrypts in
   the browser.
3. **Add (contributor):** the guest names itself once per link, picks files; per file the client creates the `FILE`
   under the folder on screen (core's `createShareNode`), uploads through core's guest transport into that node, and
   completes with `completeShareFile`, which carries the file's sealed metadata.
4. The server re-checks the link under the scope lock, attaches the blob and writes the metadata in one transaction.
5. **Replace (contributor):** an upload of a name a file of the folder already holds — or the opened file's replace, a
   header action of its page — asks first, then uploads into that file, completing with the blob the guest saw; any
   file of the subtree, a member's too. A row offers no replace (owner decision 2026-10-14, reversing inbox #392's
   refusal of a guest's same-name upload).
6. The member sees the new file and its metadata in the folder, like any other.

## Alternate / exception flows

- **E1 — a `READ` guest writes:** refused; the page offers no upload or replace.
- **E2 — a stale replace:** `409 stale_file` with the current blob; no metadata is written. The page says someone else
  replaced the file and offers "replace anyway" on the current blob, as it does a member.
- **E3 — the file was trashed, moved out of the subtree, or the link revoked between start and completion:** the
  completion is refused (`upload_not_found` / `node_not_found`), nothing is attached.
- **E4 — a trashed file** (or one under a trashed folder): not listed, its blob `404`, an upload into it `404`.
- **E5 — the link's storage limit is reached:** refused at the exact byte; the page says the link is full.
- **E6 — a name a readable sibling already holds:** a file's is the replace of step 5, after the question; a folder's,
  a Memory's or a drop box's is refused before anything is sent, the sibling named by its kind. A sibling the guest
  could not see is the server's `409 slug_taken`, said in the generic words.
- **E7 — the target is a folder, outside the subtree, or the role is not `FILE_CONTENT`:** refused.
- **A8 — the link is deleted:** a guest's identity stays while a file names it, so the member still knows who added
  it.

## Quality notes

- A file can also be shared **on its own** ([uc.depot.share-file](uc.depot.share-file.md)); a file *inside* a folder link
  opens to the same file view (a row's info button).
- A guest **deletes nothing** (nodes store no authorship) and the page offers no rename; a contributor moves a file
  between the subtree's folders through core's move dialog ([uc.memory.move-node](../../memory/use-cases/uc.memory.move-node.md)).
- The metadata is the blob's, declared with the upload and written in the attach transaction, for a guest as for a member (docs/apps/depot.md § How a file is stored).

## What this means for you / Was das für dich bedeutet

**EN —** Share a folder and the files in it come along. With a view link, people can download them; with a
contributor link, they can also add files or put a newer version in place — without an account. Everything stays
encrypted end to end: the files are decrypted only in the browser of whoever holds the link, and the person running
the server sees neither contents nor names. Anything a guest adds counts against the space you allowed that link, and
it stays yours when you switch the link off.

**DE —** Teilst du einen Ordner, gehen die Dateien darin mit. Mit einem Ansichtslink können andere sie herunterladen,
mit einem Mitmach-Link auch Dateien hinzufügen oder eine neuere Fassung ablegen — ohne Konto. Alles bleibt Ende-zu-Ende
verschlüsselt: Entschlüsselt wird nur im Browser derer, die den Link haben, und wer den Server betreibt, sieht weder
Inhalte noch Namen. Was Gäste hinzufügen, zählt gegen den Platz, den du dem Link erlaubt hast, und bleibt bei dir, wenn
du den Link abschaltest.

## Related

- End-user view (docs site): [Sharing with people who have no account](../../../../site/src/content/docs/sharing/index.mdx)
- **Feature:** [files](../_index.md); the application's description [docs/apps/depot.md](../../../apps/depot.md).
- **Use-cases:** [uc.depot.member-files](uc.depot.member-files.md),
  [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (core's link, gate and guest
  identity), [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md).
- **Decisions:** [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6 (the role matrix),
  [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) § Application guest
  operations.
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
