-- The values the instance privacy notice renders beside the operator details (ADR 0035 decision 8,
-- cr-instance-privacy-notice decision 3): a data-protection officer where one is appointed, and the recipients the
-- Server Owner names — public operational data, typed to be published, shown only through the notice. No seeded row.
--
-- veyq_operator_recipient: one row per recipient, `list_index` its place in the list (0-based) and its key; the list is
-- replaced whole on every save (OperatorDetailsStore.replaceRecipients). name and purpose are required, country is NULL
-- when not named (the full notice shows that gap).
ALTER TABLE veyq_operator_details ADD COLUMN data_protection_officer VARCHAR(500);

CREATE TABLE veyq_operator_recipient (
    list_index INT        NOT NULL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    purpose    VARCHAR(300) NOT NULL,
    country    VARCHAR(100)
);
