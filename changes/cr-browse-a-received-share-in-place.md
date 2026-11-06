---
id: cr-browse-a-received-share-in-place
title: A received share browsed in place - CORS on the share API, the CSP widened, a guest access inside the bookmark's page
status: planned
satisfies: ["uc.sharing.browse-a-received-share-in-place"]
adrs: ["0121", "0120", "0088", "0085", "0117", "0093"]
invariants:
  - operator-blind - this instance never proxies, fetches or learns a share a member embeds; the member's browser talks to the share's instance directly
  - no credentials cross an origin - Access-Control-Allow-Credentials is never sent, no cookie or bearer is ever attached to a foreign call
  - the member's access, keys and doors never reach the embedded view, and the guest's never reach the member's tree
  - every switch fails closed - core.share-embedding off keeps the CSP as before and every bookmark opens as in ADR 0120; core.public-shares.embeddable off keeps the share API same-origin
  - not merged for V1 (owner 2026-11-06) - the branch is claude/share-embed-555461, cut from the bookmark's
---

## Goal

[ADR 0121](../docs/decisions/0121-a-received-share-is-browsed-in-place-through-its-own-api.md) built: a bookmark's page
draws its share in place - of this or any veyq instance - read and, where the link allows, written, as the link's guest.
The ADR holds every decision; this work order names the sites, the phases and what proves each.

## Owner decisions this CR rests on (2026-11-06, in the bookmark's session)

- **CSP:** `connect-src https:` while `core.share-embedding` is effective (chosen over an operator-kept partner list);
  `core.share-embedding` is on by default (owner 2026-11-06).
- **Scope:** view **and** write in the first cut (a `READ_WRITE` link's create, upload, move; a `WRITE_ONLY` drop).
- **Not merged for V1.** Each phase lands on this branch; the orchestrating session decides when.

## Inventory (run each locator; the list is the command's answer, not this file's)

| Area | Locator | What changes |
|---|---|---|
| Capability register | `contracts/platform/capabilities.json`, `grep -rn "core.public-shares" src/backend/core/src/main src/web/src/features/platform` | two entries: `core.share-embedding` (`*`,`+`), `core.public-shares.embeddable` (`*`,`+`, child of `core.public-shares`) |
| CORS on the share API | `grep -rn "OPTIONS\|CORP_HEADER" src/backend/core/src/main` (`UndeclaredMethodFilter.kt`, `AppShellRoutes.kt`) | preflight + `Access-Control-Allow-Origin: *` + `CORP cross-origin` on `/api/shares/{id}/**`, `/api/setup`, `/api/moderation/key` only, gated (decision 3) |
| Contracts | `contracts/api/sharing.openapi.yaml`, `setup.openapi.yaml`, `moderation.openapi.yaml` | the cross-origin behaviour stated where the operations are; no new operation (a preflight is HTTP, not an operation - say so) |
| App shell CSP | `src/backend/core/src/main/kotlin/me/raddatz/veyq/core/appshell/AppShell*.kt`, `src/web/src/shared/security/csp.ts`, `src/web/vite.config.ts`, `scripts/assert-csp-policy-in-dist.mjs` | `https:` appended to `connect-src` per tenant while `core.share-embedding` is effective; dev adds `http://*.localhost:*` |
| Guest seams that must take a base | `grep -rln -e publicSharesApi -e publicShareApplicationBlobsApi -e publicParams -e publicModerationApi src/web/src --include='*.ts' --include='*.vue' \| grep -v -e test -e generated` | each factory takes the share's API base (`''` = this origin) from `GuestAccess` |
| Device-kept guest state | `grep -rn -e "veyq:share-" -e "veyq:terms" src/web/src --include='*.ts' \| grep -v test` | keys carry the origin for a foreign share; this origin's keys unchanged |
| The guest page's state | `src/web/src/views/ShareView.vue`, `src/web/src/core/sharing/ShareTreePage.vue`, `guestAccess.ts` | the gate sequence and the tree page extracted so the bookmark's page mounts them with an access of its own |
| The bookmark | `src/web/src/core/bookmark/` | the page embeds; the row opens the page; "Open on its own page" |
| Docs | `docs/crosscutting/cc.security.csp.md`, `security-model.md`, `site/src/content/docs/security/`, `site/src/content/docs/run/` (own store CORS), `site/.../sharing/keep-a-link.mdx` EN+DE | the widening, its switch and cost; the operator's store CORS; the user's new behaviour |

## Phases

**P1 - contracts.** The two register entries (title, purpose, `whenOff`, levels `*`+`+`), the cross-origin paragraphs on
the three specs, the coverage file of the use case with every criterion its phase. Done: guards green.

**P2 - backend: the share API answers any origin.** One filter, core's, for exactly the paths of decision 3: a preflight
`OPTIONS` answered `204` before `UndeclaredMethodFilter` refuses it, and the response headers on every answer of those
paths (refusals included, so a browser can read a `404`/`403`), while `core.public-shares.embeddable` is effective for
the share's tenant (`/api/setup` and `/api/moderation/key`: at instance level). No other path changes - assert it.
Done: a slice test per path class (resolve, blob gate, tree write, report, setup, moderation key) with the switch on and
off, an `Access-Control-Allow-Credentials` that is never sent, a same-origin path that stays CORS-free (control);
`./gradlew check`.

**P3 - backend + web: the CSP.** `AppShellRoutes.cspNow` appends `https:` to `connect-src` for a tenant whose
`core.share-embedding` is effective; the dev policy adds `http://*.localhost:*`. Done: a slice test per state (on, off by
operator, off by tenant), the built policy still without it (`assert-csp-policy-in-dist.mjs`), `cc.security.csp` and the
Security page.

**P4 - web: a guest's reads and writes take a base.** `GuestAccess.apiBase`; every factory of the inventory row takes
it; the presigner cache and the device-kept keys carry the origin; the share's `/api/setup` read and the embeddability
check (decision 4) as one pure function. Done: unit tests per seam that a foreign base reaches the wire (a `*.wire.test.ts`
at `fetch`), that this origin's keys are unchanged, the check's every refusal beside its pass.

**P5 - web: the embedded view.** The gates and the tree page extracted from `ShareView`/`ShareTreePage` so the
bookmark's page mounts them under its own `NodeHost`; the breadcrumb; writes, naming, the drop page, a guest report to
the share's instance; the row opens the page; "Open on its own page"; the fallback line. Done: component tests (the
member's doors absent below, the guest's absent above; each gate; a write reaches the foreign base; a report seals to
the foreign key), `pnpm run check`.

**P6 - the live proof and the docs.** One backend with two tenants on two hosts (`veyq.localhost:<port>` and
`other.localhost:<port>`) is two origins: a member of one embeds a `READ_WRITE` link of the other with a password, reads,
names themselves, uploads, moves, reports; the other tenant switches `core.public-shares.embeddable` off and the bookmark
falls back. Headless system Chrome, the bookmark pass's recipe. The site pages EN+DE, the Run page's store CORS, inbox
#829 deleted when done.

## Definition of done

Every phase's done; `./gradlew check`, `pnpm run check` (after `pnpm run generate:api`), every guard with
`/usr/bin/python3`; each new refusal bitten (the filter off for one path class, the CSP switch ignored, a base dropped in
one seam) and red; the P6 pass recorded; nothing merged.

## Decisions taken in implementation

- **P1 - which use-case each new entry `serves` until P5.** `scripts/validate_capabilities.py` lets an entry serve only a
  *built* use-case (ADR 0032 § 6), and `uc.sharing.browse-a-received-share-in-place` stays `status: planned` until its
  last criterion has a test (P5). So until then `core.public-shares.embeddable` serves `uc.sharing.share-public-viewer`
  (what it switches is how that use-case's guest API answers another origin) and `core.share-embedding` serves
  `uc.sharing.keep-a-received-link` (what it widens is for the kept link). **P5, in the commit that drops `status:
  planned`, adds `uc.sharing.browse-a-received-share-in-place` to both entries' `serves`.** The web pins neither id yet
  (`features/platform/capabilities.ts` pins ids it has UI for); P4 adds them with their first reader.
- **P2 - the filter.** `ShareCrossOrigin` (sharing slice), a pre-matching `@ServerRequestFilter` at `Priorities.USER -
  100`, ahead of `UndeclaredMethodFilter` and `TenantSuspensionFilter`; it answers a preflight (`OPTIONS` with
  `Access-Control-Request-Method`) `204` and marks every other granted request on the routing context (`CrossOrigin`,
  http kernel), and the app shell's headers-end handler writes `Access-Control-Allow-Origin: *`, `Expose-Headers: ETag`
  and CORP `cross-origin` from the mark, so a refusal from any later JAX-RS stage carries them. Only the public methods
  (`@PermitAll`) of a path are offered: the curator's operations under `/api/shares/{shareId}` stay same-origin (ADR 0121
  decision 3 now says so). `ApiMethodIndexContractTest` holds those methods to the contract's operations without
  `bearerAuth` and every header they read to the preflight's list.
- **P2 - whose switch (no existence oracle).** The link's tenant only for a link the guest gate serves
  (`ShareGuestGate.activeShare`); any other id - unknown, revoked, expired, blocked, role switched off - reads the instance
  level, as an unknown id does. Not the `Host`: a link is reachable from another tenant's domain.
- **P2 - `Access-Control-Max-Age: 600`.** The off switch acts on the actual answer (no `Allow-Origin`), so the cache only
  bounds how long a method stays offered.
- **P2 - residuals named in ADR 0121:** a Vert.x-level answer before JAX-RS (the body-limit `413`) carries no CORS header;
  a preflight is not rate-limited and costs the gate's reads.
- **P3 - which tenant a page's policy follows.** The tenant whose domain serves the page (`TenantHostResolver.hostOf`);
  a host that claims no tenant follows the instance level. The header is written on the event loop, so the answer is a
  per-domain snapshot (`ShareEmbeddingHosts`, behind the kernel seam `HostConnectSources`) refreshed after the Server
  Owner's and a tenant owner's switch write and after every `TenantHandler` write that reprojects domains. A stale
  snapshot is at most as wide as the tenant was; the domain move is the one write that could widen, so it refreshes.
  ADR 0088's "the served CSP is the instance level's" gains this one tenant-dependent source.
- **P3 - dev policy.** The Vite policy serves as if the switch were on: `https:` and `http://*.localhost:*`;
  `verify:csp` refuses a scheme or wildcard in the built `connect-src`.
