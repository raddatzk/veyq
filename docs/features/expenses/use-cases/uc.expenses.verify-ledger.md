# UC: A Pot's reader notices a missing revision

- **ID:** uc.expenses.verify-ledger
- **Primary actor:** The **client** of anyone who reads a Pot — a member or a guest — on every read.
- **Secondary actor:** The **server**, which could serve the list with a revision left out.
- **Goal:** No balance is computed from a list the server thinned out without the reader being told
  ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 5).
- **Preconditions:** The client has read the Pot's revisions.
- **Postconditions (success):** Every revision any read revision names is present and matches its digest, or a missing
  one is covered by a sealed erasure of its own record; otherwise the Pot says, above its balances, that changes are
  missing, and names the entries they belong to.

## Main flow

1. Every revision carries, sealed, the Pot's **heads** as its writer read them: per head the entry id, the revision and
   the digest of that revision.
2. On every read — the first load and each poll's delta — the client opens each revision under its binding (scope,
   node, entry, revision) and collects the heads it names.
3. For each named revision it checks that the revision is present and that its digest matches.
4. A named revision that is absent passes only where an erasure revision of the same record, present and opened, covers
   it.
5. Anything else marks the Pot: a notice above the balances that changes are missing, with the entries they belong to.
   The balances are still shown, computed from what is there.

## Alternate / exception flows

- **E1 — the newest revisions are held back:** looks exactly like a stale read and is not detected — the platform's
  freshness limit ([ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md) decision 5), stated, not closed.
- **E2 — a revision does not open** (wrong binding, swapped between entries or Pots): it is treated as absent, and every
  revision naming it marks the Pot.
- **E3 — two concurrent writes:** both name the same parent; the next write names both. Neither is missing.
- **E4 — an erasure by someone who is not a member:** the server takes none from a link; a forged one does not open.

## Quality notes

- No signature: the seal under the Pot's key proves the writer held it, and the server cannot forge a reference
  (ADR 0108 decision 5).
- The check runs in the client for members and guests alike; a share view is the member's view.

## User impact

If the server ever left out a change somebody built on, your Pot tells you instead of quietly showing a wrong balance.

## Related

- **Feature:** [expenses](../_index.md).
- **Use-cases:** [uc.expenses.record-entries](uc.expenses.record-entries.md), [uc.expenses.manage-participants](uc.expenses.manage-participants.md)
  (the erasure).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 3 and 5; [ADR 0072](../../../decisions/0072-a-seal-binds-its-object.md).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md#applications-are-compiled-in--never-loaded).
