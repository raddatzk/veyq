# UC: Drop box — guests send files, each into a folder of its own

- **ID:** uc.files.drop-box
- **Primary actor:** A **guest** holding a drop box's `WRITE_ONLY` link, with no account.
- **Secondary actor:** The **members** of the scope, who create the drop box, hand out its links and open what
  arrived.
- **Goal:** Collect files from people outside the space — each sender sees only what it sent, the members see
  everything, nobody approves anything.
- **Preconditions:** A member created a drop box (a `FILES_DROPBOX` node at the scope root or in a folder) and minted
  a `WRITE_ONLY` link on it.
- **Postconditions (success):** every guest identity that sent something has exactly one identity folder in the
  drop box, holding its files; the members open it; revoking the link keeps all of it. The keys and the server's part
  are [docs/apps/files.md](../../../apps/files.md) § The drop box and
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 11.

## Main flow

1. The guest opens the link and names itself once; its device keeps the identity, its token, its identity key and the
   name, and the page says whose session it is ("Sending as …").
2. With the first file, the client asks for the guest's folder (`ensureShareDropFolder`) — a guest that names itself
   and sends nothing leaves no folder: the first call creates it from the guest's
   ciphertext (name and key sealed under the guest's identity key), every later call — a reload, a second tab, a
   concurrent first upload — answers the same folder.
3. Per file the client creates a `FILE` inside that folder, uploads its blob and completes it, as a contributor does
   ([uc.files.share-folder-files](uc.files.share-folder-files.md) steps 3–4).
4. The guest sees the files it sent in this session — nothing of the drop box or of other guests.
5. A member opens the drop box: one folder per guest (a repeated guest name numbered at display time), each opened
   through the drop box's key → its drop key → the guest's identity key; inside, the files are read-only (download).
6. A member deletes a guest's folder (to the trash) or moves it out of the drop box; moving out ends its link to the
   guest identity.

## Alternate / exception flows

- **E1 — a guest names a node outside its own folder** (beside the folders, another guest's folder or file): refused.
- **E1a — a guest creates anything but a `FILE` in its own folder** (a sub-folder, any other kind): refused
  (`illegal_scope`) — a drop-box guest sends files, nothing else.
- **E2 — a member (or a `READ_WRITE` guest of a link above) creates, moves or renames into a guest's folder or the drop
  box:** refused (`illegal_structure`).
- **E3 — the link is revoked or deleted:** the drop box, its folders, files and the identities that open them stay.
- **E4 — the guest's folder is in the trash:** its next call and creates are refused; the guest starts over under a new
  identity (the page offers it), a member may restore the folder.
- **A4a — someone else uses the same device** ("Not you?"): the page forgets the session on that device and asks for a
  name again — a new identity and, with its first file, a new folder; the earlier one's folder stays as it was.
- **E5 — a guest-sealed folder entry does not open:** that folder is left out of the member's tree; the rest opens.
- **E6 — a `WRITE_ONLY` link on a plain folder, or another role on a drop box:** refused (`ShareRoots`).
- **A7 — the trashed drop box is purged:** its folders and files go, then the identities nothing names any more.
- **A8 — a guest of a `READ` or `READ_WRITE` folder link above the drop box opens it:** it sees what a member sees —
  the guests' folders and their files, with the download — read-only: nothing to send, replace, share, delete or move
  (owner decision 2026-10-08, inbox #380; one view for both, inbox #401). Two folders of one name may be numbered
  differently than for a member (the link serves no creation time).

## Quality notes

- **What the server learns** beyond a file share: how many identities sent something, which folder is whose, and when
  (docs/apps/files.md § Operator-visible).
- **No approval step:** whoever holds the drop box's key reads everything in it.
- **A guest who loses its device data** cannot reach its folder again and starts a new one.
- Where the build differs from ADR 0080 decision 11 is inbox #377, for the owner to review.

## What this means for you / Was das für dich bedeutet

**EN —** A drop box is a place where others can hand you files without seeing anything else. Send someone the link:
they type a name, pick their files, done — no account. Each person gets their own folder, so nobody sees what others
sent, and you and the other members of your space see everything in one place. The files are encrypted in the sender's
browser; the person running the server can't read them or their names. Switching the link off keeps everything that
already arrived.

**DE —** Ein Briefkasten ist ein Ort, an dem dir andere Dateien geben können, ohne sonst etwas zu sehen. Schick jemandem
den Link: Name eintippen, Dateien auswählen, fertig — ohne Konto. Jede Person bekommt einen eigenen Ordner, niemand sieht,
was andere geschickt haben, und du und die anderen Mitglieder deines Bereichs seht alles an einem Ort. Die Dateien werden
im Browser der sendenden Person verschlüsselt; wer den Server betreibt, kann weder sie noch ihre Namen lesen. Schaltest du
den Link ab, bleibt alles erhalten, was schon angekommen ist.

## Related

- End-user view (wiki): [Sharing with people who have no account](../../../../wiki/en/sharing.md)
- **Feature:** [files](../_index.md); the application's description [docs/apps/files.md](../../../apps/files.md).
- **Use-cases:** [uc.files.share-folder-files](uc.files.share-folder-files.md),
  [uc.sharing.share-uploader](../../sharing/use-cases/uc.sharing.share-uploader.md) (Memories' write-only link, whose
  drop key this reuses).
- **Decisions:** [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 11, [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) § the drop
  key.
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
