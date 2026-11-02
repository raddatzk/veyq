-- ADR 0115: a scope key is replaced, never chained. ADR 0114 decisions 3-4: the node model both records share.
--
-- A node's `epoch` becomes its OWN key's (0 at its mint, +1 with every replacement), and what it meant until now - the
-- scope's epoch when the key was minted, which the `content-key` and `node-name` seals bind - moves to `scope_epoch`.
-- A node lags exactly when it is marked due (`rekey_due`): a rotation's commit marks every node of its scope, a link's
-- close every node below the link's root, and the node's re-key commit clears the mark. A node that lagged before this
-- migration (its key minted below its scope's current epoch) is due from here on; every own epoch starts at 0 (no
-- instance is live, and a node's own key has not been replaced under the new meaning yet). The index answers "is any
-- node of this scope due?" (`ScopeRekeying`) with one range read, as `ix_node_scope_epoch` answered "does any lag?".
ALTER TABLE veyq_node ADD COLUMN scope_epoch INT NOT NULL DEFAULT 0;
UPDATE veyq_node SET scope_epoch = epoch;
ALTER TABLE veyq_node ADD COLUMN rekey_due BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE veyq_node SET rekey_due = TRUE
    WHERE scope_epoch < (SELECT s.current_epoch FROM veyq_scope s WHERE s.id = veyq_node.scope_id);
UPDATE veyq_node SET epoch = 0;
DROP INDEX ix_node_scope_epoch ON veyq_node;
CREATE INDEX ix_node_scope_due ON veyq_node (scope_id, rekey_due);

-- The epoch chain goes (decision 1): nothing seals an older scope key under a newer one. Each epoch n >= 1 keeps its
-- succession statement instead (decision 5): the epoch's succession public key and the signature of epoch n - 1's
-- succession key over (scope, n, that key) - public values the server stores and serves and never verifies. The scope
-- FK puts the table in the scope erasure's closure (validate_scope_erasure_arity.py).
DROP TABLE veyq_scope_epoch;
CREATE TABLE veyq_scope_succession (
    scope_id              UUID        NOT NULL,
    epoch                 INT         NOT NULL,
    succession_public_key VARCHAR(44) NOT NULL,
    succession_signature  VARCHAR(88) NOT NULL,
    CONSTRAINT pk_scope_succession PRIMARY KEY (scope_id, epoch),
    CONSTRAINT fk_scope_succession_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

-- A link's recovery copy moves to every new epoch with the rotation's re-seal (decision 2), so the epoch it is sealed at
-- no longer says when the link was minted. The re-issue rule (ADR 0075 point 4) reads `minted_epoch` instead: the
-- scope's epoch at the mint, stamped once, never moved. Null exactly for a link minted without a recovery copy.
ALTER TABLE veyq_share ADD COLUMN minted_epoch INT NULL;
UPDATE veyq_share SET minted_epoch = sealed_share_key_epoch;

-- The scope-level rows a rotation re-seals (decision 2), staged in pages before its commit under the rotator's re-key
-- id: one ciphertext per (owner part, row, column). A table of its own rather than `veyq_node_rekey_stage`: a scope-level
-- row is addressed by no node (a verification by two account ids, longer than that table's item id), a value runs to a
-- Memories item-mark row's 256 KiB (MEDIUMTEXT), and the commit's compare-and-set needs the digest of the ciphertext the
-- value replaces (`replaced_digest`, base64 SHA-256; null when the row held none at the stage) — and, for a scope-root
-- node's entry, which carries one, the placement revision it was re-sealed against (`revision`).
-- Nothing reads a staged value but the rotation's commit; a stage no commit took is deleted after a day. No foreign key:
-- the scope's erasure deletes these rows itself (`ScopeRekeyStageScopeEraser`).
CREATE TABLE veyq_scope_rekey_stage (
    id              UUID        NOT NULL,
    rekey_id        UUID        NOT NULL,
    scope_id        UUID        NOT NULL,
    part            VARCHAR(32) NOT NULL,
    item_id         VARCHAR(80) NOT NULL,
    field           VARCHAR(32) NOT NULL,
    stage_value     MEDIUMTEXT  NOT NULL,
    replaced_digest VARCHAR(44) NULL,
    revision        BIGINT      NULL,
    created_at      DATETIME(3) NOT NULL,
    CONSTRAINT pk_scope_rekey_stage PRIMARY KEY (id),
    CONSTRAINT ux_scope_rekey_stage_value UNIQUE (rekey_id, part, item_id, field)
);
CREATE INDEX ix_scope_rekey_stage_scope ON veyq_scope_rekey_stage (scope_id);
CREATE INDEX ix_scope_rekey_stage_created ON veyq_scope_rekey_stage (created_at);
