-- #94 — five columns declared a contract `maxLength` larger than a MariaDB `TEXT` column can hold, and H2
-- (the test engine) hid it: `TEXT` is a **65 535-byte** ceiling on MariaDB, effectively unbounded on H2. Under
-- the shipped `sql_mode` (STRICT_TRANS_TABLES) an oversize write is a hard `ERROR 1406 (Data too long)` → a 500
-- on a contract-valid body; with strict off it degrades to a silent truncation of the ciphertext. Either way the
-- hermetic H2 suite stays green and only prod fails. See the base64-cap guard (`scripts/validate_maxlength_storage.py`)
-- that now pins the contract side of this.
--
-- The four sealed columns hold **base64** ciphertext (ASCII → 1 byte/char), so their contract char-caps map
-- 1:1 to bytes: `blob` caps at 1 MiB, the three others at 65 536 (= 64 KiB, one byte over `TEXT`). The fifth,
-- `terms_markdown_by_locale`, is the operator's **free-text** terms as a per-locale JSON map (NOT base64); under
-- the server's utf8mb4 default a codepoint is up to 4 bytes, so two 20 000-char locales can reach ~160 KB — no
-- single `maxLength` exceeds `TEXT`, but the serialized column does. All five move to MEDIUMTEXT (16 MiB).
--
-- `MODIFY COLUMN` is MariaDB-native; H2 in its default mode rejects it (only `ALTER COLUMN … SET DATA TYPE` parses
-- there), and MariaDB rejects that form — there is **no** type-change statement in the H2 ∩ MariaDB subset. So the
-- test datasource now runs H2 with `MODE=MariaDB` (application-test.properties), which makes `MODIFY COLUMN` parse
-- and raises DDL parity in general; it does NOT make H2 enforce the byte ceiling — the guard owns that.
ALTER TABLE veyq_identity_pins MODIFY COLUMN sealed_blob MEDIUMTEXT;              -- 1 MiB pin store (base64)
ALTER TABLE veyq_scope_favorites MODIFY COLUMN ciphertext MEDIUMTEXT NOT NULL;    -- 64 KiB favorites set (base64), preserve NOT NULL
ALTER TABLE veyq_job MODIFY COLUMN sealed_body MEDIUMTEXT;                        -- 64 KiB task body (base64)
ALTER TABLE veyq_job MODIFY COLUMN sealed_result MEDIUMTEXT;                      -- 64 KiB task result (base64)
ALTER TABLE veyq_tenant MODIFY COLUMN terms_markdown_by_locale MEDIUMTEXT;        -- ~160 KB worst-case free-text terms map (utf8mb4)
