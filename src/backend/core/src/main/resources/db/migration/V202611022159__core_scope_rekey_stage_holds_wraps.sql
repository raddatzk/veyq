-- ADR 0115 decision 2 (owner 2026-11-02): a rotation stages wraps only, so its stage is sized for them.
--
-- `veyq_scope_rekey_stage` was built to hold re-sealed values (a Memories item-mark set ran to 256 KiB, so MEDIUMTEXT)
-- and the SHA-256 of the ciphertext each replaced. What it holds now is a value key's wrap (`value-key`: 1 + 12 + 32 + 16
-- bytes, 84 base64 characters, a contract cap of 128) or a scope-root entry's part: its key wrap (84, cap 256), its slug
-- token (an HMAC-SHA256, 44, cap 128) and its name (a field seal of any name a member may write, the contract's cap of
-- 4,096). The name is the largest, so `stage_value` is TEXT (65,535 bytes); a column of its own for the short parts
-- would add a rule and save nothing, as TEXT stores what it holds. A wrap is short enough to compare itself: `replaced`
-- is the wrap the rotator re-wrapped, as it read it (VARCHAR(128), the wrap columns' width), and the commit refuses a
-- row that holds another — no digest on either side. A root entry is held by `revision`, its placement revision, as
-- before. The table is recreated rather than altered: a stage is a day's scratch that no commit can take across this
-- change (it names no `replaced`), so its rows go either way and the rotator stages again.
DROP TABLE veyq_scope_rekey_stage;
CREATE TABLE veyq_scope_rekey_stage (
    id          UUID         NOT NULL,
    rekey_id    UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    part        VARCHAR(32)  NOT NULL,
    item_id     VARCHAR(80)  NOT NULL,
    field       VARCHAR(32)  NOT NULL,
    stage_value TEXT         NOT NULL,
    replaced    VARCHAR(128) NULL,
    revision    BIGINT       NULL,
    created_at  DATETIME(3)  NOT NULL,
    CONSTRAINT pk_scope_rekey_stage PRIMARY KEY (id),
    CONSTRAINT ux_scope_rekey_stage_value UNIQUE (rekey_id, part, item_id, field)
);
CREATE INDEX ix_scope_rekey_stage_scope ON veyq_scope_rekey_stage (scope_id);
CREATE INDEX ix_scope_rekey_stage_created ON veyq_scope_rekey_stage (created_at);

-- A link's mint epoch (`minted_epoch`, ADR 0075 point 4's re-issue rule) was kept apart from its recovery copy's epoch
-- while a rotation re-sealed the copy at every new epoch. A rotation now re-wraps only the copy's value key, and the copy
-- and `sealed_share_key_epoch` are written once, at the mint (a re-issue mints a new row): the two are one column. On
-- every row that holds a copy they are equal already — the value-key migration cleared every copy written before, and
-- every copy since was minted with both — so the rule reads `sealed_share_key_epoch` and this one goes.
ALTER TABLE veyq_share DROP COLUMN minted_epoch;
