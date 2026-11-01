-- ADR 0112 decision 5: Expenses' receipts are blobs of core's blob service now (`veyq_application_blob`), and the
-- receipts of `veyq_expenses_receipt` are discarded, not migrated (no instance is live; the owner chose a reset of
-- Expenses' data on the dev instances over a migration).
--
-- **This migration deletes no row: it refuses to run while the table holds one.** The CHECK below can hold for no row,
-- so adding it fails on a table that holds any — and the instance does not start, naming
-- `ck_expenses_receipt_reset_first`. The reset before it is a manual step (infra/README.md § Expenses' receipts moved to
-- core): every Pot deleted and purged from its space's trash, which is today's erasure of its receipts, their blob rows,
-- their store objects (put on the reclaim list) and everything else of the Pot, or the dev instance reset whole with
-- its store volume. Deleting here in SQL would be a second copy of that erasure that no arity guard watches, and a bare
-- DROP would leave the receipts' blob rows with no arm and their objects in the store.
--
-- On an empty table the CHECK is added and the table dropped with it. A failed run changed nothing: the ALTER is refused
-- whole, so the same migration runs again once the table is empty.
ALTER TABLE veyq_expenses_receipt ADD CONSTRAINT ck_expenses_receipt_reset_first CHECK (id IS NULL);
DROP TABLE veyq_expenses_receipt;
