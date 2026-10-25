---
id: cr-expenses-exchange-rates
title: Expenses — the day's exchange rates, fetched by the device
status: planned
satisfies: ["uc.expenses.exchange-rates"]
adrs: ["0108", "0088", "0016", "0093"]
invariants:
  - the fetch goes straight from the browser to the provider the instance names, never through the backend, and only while its switch is on and an address is set
  - the request carries no currency, amount or veyq identity; the provider learns the client's address, the instance's Origin and a date
  - a save never waits for the provider; the rate an entry was saved with never moves
  - no doc names the fetch before it runs, and every doc that names the breached-password check as the one automatic third-party request is corrected in the same CR
  - every text follows docs/crosscutting/ui-web-styleguide.md § Copy
---

## Goal

The third of Expenses' three CRs (owner 2026-10-23): where an entry's currency differs from the Pot's base, the device
fetches the whole day's table once per day for every Pot on it, from the provider the instance names — a new instance
starts with **Frankfurter** (owner 2026-10-23) — and fills the rate; the operator and the tenant owner switch it off as
`expenses.exchange-rates` ([ADR 0108](../docs/decisions/0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md)
decision 8, [uc.expenses.exchange-rates](../docs/features/expenses/use-cases/uc.expenses.exchange-rates.md)).

## Entry condition

`cr-expenses-ledger` has landed (`test -f changes/cr-expenses-ledger.md && echo "ledger CR still open"`): its editor's
typed rate is what this CR fills. `cr-expenses-receipts` has landed too (2026-10-25): the editor it fills now also holds
the receipts (`EntryEditor` + `EntryReceipts`), so a refine re-reads it before the first edit.

## Context & constraints

- **The precedent is the breached-password check** ([security-model](../docs/crosscutting/security-model.md) § third-party
  services): automatic, no opt-in, because what crosses carries no content; its address is the Server Owner's
  (`core.breached-password`, ADR 0088 decision 7). This becomes the second such request, so every doc that calls that
  check *the one* exception is rewritten (locator below).
- **Measured at refine (329b92cf):** the CSP's outside origins are a hand-written list,
  `ExternalServiceOrigins.refresh()` in `core/platform/capabilities/ExternalServices.kt` (`connect = [MEMORIES_PLACES,
  BREACHED_PASSWORD]`), each origin added only while its URL is configured and the capability active at instance level;
  the privacy notice gives the breached-password check a passage of its own (`contracts/terms/privacy-notice.json`,
  `"switch": "core.breached-password"`) and lists every other external service in `browser-services`;
  `PrivacyNoticeRenderer.kt` names known hosts in a hand-written `KNOWN` map; `scripts/validate_capabilities.py` refuses
  an entry whose `serves` names a use-case still `planned`.
- **Measured 2026-10-25 (phase 1, `curl` from outside the browser, `Origin: https://veyq.example.org`; plain public
  requests carrying no veyq data):**
  - **CORS allows it.** `GET https://api.frankfurter.dev/v2/rates?date=2026-10-23` → `200`,
    `access-control-allow-origin: *`, `access-control-allow-methods: GET, OPTIONS`, `vary: Origin`,
    `cache-control: public, max-age=86400, …`; a preflight (`OPTIONS` with `Access-Control-Request-Method: GET`) gets
    the same headers. A plain `GET` with no custom header needs no preflight at all.
  - **v1 is deprecated.** `api.frankfurter.dev/v1/<date>` (the ECB's 29 currencies against EUR, the shape remembered at
    refine) answers `deprecation: @1779103800` (2026-05-18) and `link: <…/v2/rates>; rel="successor-version"`;
    `api.frankfurter.app` answers `301` to it. The site: "deprecated in favor of v2, but remains available
    indefinitely". So the default is **v2** (CLAUDE.md: no deprecated dependency).
  - **The whole-day table is `/v2/rates?date=<YYYY-MM-DD>`**: a JSON array of `{date, base, quote, rate}`, base `EUR`,
    **170 rows** for 2026-10-23, blended from 105 central banks and official sources — far more than the ECB's ~30,
    and it includes non-ISO rows (`BTC`, `ETH`, `XRP`, `LTC`, `BCH`, `XAU`, `XAG`, `XPT`, `XPD`, `XDR`, `CMD`, …). A
    weekend date gives each quote's latest observation (rows dated 2026-10-23 and 2026-10-25 for `date=2026-10-25`);
    a future date (`2026-10-27`) answers `200` with the latest rows, never `404`. The ECB alone is
    `/v2/providers/ecb/rates?date=…` (30 rows, the ECB's own terms apply).
  - **Terms allow it** (frankfurter.dev, its FAQ and `/license/`): code MIT; no API key; "Blended rates are
    Frankfurter's own calculation and free to use" for commercial use too, while a single provider's endpoint "remain[s]
    subject to each provider's terms"; caching locally is encouraged; no quotas, rate-limited against abuse only; rates
    "as is, with no warranty", blended rates are not an official rate. Privacy, as the site states it: the API "doesn't
    collect or log personal data, IP addresses, or request URLs"; the public instance runs behind Cloudflare.
  - **So the stop condition did not hold:** neither CORS nor the terms refuse a browser's request. The seeded address is
    `https://api.frankfurter.dev/v2/rates?date={date}` (blended, Frankfurter's own terms); an operator who wants the
    ECB's official rates alone sets `…/v2/providers/ecb/rates?date={date}`.
- **A legal text.** The notice's passage is GDPR Art. 13 information; its surfaces are derived from the duty (the
  instance notice and the Run page that tells the Server Owner what to state), and a question counsel must answer stays
  named open, never guessed (changes/README.md § A CR that writes a legal text).

## Decisions this CR takes

1. **The register entry** `expenses.exchange-rates`: `source: external-service`, `levels: [instance, tenant]`, like
   `memories.places`; a new instance's address is Frankfurter's, set by the migration that seeds the external-service
   table, as `core.breached-password`'s is.
2. **The device cache** holds one table per date, keyed by the date only (one for every Pot), in the device's local
   storage; a missing or stale table never blocks a save.
3. **The request is the provider's whole-day table** — a fixed URL per date, no currency in it. The address is a URL
   template holding `{date}` (the device's date, `YYYY-MM-DD`), as the map tiles' holds `{z}/{x}/{y}`; the Admin API
   refuses one without it (phase 2), and the answer is Frankfurter's v2 shape, base `EUR`, so a cross rate is computed
   on the device and no `base=` ever names a currency.
4. **The notice gets a passage of its own**, beside the breached-password check's, switched by
   `expenses.exchange-rates`.

## Inventory

| Area | What changes | Locator |
|---|---|---|
| Register | the entry, generated ids | `python3 -c "import json;print([k for k in json.load(open('contracts/platform/capabilities.json'))['capabilities']])"` |
| Seeded address | the external-service seed | `grep -rn "core.breached-password" src/backend/core/src/main/resources/db/migration` |
| CSP | the hand-written connect list; dev | `grep -n "BREACHED_PASSWORD\|MEMORIES_PLACES" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/platform/capabilities/ExternalServices.kt`; `grep -n "DEV_BREACH_ORIGIN" src/web/vite.config.ts src/web/src/shared/state/mapDefaults.ts` |
| Notice | its passage; the host map | `grep -n "breached-password-check\|browser-services" contracts/terms/privacy-notice.json`; `grep -n "KNOWN" src/backend/core/src/main/kotlin/me/raddatz/veyq/core/terms/PrivacyNoticeRenderer.kt` |
| e2e egress | the allow-list | `grep -n "pwnedpasswords" tests/e2e/src/egress.ts` |
| "The one exception" | every doc that says so | `git grep -n -i -E "one (stated )?exception\|the one third-party\|only third-party\|second instance" -- docs site/src/content/docs` |
| Site | third-party pages | `git grep -ln -i "breached\|pwned\|third.part" -- site/src/content/docs` (at refine: `privacy/is-my-data-safe` EN+DE, `run/capabilities.mdx`, `run/data-protection.md`, `run/hardening.md`, `security/start-here`, `security/threat-model`, `security/known-limitations`, `security/roadmap`) |

## Phases

1. **Contracts and the provider.** ✅ **Built 2026-10-25**, lands with phase 2 and with phase 3 (§ Retrospective). First measure Frankfurter's CORS headers, terms and currency list (a request from the
   browser pane, the result quoted in this CR); then the register entry (decision 1) in the same commit as
   `uc.expenses.exchange-rates`' first refs are due — so phase 1 writes it but the coverage flip of phase 3 must land
   in the same merge, or `validate_capabilities.py` is red; the notice's passage (decision 4). **Done when:** the
   contracts guards green except `validate_capabilities.py`, whose red names only this entry and turns green in phase 3.
2. **Backend.** ✅ **Built 2026-10-25** (§ Retrospective). The seeded address; the origin in `ExternalServices`; the notice's renderer and host map. **Done when:**
   `./gradlew check` green; a slice test that the CSP carries the origin only while the address is set and the entry on
   (control: both set → present); the notice names the provider only while the switch is on.
3. **Web, docs, site, coverage.** The fetch and its cache (decisions 2, 3); the editor fills the rate and keeps it
   typeable; the dev CSP; the e2e egress allow-list. Docs: `security-model.md` § third-party services lists the fetch
   beside the breached-password check, and every "one exception" hit is rewritten. Site: the pages in the inventory.
   Coverage of `uc.expenses.exchange-rates` from `planned` to refs. **Done when:** `npm run check` and `site`
   `npm run check` green; unit tests on the request itself: one request per device and day for any number of Pots, a
   URL that carries no currency or amount, none with the switch off or no address (control: one with it on), a save
   that does not wait for an unreachable provider; every `scripts/validate_*.py` exits `0`; a **browser pass**: an entry
   in a foreign currency fills its rate with zero CSP violations in the console, and with the entry switched off by the
   tenant owner it asks for the rate and no request is made (Resource Timing, since the pane hides cross-origin
   requests).

## Definition of done

The fetch runs only while switched on and addressed, straight to the provider; the privacy notice, the security model
and the site name it; ADR 0108 and inbox #325 say Expenses is built, and the row is deleted.

## Open questions (owner)

Frankfurter's CORS and terms do not refuse a browser's request (measured 2026-10-25, § Context), so that hand-back did
not happen. **One question opened in phase 2, for phase 3's guest path (changes/README.md § A refine that promises a
viewer sees a switched state names what that viewer's client is handed):** a `READ_WRITE` link guest writes entries
(the use-case's primary actor), but a guest's client holds only the instance's manifest (`GET /api/setup`) — the
tenant's set is members-only (`useCapabilities` falls back to the instance's). So with the tenant owner's switch off
and the instance's on, a guest's device would still fetch, while the notice on that tenant's host (rightly, per
`isActive(id, tenant)`) names no fetch: the invariant "only while its switch is on" and Art. 13 both break for guests.
Either (a) the guest's Pot read (or the share's resolve) carries the tenant's effective state and `serviceUrl` for
`expenses.exchange-rates` — an optional response field, a contracts + backend addition — or (b) a guest's device never
fetches and asks for the rate (a function removed, as a share may). Not decided here; nothing for guests is built.

## Retrospective

**Entry condition, checked 2026-10-25 on `main` `170de083`:** `changes/cr-expenses-ledger.md` and
`changes/cr-expenses-receipts.md` are both gone — the ledger and the receipts have landed.

**Phases 1 and 2 (2026-10-25), one branch; it must land together with phase 3.** The register entry's `serves` names
`uc.expenses.exchange-rates`, still `planned`, so `validate_capabilities.py` is red until phase 3's coverage flip
(its one red names only this entry); and the seeded address makes the privacy notice name the fetch at once, which no
web code runs yet (the invariant "no doc names the fetch before it runs").

- **The provider, measured** (§ Context): CORS `*`, terms allow it, v1 deprecated → the seed is v2's blended day table,
  `https://api.frankfurter.dev/v2/rates?date={date}`.
- **The register entry** `expenses.exchange-rates` (`source: external-service`, `levels: [instance, tenant]`, under
  `expenses`); generated constant `CapabilityIds.EXPENSES_EXCHANGE_RATES`.
- **The address is a `{date}` template** (decision 3 as refined): `ExternalServicesHandler` refuses one without `{date}`,
  and an attribution, with `400 invalid_request`; the contracts' descriptions (`serviceUrl`, the external-services
  operations) say so.
- **The seed** is a core migration, `V202610251600__core_exchange_rate_service.sql` (pinned), as the precedent's rows for
  `memories.*` are core's: the table and the CSP are core's, and core's own slice tests see the row.
- **The CSP**: `ExternalServiceOrigins.CONNECT` names the three `fetch`ed services, written out, never derived, so a new
  external-service entry reaches `connect-src` only by being named there.
- **The notice**: passage `exchange-rates` beside `breached-password-check`, `switch: expenses.exchange-rates`
  (the switch is `isActive`, so an absent address, the entry off at either level, or Expenses off all drop it), placeholder
  `{exchangeRateService}`, `KNOWN["api.frankfurter.dev"] = "Frankfurter"` (a mirror is named by its host alone);
  `PrivacyNotices.OWN_PASSAGE` keeps both services out of `browser-services`, so neither is named twice. `revision` 4 → 5.
  The right to object needed no edit: "the services your browser contacts" covers the fetch.
- **The legal question** the fetch shares with the breached-password check (Art. 6(1)(f), a third-country transfer through
  the CDN, on by default under Art. 25(2)) is named open in ADR 0093 § Still open 7, not answered.
- **Bites** (snapshot-and-restore script, each red, each restored): the passage's `switch` removed (renderer and slice red;
  the passage rendered with the gap where the provider stood); the origins computed without `isActive` (red on the
  entry's own and on Expenses' switch); the `{date}` check removed (the `400` red); `OWN_PASSAGE` back to the breach
  check alone ("named once" red); the exchange rates left out of `CONNECT` (four reds, the served policy's control
  among them); an unconfigured address no longer counting as off ("no address: no passage" red); `KNOWN` without
  Frankfurter (renderer and slice red); the notice asked at the instance level instead of the host's tenant (the
  tenant's "not named on its host" red, the map's existing case beside it).
- **Measured:** `./gradlew check` green (root 205, `:core` 1675, `:apps:memories` 442, `:apps:depot` 83,
  `:apps:expenses` 31 tests); every quick `scripts/validate_*.py` 0 except `validate_capabilities.py` (above). No
  guard changed, so `validate_guard_gaps.py` was not run; no web package was touched.

**For phase 3.**
- **The guest question above** decides the guest path before any of it is built.
- **The request:** the device fills `{date}` with its own date (`YYYY-MM-DD`) in the entry's `serviceUrl` from
  `capabilityValues('expenses.exchange-rates')`, and sends nothing else — no `base=`, no `quotes=`, no custom header (a plain
  `GET` needs no preflight). The answer is an array of `{date, base: "EUR", quote, rate}`; the rate from currency X to the
  Pot's base B is `rate(B) / rate(X)` (EUR's own row is `1`), computed exactly as the entry format computes. Rows can carry
  an earlier date than asked (weekends), and a future date answers the latest rows. Rows that are no ISO 4217 currency
  (`BTC`, `ETH`, `XAU`, `XDR`, `CMD`, …) are never offered; a currency the table lacks is typed (decision 8).
- **Copy the new words verbatim where a test quotes them:** the notice's EN passage contains
  "from Frankfurter (api.frankfurter.dev), at most once a day"; the e2e `privacy-notice.spec.ts` can assert it.
- **Docs that now lag the code:** ADR 0108 decision 8 says Frankfurter serves "the ECB's reference rates" — v2's default is
  blended from 105 sources, the ECB alone being `/v2/providers/ecb/rates`; `uc.admin.configure-external-services` and the
  site's `run/capabilities.mdx` list three services and the tile template only; admin-web's CLI help
  (`src/admin-web/src/cli/commands.ts`) and `features/instance/externalServices.ts` name only the tile template's
  placeholders; `contracts/api/_common.openapi.yaml`'s `serviceUrl` already names the fourth.
- **The dev CSP** (`src/web/vite.config.ts`, `DEV_BREACH_ORIGIN` in `mapDefaults.ts`) and the e2e egress allow-list
  (`tests/e2e/src/egress.ts`) need `https://api.frankfurter.dev`; the stack lane's instance seeds the row through the
  migration, so its browser reaches Frankfurter unless the lane points the address at a local fixture.
