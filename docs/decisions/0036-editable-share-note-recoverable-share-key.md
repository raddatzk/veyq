# ADR 0036 — A curator note on a share, editable in place via a member-recoverable share key

- Status: Accepted
- Built: yes
- Date: 2026-07-10
- Model: [en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md)
- Replaced in part: point 2 by ADR 0093 (built)

## Context

A curator hands out a link `/s/{shareId}#k=<K_share>` ([ADR 0029](0029-public-viewer-share.md)). `K_share` is minted
on the client, shown once in the copyable link and not kept, so after minting nobody can re-seal a field sealed under
it, and the management list cannot even show a minted link's sealed content.

We want a **curator note** — a short message shown on the share page to whoever opens the link (a greeting, an
upload instruction) — operator-blind, per link, and **editable after the link is made without invalidating it**. The
natural key is `K_share` (it binds the note to *this* link); but `K_share` is not retained. Sealing under the node's key
instead would be editable, but keys the note to the content, not the link, and leaves every other sealed field of a
link unshowable.

## Decision

Make the share key **recoverable by the scope's members**, seal the note under `K_share`, and add an update operation.
The fields and gates as built are the model,
[en.sharing.public-link](../features/sharing/enablers/en.sharing.public-link.md).

1. **Recoverable share key.** Beside the link's wrap of the node key, the share row stores a **recovery copy**:
   `K_share` sealed under the scope key `K_SC` at the scope's current epoch, bound to scope, node, share and epoch
   ([ADR 0072](0072-a-seal-binds-its-object.md)), with that epoch beside it. Every scope member holds the epoch chain,
   so any member can recover `K_share` and read or re-seal **any `K_share`-sealed field** of a link. The server holds
   only ciphertext. Why `K_SC` and not the node's key: point 4.

2. **The curator note** is sealed under a field key derived from `K_share`
   ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 9), optional,
   per locale, set at create and served by the anonymous resolve. It is rendered as markdown with the safe renderer of
   the tenant terms ([ADR 0035](0035-server-terms-of-service.md)), for a `WRITE_ONLY` link as its upload instruction.
   **The curator can ask the visitor to accept the note before anything is shown**; the switch travels **inside** the
   sealed note, so the operator does not learn which links ask for acceptance, and the acceptance is recorded **on
   the visitor's device only**, as the tenant terms' is. Rejected: a server-stored acceptance gating the resolve — it
   gates nothing a visitor cannot send, and would make the server tell visitors apart.

3. **In-place edit.** An **owner-gated `updateShare`** — the link's owner, while still a member of its scope — replaces
   the note and the private label (full replace each), and the link stays valid. It also carries the link's **role**,
   restricted to **`READ` ↔ `READ_WRITE`**: a pure server-side authorization change, since both share one wrap of the
   node key, so the wrap, `K_share` and the link are untouched. A change into or out of `WRITE_ONLY` is refused: a
   drop box's "cannot see" promise cannot honestly be flipped on links already handed out, and a `WRITE_ONLY` link
   carries the drop public key instead of that wrap ([ADR 0031](0031-uploader-public-share.md) point 7).

4. **The recovery copy is for the scope's members only — never for a link.** A `READ` or `READ_WRITE` link carries the
   node's key (it is what the link hands over), so a copy sealed under that key would let the link's holder, with a
   database dump, open **every other link of the same node**: its `K_share`, and through it the link's password in
   plain text ([ADR 0037](0037-password-protected-shares.md)), its label, its note and — the dump holds every share id
   in clear — the whole link. `K_SC` is the key no link carries ([ADR 0012](0012-content-key-hierarchy.md)), so
   sealing under it closes all four at once and keeps what a member does: reveal, edit, copy.
   - **The epoch is the scope's current one.** A create or a re-issue whose copy is not at the current epoch is
     refused and writes nothing — no client writes under an epoch it knows is stale
     ([ADR 0024](0024-scope-key-rotation.md) decision 6). `updateShare` and re-activation do not touch the copy.
   - **A copy that does not open degrades** to "no label, no note, no password, no link" in the manage list; it is not
     a refusal, and the link's guests are unaffected.
   - **The residual, stated.** `K_share` does not change for the life of a link, and every member can rebuild every
     link of the scope from its copy — so a member removed from a group may hold every link that existed while they
     belonged, and uses one with no dump at all. Re-sealing the copy under a new epoch on rotation would not help;
     cutting such a member off a link means **re-issuing** it — a new id and a new `K_share`
     ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md)).
   - **Rejected alternatives.** Storing only the password verifier (drop the reveal) closes the password, even against
     members, but leaves the label, the note and the link; sealing only the password under `K_SC` costs the same
     machinery and closes the same one field. Either can be layered on this one; neither replaces it. Folding the
     password into the key (the crypto gate [ADR 0037](0037-password-protected-shares.md) defers) answers a different
     threat and needs a new link format.

**Roles and scopes.** All three roles carry a note, in every scope a link can live in
([ADR 0029](0029-public-viewer-share.md) §6).

## Consequences

- **`K_share` becomes derivable by every scope member** — a conscious relaxation. A member can rebuild a co-member's
  link. This grants nothing new in practice: a member already decrypts the content. Switching off a link one at a time
  and editing it stay its owner's; a scope owner can switch off every link of the scope at once
  ([ADR 0075](0075-a-link-a-departed-member-could-copy-is-reissued.md) point 5). It is **not** "every holder of the
  node's key": a link holds that key too, and recovers nothing (point 4). Recorded in the
  [security model](../crosscutting/security-model.md).
- **The management list can show and edit every minted link's sealed content**, not just the one fresh link.
- **Links stop being strictly write-once.** `updateShare` swaps ciphertext for ciphertext and may move the role
  between `READ` and `READ_WRITE`; it never touches `K_share`, the node key or the wrap, so the link's identity stays
  immutable. The root's own sealed name is the one sealed field outside its gate: any member who renames the root
  re-seals it on every link through this recovery copy
  ([ADR 0080](0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md) Decision 14).

## Alternatives considered

- **(A) `K_share`, note immutable (edit = revoke + re-share).** Cleanest, no new crypto — but a note typo means a new
  link to redistribute, and the management list still cannot show minted links. Rejected.
- **(B) A note sealed under the node's key + `updateShare`.** Editable, but keyed to the content, not the link, and it
  makes no other `K_share`-sealed field recoverable. Rejected: solves less for the same new operation.
- **(C, chosen) Recoverable `K_share` + a `K_share`-sealed note + `updateShare`.** Per-link binding, durable in-place
  editing, and it generalizes to every `K_share`-sealed field, for one opaque column and its epoch — harmless as long
  as the recovery key is one no link carries (point 4).
