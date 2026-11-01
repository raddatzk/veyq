# UC: Record, edit and delete entries

- **ID:** uc.expenses.record-entries
- **Primary actor:** Whoever may write the Pot — a **member**, or a **`READ_WRITE` guest**.
- **Goal:** Put in what was paid, what came back and who paid whom, split the way the bill was.
- **Preconditions:** The Pot has at least one active participant; the actor may write it.
- **Postconditions (success):** The entry is a new revision of its record, sealed under the `expense-entry` purpose
  and padded to a size class ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decision 4); it carries the format version, its kind, its amounts in minor units, its currency with the rate it was
  saved with, its allocation and the Pot's heads as read (decision 5). Every device computes the same balance from it ([docs/apps/expenses.md](../../../apps/expenses.md) § Entries,
  rounding).

## Main flow

1. The actor chooses **Add** and one of **Expense**, **Refund** or **Transfer**.
2. **Expense / refund:** title, date, category, currency; one or more payers (receivers for a refund), each with an
   amount — the entry's amount is their sum, shown, never typed; and the allocation: any number of **items** (label,
   exact amount, and its own split: equally, by shares or by percent), and the **rest** — the amount less the items —
   with a split of its own. A new entry has one payer and no items, so its whole amount is the rest, split equally among
   the Pot's active participants. A refund may name the expense it refunds.
3. **Transfer:** from one participant to another, one amount, no category.
4. In a foreign currency the rate fills in from the day's table, or is typed
   ([uc.expenses.exchange-rates](uc.expenses.exchange-rates.md)); the editor shows each person's share in the base
   currency as it will be saved.
5. On save the client mints the entry's id (a new entry) and appends revision `n + 1` of the revision it read; the
   server takes it only where `n` is the latest, bumps the Pot's item revision, and every open view of the Pot learns
   of it on its next poll ([docs/apps/README.md](../../../apps/README.md) § How an application publishes a revisioned list).
6. **Edit:** the same editor over the entry's current revision; the save is the next revision. The entry shows that it
   was edited.
7. **Delete:** a tombstone revision; the entry leaves the list and the balances and moves to the Pot's deleted entries
   ([uc.expenses.entry-history](uc.expenses.entry-history.md)).
8. **Receipts** are attached in the same editor ([uc.expenses.attach-receipts](uc.expenses.attach-receipts.md)).

## Alternate / exception flows

- **E1 — someone else saved the entry first:** `409 stale_revision`; the editor reads the current revision, shows what
  changed, and the actor saves again on top of it. Nothing is overwritten.
- **E2 — the entry does not add up:** items above the amount (the editor says by how much), a rest no one shares,
  percents that do not make 100 %, a payer without an amount — the editor says which, and does not save.
- **E3 — the revision would exceed 16 KiB:** the editor says the entry is too large (too many items) before
  anything is sent; the server refuses a larger one.
- **E4 — the entry was deleted meanwhile:** the save is refused as stale; the editor offers to restore it first.
- **E5 — no active participant:** **Add** is not offered; the Pot points to its People.

## Quality notes

- Amounts are integers in the currency's minor unit and every rate a decimal string; the arithmetic is exact, never
  floating point, and the rounding rule is part of the format version (ADR 0108 decision 9).
- The server cannot tell an expense from a refund, a transfer or a participant; it sees a revision's size class, time
  and writer, and whether it is a tombstone.

## User impact

You can put in every kind of shared cost — split evenly, by shares, by percent or item by item — and fix it later without losing
the earlier version.

## Related

- **Feature:** [expenses](../_index.md); entry kinds, allocation and rounding in [docs/apps/expenses.md](../../../apps/expenses.md) § Entries.
- **Use-cases:** [uc.expenses.entry-history](uc.expenses.entry-history.md), [uc.expenses.verify-ledger](uc.expenses.verify-ledger.md),
  [uc.expenses.exchange-rates](uc.expenses.exchange-rates.md), [uc.expenses.balances](uc.expenses.balances.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 3, 4, 5 and 9; [ADR 0033](../../../decisions/0033-live-media-updates-revision-polling.md) (the live list).
- End-user view (docs site): [Record an expense](../../../../site/src/content/docs/expenses/record-an-expense.mdx)
