-- Group lifecycle: soft-delete + grace period (uc.spaces.delete-group, cr-0090, ADR 0014 §6). A GroupOwner
-- soft-deletes; the scope enters pending-deletion (deleted_at set) and a scheduled job hard-erases it after a
-- grace window. During grace the group stays readable but read-only. `null` = active (the default), so existing
-- rows are untouched. Operational metadata (a lifecycle timestamp) — no key.

ALTER TABLE veyq_scope ADD COLUMN deleted_at TIMESTAMP NULL;
