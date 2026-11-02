-- ADR 0115 (owner 2026-11-02): a rotation re-wraps keys and never rewrites a value. Every scope-level value that has an
-- author is sealed under a value key K_v of its own, minted at every write, and only K_v is under the scope key: its
-- wrap (seal purpose `value-key`, base64, at most 128 characters) is stored beside the value, non-null exactly when the
-- value is. A rotation's commit rewrites the wraps only (ScopeRekeyCommit), so no rotator rewrites another member's
-- name, picture, join record or verification, a group's name or icon, or a link's recovery copy.
--
-- No instance is live and the dev database is reset, so no stored value is migrated: a value written before this one has
-- no wrap and none can be made for it here (the server holds no key), so it is cleared where its column allows and its
-- row deleted where the value is the row — a verification, read by nobody without its wrap. A group's name stays (a
-- group is never nameless): without a wrap it reads as unreadable until the owner renames it.
ALTER TABLE veyq_tenant_profile ADD COLUMN wrapped_name_value_key VARCHAR(128) NULL;
ALTER TABLE veyq_tenant_profile ADD COLUMN wrapped_avatar_value_key VARCHAR(128) NULL;
ALTER TABLE veyq_tenant_profile ADD COLUMN wrapped_join_record_value_key VARCHAR(128) NULL;
UPDATE veyq_tenant_profile SET sealed_name = NULL, sealed_avatar = NULL, sealed_join_record = NULL, join_endorsement = NULL;

DELETE FROM veyq_verification;
ALTER TABLE veyq_verification ADD COLUMN wrapped_value_key VARCHAR(128) NOT NULL;

ALTER TABLE veyq_scope ADD COLUMN wrapped_name_value_key VARCHAR(128) NULL;
ALTER TABLE veyq_scope ADD COLUMN wrapped_icon_value_key VARCHAR(128) NULL;
UPDATE veyq_scope SET sealed_icon = NULL, icon_epoch = 0, icon_editor_user_id = NULL;

ALTER TABLE veyq_share ADD COLUMN wrapped_recovery_value_key VARCHAR(128) NULL;
UPDATE veyq_share SET sealed_share_key = NULL, sealed_share_key_epoch = NULL;

-- A stage left from before this rule holds re-sealed values, which the commit now refuses (`rekey_writes_value`): it
-- carries nothing the next rotation can use.
DELETE FROM veyq_scope_rekey_stage;
