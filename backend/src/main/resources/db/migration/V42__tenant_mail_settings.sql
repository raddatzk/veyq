-- cr-email-smtp-transport (ADR 0048/0049/0050): per-tenant SMTP + owner-authored invite templates.
--
-- Each tenant may configure its OWN SMTP as runtime settings (mirrors terms-of-service, ADR 0048) so member
-- invites are emailed from the tenant's own domain. One row per tenant (PK = tenant_id). All transport fields
-- are nullable = "not configured" -> the copy-link fallback (ADR 0048 §4). The SMTP password is a server-held
-- secret NEVER stored in plaintext: it lives sealed as ciphertext + nonce (AES-256-GCM under the env KEK,
-- ADR 0050); a null password_ciphertext = no password stored. subject_template / body_template are the
-- owner-authored invite templates (null = veyq's bilingual EN+DE default), rendered over the closed variable
-- set {{link}}/{{domain}}/{{expiry}} (ADR 0049) -- operator-visible operational config, not E2EE content.

CREATE TABLE veyq_tenant_mail_settings (
    tenant_id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    host                VARCHAR(253),
    port                INT,
    from_address        VARCHAR(254),
    username            VARCHAR(254),
    password_ciphertext TEXT,
    password_nonce      TEXT,
    tls                 BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    subject_template    VARCHAR(200),
    body_template       TEXT,
    CONSTRAINT fk_mail_settings_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE
);
