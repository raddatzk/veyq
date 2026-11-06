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
- **P4 - where a guest call goes: `ShareAt`** (`core/sharing/shareAt.ts`): a share as this device addresses it, a bare id
  (this origin) or `{ shareId, apiBase }`. A `GuestAccess` carries `apiBase` (required) and so IS a `ShareAt`: a view hands
  `access` to every guest read and write. The head records where it was resolved (`PublicShareHead.apiBase`, ABSENT for
  this origin, so the share page's head is deep-equal to before), and what starts from a head (`guestTreeContext`, its
  re-resolve, `nameShareGuest`, a drop-box guest's naming and folder) goes there. The public factories take `apiBase` as a
  REQUIRED argument (`publicParams`, `publicSharesApi`, `publicShareApplicationBlobsApi`, `publicModerationApi`,
  `publicShareMediaApi`, `publicShareFilesApi`, `publicShareExpensesApi`), so no guest call reaches a base its caller did
  not choose; `setupApi(apiBase = '')` and the this-origin factories (`publicPairingApi`, `publicOperatorApi`) pass `''`.
  A positional `shareId` parameter became `ShareAt` (`shareChunkUrls`, `readGuestNotice`, Memories' share reads, Expenses'
  `readGuestPot`/`readGuestReceipts`, `readGuestApplicationBlobs`); an object target gained a required `apiBase`
  (`GuestUploadTarget`, `GuestTreeContext`, `ApplicationBlobTarget`/`PotTarget` guest). `fetch` sends no cookie to another
  origin by default and no factory sets `credentials`; the wire tests assert no `Authorization` and no `include`.
- **P4 - device keys.** This origin's are byte-identical (`<prefix>:<id>`); another instance's are `<prefix>@<origin>:<id>`
  (`deviceKey`/`originKey`) - the character after the prefix (`:` / `@`) tells them apart, so no id a `/s/:shareId` route
  hands the share page (vue-router decodes `%2F`, so it can hold anything) reads another origin's entry. Covered: the
  guest's session (and a drop-box guest's), a note's acceptance, the accepted terms (per tenant AND origin: a foreign
  tenant id is that instance's), Memories' record of its own uploads, the presigner cache, the guest-notice read cache.
- **P4 - reporting per instance.** `publishedKey(fresh, apiBase)` and `useReportOffered(apiBase)` hold one answer per
  instance; `reportBaseOf(access)` is a guest's link's base, a member's `''`. The level, core's node actions, Memories'
  detail host and the Pot page pass it, so a foreign guest's Report shows by the foreign instance's key and seals to it.
- **P4 - "no answer" is its own unavailable.** `ShareUnavailableError('unreachable')` (`SHARE_UNREACHABLE`) where the
  generated client got no HTTP response (network, or a browser that withheld a cross-origin answer); every refusal stays
  `not_found`. The share page treats both alike; a bookmark's page falls back on `unreachable`.
- **P4 - embeddability** (`core/sharing/shareInstance.ts`): `embeddability({ embedding, setup, major })`, pure, refusals
  `switchedOff` (this instance's `core.share-embedding`), `unreachable` (no answer, or no version/capability list),
  `otherVersion` (any major but `EXPECTED_MAJOR`), `noLinks`, `notEmbeddable`; `readShareInstance(apiBase)` reads
  `/api/setup` and turns every failure into null. `.contribute` is not read (the share's server refuses a write).
- **P4 - tests changed outside the new ones, and why.** `src/test/apiMock.ts`' `ApiFactory` takes the base; every test
  that called a public factory to reach its mock now passes `''` (13 files, `ShareView.memory.test.ts` among them: four
  `publicShareMediaApi()` -> `publicShareMediaApi('')`, the assertions unchanged); assertions on `shareChunkUrls`,
  `openShare*`, `deleteSharePhoto`, `saveDropGuest`/`forgetDropGuest` now name the share's address
  (`{ shareId, apiBase: '' }`) where they named its id; test literals of a `GuestAccess`, `GuestUploadTarget`,
  `GuestTreeContext` and `PotTarget` gained `apiBase: ''`; `access.test.ts` lists `apiBase` among a guest access's keys.
- **P5 - the gates extracted, not copied.** `core/sharing/shareGates.ts` (`useShareGates({ at, fragmentKey,
  handedPassword, locale })`: the state machine, moved from `ShareView` unchanged) and `core/sharing/ShareGates.vue` (the
  cards, the guest's line, the body and the naming dialog, with their styles). `ShareView` keeps what only it has (the
  moderation mode, a bookmark's report hand-off, the footer, the guest audience, `openGuestPage`) and passes its id bare,
  so every call it makes is the one it made; its twelve test files are unchanged but `ShareView.memory.test.ts`' four
  factory calls (P4). Embedded, the cards head with `h2` and the guest uploader's statements (`guestPage`, the share page's
  singleton) are not asked; the removal-order notice is, of the share's instance (`OrderNotice`'s `apiBase`).
- **P5 - navigation inside the share.** `ShareTreePage`'s `embedded`: the node on screen is the page's own ref, never
  `?node=`; each crumb's real `href` is the bookmark's page (`route.fullPath`), its plain click prevented and run in place.
- **P5 - isolation.** `BookmarkEmbed` provides `NO_DOORS`, `provideNoCoreNodeActions()` and `provideNoNodeHeader()` (the
  two keys now admit `null`): core's page of the link provides the guest's own below; a `WRITE_ONLY` drop page finds none
  of the member's (pinned by a probe drop page in `BookmarkEmbed.test.ts`).
- **P5 - the switch and the row.** `core.share-embedding` is read through `useCapabilities().hasCapability` (the tenant's
  set once loaded, else the instance's). Off: no instance is asked, the page is ADR 0120's without a line, the row opens
  the link (as before). On: the row's plain click is `host.open` (the bookmark's page); `open-own-page` leads the
  bookmark's header (`header.order`) in both states. The fallback line names every refusal but `switchedOff`.
- **P5 - a resolve with no answer falls back.** After the setup check passed, a resolve that ends `unreachable` (the
  link's tenant switched `.embeddable` off: no CORS on its answers) hands `unreachable` back; any refusal (`not_found`)
  is the share page's unavailable card, in place.
- **P5 - known limits, for P6 to look at.** The embedded node's own header is core's (a second `h1` on the page, under the
  bookmark's); the embed's stored view mode and tab are the member's account preferences (the share page's are the
  guest's); the guest uploader's statement of reasons on a blocked placeholder is shown on the share's own page only.

## P6 - the live two-origin pass (2026-11-06)

Run headless in system Chrome against one quarkusDev backend serving two tenants on two hosts (`veyq.localhost:8091` as the
member's, `other.localhost:8091` as the share's), own database, CDP network capture. **Passed:** the embed in place with the
stored password (no form), every call to the share's origin answered `Access-Control-Allow-Origin: *`, `CORP cross-origin`,
no credentials sent and none allowed, preflights `204` with `Max-Age: 600`, no request of this origin carrying the share id;
navigation inside the share with the URL naming the bookmark only; the naming gate, a guest folder and an upload (the PUT
to the share's store) seen by the share's member as the guest's; a move inside the share; a guest report `201` at the
share's instance; "Open on its own page" in a new tab (no opener, no referrer); `core.public-shares.embeddable` off for the
share's tenant → the fallback line and the link, on again → the embed; `core.share-embedding` off here → ADR 0120's page and
row, no request to the other origin. A curator's `DELETE` preflight offers `GET` only; a member path's preflight is `405`.

**Found and fixed:** Vite's default dev CORS answered a `*.localhost` preflight itself and echoed the origin on proxied
answers, so a switched-off `.embeddable` still embedded in dev; `src/web/vite.config.ts` sets `server.cors: false`, and the
switch was proved under it. **Left as it is:** an embed whose resolve is blocked mounts the unavailable state's TCO removal
notice once more (`OrderNotice`, one blocked request, harmless); the bookmark page carries two `h1`s; the share's member
saw the guest's folder after a reload only (a share dialog was open on that page; not investigated).

**Not proven live:** the backend-served per-tenant CSP losing and gaining `https:` (quarkusDev serves no shell; P3's
`AppShellShareEmbeddingSliceTest` is the proof); a report sealed to a *distinct* instance's moderation key (one backend,
one key); browsers other than Chromium. The user-facing word is "server", not "instance" (owner 2026-11-06), in the
bookmark's and the embed's texts.
