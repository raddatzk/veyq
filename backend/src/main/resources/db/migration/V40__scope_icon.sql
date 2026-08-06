-- uc.spaces.set-space-icon: an optional space icon on the scope row — a padded, constant-length image sealed
-- under K_icon = HKDF(E_iconEpoch, "veyq/v1/space-icon-key") (ADR 0027 derived-key + per-field epoch model).
-- The first sealed content on a TENANT row; opaque ciphertext the server never opens. Types kept to the
-- H2 ∩ MariaDB common subset (TEXT for opaque base64, INT for the epoch), like V24/V28's name pair.
ALTER TABLE veyq_scope ADD COLUMN sealed_icon TEXT NULL;              -- opaque base64 ciphertext; null = no icon
ALTER TABLE veyq_scope ADD COLUMN icon_epoch  INT NOT NULL DEFAULT 0; -- the content-key epoch the icon was sealed under
