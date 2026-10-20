-- Everything from one uploader (ADR 0093 §§ 1, 6; a moderator's UPLOADER_CONTENT step, uc.moderation.take-a-measure):
-- a measure of kind CONTENT_UPLOADER names the uploader (target_id: an account's or a guest identity's id; share_id the
-- guest's link) and the space (scope_id), and blocks exactly the items it counted when it was taken — listed here, one row
-- per item: item_kind NODE (a node whose content the uploader put there, a Depot file) or ITEM (an application's item, a
-- Memories medium), target_id its id. Never what the uploader adds later: a person decided on what existed. An item's
-- erasure deletes its row (MeasureTargets.erased), and the measure ends with its last one or with its uploader. The rows
-- go with their measure's (ModerationRecordsPurge deletes them first; the foreign key refuses the other order). Clear ids
-- only, as every measure's.
CREATE TABLE veyq_moderation_block_reach (
    block_id  UUID        NOT NULL,
    item_kind VARCHAR(8)  NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (block_id, item_kind, target_id),
    CONSTRAINT fk_moderation_block_reach_block FOREIGN KEY (block_id) REFERENCES veyq_moderation_block (id)
);
CREATE INDEX ix_moderation_block_reach_target ON veyq_moderation_block_reach (target_id);
