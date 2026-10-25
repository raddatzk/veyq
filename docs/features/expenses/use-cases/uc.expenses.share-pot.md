# UC: Share a Pot through a link

- **ID:** uc.expenses.share-pot
- **Primary actor:** A **guest** holding a Pot's link — `READ` or `READ_WRITE` — with no account.
- **Secondary actor:** The **member** who shared the Pot, and every member who reads what guests wrote.
- **Goal:** Let the friends of a trip, who have no account, see the Pot and — with a contributor link — put in their
  own expenses.
- **Preconditions:** A member shared the Pot with core's share dialog (`READ` or `READ_WRITE`); the guest opened the
  link and passed its password and terms if any.
- **Postconditions (success):** The guest sees the member's view of the Pot with what the role does not allow removed
  ([docs/apps/expenses.md](../../../apps/expenses.md) § Rights); a contributor's revisions and receipts are guest writes
  like any other: behind the link's gate, each recorded as a guest write, reportable with a hold copy
  ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 6).

## Main flow

1. The member shares the Pot; the dialog offers `READ` and `READ_WRITE`, never an upload-only link.
2. The guest opens the link; the Pot renders as it does for a member: entries, history, deleted entries, balances,
   settle-up and overviews, under the keys the link's chain gives.
3. **Contributor:** the guest names itself once per link (core's guest identity), then adds, edits, deletes and
   restores entries, adds and renames participants, and adds and removes receipts
   ([uc.expenses.record-entries](uc.expenses.record-entries.md), [uc.expenses.manage-participants](uc.expenses.manage-participants.md),
   [uc.expenses.attach-receipts](uc.expenses.attach-receipts.md)).
4. The server checks each write against the link under its gate, records it as a guest write, and takes it like a
   member's.
5. Members see the guest's revisions with the guest's name as writer; the guest sees no writer's name.

## Alternate / exception flows

- **E1 — a `READ` guest writes:** the page offers no write; the server refuses one.
- **E2 — an upload-only (`WRITE_ONLY`) link on a Pot:** not offered; the server refuses to create one — it reads
  nothing, so it has no balance to write against (ADR 0108 decision 2).
- **E3 — what stays a member's:** settings (base currency, own categories), deactivate, delete and remove of a
  participant, and creating a Pot; the page does not offer them, and the server refuses the erasure and the meta write
  from a link.
- **E4 — the link is revoked or expires mid-edit:** the write is refused; nothing lands.

## Quality notes

- A guest may change and delete another person's entry, unlike a Memory's media: in a ledger forbidding it protects
  nothing, since a writer can cancel any entry with a counter-entry; what protects is that nothing is lost
  (ADR 0108 decision 6).
- Owner principle (inbox #401): a share is the member's view with functions removed — no guest-only screen.

## User impact

People without an account can follow a shared Pot and, if you let them, add their own expenses, without anything they
change getting lost.

## Related

- **Feature:** [expenses](../_index.md).
- **Use-cases:** [uc.sharing.share-contributor](../../sharing/use-cases/uc.sharing.share-contributor.md) (core's link,
  gate and guest identity), [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decisions 2 and 6, [ADR 0080](../../../decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md)
  decision 6, [ADR 0093](../../../decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § 10.
- End-user view (docs site): [Share a Pot](../../../../site/src/content/docs/expenses/share-a-pot.mdx)
