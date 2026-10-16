-- The release check and the instance mail (ADR 0097).
--
-- veyq_release_check: the check's setting, one row. On by default with the published GitHub Releases as its source
-- (source NULL), so an instance that never opens the setting still hears of a new release. last_success_at is the
-- last check that read the source.
CREATE TABLE veyq_release_check (
    id              INT           NOT NULL PRIMARY KEY,
    enabled         BOOLEAN       NOT NULL,
    source          VARCHAR(2048),
    last_success_at TIMESTAMP(3)
);

INSERT INTO veyq_release_check (id, enabled, source, last_success_at) VALUES (1, TRUE, NULL, NULL);

-- What the check found (ADR 0097 decision 2), one row per notice. notice_key is kind, release, date and lead in one
-- string, unique, so a second check that finds the same thing writes nothing; a nullable column in a unique index
-- would not hold that on MariaDB, where NULLs never collide. mailed_at is set once the notice was mailed.
CREATE TABLE veyq_release_notice (
    id              UUID         NOT NULL PRIMARY KEY,
    notice_key      VARCHAR(96)  NOT NULL,
    kind            VARCHAR(32)  NOT NULL,
    release_version VARCHAR(32)  NOT NULL,
    notice_date     DATE,
    days_ahead      INT,
    found_at        TIMESTAMP(3) NOT NULL,
    mailed_at       TIMESTAMP(3)
);
CREATE UNIQUE INDEX ux_release_notice_key ON veyq_release_notice (notice_key);

-- The instance's own SMTP settings (ADR 0097 decision 3), one row once saved. Sends only instance notices, only to
-- the Server Owner's account address; never a tenant's mail. The password is sealed under the config-secret KEK with
-- the fingerprint of the key that sealed it (ADR 0050, ADR 0089 decision 4). owner_user_id is the Server Owner who
-- saved them, whose address the mail goes to; no foreign key, so this row never keeps an account from being erased (a
-- missing user is sent nothing). The confirmation (decision 5): code_hash is the hash of the one-time code the test
-- mail carried, valid until code_expires_at and while code_attempts wrong entries stay below the limit, for the address
-- it was sent to (code_address); confirmed_address is the address a code confirmed. test_mail_failed says the last
-- save's test mail could not be delivered.
CREATE TABLE veyq_instance_mail_settings (
    id                   INT          NOT NULL PRIMARY KEY,
    host                 VARCHAR(253),
    port                 INT,
    from_address         VARCHAR(254),
    username             VARCHAR(254),
    password_ciphertext  TEXT,
    password_nonce       TEXT,
    password_fingerprint VARCHAR(64),
    tls                  BOOLEAN      NOT NULL,
    owner_user_id        UUID,
    code_hash            VARCHAR(64),
    code_address         VARCHAR(254),
    code_expires_at      TIMESTAMP(3),
    code_attempts        INT          NOT NULL,
    test_mail_failed     BOOLEAN      NOT NULL,
    confirmed_address    VARCHAR(254),
    confirmed_at         TIMESTAMP(3)
);
