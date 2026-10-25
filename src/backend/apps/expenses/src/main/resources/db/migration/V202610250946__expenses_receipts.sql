-- ADR 0108 decision 7: a receipt is a blob kept with an entry of a Pot, any number per entry, in Expenses' own table —
-- core's attachments hold one blob per named slot of a node (ux_attachment_node_slot), so a receipt is a row here,
-- filled through core's UploadCompletion. The server holds what ADR 0108 § Consequences names and nothing else:
--
-- - `id`: the receipt's client-minted id (ADR 0072 decision 3), the one the entry's sealed revisions list it by.
-- - `entry_id`: the record it is kept with. Plain, no FK: a new entry's receipts are completed before its first
--   revision, and a record belongs to one Pot (the completion refuses an entry of another Pot, `id_taken`).
-- - `blob_id`: the blob, its K_blob wrapped under the Pot's key and bound to (blob, Pot, `EXPENSES_RECEIPT`) in its
--   `veyq_blob` row (no new blob purpose). The fourth arm of `veyq_blob` (validate_blob_reference_arity.py), born with
--   its foreign key; unique, as every arm's blob is its own.
-- - `writer_user_id` / `writer_identity_id`: who completed it, a member or a link identity, exactly one
--   (ck_expenses_receipt_writer), the pair `PrincipalKey` names — as a revision's writer.
-- - `created_at`: when the server stored it. `dropped_at`: when a revision of its entry named it dropped (null while
--   none did); the purge erases it once the trash retention has passed since.
--
-- The scope FK puts the table in the scope erasure's closure (validate_scope_erasure_arity.py). node_id has no FK, as a
-- revision's: a Pot's trash is soft, and its purge erases these rows first (phase 150).
CREATE TABLE veyq_expenses_receipt (
    id                 UUID         NOT NULL,
    scope_id           UUID         NOT NULL,
    node_id            UUID         NOT NULL,
    entry_id           UUID         NOT NULL,
    blob_id            UUID         NOT NULL,
    writer_user_id     UUID         NULL,
    writer_identity_id UUID         NULL,
    created_at         DATETIME(3)  NOT NULL,
    dropped_at         DATETIME(3)  NULL,
    CONSTRAINT pk_expenses_receipt PRIMARY KEY (id),
    CONSTRAINT ux_expenses_receipt_blob UNIQUE (blob_id),
    CONSTRAINT fk_expenses_receipt_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_expenses_receipt_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_expenses_receipt_identity FOREIGN KEY (writer_identity_id) REFERENCES veyq_share_identity (id),
    CONSTRAINT ck_expenses_receipt_writer CHECK ((writer_user_id IS NULL AND writer_identity_id IS NOT NULL)
        OR (writer_user_id IS NOT NULL AND writer_identity_id IS NULL))
);
-- A Pot's receipts in id order (its reads, its re-key, its purge); an entry's (a drop, a report, a hold).
CREATE INDEX ix_expenses_receipt_node ON veyq_expenses_receipt (node_id, id);
CREATE INDEX ix_expenses_receipt_entry ON veyq_expenses_receipt (entry_id);
CREATE INDEX ix_expenses_receipt_scope ON veyq_expenses_receipt (scope_id);
CREATE INDEX ix_expenses_receipt_identity ON veyq_expenses_receipt (writer_identity_id);
CREATE INDEX ix_expenses_receipt_user ON veyq_expenses_receipt (writer_user_id);
-- The purge's selection: the dropped ones, oldest first.
CREATE INDEX ix_expenses_receipt_dropped ON veyq_expenses_receipt (dropped_at);
