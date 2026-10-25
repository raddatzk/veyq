# UC: Manage the people of a Pot

- **ID:** uc.expenses.manage-participants
- **Primary actor:** Whoever may write the Pot — a **member**, or a **`READ_WRITE` guest** — for adding and renaming; a
  **member** for every other step.
- **Goal:** Keep the Pot's list of people right: add who joined, fix a name, and take out who is settled and gone
  without changing a single balance.
- **Preconditions:** The Pot is open; the actor may write it.
- **Postconditions (success):** Every step is a new revision of the participant's record; entries name a participant by
  its id only, so none of them changes. A delete or a remove has erased every earlier revision of that record, so its
  name is in no row the server holds ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 3 and 6).

## Main flow

1. The actor opens the Pot's **People**.
2. **Add:** a name; the client mints the record's id and appends its first revision. It joins at the end of the order
   the rounding and the settle-up use ([docs/apps/expenses.md](../../../apps/expenses.md) § Entries).
3. **Rename:** a new revision of the record with the new name.
4. **Deactivate** (member, balance settled): a revision marking it inactive; entries keep showing the name, new
   entries and the overviews no longer offer it. **Activate** is the revision back.
5. **Delete** (member, balance settled): the client writes a sealed **erasure** revision of the record through the
   member-only erasure; the server erases the record's earlier revisions in the same transaction. Entries show it, with
   every other deleted participant, as **deleted people**.
6. **Remove** (member, no revision of any entry, live or deleted, names it): the same erasure; the participant appears
   nowhere afterwards.

## Alternate / exception flows

- **E1 — not settled:** deactivate and delete are not offered while the participant's balance is not zero.
- **E2 — named by an entry:** remove is not offered; delete is.
- **E3 — an edit of an old entry unsettles a deactivated or deleted participant:** it shows again in the balances and
  the settle-up — a deactivated one by name, a deleted one as *deleted people* — and still counts as before.
- **E4 — a guest:** the page offers add and rename only; the server refuses the erasure from a link.
- **E5 — two edits at once:** the second is refused `409 stale_revision`; the client reads the record again and shows
  the current name before writing.

## Quality notes

- The server cannot tell a deactivation from a rename: both are sealed revisions. Steps 4–6's preconditions are the
  client's rules, and a revision that breaks one is visible in the history like any other
  ([uc.expenses.entry-history](uc.expenses.entry-history.md)).
- The erasure is the one write that removes something; the server takes it from a member only, and the ledger check
  accepts a missing revision only where an erasure of its own record covers it
  ([uc.expenses.verify-ledger](uc.expenses.verify-ledger.md)).
- In the data every deleted participant keeps its own id, which entries and the rounding order use; only the screen
  merges them.

## User impact

You can keep a Pot's list of people current and take out someone who has left, without any old expense or balance
changing.

## Related

- **Feature:** [expenses](../_index.md); the participant steps' table [docs/apps/expenses.md](../../../apps/expenses.md) § The Pot.
- **Use-cases:** [uc.expenses.create-pot](uc.expenses.create-pot.md), [uc.expenses.balances](uc.expenses.balances.md),
  [uc.expenses.verify-ledger](uc.expenses.verify-ledger.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 2, 3 and 6.
- End-user view (docs site): [Expenses](../../../../site/src/content/docs/expenses/index.mdx)
