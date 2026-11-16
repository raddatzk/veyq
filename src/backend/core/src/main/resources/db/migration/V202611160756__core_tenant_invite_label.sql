-- A link member invite's private label (ADR 0129 decision 7): the owners' name for whom the link is for, sealed under a
-- value key of its own (field purpose `member-invite-label`, base64) whose wrap is under the tenant scope's current key
-- (seal purpose `value-key`, base64, at most 128 characters), bound to the tenant and the invite. Null on a mailed
-- invite, on an owner invite and on every invite that is no longer ACTIVE - dropped with the invite's key and grant.
-- label_editor_user_id: the owner who last wrote sealed_label (ADR 0093 § 8) - no foreign key, null exactly while
-- the label is.
ALTER TABLE veyq_tenant_invite ADD COLUMN sealed_label TEXT NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN wrapped_label_value_key VARCHAR(128) NULL;
ALTER TABLE veyq_tenant_invite ADD COLUMN label_editor_user_id UUID NULL;
