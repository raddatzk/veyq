# UC: See who owes whom, settle up, and see where the money went

- **ID:** uc.expenses.balances
- **Primary actor:** Anyone who reads the Pot — a **member** or a **guest**; recording a transfer is a writer's.
- **Goal:** See at any time each person's balance, the few transfers that settle the Pot, and what the money was spent
  on.
- **Preconditions:** The Pot is open and its revisions read ([uc.expenses.verify-ledger](uc.expenses.verify-ledger.md)).
- **Postconditions (success):** Every figure is computed in the client from the live entries, the same on every device
  ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 9). A
  recorded settle-up transfer is an ordinary transfer entry.

## Main flow

1. **Balances** (the Pot's **Overview** tab): per participant, what they paid and received against their share of every
   expense and refund, transfers included ([docs/apps/expenses.md](../../../apps/expenses.md) § What the client
   computes); each row explains its balance by what the person paid, what they consumed and the net of their transfers
   (sent or received), which add up to it. The viewer's own row is marked *you* when they chose who they are (step 2).
2. **Who am I:** the viewer picks their participant once, above the balances; the choice stays on the device — per Pot,
   and per account or per guest on that browser — and is never sent. Until a signed-in member has chosen, their own
   member participant is preselected; it is not stored, so their choice — "nobody" too — is never overwritten.
3. **Settle up:** the suggested transfers that bring every balance to zero, computed greedily as the domain says — at
   most one fewer than there are people, the same on every device.
4. **Record** (a writer): one suggested transfer, or all of them, is saved as transfer entries
   ([uc.expenses.record-entries](uc.expenses.record-entries.md)); the balances follow.
5. **Overviews**, on the same tab below the settle-up: the total cost per category; per person, paid and consumed side
   by side, are the balance rows of step 1. Refunds count against their category; transfers are not costs.

## Alternate / exception flows

- **E1 — a deactivated or deleted participant with a balance or with costs:** shown in the balances — by name, or as
  *deleted people* — with an open balance in the settle-up too, and not offered for new entries. A settled one keeps
  its row while it paid or consumed anything, so the rows add up to the cost per category; one with neither is left
  out until an edit gives it either.
- **E2 — the Pot marked as missing changes:** the figures are shown with the notice above them
  ([uc.expenses.verify-ledger](uc.expenses.verify-ledger.md) step 5).
- **E3 — a `READ` guest:** balances, settle-up suggestion and overviews, no **Record**.
- **E4 — all settled:** the settle-up says nobody owes anything.
- **E5 — no member identity to preselect:** a link guest, or a member who is no participant of the Pot, starts with
  nobody chosen.

## Quality notes

- No amount, balance or category reaches the server; the operator sees only that entries were written.
- The settle-up is not always the fewest transfers possible (that is NP-hard in general); it is deterministic, which
  matters more at the size of a trip.

## User impact

You always know who owes whom, can settle a whole trip in a few transfers, and see where the money went.

## Related

- **Feature:** [expenses](../_index.md); the computations in [docs/apps/expenses.md](../../../apps/expenses.md) § What the client computes.
- **Use-cases:** [uc.expenses.record-entries](uc.expenses.record-entries.md), [uc.expenses.manage-participants](uc.expenses.manage-participants.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 9.
- End-user view (docs site): [Settle up](../../../../site/src/content/docs/expenses/settle-up.mdx)
