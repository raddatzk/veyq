# UC: Manage the people of a Pot

- **ID:** uc.expenses.manage-participants
- **Primary actor:** Whoever may write the Pot — a **member**, or a **`READ_WRITE` guest** — for adding and renaming; a
  **member** for every other step.
- **Goal:** Keep the Pot's list of people right: add who joined — a member of the space or a name — fix a name, and
  take out who is settled and gone without changing a single balance.
- **Preconditions:** The Pot is open; the actor may write it.
- **Postconditions (success):** Every step is a new revision of the participant's record; entries name a participant by
  its id only, so none of them changes. A delete or a remove has erased every earlier revision of that record, so its
  name is in no row the server holds ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 3 and 6).

## Main flow

1. The actor opens the Pot's **Settings**, whose people list shows each person with their balance in short (the
   **Overview** explains it); a link guest who may add people sees this list alone there.
2. **Add:** a name typed, or — a member's view — one of the space's current members not yet in the Pot; the client
   mints the record's id and appends its first revision, for a member with their user id and, sealed with it, their
   display name as it is now ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
   decision 2). It joins at the end of the order the rounding and the settle-up use
   ([docs/apps/expenses.md](../../../apps/expenses.md) § Entries).
3. **Rename:** a name participant's new revision with the new name. A member participant is not renamed: a member's
   view shows their current display name and picture, a link guest's the name sealed at adding.
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
- **E4 — a guest:** the page offers add and rename only, and adds a name only — it reads no member list; it shows a
  member participant by the name sealed at adding, with no picture. The server refuses the erasure from a link.
- **E6 — a member leaves or is erased:** their participant and its entries stand; a member's view shows them as a
  former member by the name sealed at adding.
- **E7 — a member already in the Pot:** not offered again; the writer refuses a second record for them, and a revision
  that would change a participant's kind (a name made a member, a member made a name or another member). A reader
  takes a participant's kind from its record's first revision, so a revision written around this client changes no
  one's kind either.
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
- The member's user id is sealed with the record like everything else of it; the server sees no participant. A Pot
  written before member participants existed reads as names, and a client that predates them reads a member
  participant as its sealed name.
- A download keeps a member's id only for an import into the same tenant; elsewhere the participant becomes its sealed
  name ([docs/apps/expenses.md](../../../apps/expenses.md) § Download and import).

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
