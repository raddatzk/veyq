---
id: cr-external-services-as-settings
title: The map tiles, the geocoder and the breached-password check become Server-Owner settings and register entries
status: in-progress
satisfies: ["uc.admin.configure-external-services"]
adrs: ["0088", "0089", "0069", "0016", "0018"]
invariants:
  - "Self-hostable by default: an instance without internet works with every service unconfigured or switched off"
  - "Operator-blind: a setting is an address the browser may reach; no content and no password passes the server"
---

## Goal

Inbox #439 step 4. Today the map tiles and the geocoder are build-time `VITE_VEYQ_MAP_*` variables with OpenStreetMap
defaults, and the breached-password check calls a fixed origin. They become Server-Owner settings in the database
(ADR 0089: admin-web and its CLI, no restart) and three register entries: `memories.map`, `memories.places`,
`core.breached-password` (ADR 0088 decision 7). An entry is active only while its service is configured and switched
on; the client reads the address from the manifest, and the Content Security Policy the backend serves (ADR 0069) names
an origin only while its entry is active for the tenant the page is served for.

## Context & constraints

- Today (measured): `web/src/shared/state/mapDefaults.ts` (OSM tiles, attribution, max zoom 19, Nominatim),
  `useMapConfig.ts` (a per-device override nobody can set, then `VITE_VEYQ_MAP_*`, then the defaults),
  `web/src/shared/pwned.ts` and an identical `admin-web/src/shared/pwned.ts` (`api.pwnedpasswords.com/range/`),
  `web/src/shared/security/csp.ts` + `vite.config.ts` (the built policy carries the three origins),
  `backend/core/.../appshell/AppShellRoutes.kt` + `AppShell.kt` (the served policy, the store origin appended per
  response). No infra, chart or workflow sets the variables.
- The kernel/slice rule (`scripts/validate_kernel_imports_no_slices.py`): `core.appshell` is kernel and may not import
  `core.platform`; it gets the origins through an interface the slice implements.
- No native client exists in this repo; the manifest is what one would read.

## Decisions

- **D1 — three entries, source `external-service`**: active only while a URL is configured AND the switches allow it.
  `memories.map` and `memories.places` are `*+` (used only after sign-in, in a Memory); `core.breached-password` is `*`:
  the check runs on the join and recover screens before sign-in, the step-2 rule (an entry a client offers before
  sign-in is the Server Owner's alone). ADR 0088's register is edited to match. `memories` becomes a root of the tree;
  the guard derives the application roots from `backend/apps/` instead of a typed list.
- **D2 — the settings**: `veyq_external_service(capability_id PK, url, attribution)`, seeded by the migration with
  today's defaults (OSM tiles with its attribution, Nominatim, the HIBP range API), so an upgrade changes nothing.
  `PUT /api/instance/external-services/{capabilityId}` `{ url, attribution? }` sets one, `DELETE` unconfigures it,
  `GET` lists all three; the console panel **Server → External services** and `veyq-admin services list|set|unset`.
  URL rules: `https://` or `http://`, at most 512; the tiles' URL holds `{z}`, `{x}` and `{y}`; an attribution (plain
  text, at most 256) only for the tiles. A cache invalidated after commit, the switches' pattern.
- **D3 — the manifest carries the address**: `Capability.serviceUrl` and `.serviceAttribution`, set only on an active
  external entry, in both deliveries. The web reads them; `VITE_VEYQ_MAP_*`, the env defaults and the dead per-device
  override go. The user's own consent to maps stays a third gate (ADR 0088 § The register).
- **D4 — the CSP follows the entries at the instance level**: the built policy names no external service; the served
  policy appends `connect-src` += the geocoder's and the breach check's origins and `img-src` += the tiles' origin, each
  only while its entry is active at the instance level. The origins are a snapshot recomputed after every write that
  can change them (a service setting, an instance switch) and at startup, never on the response path: the header is
  written on the event loop, where a database read would block. A tenant's own switch therefore acts through its set
  (its members' clients do not call), not through the policy. Through `appshell.ExternalOrigins`, implemented by the
  services slice. The boot check requires an `img-src` beside `connect-src`.
- **D5 — what is inactive is never asked**: no tiles and no consent prompt for maps without `memories.map`; no name
  suggestion without `memories.places` (a place is named by hand); no breach request without
  `core.breached-password`, and no "could not check" note either, because nothing was attempted.
- **D6 — the dev server**: Vite's dev CSP header keeps OpenStreetMap, Nominatim and HIBP, so a dev instance seeded with
  the defaults works; a dev instance pointed elsewhere needs its own `vite.config.ts` edit (said there).
- **D7 — admin-web**: its CSP is a `<meta>` served by nginx, outside the backend, and its first-run screen runs before
  any setting exists; it keeps the default HIBP origin and reads `core.breached-password` from the manifest (absent →
  no check). A mirror at another origin is therefore refused by admin-web's own policy and reads as "could not check" —
  a recorded residual.

## Phases

- [ ] 1 contracts + catalog — D1-D3; the guard's roots and source.
- [ ] 2 backend — the store, the registry's precondition, the wire values, the served CSP. Tests that bite: an
      unconfigured or switched-off service leaves the manifest and its origin leaves the served CSP while another
      service's stays; a tenant's switch leaves that tenant's set only; URL validation.
- [ ] 3 web + admin-web — D3, D5-D7, the console panel and CLI.
- [ ] 4 docs — ADR 0088, ADR 0089, ADR 0069, cc.security.csp, ADR 0016/0018 where they name build variables, the Run
      pages, the use case, inbox #439.

## Definition of done

Checks green on every surface, the guard glob green, coverage for the use case, site pages updated, CR harvested and
deleted.

## Retrospective

(filled at close)
