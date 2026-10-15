# UC: Share one file — a guest sees, downloads and (as a contributor) replaces that file

- **ID:** uc.files.share-file
- **Primary actor:** A **guest** holding the public link of one file — `READ` or `READ_WRITE` — with no account.
- **Secondary actor:** The **member** who shared the file.
- **Goal:** Hand someone exactly one file through a link, with nothing of the folder it lives in; with a contributor
  link, let them put a newer version in place.
- **Preconditions:** A member opened a `FILE` and made a link on it (Files' share dialog, `READ` or `READ_WRITE`); the
  guest opened the link and passed its password and terms if any.
- **Postconditions (success):** the guest sees the member's own file view — the file's name, size, type and date, opened
  under the key the link carries — and downloads it byte for byte; a contributor's replace is a new blob in the file's
  slot with its own sealed metadata, counted against the link's storage limit. The role matrix is
  [docs/apps/files.md](../../../apps/files.md) § Guests.

## Main flow

1. **The member** opens a file (a row's info button in a folder's list) and makes a link from the file's header card,
   the same card, dialog and wording rules as a folder's (core's link manager; only the sentences about *what* is shared
   are the file's). The link wraps the file's own key under a fresh `K_share`; nothing above or beside the file is
   reachable from it.
2. **The guest** opens the link; core's page renders the file's view — the member's `FileDetail`, handed the guest's
   access: the file's name, size, type and date, and a download.
3. **Download:** the client presigns through the link (`resolveShareBlob`) and decrypts in the browser.
4. **Replace (contributor):** the guest chooses the replace in the file page's header and picks a file; the page asks first — the content changes for everyone who has
   the link, the owner too, and no version is kept — then names the guest once per link, uploads through core's guest
   transport and completes with `completeShareFile`, carrying the blob it saw.
5. The member sees the new content and its metadata in the file's view, like after their own replace.

## Alternate / exception flows

- **E1 — a `READ` guest writes:** refused; the page offers no replace.
- **E2 — a `WRITE_ONLY` link on a file:** never offered, and refused by the server (`422 illegal_scope`): a write-only
  guest holds no key and reads nothing back, and a file has no folder of guests to send into. A file is sent *to* through
  a drop box, not shared as one.
- **E3 — a stale replace:** `409 stale_file` with the current blob; the page offers "replace anyway", as it does a member.
- **E4 — the file is trashed:** the link serves nothing — not listed, its blob `404`, an upload refused. A node in the
  trash cannot be shared.
- **E5 — the guest asks for anything else:** a sibling, the folder above, a child of the file — none is a node of the
  link (`404` / `422`); the guest holds no key of them.
- **E6 — the link's storage limit is reached:** refused at the exact byte, as for a folder link.

## Quality notes

- **A share is the member's view with functions removed** (inbox #401): the file's page is one component for a member
  and a guest; a guest never renames, deletes or shares (nodes store no authorship), and moves a file only where it lies
  in a folder of a folder link, never the link's root.
- **Operator-blind.** The server sees the file's id, kind, sealed size and the link's role — never a name, a type or a
  key; every seal is bound to the file node (ADR 0072), so a blob listed for another node opens nothing.
- **A file inside a folder link opens to the same view**, with the folder link's role.

## What this means for you / Was das für dich bedeutet

**EN —** You can share a single file — no need to share the whole folder it sits in. With a view link, people see the
file's details and can download it; with a contributor link they can also put a newer version in place. They need no
account, everything stays encrypted end to end, and nobody can see the rest of your folder through that link.

**DE —** Du kannst eine einzelne Datei teilen — der ganze Ordner, in dem sie liegt, muss dafür nicht freigegeben
werden. Mit einem Ansichtslink sehen andere die Angaben zur Datei und können sie herunterladen; mit einem
Mitmach-Link können sie auch eine neuere Fassung ablegen. Sie brauchen kein Konto, alles bleibt Ende-zu-Ende
verschlüsselt, und über diesen Link kann niemand den Rest deines Ordners sehen.

## Related

- End-user view (docs site): [Sharing with people who have no account](../../../../site/src/content/docs/sharing.md)
- **Feature:** [files](../_index.md); the application's description [docs/apps/files.md](../../../apps/files.md).
- **Use-cases:** [uc.files.share-folder-files](uc.files.share-folder-files.md) (a folder link carries its files),
  [uc.files.member-files](uc.files.member-files.md),
  [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md).
- **Decisions:** [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decisions 1 and 6, [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md).
