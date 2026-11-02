-- ADR 0115 decision 2: a rotation re-seals every member's item marks under the new scope key, through Memories' scope
-- re-key participant. The rotator reads and stages them by an opaque `mark_id`, never by the row's own id, which spells
-- the member's account id (`user|scope|node`): the rows stay as unattributed to the rotator as the node's read serves
-- them (ADR 0068 § 9). Each existing row gets a fresh random one.
ALTER TABLE veyq_scope_item_favorites ADD COLUMN mark_id UUID NULL;
UPDATE veyq_scope_item_favorites SET mark_id = UUID();
ALTER TABLE veyq_scope_item_favorites MODIFY COLUMN mark_id UUID NOT NULL;
CREATE UNIQUE INDEX ux_item_favorites_mark ON veyq_scope_item_favorites (mark_id);
