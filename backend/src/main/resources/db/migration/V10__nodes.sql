-- The encrypted Memory tree (en.memory.tree, cr-0027). A node is a FOLDER (organization only), a MEMORY
-- (content unit; owns a per-Memory key K_mem in wrapped_content_key) or a CHAPTER (inside a Memory). The
-- server is operator-blind: encrypted_name + wrapped_content_key are opaque ciphertext, slug_token is a
-- blinded keyed MAC; it stores only the tree's shape + ordering + the blinded tokens, never a name or key.
--
-- parent_id is the "parent slot": the parent node id, or the SCOPE id for a root-level node (matching the
-- client's parentSlot used to compute the slug token). It is therefore NOT NULL and NOT a single-table FK
-- (it references a node OR the scope). Slug uniqueness is per parent (UNIQUE(parent_id, slug_token)) — works
-- at the root too because root nodes share parent_id = scope_id. Types kept to the H2 ∩ MariaDB subset.
CREATE TABLE veyq_node (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id            VARCHAR(36) NOT NULL,
    parent_id           VARCHAR(36) NOT NULL,
    kind                VARCHAR(16) NOT NULL,
    encrypted_name      TEXT NOT NULL,
    slug_token          VARCHAR(128) NOT NULL,
    order_index         INT NOT NULL,
    wrapped_content_key TEXT,
    created_at          TIMESTAMP NOT NULL,
    CONSTRAINT fk_node_scope FOREIGN KEY (scope_id) REFERENCES veyq_scope (id)
);

CREATE UNIQUE INDEX ux_node_parent_slug ON veyq_node (parent_id, slug_token);
CREATE INDEX ix_node_scope ON veyq_node (scope_id);
