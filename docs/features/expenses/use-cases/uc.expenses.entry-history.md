# UC: See what changed, and restore a deleted entry

- **ID:** uc.expenses.entry-history
- **Primary actor:** Anyone who reads the Pot — a **member**, or a **guest** of a `READ` or `READ_WRITE` link; a
  restore is a writer's.
- **Goal:** Know what an entry said before and when it changed, and bring back an entry deleted by mistake.
- **Preconditions:** The Pot is open.
- **Postconditions (success):** The history shows every revision the server holds for the entry, in order, with its
  time — and, to a member, its writer. A restored entry is a new revision, live again in the list and the balances.

## Main flow

1. An edited entry says so in the list; opening it offers **History**.
2. History lists the entry's revisions, newest first: what changed against the one before (amounts, people, title,
   date, rate, receipts) and when. A member also sees who wrote each — the writing member's name or the link guest's
   name, as the platform recorded it, not a participant.
3. **Deleted entries** is a list of its own in the Pot: each deleted entry with when it was deleted, open to its
   history.
4. **Restore** (a writer): a new revision with the entry's last content before its tombstone; it is live again.

## Alternate / exception flows

- **E1 — a guest:** history and deleted entries show the same revisions and times, no writer's name (a link is served
  no name, [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 6);
  a `READ` guest has no restore.
- **E2 — past the trash retention:** the deleted entry stays a line in the history, restore is no longer offered, and
  its receipts are erased ([uc.expenses.attach-receipts](uc.expenses.attach-receipts.md)).
- **E3 — a stale restore:** someone wrote the entry meanwhile; `409 stale_revision`, the list reloads.
- **E4 — a participant was deleted:** an old revision that named them shows *deleted people*, as the current ones do.

## Quality notes

- Revisions stay as long as the Pot does; no guest action erases anything (ADR 0108 decision 6). The one erasure is a
  member's, of a participant's record ([uc.expenses.manage-participants](uc.expenses.manage-participants.md)).
- The writer is a fact the server holds (a member's id or a link identity), never a sealed claim; the server serves it
  to members only.

## User impact

Nothing put into a Pot is lost: you can always see what an entry said before and who changed it, and bring back one
that was deleted.

## Related

- **Feature:** [expenses](../_index.md).
- **Use-cases:** [uc.expenses.record-entries](uc.expenses.record-entries.md), [uc.expenses.verify-ledger](uc.expenses.verify-ledger.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 3 and 6; [ADR 0061](../../../decisions/0061-node-deletion-reversible-node-trash.md) (the retention).
- End-user view (docs site): [Record an expense](../../../../site/src/content/docs/expenses/record-an-expense.mdx)
