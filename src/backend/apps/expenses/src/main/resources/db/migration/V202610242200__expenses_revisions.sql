-- ADR 0108 decisions 3-6: a Pot's records (participants and entries) are sequences of sealed revisions, appended and
-- never overwritten. One row per revision; the server holds what ADR 0108 § Consequences names and nothing else:
--
-- - `entry_id` / `revision`: the record (client-minted id) and its n-th revision, unique together. A record belongs to
--   one Pot; the append refuses an id another Pot holds (`id_taken`).
-- - `tombstone`: the writer marked a delete. `erasure`: written by a member's erasure of a participant, after which
--   none of the record's earlier revisions is held.
-- - `sealed_body`: the padded plaintext sealed under the revision's own K_rev (`expense-entry`), one of four lengths;
--   `size_class` its padded plaintext size (512, 2048, 8192, 16384). A re-key never rewrites it.
-- - `wrapped_revision_key`: K_rev under the Pot's key (`expense-entry-key`) — the one field a re-key rewrites.
-- - `writer_user_id` / `writer_identity_id`: who appended it, a member or a link identity, exactly one
--   (ck_expenses_revision_writer), the pair `PrincipalKey` names: no foreign key on the member, who stays named after
--   leaving; one on the identity, which `ExpensesGuestIdentityUsage` keeps while a revision names it.
-- - `item_position`: the Pot's item revision core's bump returned for the append, the list's keyset with
--   (entry_id, revision) (docs/apps/README.md § How an application publishes a revisioned list).
--
-- The scope FK is load-bearing: it puts the table in the scope erasure's closure (validate_scope_erasure_arity.py).
-- node_id has no FK, as a medium's: a Pot's trash is soft, and its purge erases these rows first (phase 100).
CREATE TABLE veyq_expenses_revision (
    id                   UUID         NOT NULL,
    scope_id             UUID         NOT NULL,
    node_id              UUID         NOT NULL,
    entry_id             UUID         NOT NULL,
    revision             BIGINT       NOT NULL,
    tombstone            BOOLEAN      NOT NULL,
    erasure              BOOLEAN      NOT NULL,
    sealed_body          TEXT         NOT NULL,
    wrapped_revision_key TEXT         NOT NULL,
    size_class           INT          NOT NULL,
    writer_user_id       UUID         NULL,
    writer_identity_id   UUID         NULL,
    item_position        INT          NOT NULL,
    created_at           DATETIME(3)  NOT NULL,
    CONSTRAINT pk_expenses_revision PRIMARY KEY (id),
    CONSTRAINT ux_expenses_revision_entry UNIQUE (entry_id, revision),
    CONSTRAINT fk_expenses_revision_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id),
    CONSTRAINT fk_expenses_revision_identity FOREIGN KEY (writer_identity_id) REFERENCES veyq_share_identity (id),
    CONSTRAINT ck_expenses_revision_writer CHECK ((writer_user_id IS NULL AND writer_identity_id IS NOT NULL)
        OR (writer_user_id IS NOT NULL AND writer_identity_id IS NULL))
);
-- The keyset's index: one Pot's revisions in (item_position, entry_id, revision) order, so a page is a range read.
CREATE INDEX ix_expenses_revision_keyset ON veyq_expenses_revision (node_id, item_position, entry_id, revision);
CREATE INDEX ix_expenses_revision_scope ON veyq_expenses_revision (scope_id);
CREATE INDEX ix_expenses_revision_identity ON veyq_expenses_revision (writer_identity_id);
CREATE INDEX ix_expenses_revision_user ON veyq_expenses_revision (writer_user_id);
