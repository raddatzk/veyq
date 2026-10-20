-- The outside services a member's browser calls (ADR 0088 decision 7, ADR 0089): one row per service the Server Owner
-- configured, keyed by its register entry (contracts/platform/capabilities.json, source external-service), none for one
-- that is not configured — its entry is then not active and the served CSP leaves its origin out. A new instance starts
-- with the defaults the web client was built with until now, so an upgrade changes nothing: OpenStreetMap's tiles with
-- their attribution (ADR 0018), Nominatim (ADR 0016), the HIBP range API.
CREATE TABLE veyq_external_service (
    capability_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    url           VARCHAR(512) NOT NULL,
    attribution   VARCHAR(256)
);

INSERT INTO veyq_external_service (capability_id, url, attribution) VALUES
    ('memories.map', 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', '© OpenStreetMap contributors'),
    ('memories.places', 'https://nominatim.openstreetmap.org', NULL),
    ('core.breached-password', 'https://api.pwnedpasswords.com/range/', NULL);
