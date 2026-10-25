# UC: Keep the receipts with an entry

- **ID:** uc.expenses.attach-receipts
- **Primary actor:** Whoever may write the Pot — a **member**, or a **`READ_WRITE` guest** — to add and remove; anyone
  who reads it to open one.
- **Goal:** Keep the photo of the bill or the PDF of the booking beside the entry it belongs to.
- **Preconditions:** The entry exists or is being saved ([uc.expenses.record-entries](uc.expenses.record-entries.md)).
- **Postconditions (success):** Each receipt is a blob in Expenses' own table — receipt, entry, blob, writer — its
  `K_blob` wrapped under the Pot's key and bound to the blob, the node and the role `EXPENSES_RECEIPT`, so it needs no
  new blob purpose ([ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
  decision 7). The server saw its size and which entry holds it.

## Main flow

1. In the entry editor the actor adds any number of images or PDFs, each at most 10 MB.
2. The client seals and uploads each into the Pot through core's transport (a guest through the link's), and completes
   it against the entry; the entry's next revision lists the receipts it holds.
3. **Open:** an image or a PDF is shown in the client (the PDF through the page renderer Depot's view uses); anything
   else is offered for download and never rendered.
4. **Remove:** the entry's next revision names the receipt it drops.
5. A receipt nothing holds any more, or whose entry is deleted, is erased after the trash retention.

## Alternate / exception flows

- **E1 — a receipt above 10 MB:** refused before upload; the server refuses it by its size, which it sees.
- **E2 — a link's storage limit is reached:** the upload is refused at the exact byte, as every guest upload is; the
  entry itself saves.
- **E3 — an entry restored within the retention:** its receipts come back with it.
- **E4 — a `READ` guest:** opens and downloads, adds and removes nothing.

## Quality notes

- Only the client can enforce the type, which is sealed; the server enforces the size, which it sees.
- No previews are made (out of the first cut).

## User impact

The receipt stays with the expense it proves, readable only by the people of the Pot.

## Related

- **Feature:** [expenses](../_index.md).
- **Use-cases:** [uc.expenses.record-entries](uc.expenses.record-entries.md), [uc.expenses.share-pot](uc.expenses.share-pot.md),
  [uc.sharing.share-link-storage-limit](../../sharing/use-cases/uc.sharing.share-link-storage-limit.md).
- **Decisions:** [ADR 0108](../../../decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 7.
- End-user view (docs site): [Record an expense](../../../../site/src/content/docs/expenses/record-an-expense.mdx)
