-- A medium's place in its node's list (cr-gallery-list-paging, ADR 0033 decisions 3 and 6): the node's item revision its
-- last change bumped to, stamped under the revision row's lock that bump holds to the commit, so places become visible
-- in commit order. The gallery's keyset is (item_position, id): a list read in pages, and the live poll after it, never
-- see a row land behind a cursor they already passed - which updated_at, stamped before that lock, allowed. A row from
-- before this column sits at 0, ordered by its id among the others there; its next change gives it a place.
ALTER TABLE veyq_media ADD COLUMN item_position INT NOT NULL DEFAULT 0;

-- The keyset's index: one node's rows in (item_position, id) order, so a page is a range read, not a sort of the node.
CREATE INDEX ix_media_keyset ON veyq_media (node_id, item_position, id);
