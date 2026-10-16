# UC: Drop box — guests send files, each into a folder of its own

- **ID:** uc.depot.drop-box
- **Primary actor:** A **guest** holding a drop box's `WRITE_ONLY` link, with no account.
- **Secondary actor:** The **members** of the scope, who create the drop box, hand out its links and open what
  arrived.
- **Goal:** Collect files from people outside the space — each sender sees only what it sent, the members see
  everything, nobody approves anything.
- **Preconditions:** A member created a drop box (a `DEPOT_DROPBOX` node at the scope root or in a folder) and minted
  a `WRITE_ONLY` link on it.
- **Postconditions (success):** every guest identity that sent something has exactly one identity folder in the
  drop box, holding its files; the members open it; revoking the link keeps all of it. The keys and the server's part
  are [docs/apps/depot.md](../../../apps/depot.md) § The drop box and
  [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) decision 11.

## Main flow

1. The guest opens the link and names itself once; its device keeps the identity, its token, its identity key and the
   name, and the page says whose session it is ("Sending as …").
2. With the first file, the client asks for the guest's folder (`ensureShareDropFolder`) — a guest that names itself
   and sends nothing leaves no folder: the first call creates it from the guest's
   ciphertext (the key wrapped under the guest's identity key, the name under a field key derived from it), every later call — a reload, a second tab, a
   concurrent first upload — answers the same folder.
3. Per file the client creates a `FILE` inside that folder, uploads its blob and completes it, as a contributor does
   ([uc.depot.share-folder-files](uc.depot.share-folder-files.md) steps 3–4).
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
- **E9 — a file is refused or given up** (the link's storage limit is reached, the guest cancels): the empty file its
  create made in the guest's folder is taken back and erased (`abandonShareNode`), so nothing of it is kept; a guest
  takes back nothing in another guest's folder, nor its folder itself, nor a file that holds content, nor one whose
  name its identity did not write last (ADR 0093 § 8).

## Quality notes

- **What the server learns** beyond a file share: how many identities sent something, which folder is whose, and when
  (docs/apps/depot.md § Operator-visible).
- **No approval step:** whoever holds the drop box's key reads everything in it.
- **A guest who loses its device data** cannot reach its folder again and starts a new one.
- Where the build differs from ADR 0080 decision 11 is inbox #377, for the owner to review.

## User impact

People without an account can hand you files through a link, each into a folder of their own, without seeing anything
else in your space.

## Related

- End-user view (docs site): [Sharing with people who have no account](../../../../site/src/content/docs/sharing/index.mdx)
- End-user view (docs site): [Collect files with a drop box](../../../../site/src/content/docs/depot/drop-boxes.mdx)
- **Feature:** [files](../_index.md); the application's description [docs/apps/depot.md](../../../apps/depot.md).
- **Enabler:** [en.platform.field-editor](../../platform/enablers/en.platform.field-editor.md) (planned: the last editor of a name, which narrows the take-back to a guest's own placeholder, ADR 0080 decision 6).
- **Use-cases:** [uc.depot.share-folder-files](uc.depot.share-folder-files.md),
  [uc.sharing.share-uploader](../../sharing/use-cases/uc.sharing.share-uploader.md) (Memories' write-only link, whose
  drop key this reuses).
- **Decisions:** [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 11, [ADR 0079](../../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) § the drop
  key.
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
- End-user view (docs site): [Ask people to add photos](../../../../site/src/content/docs/sharing/request-uploads.mdx)
