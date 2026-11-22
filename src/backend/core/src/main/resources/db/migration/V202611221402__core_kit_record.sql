-- The account's kit record (ADR 0157 decision 3): which Emergency Kit layout the account last made, the compact JSON
-- {"layout":n} sealed under its Master Key (seal purpose `kit-record`, base64, at most 65532 characters). One row per
-- account, replaced by every flow that makes a kit (last writer wins: no revision); no row until the first one does.
CREATE TABLE veyq_kit_record (
    account_id  UUID NOT NULL PRIMARY KEY,
    sealed_blob TEXT NOT NULL,
    CONSTRAINT fk_kit_record_user FOREIGN KEY (account_id) REFERENCES veyq_user (id)
);
