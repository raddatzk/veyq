-- ADR 0109: a scope rotation re-keys every node below it.
--
-- `veyq_node.epoch` is the epoch a node's key was minted at; a node below its scope's current epoch still carries a
-- key from before the rotation. Whether a scope is re-keying is derived from that, never stored, and the index keeps
-- the probe ("does any node of this scope lag its current epoch?") to one range read.
CREATE INDEX ix_node_scope_epoch ON veyq_node (scope_id, epoch);

-- One node's re-key arrives in staged parts before its commit (a folder of many children, a Memory of many photos):
-- each row is one ciphertext value the commit swaps into place, keyed by the client-minted re-key id. Nothing reads a
-- staged value but the commit; a stage no commit took is deleted after a day. No foreign key: the purge and the
-- scope's erasure delete these rows themselves (core's re-key erasers).
CREATE TABLE veyq_node_rekey_stage (
    id         UUID         NOT NULL,
    rekey_id   UUID         NOT NULL,
    scope_id   UUID         NOT NULL,
    node_id    UUID         NOT NULL,
    part       VARCHAR(32)  NOT NULL,
    item_id    VARCHAR(64)  NOT NULL,
    field      VARCHAR(32)  NOT NULL,
    stage_value TEXT        NOT NULL,
    revision   BIGINT,
    created_at DATETIME(3)  NOT NULL,
    CONSTRAINT pk_node_rekey_stage PRIMARY KEY (id),
    CONSTRAINT ux_node_rekey_stage_value UNIQUE (rekey_id, part, item_id, field)
);
CREATE INDEX ix_node_rekey_stage_node ON veyq_node_rekey_stage (node_id);
CREATE INDEX ix_node_rekey_stage_scope ON veyq_node_rekey_stage (scope_id);
CREATE INDEX ix_node_rekey_stage_created ON veyq_node_rekey_stage (created_at);
