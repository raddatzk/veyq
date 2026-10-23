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
typed rate is what this CR fills. Independent of `cr-expenses-receipts`; not side by side with it in the same files.

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
- **Remembered, not measured:** that Frankfurter serves the ECB's reference rates (about 30 currencies) with CORS headers
  and terms that allow a browser's request from any origin. Phase 1 measures all three before anything depends on them;
  a no on CORS or terms is a question back to the owner, not a decision for the implementer.
- **A legal text.** The notice's passage is GDPR Art. 13 information; its surfaces are derived from the duty (the
  instance notice and the Run page that tells the Server Owner what to state), and a question counsel must answer stays
  named open, never guessed (changes/README.md § A CR that writes a legal text).

## Decisions this CR takes

1. **The register entry** `expenses.exchange-rates`: `source: external-service`, `levels: [instance, tenant]`, like
   `memories.places`; a new instance's address is Frankfurter's, set by the migration that seeds the external-service
   table, as `core.breached-password`'s is.
2. **The device cache** holds one table per date, keyed by the date only (one for every Pot), in the device's local
   storage; a missing or stale table never blocks a save.
3. **The request is the provider's whole-day table** — a fixed URL per date, no currency in it.
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

1. **Contracts and the provider.** First measure Frankfurter's CORS headers, terms and currency list (a request from the
   browser pane, the result quoted in this CR); then the register entry (decision 1) in the same commit as
   `uc.expenses.exchange-rates`' first refs are due — so phase 1 writes it but the coverage flip of phase 3 must land
   in the same merge, or `validate_capabilities.py` is red; the notice's passage (decision 4). **Done when:** the
   contracts guards green except `validate_capabilities.py`, whose red names only this entry and turns green in phase 3.
2. **Backend.** The seeded address; the origin in `ExternalServices`; the notice's renderer and host map. **Done when:**
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

None open. Handed back if met: Frankfurter's CORS or terms refusing a browser's request.

## Retrospective

_Filled by the implementer._
