-- The operating mode and the operator details (ADR 0093 §§ 11 and 14, ADR 0035 decisions 6-7), one row once the
-- Server Owner chose a mode or saved a value; first-run setup writes it with the mode. No seeded row: a write finds or
-- creates it under the instance row's lock (OperatorDetailsStore).
--
-- operating_mode is PRIVATE or ORGANISATION; NULL reads as ORGANISATION ("until one is chosen", ADR 0093 § 14).
-- contact_email, contact_languages (comma-joined language tags), statement_email, the provider_* columns and
-- supervisory_authority are public operational data, typed by the Server Owner to be published. notification_email is
-- not public: it is where moderation notices go once confirmed (ADR 0097 decisions 3 and 5). The confirmation: code_hash
-- is the hash of the one-time code mailed to code_address, valid until code_expires_at and while code_attempts wrong
-- entries stay below the limit; confirmed_address is the address a code confirmed, so a changed notification address
-- unconfirms without a write. code_failed says the last save's code could not be mailed.
CREATE TABLE veyq_operator_details (
    id                      INT          NOT NULL PRIMARY KEY,
    operating_mode          VARCHAR(16),
    contact_email           VARCHAR(254),
    contact_languages       VARCHAR(512),
    statement_email         VARCHAR(254),
    notification_email      VARCHAR(254),
    provider_name           VARCHAR(200),
    provider_address        VARCHAR(500),
    provider_email          VARCHAR(254),
    provider_representative VARCHAR(200),
    supervisory_authority   VARCHAR(500),
    code_hash               VARCHAR(64),
    code_address            VARCHAR(254),
    code_expires_at         TIMESTAMP(3),
    code_attempts           INT          NOT NULL,
    code_failed             BOOLEAN      NOT NULL,
    confirmed_address       VARCHAR(254),
    confirmed_at            TIMESTAMP(3)
);
