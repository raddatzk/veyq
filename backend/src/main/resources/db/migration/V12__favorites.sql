-- Per-(user, scope) favorites (en.memory.favorites, cr-0031). Operator-blind: `ciphertext` is the caller's
-- pinned-Memory set, AEAD-sealed under K_SC — stored verbatim, never interpreted. The natural key (user, scope)
-- is encoded as a single synthetic id (`userId|scopeId`) so upsert is a plain primary-key lookup.
CREATE TABLE veyq_scope_favorites (
    id         VARCHAR(73)  NOT NULL,
    user_id    VARCHAR(36)  NOT NULL,
    scope_id   VARCHAR(36)  NOT NULL,
    ciphertext TEXT         NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
