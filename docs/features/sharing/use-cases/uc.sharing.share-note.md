# UC: Share note — a curator attaches an editable message to a share

- **ID:** uc.sharing.share-note
- **Primary actor:** The **curator** (the person who owns/shares the Memory), signed in with the Memory open in
  their scope — so they hold **`K_mem`**.
- **Goal:** Attach an **optional short note** to a share link (a greeting / instruction), **set when creating the
  share and editable in place afterward**, shown E2EE on the share page to whoever opens the link. One-way
  (curator → viewer).
- **Preconditions:** A Memory the curator can share (personal-scope, v1); for an edit, an existing share of theirs.
  The curator holds **`K_mem`** (the Memory is open) — the note seals under **`K_share`**, and `K_share` is
  recovered from `K_mem` for a later edit ([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)).
- **Postconditions (success):** the share carries a **`sealed_note`** (sealed under `K_share`) + a
  **`sealed_share_key`** (`symWrap(K_mem, K_share)`); the anonymous share page decrypts and **renders** the note
  (markdown). Editing swaps the ciphertext — **the link stays valid**.

## The mechanism here

The note is sealed under the share's own key **`K_share`** (AAD `share-note`) — per-share, least-privilege, only
that link's holders read it (mirrors the Memory-name seal `sealedName`). Because `K_share` is minted once and
never stored in the clear, the share also carries a **recoverable copy** — **`sealed_share_key = symWrap(K_mem,
K_share)`** (AAD `share-key-recovery` plus the share's id, since `K_mem` is common to all of a Memory's shares —
[ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)) — so the curator, who always holds `K_mem`, can
recover `K_share` **anytime** to re-seal (edit) the note without minting a new link
([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)).
**Operator-blind:** the server stores only the two ciphertexts + the opaque note; it never sees `K_share`,
`K_mem`, or the note text. The share page renders the note with the **safe markdown renderer** reused from tenant
terms ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) follow-up).

## Main flow

1. The curator opens the Memory's **share dialog** and mints a share (VIEWER / CONTRIBUTOR / UPLOADER), optionally
   typing a **note**. The client seals it under `K_share` and stores `sealed_note` + `sealed_share_key` with the
   share.
2. A visitor opens `/s/{shareId}#k=<K_share>` → the anonymous `resolveShare` returns `sealed_note`; the share page
   unwraps it with the fragment `K_share` and **renders it** — a banner under the title (VIEWER/CONTRIBUTOR) or the
   primary instruction above the name field (UPLOADER).
3. Later, the curator **edits** the note: in the Memory's share dialog (which holds `K_mem`) they pick the share,
   the client **recovers `K_share`** from `sealed_share_key`, re-seals the new note, and calls the owner-gated
   **`updateShare`** — the same link now shows the new note.

## Alternate / exception flows

- **No note** → nothing is sealed/stored; the share page shows no banner (the note is optional).
- **UPLOADER share** → the note is the write-only drop-box's instruction ("please upload your pics here"), shown
  above the name field (there is no gallery to banner).
- **Revoked / expired share** → `resolveShare` is `404` (non-enumerable); the note is gone with the share.
- **A non-owner tries to edit** → refused (`updateShare` is `owner_account`-gated); only the share's creator edits,
  and only while they are still a member of the share's scope — a creator who has left it gets a non-member's 404
  ([uc.sharing.share-shared-scope](uc.sharing.share-shared-scope.md) A3).
- **A co-member reconstructs the link** (group/tenant scope, later) → possible (they hold `K_mem`), but grants
  nothing new — they can already read the Memory, and **revoke stays owner-gated** ([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)
  consequences). Moot in the v1 personal-scope cut (one member).

## What this means for you / Was das für dich bedeutet

**EN —** When you share a memory, you can add a short message that whoever opens the link will see — "Enjoy our
trip!" or "Drop your photos here." You can change that message later without making a new link. It's encrypted
like everything else: the person running the server can't read it, only people you send the link to.

**DE —** Wenn du eine Erinnerung teilst, kannst du eine kurze Nachricht hinzufügen, die alle sehen, die den Link
öffnen — „Viel Spaß mit unserer Reise!" oder „Ladet eure Fotos hier hoch." Du kannst die Nachricht später ändern,
ohne einen neuen Link zu erzeugen. Sie ist wie alles andere verschlüsselt: Die Person, die den Server betreibt,
kann sie nicht lesen — nur wer den Link von dir bekommt.

## Related

- **Feature:** [sharing](../_index.md)
- **Use-cases:** [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md) (the VIEWER link the note
  greets), [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md) (the note is its upload instruction).
- **Decisions:** [ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md) (the recoverable
  share key + the editable note), [ADR 0029](../../../decisions/0029-public-viewer-share.md) (the share model +
  `K_share`), [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the reused markdown renderer).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (`sealed_share_key` = new opaque
  ciphertext; the `K_share`-derivability note).
