-- A recipient's country becomes an ISO 3166-1 alpha-2 code (ADR 0035 decision 8, owner 2026-10-19), which the privacy
-- notice names in each page's own language through the JDK's locale data (core/terms/RecipientCountries), so the German
-- page never shows an English country name. NULL is still "not named".
--
-- The free-text countries are cleared, not mapped: no release carried them, and a typed name has no reliable code. The
-- rows themselves stay — name, kind and description are unchanged; the Server Owner picks each country again.
UPDATE veyq_operator_recipient SET country = NULL;

ALTER TABLE veyq_operator_recipient MODIFY country VARCHAR(2) NULL;
