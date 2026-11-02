# UC: Share note — a curator attaches an editable message to a share

- **ID:** uc.sharing.share-note
- **Primary actor:** The **curator** (the person who owns/shares the Memory), signed in with the Memory open in
  their scope — so they hold the scope key (**`K_SC`**) and the Memory's **`K_node`**.
- **Goal:** Attach an **optional short note** to a share link (a greeting / instruction), **set when creating the
  share and editable in place afterward**, shown E2EE on the share page to whoever opens the link. One-way
  (curator → viewer).
- **Preconditions:** A node the curator can share (a Memory or a folder, in any scope the curator is a member of); for an edit, an existing share of theirs.
  The curator holds the scope key (the Memory is open) — the note seals under a field key derived from **`K_share`**, and `K_share`
  is recovered through `K_SC` for a later edit ([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)).
- **Postconditions (success):** the share carries a **`sealed_note`** (sealed under a field key derived from `K_share`) + a
  **`sealed_share_key`** (`K_share` under `K_SC` at the scope's current epoch, stored with that epoch); the
  anonymous share page decrypts and **renders** the note
  (markdown). Editing swaps the ciphertext — **the link stays valid**.

## The mechanism here

The note is sealed under a field key derived from the share's own key **`K_share`**, fresh per write (AAD
`share-note`, [en.platform.field-key](../../platform/enablers/en.platform.field-key.md)) — per-share, least-privilege, only
that link's holders read it (mirrors the Memory-name seal `sealedName`). Because `K_share` is minted once and
never stored in the clear, the share also carries a **recoverable copy** — **`sealed_share_key`**, `K_share`
sealed under the scope key **`K_SC` at the scope's current epoch** (AAD `share-key-recovery` bound to scope,
Memory, share and epoch — [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md)), with the epoch stored
beside it, under a value key of its own that the scope key wraps; every rotation re-wraps that key under the new one
and leaves the copy, with the epoch the link was minted at beside it for the re-issue rule ([ADR 0115](../../../decisions/0115-a-scope-key-is-replaced-never-chained.md) decision 2) — so every scope member can recover `K_share` **anytime** to re-seal (edit) the note without minting a
new link ([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)).
**The recovery copy is for the Memory's members only.** It is deliberately *not* under `K_node`: every READ or
READ_WRITE link carries `K_node`, so such a copy would let one link plus a database dump open every other share of
the Memory — its password, its private label, its note, and (the dump holds every share id) its whole link.
`K_SC` is a key no share carries (ADR 0036 point 4). A create sealed at a stale epoch is `409 stale_epoch`; the
client re-opens its scope key and retries once.
**Operator-blind:** the server stores only the two ciphertexts + the opaque note; it never sees `K_share`,
`K_SC`, `K_node`, or the note text. The share page renders the note with the **safe markdown renderer** reused from tenant
terms ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) follow-up).

## Main flow

1. The curator opens the node's **share dialog** and mints a share (READ / READ_WRITE / WRITE_ONLY), optionally
   typing a **note** and, with a note, optionally ticking **"must be accepted before opening"** (off by default; a
   greeting needs no acceptance). The client seals the note, the switch inside it, under a field key derived from `K_share` and stores
   `sealed_note` + `sealed_share_key` with the share.
2. A visitor opens `/s/{shareId}#k=<K_share>` → the anonymous `resolveShare` returns `sealed_note`; the share page
   unwraps it with the fragment `K_share` and **renders it** — a banner under the title (READ/READ_WRITE) or the
   primary instruction above the name field (WRITE_ONLY). **When the note asks for acceptance**, it is instead a gate
   after the password and the tenant's terms and before anything of the share is drawn, for every role: the note and
   **Accept**. Accepting is remembered on this device for this share and this note's text, so an edited note asks
   again; where the device cannot remember (storage refused), it asks every time. Nothing is sent to the server.
3. Later, the curator **edits** the note: in the Memory's share dialog (which holds the scope key) they
   pick the share, the client **recovers `K_share`** from `sealed_share_key` under the current scope key, re-seals the new note,
   and calls the owner-gated
   **`updateShare`** — the same link now shows the new note.

## Alternate / exception flows

- **No note** → nothing is sealed/stored; the share page shows no banner (the note is optional).
- **A visitor goes past the gate by other means** (devtools, a cleared flag) → nothing stops them, and nothing
  should: the content opens with the fragment key alone. Going past it is, in effect, not accepting, and that is the
  visitor's to own. The gate makes sure an honest visitor has seen the note.
- **A note written by an older client** (the plaintext without the switch) → no acceptance asked.
- **WRITE_ONLY share** → the note is the write-only drop-box's instruction ("please upload your pics here"), shown
  above the name field (there is no gallery to banner).
- **Revoked / expired share** → `resolveShare` is `404` (non-enumerable); the note is gone with the share.
- **A non-owner tries to edit** → refused (`updateShare` is `owner_account`-gated); only the share's owner (its creator, or
  the member who re-activated or renewed it) edits, and only while still a member of its scope — an owner who has left it gets a non-member's 404
  ([uc.sharing.share-shared-scope](uc.sharing.share-shared-scope.md) A3).
- **A co-member reconstructs the link** (group/tenant scope) → possible (they hold `K_SC`), but grants nothing
  new — they can already read the Memory, and **revoke stays owner-gated** ([ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md)
  consequences).
- **A link holder tries the same** → nothing opens: a READ or READ_WRITE link holds `K_node` and its own
  `K_share`, neither of which is `K_SC`, so even with a dump it reads no other share's password, label or note and
  rebuilds no other link (ADR 0036 point 4).
- **A member removed from a group scope** keeps the `K_share` of every share they ever opened — it does not change
  for the life of the link — so with a dump they keep reading that share, including later edits of its note, label
  or password, and can still use its link while it is open. Shares minted after the removal are out of their reach;
  cutting them off an existing link means re-issuing it (ADR 0036 point 4), which closes it and re-keys the subtree
  below its root.
- **A share minted before the recovery copy moved to `K_SC`** (an un-reset development instance) → its copy does not
  open; the manage list shows no label, note or password for it and cannot re-copy its link. Its guests are
  unaffected.

## User impact

You can greet or instruct whoever opens your link with a short message you can change later, readable only by people
with the link.

## Related

- Enabler: [en.sharing.public-link](../enablers/en.sharing.public-link.md) (the record, keys, gates and lifecycle)
- **Feature:** [sharing](../_index.md)
- **Use-cases:** [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md) (the READ link the note
  greets), [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md) (the note is its upload instruction).
- **Decisions:** [ADR 0036](../../../decisions/0036-editable-share-note-recoverable-share-key.md) (the recoverable
  share key + the editable note), [ADR 0029](../../../decisions/0029-public-viewer-share.md) (the share model +
  `K_share`), [ADR 0035](../../../decisions/0035-server-terms-of-service.md) (the reused markdown renderer).
- End-user view (docs site): [Sharing with people who have no account](../../../../site/src/content/docs/sharing/index.mdx)
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (`sealed_share_key` = new opaque
  ciphertext; the members-only `K_share`-derivability note; the § What holds today row on a sibling link).
- End-user view (docs site): [Create a link](../../../../site/src/content/docs/sharing/create-a-link.mdx)
- End-user view (docs site): [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx)
