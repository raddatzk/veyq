-- Scope-key rotation via content-key epochs (en.spaces.scope-key-rotation, cr-0088, ADR 0024). Removing a member
-- rotates the scope's content key forward into a new epoch granted only to the remaining members; new content
-- seals under it. Operator-blind: the server stores only the opaque epoch-chain links + a per-membership granted
-- epoch + a per-node epoch index — never a key. Everything defaults to epoch 0 (the pre-rotation baseline), so
-- existing rows are untouched.

ALTER TABLE veyq_scope ADD COLUMN current_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_membership ADD COLUMN granted_epoch INT NOT NULL DEFAULT 0;
ALTER TABLE veyq_node ADD COLUMN epoch INT NOT NULL DEFAULT 0;

-- The epoch-chain links: for epoch n >= 1, wrapped_prev = symWrap(E_n, E_{n-1}) (opaque base64). A member holding
-- the latest epoch peels these down to E_0 to read content of any epoch.
CREATE TABLE veyq_scope_epoch (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id     VARCHAR(36) NOT NULL,
    epoch        INT         NOT NULL,
    wrapped_prev TEXT        NOT NULL,
    created_at   TIMESTAMP   NOT NULL,
    CONSTRAINT fk_scope_epoch_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);
CREATE UNIQUE INDEX ux_scope_epoch ON veyq_scope_epoch (scope_id, epoch);
