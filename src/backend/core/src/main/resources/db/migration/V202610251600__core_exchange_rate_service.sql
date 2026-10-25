-- The day's exchange-rate table Expenses' editor fills a foreign entry's rate from (ADR 0108 decision 8,
-- expenses.exchange-rates): a new instance starts with Frankfurter's endpoint for the European Central Bank's euro
-- reference rates, as it starts with HIBP for the breached-password check (V202610161210__core_external_service.sql seeds
-- the others). The address is a template the browser fills with the device's date and fetches straight from the
-- provider: v2's ECB table (30 rows, base EUR, measured 2026-10-25), never the blended default, because the rates are
-- the ECB's alone (owner 2026-10-25) and v1 is deprecated. The Server Owner replaces it (a mirror, a self-hosted
-- Frankfurter's same endpoint) or removes it, which switches the fetch off and its origin out of the CSP.
INSERT INTO veyq_external_service (capability_id, url, attribution) VALUES
    ('expenses.exchange-rates', 'https://api.frankfurter.dev/v2/providers/ecb/rates?date={date}', NULL);
