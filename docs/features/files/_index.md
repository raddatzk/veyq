# Feature: Files

The **Files** application's user-facing flows: a member's file tree, files on a shared folder, one shared file, and the drop box.
What a file *is* — its node, its one blob, its sealed metadata, its contract and the operator-visible metadata — is
the application's description, [docs/apps/files.md](../../apps/files.md); the use-cases below say what a person does
with it and point there instead of repeating it.

- **Decisions:** [ADR 0079](../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (Files is
  an application on core's nodes, attachments, quota and links),
  [ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) (every node
  carries a key; decision 6 the guest role matrix, decision 11 the drop box, § Decided with the owner the
  file-name folding), [ADR 0077](../../decisions/0077-a-write-carries-the-version-it-read.md) (a replace carries the
  blob it read), [ADR 0073](../../decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md) (the quota, and a link's storage limit).
- **Cross-cutting:** [security-model](../../crosscutting/security-model.md) (the node `kind` is the one fact an
  application adds to what the operator sees; Files' own list is in its description).

## Use-cases

- [uc.files.member-files](use-cases/uc.files.member-files.md) — **built (web)**: a member uploads files into a folder
  or the scope root, downloads, replaces, renames and deletes them.
- [uc.files.share-folder-files](use-cases/uc.files.share-folder-files.md) — **built (web)**: a folder link carries
  its files — a `READ` guest downloads them, a `READ_WRITE` guest also adds and replaces files.
- [uc.files.share-file](use-cases/uc.files.share-file.md) — **built (web)**: one file, shared on its own — a `READ` guest
  sees and downloads it, a `READ_WRITE` guest also replaces its content.
- [uc.files.view-file](use-cases/uc.files.view-file.md) — **built (web)**: an opened file is shown on its page (image,
  text, video, audio; full screen), a member's and a guest's alike, or comes with a note and the download.
- [uc.files.drop-box](use-cases/uc.files.drop-box.md) — **built (web)**: guests send files into a drop box, each
  into a folder of its own, and the members open them.
