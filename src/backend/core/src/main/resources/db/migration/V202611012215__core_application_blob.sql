-- ADR 0112 decision 1: the blobs an application stores, managed by core's blob service — any number per node, each in a
-- role its application declares and held by an owner, an application-side id core stores and compares and never
-- resolves (Expenses' entry id). The server holds what ADR 0112 decision 6 names and nothing else:
--
-- - `id`: the client-minted id (ADR 0072 decision 3), the one the application's own sealed records name it by
--   (Expenses: a receipt id in an entry's revision).
-- - `node_id`: the node it belongs to; its `K_blob` is wrapped under the node's key and bound to (blob, node, `role`) in
--   its `veyq_blob` row (no new blob purpose). No FK, as an application's record rows: a node's trash is soft, and its
--   purge erases these rows first (`ApplicationBlobNodeSubtreeEraser`, phase 160).
-- - `owner_id`: the application's record that holds it. Plain, no FK: a record may get its first row after its blobs.
-- - `role`: the application's open `BlobRole` token (16 characters, the contract's bound).
-- - `blob_id`: the blob — the fourth arm of `veyq_blob` (validate_blob_reference_arity.py), born with its foreign key;
--   unique, as every arm's blob is its own.
-- - `writer_user_id` / `writer_identity_id`: who completed it, a member or a link identity, exactly one
--   (ck_application_blob_writer), the pair `PrincipalKey` names.
-- - `created_at`: when the server stored it. `kept_at`: when its owner last kept it — set by the application's write
--   (`ApplicationBlobs.keep`), cleared for good by a release by name. `released_at`: when it was released, by name or with
--   its owner's end; the owner's next keep clears the second kind. The purge erases a row once the trash retention has
--   passed since its release, or since its completion while nothing ever kept it.
--
-- The scope FK puts the table in the scope erasure's closure (validate_scope_erasure_arity.py).
CREATE TABLE veyq_application_blob (
    id                 UUID         NOT NULL,
    scope_id           UUID         NOT NULL,
    node_id            UUID         NOT NULL,
    owner_id           UUID         NOT NULL,
    role               VARCHAR(16)  NOT NULL,
    blob_id            UUID         NOT NULL,
    writer_user_id     UUID         NULL,
    writer_identity_id UUID         NULL,
    created_at         DATETIME(3)  NOT NULL,
    kept_at            DATETIME(3)  NULL,
    released_at        DATETIME(3)  NULL,
    CONSTRAINT pk_application_blob PRIMARY KEY (id),
    CONSTRAINT ux_application_blob_blob UNIQUE (blob_id),
    CONSTRAINT fk_application_blob_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_application_blob_blob FOREIGN KEY (blob_id) REFERENCES veyq_blob (id),
    CONSTRAINT fk_application_blob_identity FOREIGN KEY (writer_identity_id) REFERENCES veyq_share_identity (id),
    CONSTRAINT ck_application_blob_writer CHECK ((writer_user_id IS NULL AND writer_identity_id IS NOT NULL)
        OR (writer_user_id IS NOT NULL AND writer_identity_id IS NULL))
);
-- A node's blobs in id order (its reads, its re-key, its purge); an owner's (a keep, a release, a report, a hold).
CREATE INDEX ix_application_blob_node ON veyq_application_blob (node_id, id);
CREATE INDEX ix_application_blob_owner ON veyq_application_blob (owner_id);
CREATE INDEX ix_application_blob_scope ON veyq_application_blob (scope_id);
CREATE INDEX ix_application_blob_identity ON veyq_application_blob (writer_identity_id);
CREATE INDEX ix_application_blob_user ON veyq_application_blob (writer_user_id);
-- The purge's selections: the released ones, and the never kept ones, oldest first.
CREATE INDEX ix_application_blob_released ON veyq_application_blob (released_at);
CREATE INDEX ix_application_blob_created ON veyq_application_blob (created_at);
