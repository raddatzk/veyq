-- Per-(member, scope, node) item favorites (en.memory.favorites, ADR 0068). Operator-blind: `ciphertext` is
-- one member's marked-media-id set for ONE Memory, AEAD-sealed under K_SC at the base epoch — stored verbatim,
-- never interpreted. Unlike the pin set in veyq_scope_favorites this blob is deliberately SHARED: the read
-- returns every member's row for the node so the client can render "everyone's / only mine" and a per-item
-- count, which a blind server cannot aggregate for it.
--
-- Partitioned per node rather than per scope because the server has already observed that node id (the client
-- just listed the node's media), so the partition leaks nothing new — while it keeps the 65 536-char cap
-- beyond reach and keeps a single toggle from re-uploading the whole scope's set.
--
-- The natural key (user, scope, node) is encoded as one synthetic id (`userId|scopeId|nodeId`, 3x36 + 2) so
-- upsert is a plain primary-key lookup, as in V12.
--
-- The scope FK is load-bearing, not decoration: it puts this table in the erasure closure, so
-- scripts/validate_scope_erasure_arity.py stays red until ScopePurge.eraseScope names it. `node_id` carries
-- NO foreign key on purpose — node deletion is a SOFT delete (ADR 0061), so a cascade would destroy marks at
-- the moment a Memory is trashed rather than when it is purged. It is reclaimed by code, like veyq_media.
CREATE TABLE veyq_scope_item_favorites (
    id         VARCHAR(110) NOT NULL,
    user_id    VARCHAR(36)  NOT NULL,
    scope_id   VARCHAR(36)  NOT NULL,
    node_id    VARCHAR(36)  NOT NULL,
    ciphertext MEDIUMTEXT   NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_item_favorites_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

CREATE INDEX ix_item_favorites_scope_node ON veyq_scope_item_favorites (scope_id, node_id);
CREATE INDEX ix_item_favorites_node ON veyq_scope_item_favorites (node_id);
