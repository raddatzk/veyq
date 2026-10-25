-- The day's exchange-rate table Expenses' editor fills a foreign entry's rate from (ADR 0108 decision 8,
-- expenses.exchange-rates): a new instance starts with Frankfurter, as it starts with HIBP for the breached-password
-- check (V202610161210__core_external_service.sql seeds the others). The address is a template the browser fills with
-- the device's date and fetches straight from the provider: the v2 whole-day table, blended from central banks and
-- free to use, because v1 is deprecated (measured 2026-10-25). The Server Owner replaces it (a mirror, or the ECB's
-- own /v2/providers/ecb/rates?date={date}) or removes it, which switches the fetch off and its origin out of the CSP.
INSERT INTO veyq_external_service (capability_id, url, attribution) VALUES
    ('expenses.exchange-rates', 'https://api.frankfurter.dev/v2/rates?date={date}', NULL);
