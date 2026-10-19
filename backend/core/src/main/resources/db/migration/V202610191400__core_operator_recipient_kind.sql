-- A recipient's "what they do" becomes a fixed kind the privacy notice names in each language (ADR 0035 decision 8,
-- owner 2026-10-19), so the German and the English notice never mix languages: `kind` one of HOSTING, OBJECT_STORAGE,
-- MAIL_RELAY, BACKUP, OTHER (RecipientKind), and `description` the operator's own words for an OTHER one only — NULL for
-- every other kind (OperatorDetailsHandler refuses a description that does not fit its kind).
--
-- The free-text `purpose` is dropped, not mapped: no release carried it, and a typed sentence has no reliable kind. The
-- recipients a development instance holds go with it; the Server Owner names them again. The table is recreated rather
-- than altered, since its rows are dropped either way and NOT NULL `kind` then needs no default.
DROP TABLE veyq_operator_recipient;

CREATE TABLE veyq_operator_recipient (
    list_index  INT          NOT NULL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    description VARCHAR(120),
    country     VARCHAR(100)
);
