-- Node lifecycle: a delete becomes a SOFT-delete of the whole subtree (uc.memory.delete-node, ADR 0061).
-- Mirrors V26__scope_soft_delete.sql: `deleted_at` null = live (the default), so existing rows are untouched.
-- The subtree leaves every user- and guest-facing read while the marker is set; a scheduled job hard-erases it
-- (nodes + media + derivatives + buckets + blobs + store objects) once the marker is older than the retention
-- window. Operational metadata (a lifecycle timestamp) — no name, no key, no content.
--
-- Deliberately does NOT touch ux_node_parent_slug (V10__nodes.sql): the unique index still spans trashed rows,
-- so the slug is freed by the WRITE (slug_token := the node's own id, ADR 0061 Decision 5) and not by the
-- schema. A partial index filtered on `deleted_at IS NULL` is the standard answer and is unavailable here —
-- prod is MariaDB, which has no filtered indexes, and the schema is held to the H2 ∩ MariaDB subset
-- (ADR 0061 rejected (d); V35 was already told to add one and could not).

ALTER TABLE veyq_node ADD COLUMN deleted_at TIMESTAMP NULL;
