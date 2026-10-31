# Enabler: The capability register — what this server serves, switched off by the operator and a tenant owner

- **ID:** en.platform.capability-register
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
  A **model** enabler: it states the register, its levels and what each kind of switch does, not one flow.
- **Status:** **built**, except what [§ Decided, not built](#decided-not-built) names. Decided in
  [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) (the manifest, the `major` gate,
  capability ≠ permission, advertised **and** enforced, the grain) and
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) (the tree, the
  two levels, what "off" does, requirements, external services); an application's own switch is
  [ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md) decision 5.
- **Provides:** one register of what this server serves, which the Server Owner narrows for the instance and a tenant
  owner for the tenant; one effective set per level that every client reads and never computes; and one server gate per
  entry that refuses what the set says is off.
- **Serves (use-cases):** [uc.platform.capability-discovery](../use-cases/uc.platform.capability-discovery.md),
  [uc.admin.switch-capabilities](../../admin/use-cases/uc.admin.switch-capabilities.md),
  [uc.admin.tenant-switch-capabilities](../../admin/use-cases/uc.admin.tenant-switch-capabilities.md),
  [uc.admin.capability-requirements](../../admin/use-cases/uc.admin.capability-requirements.md),
  [uc.admin.configure-external-services](../../admin/use-cases/uc.admin.configure-external-services.md),
  [uc.platform.switch-application-off](../use-cases/uc.platform.switch-application-off.md),
  [uc.platform.erase-switched-off-application](../use-cases/uc.platform.erase-switched-off-application.md).
- **Depends on:** [en.account.key-model](../../account/enablers/en.account.key-model.md) (the `DEVICE` and `DEVICE_KEY`
  wraps a device switch withholds); [en.platform.node-tree](en.platform.node-tree.md) (the node kinds an
  application switch refuses by).

## The catalog

`contracts/platform/capabilities.json` is the one list of ids; the backend's ids, texts, levels and requirements are
generated from it (`CapabilityIds`, `CapabilityTexts`, `CapabilityIds.TENANT_SWITCHABLE`, `CapabilityRequirements`), and
no client keeps a copy. Each entry carries:

- **`id`** — a dotted path under a root: `core.<x>`, or an application's own name (`memories`, `depot`, `expenses`) and
  its children. An application's one-segment id is its own switch; `core` is no entry. An id of three or more segments
  has its parent as an entry.
- **`levels`** — `instance` always; `tenant` where a tenant owner may narrow it too.
- **`source`** — `operator-policy` (a switch), `external-service` (an outside service the browser calls: active only
  while the Server Owner has configured its address, and its switches are on) or `infra-preflight` (computed at
  runtime, no switch; none exists today).
- **`requirements`** — the values a level may set on the entry (a `flag` or a number of `days`).
- **`title`, `purpose`, `whenOff`** (and `whenSet` per requirement) in English and German — the words every console
  shows, served by the API.
- **`serves`** — the use-cases it gates, catalog-only, never on the wire.

`validate_capabilities.py` holds the catalog to that shape: every root is `core` or an application the backend
composes, every application has its own entry, every parent exists, every `serves` resolves to a built use-case, and
every requirement is a property of the wire's `Capability` in `_common.openapi.yaml`.

## The effective set

An entry is active only if it and every ancestor that is an entry are active, at both levels: an **AND** along the path
and across the instance and the tenant (`CapabilityRegistry.isActive(id, tenantId)`; `offSwitches` names each switch that
makes it off, and its emptiness is `isActive`). A tenant only narrows what the instance allows. A requirement is in
effect at the strictest of both levels.

**Two deliveries.**
1. The pre-auth `GET /api/setup` carries `version: { major, build }` and the **instance** level, for what a client
   offers before sign-in. It is the same for every caller.
2. After sign-in, `GET /api/tenants/{tenantId}/capabilities` carries the **effective** set for the tenant, read by every
   member on entering a space.

Both carry an entry as `{ id, …values }`, the requirement values in effect on it. A client knows only the ids it has
UI for: it ignores an unknown id and hides the feature whose id is missing, without asking why (an older server, the
operator, the tenant). The web client's `hasCapability` reads the pre-auth set until the tenant set is read.

**The version gate.** `major` is the platform's one major ([ADR 0085](../../../decisions/0085-one-major-moved-on-a-yearly-train.md));
a client compiled for other majors stops before sign-in with "update your app" or "update this server". `build` never
gates ([uc.platform.capability-discovery](../use-cases/uc.platform.capability-discovery.md)).

## What "off" does

The client hides; the server refuses. Every endpoint that would exercise an entry answers `422 capability_disabled`
while it is effectively off, so a stale or hostile client gains nothing. Per kind of entry:

| Kind of entry | New | What exists |
|---|---|---|
| **A feature** (`core.group-space`) | refused at its one creating endpoint | untouched |
| **A link role** (`core.public-shares`, `.contribute`, `.drop-box`) | creating, re-activating, re-issuing a link of the role, or turning a `READ` link into a `READ_WRITE` one, refused; a Depot drop box needs `.drop-box` to be created | every guest endpoint, core's and each application's, answers a link of the role exactly as an unknown link (`404`, same body, before the password is asked): the one predicate every guest path passes (`ShareGuestGate.activeShare`). Nothing is written onto the link; switched on, it serves again. Presigned URLs already handed out run to their TTL |
| **A device entry** (`core.remember-device`, `.passwordless`, `core.device-linking`) | enrolling the wrap refused; every `/api/pairing/*` endpoint refused | `GET /api/account/key-material` (`KeyMaterialHandler`) withholds every `DEVICE` wrap, or every `DEVICE_KEY` wrap, and deletes neither; the `PASSWORD` wrap is always served. The client keeps the device's local key and asks for password and Secret Key; switched on, every remembered device unlocks again |
| **An application** (`memories`, `depot`, `expenses`) | every write of it refused | read-only, and erased after the window (below) |
| **An external service** (`core.breached-password`, `memories.places`, `memories.map`, `expenses.exchange-rates`) | the browser may no longer reach it | the served CSP leaves its origin out |

**An application switch** is the one feature switch that reaches what exists. Core's `ApplicationSwitch` refuses a write
two ways: by kind, for core's own writes of a node (create, update, cover, a link rooted on one, an upload's start),
through the kind's `NodeKindRules.applicationOf`; and by name, in each application's write handlers, which call
`requireWritable` right after their scope gate. A delete to the trash and its restore, the purge, erasures, moderation
measures, a rotation's re-key and a member's own item marks never ask it. `ApplicationSwitchSweepTest` derives every
write operation of every application and holds each to refused or exempt with its reason. Its children are off with it
by the AND. The web reads it as `MemberAccess.applicationsOff` through the registry's `applicationOf`, and the host shows
one banner per application off; a guest's page is not told, and its write is refused by the server.

**Off is a window with an end.** Switched off at either level, the tenant gets a day: that moment plus
`veyq.application.off.retention` (default `P30D`, the Server Owner's alone). Core's `veyq_application_erasure` holds one
row per tenant and application while it is off (its day and the claims of the two mails), kept by both levels' switches
under the instance's row lock, then the tenant's. `ApplicationErasurePurge` (`veyq.application.erasure.purge.every`,
hourly by default) mails the notice and the reminder seven days before where the instance has mail, and on the day hands
each scope's nodes of the application's kinds to `NodeSubtreePurge`, found by `NodeKindRules.applicationOf`, so core
names no application. Switched on before the day, the row goes and nothing is lost; switched on after it, the erasure
completes and spares a node of the application's kinds made after the switch-on. An instance switch gives every tenant
its own day, and a tenant already off keeps its earlier one
([uc.platform.erase-switched-off-application](../use-cases/uc.platform.erase-switched-off-application.md)).

**An external service** is active only while the Server Owner has configured its address (`veyq_external_service`, seeded
with OSM tiles, Nominatim, the HIBP range API and Frankfurter's ECB rates; admin-web's **External services**,
`PUT /api/instance/external-services/{capabilityId}`). Both deliveries carry the address as `serviceUrl` (and the
tiles' `serviceAttribution`); the web reads only that. The served CSP names an origin only while its entry is active at
the instance level (`AppShellRoutes.cspNow`, over a snapshot `ExternalServiceOrigins` recomputes after each write); a
tenant's own switch acts through its set. A place still works with `memories.map` and `memories.places` both off: named
by hand, positioned by the photos' GPS or an earlier pin.

## Requirements

Built: `core.public-shares` `linkPassword` and `linkMaxDays`, `core.group-space` `addConsent`, `core.remember-device`
`passwordMaxDays` (instance only, as its entry). They act at once, on what exists too:

- a link's lifetime counts from when it was made, so an edit fixes a younger link and an older one is renewed as a new
  link (`LinkRequirements`, whose first missed requirement is the refusal a write meets);
- `addConsent` leaves members already added alone, a direct add whose key is still on its way included;
- `passwordMaxDays` asks every device past the age for the password again (`SessionService.pastPasswordMaxDays`).

A member's share list says why a link serves no guest: `ShareGuestGate.guestStops` returns the reasons (revoked,
expired, a space paused or being deleted, each switch along the role's path at each level, each requirement missed),
`activeShare` is that list being empty, and the list is served on every row as codes the client words itself
(`ShareGuestStops.vue`). A guest's `404` is unchanged.

## Where each level switches

- **The Server Owner**: admin-web's Server → Capabilities panel and `veyq-admin capabilities list | off <id> | on <id> |
  require …`, through the Admin API (`GET /api/instance/capabilities`, `PUT /api/instance/capabilities/{capabilityId}`,
  `…/requirements/{requirement}`). One row per switched-off entry in `veyq_capability_switch`, one per requirement in
  `veyq_capability_requirement`, read through an in-process cache a write invalidates after commit, so a switch acts
  without a restart on the one replica the chart runs. There is no configuration property for it.
- **A tenant owner**: the tenant settings in `src/web/` (`TenantCapabilitiesPanel.vue`;
  `GET|PUT /api/tenants/{tenantId}/capability-switches/{capabilityId}`, `…/requirements/{requirement}`), for the entries
  whose `levels` name `tenant`; rows in `veyq_tenant_capability_switch` and `veyq_tenant_capability_requirement`, behind a
  per-tenant cache. An entry a client offers before sign-in (`core.remember-device` and its child,
  `core.device-linking`, `core.breached-password`) is the Server Owner's alone.

Every gate passes the tenant the content lives in: the share gates and the guest gate the share's scope's tenant, group
creation the request's, node creation the scope's; the key material and pairing pass none. Lane L1's
`capability-off-states.spec.ts` drives the off states in a browser.

## Decided, not built

- **Entries of the register not in the catalog** ([ADR 0088](../../../decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md)
  § The register): `core.personal-space`, `core.member-invites`, `core.self-registration`, `core.push`,
  `memories.chapters`, `memories.journal`, `memories.gpx`, `memories.faces`, `memories.download-originals` (waits for a
  measurement of guests' video streaming from the `ORIGINAL`), and Inventory's.
- **Requirements**: share only with verified identities (not buildable as specified: the server knows no verified
  identity) and `core.offline-cache` (only a native client can keep it).
- **Configuration as code**: switches declared in compose or Helm values.
- **admin-web's own CSP**: a static `<meta>` that keeps the default HIBP origin whatever the instance configured.
- **The deprecation lifecycle**, per-user "new feature" notices, audience filtering, and folding `suites` into the
  manifest ([ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md) Consequences).

No `## User impact`: an enabler has no standalone end-user story; its value surfaces through the use-cases it serves.

## Related

- The current picture of the platform: [Applications](../../../apps/README.md).
- On the docs site: [Capabilities](../../../../site/src/content/docs/run/capabilities.mdx) (the register for the Server
  Owner).
