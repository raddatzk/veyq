# Feature: Expenses

The **Expenses** application's user-facing flows: a Pot, its participants, its entries and receipts, their history,
what the client computes from them, exchange rates, and a Pot shared through a link. What a Pot *is* — its node, its
records, its seal, what the operator sees — is [ADR 0108](../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md);
its domain (entry kinds, allocation, rounding, balances, rights) is [docs/apps/expenses.md](../../apps/expenses.md). The
use-cases below say what a person does and point there instead of repeating it.

- **Decisions:** [ADR 0108](../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) (the
  whole application), [ADR 0079](../../decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) (an
  application on core), [ADR 0080](../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6 (the guest role matrix Expenses interprets), [ADR 0109](../../decisions/0109-a-scope-rotation-re-keys-every-node-below-it.md)
  (a rotation re-keys a Pot like every node), [ADR 0093](../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  (a report and its hold reach an entry and a receipt).
- **Cross-cutting:** [security-model](../../crosscutting/security-model.md) (the exchange-rate fetch is a third-party
  request, named there once it runs).

## Use-cases

All planned. `changes/cr-expenses-ledger.md` builds the Pot, its people and entries, the history, the ledger check,
the balances and the link (rates typed); `cr-expenses-receipts` and `cr-expenses-exchange-rates` follow it.

- [uc.expenses.create-pot](use-cases/uc.expenses.create-pot.md) — a member creates a Pot with its base currency and
  participants, edits its settings, and deletes it.
- [uc.expenses.manage-participants](use-cases/uc.expenses.manage-participants.md) — whoever may write adds and renames
  people; a member deactivates, deletes and removes them.
- [uc.expenses.record-entries](use-cases/uc.expenses.record-entries.md) — expenses, refunds and transfers, their
  allocation and rounding; editing and deleting.
- [uc.expenses.attach-receipts](use-cases/uc.expenses.attach-receipts.md) — images and PDFs kept with an entry.
- [uc.expenses.entry-history](use-cases/uc.expenses.entry-history.md) — what changed and when, the deleted entries and
  their restore.
- [uc.expenses.verify-ledger](use-cases/uc.expenses.verify-ledger.md) — every read checks that no revision anybody
  built on is missing.
- [uc.expenses.balances](use-cases/uc.expenses.balances.md) — balances, the settle-up suggestion and the overviews.
- [uc.expenses.exchange-rates](use-cases/uc.expenses.exchange-rates.md) — the day's rates, fetched by the device, or
  typed.
- [uc.expenses.share-pot](use-cases/uc.expenses.share-pot.md) — a `READ` or `READ_WRITE` link on a Pot: the member's
  view with functions removed.

A Pot's download and import, its copy, a report of an entry or a receipt, and its re-key after a rotation are core's
flows; their Expenses criteria live in [uc.platform.export-import](../platform/use-cases/uc.platform.export-import.md),
[uc.platform.copy-node](../platform/use-cases/uc.platform.copy-node.md), [uc.moderation.report](../moderation/use-cases/uc.moderation.report.md)
and [uc.spaces.rekey-after-rotation](../spaces/use-cases/uc.spaces.rekey-after-rotation.md), written by the CR's phase
that brings their tests.
