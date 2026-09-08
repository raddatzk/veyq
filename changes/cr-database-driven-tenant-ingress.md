---
id: cr-database-driven-tenant-ingress
title: A tenant's domain is DB-driven end-to-end — routing, and presigned blob URLs
status: done
satisfies: [uc.admin.create-tenant]
adrs: ["0063", "0013"]
invariants: [operator-blind everywhere, self-hostable by default]
---

## Goal

Creating, moving, suspending, or reactivating a tenant makes its domain **actually route** — the reverse
proxy learns about it without an operator hand-editing `infra/docker-compose.prod.yml` — and presigned blob
URLs are signed against **whichever tenant domain the request arrived on**, not a single instance-wide
default. Today only the first (`VEYQ_DOMAIN`) tenant works end-to-end; a second tenant's API works but its
domain never resolves and its media uploads would receive a URL signed for the wrong origin.

## Context & constraints

- [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) — the decision this CR implements:
  a full-regenerate ingress projector (never incremental), a shared Traefik file-provider volume, per-request
  presigning.
- [ADR 0013 §4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) — amended in place by ADR
  0063 to per-request signing; this CR is what actually builds that amendment.
- [uc.admin.create-tenant.md](../docs/features/admin/use-cases/uc.admin.create-tenant.md) — "A created
  tenant's domain must actually route" section (added this refine session) is the postcondition this CR
  closes; also gains the **change-domain** operation (available on `PENDING` and `ACTIVE`, unlike
  resend/reassign/revoke).
- Already shipped (`cr-tenant-suspend-request-gate`, harvested): `TenantStore.suspend`/`reactivate` and the
  shared `TenantHostResolver` (`backend/src/main/kotlin/com/veyq/app/shared/tenant/TenantHostResolver.kt`)
  this CR hooks into and reuses respectively. `TenantHostResolver.tenantFor(host)` already returns the full
  `TenantEntity`, not just an id — useful here directly.
- Invariants at risk: **operator-blind everywhere** (a domain is operational metadata, never a
  confidentiality boundary — the projector and the presigner must not become a second place holding or
  deriving key material); **self-hostable by default** (the projector must no-op cleanly on a `%dev`/`%test`
  box with no Traefik and no configured dynamic-config path, never a startup requirement).

## Decisions

**A — Ingress projector (backend + infra).**
- New `veyq.tenant.ingress` config block (`VeyqConfig.kt`, nested under the existing `Tenant` interface next
  to `invite()`): `dynamicConfigPath(): Optional<String>` (absent ⇒ projector is a no-op — the `%dev`/`%test`
  default) and `bootstrapDomain(): Optional<String>` (the `VEYQ_DOMAIN` value — the backend does not
  currently know this at all; it is projected into an env var for the first time by this CR).
- New `TenantIngressProjector` (`@ApplicationScoped`, `shared/tenant/`): `regenerate()` queries
  `TenantEntity.list("status != ?1", TenantEntity.SUSPENDED)` (PENDING + ACTIVE both route — a `PENDING`
  tenant's owner must reach the accept link), excludes `bootstrapDomain` (already statically routed), and
  writes a **complete** Traefik file-provider YAML (temp file + atomic rename — never a partial write a
  concurrent Traefik reload could read half-written) to `dynamicConfigPath`. Absent config ⇒ `regenerate()`
  returns immediately.
- **Never incremental** (ADR 0063): every mutating `TenantHandler` method that changes routability —
  create, revoke (delete), the new change-domain, suspend, reactivate — calls `ingress.regenerate()` once at
  the end, after its own transaction commits. Locate every such site:
  `grep -n "fun create\|fun revoke\|fun suspend\|fun reactivate\|fun changeDomain" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt`
  — five sites, all in one file.
- Also called once at startup (`@Observes StartupEvent`, so a wiped/fresh Traefik volume self-heals before
  the app serves traffic — ADR 0063's "self-healing" property).
- **docker-compose.prod.yml**: add `--providers.file.directory=/etc/traefik/dynamic --providers.file.watch=true`
  to the Traefik command block (`infra/docker-compose.prod.yml:144-176`); a new named volume
  (`traefik-dynamic`) mounted read-write on `app` and read-only on `traefik` at that path; `app` gets
  `VEYQ_TENANT_INGRESS_DYNAMIC_CONFIG_PATH=/etc/traefik/dynamic/tenants.yml` and
  `VEYQ_TENANT_INGRESS_BOOTSTRAP_DOMAIN=${VEYQ_DOMAIN}`. The static `veyq-app`/`veyq-blobs` routers
  (`infra/docker-compose.prod.yml:55-66,131-138`) are **untouched** — they remain the always-present
  bootstrap path per ADR 0063.
- **Kubernetes sink is explicitly out of this CR** — ADR 0064 (separately refined, not yet a CR) gives the
  projector a second sink against the K8s Ingress API; this CR only builds the docker-compose file-provider
  sink and the DB-facing `regenerate()` logic the K8s sink will later reuse.

**B — Change-domain endpoint (contracts + backend).**
- `POST /api/tenants/{tenantId}/change-domain` (mirrors `reassignTenantInvite`'s shape), Server-Owner-only,
  body `{ domain: string }`. Validation reuses the existing rules from create (`400 invalid_domain`,
  `409 domain_taken` — `Failures` catalog already has both via `TenantHandler`'s create path; locate with
  `grep -n "invalid_domain\|domain_taken\|domainExists" backend/src/main/kotlin/com/veyq/app/features/admin/tenant/*.kt`).
  Allowed from `PENDING` or `ACTIVE` (not `SUSPENDED` — changing a suspended tenant's domain while its old
  routing may still be winding down is needless complexity for a state whose whole point is "leave it
  alone"; reactivate first). `TenantStore.changeDomain(tenantId, domain): Boolean`, same guarded-`UPDATE`
  idiom as `suspend`/`reactivate` (`TenantStore.kt:227-238`).

**C — Per-request presigning (backend).**
- `BlobStore` interface: `presignPut`/`presignGet` gain a `domain: String? = null` parameter (default keeps
  every existing call site compiling during the refactor — but every real call site is updated in the same
  phase, not left on the default; the default exists for `InMemoryBlobStore`'s trivial dev/test
  implementation, which ignores it).
- `S3BlobStore` (`backend/src/main/kotlin/com/veyq/app/shared/blobstore/BlobStore.kt:100-124`) stops taking
  one prebuilt `S3Presigner`; it takes the shared credentials/region/path-style config plus the bootstrap
  sign-endpoint, and lazily builds + caches one `S3Presigner` per resolved domain
  (`ConcurrentHashMap<String, S3Presigner>`, `computeIfAbsent`) — construction has no network I/O (pure local
  HMAC signing setup), so a small unbounded-but-tenant-count-sized cache is not a resource concern.
  `BlobStoreProducer.blobStore()` (`BlobStore.kt:156-193`) is the one call site that changes shape — it now
  hands `S3BlobStore` the ingredients instead of a finished presigner.
- **The resolved domain is the tenant's own registered domain, looked up via `TenantHostResolver`, never the
  raw `Host` header echoed back.** `MediaResource`/`SharingResource` (currently neither injects `@Context
  HttpHeaders` — `grep -n "@Context" backend/src/main/kotlin/com/veyq/app/features/media/MediaResource.kt
  backend/src/main/kotlin/com/veyq/app/features/sharing/SharingResource.kt` confirms zero hits today) each
  gain `@Context HttpHeaders` + inject `TenantHostResolver`, resolve
  `hosts.tenantFor(hosts.hostOf { headers.getHeaderString(it) })?.domain`, and pass that (nullable) domain
  down to the three presign call sites: `MediaHandler.kt:210,414`, `SharingHandler.kt:360`. A `null` (Host
  resolves to no tenant — the bootstrap domain, or a request that legitimately has none) signs against the
  bootstrap default, matching ADR 0063 exactly ("the config value remains the bootstrap-domain default, used
  only until a tenant row supplies its own").
- Going through `TenantHostResolver.tenantFor` (a DB lookup) rather than trusting the raw header directly is
  deliberate: an unresolvable or spoofed `Host` cannot make the server sign for an arbitrary attacker-named
  domain, only fall back to the safe default — same reasoning `TenantSuspensionFilter`'s docstring already
  gives for the identical header.

**D — Web.** `TenantDetailPanel.vue` gains a change-domain action, mirroring the existing reassign-email
form (`TenantDetailPanel.vue`, same component the suspend/reactivate action just landed in).

## Phases

- [x] 1 contracts — `tenants.openapi.yaml`: `changeDomain` operation (Decision B) ·
      Done when: `npm run generate:api` regenerates cleanly, `npm run check` green.
- [x] 2 backend — `TenantIngressProjector` + config + `TenantHandler` wiring (Decision A);
      `TenantStore.changeDomain` + `TenantHandler` endpoint (Decision B) ·
      Done when: `./gradlew check` green, including a new `TenantIngressProjectorTest` (regenerate produces
      the expected router set for a PENDING+ACTIVE+SUSPENDED mix, excludes the bootstrap domain, no-ops with
      no configured path) and `TenantChangeDomainSliceTest` (happy path from both PENDING and ACTIVE,
      `409 domain_taken`, `409` from SUSPENDED, `400 invalid_domain`, Server-Owner-only, 404 unknown tenant).
- [x] 3 backend — per-request presigning (Decision C) ·
      Done when: `./gradlew check` green, including a bite test that two tenants' presigned PUT URLs for the
      same object key resolve to two different signed hosts, and a still-passing `MediaSliceTest`/
      `SharingSliceTest` suite (the refactor is behavior-preserving for the single-tenant case every existing
      test runs under).
- [x] 4 infra — `docker-compose.prod.yml` Traefik file-provider + shared volume + new env vars (Decision A) ·
      Done when: `docker compose -f infra/docker-compose.prod.yml config -q` validates the compose file, and
      `infra/README.md` gains a short paragraph on the new volume (what it holds, that it's projector-owned,
      never hand-edited).
- [x] 5 web — change-domain UI (Decision D) · Done when: `npm run check` green, `TenantDetailPanel.test.ts`
      covers the new action.
- [x] 6 coverage — add the routing + presigning postcondition to
      `docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml` (the new use-case prose section
      this refine session added) with real refs · Done when: `python3 scripts/validate_coverage.py` green.

## Bite tests

- **Projector excludes the bootstrap domain — live defect.** Written against the untouched tree it can't
  even compile (the class doesn't exist); once built, assert the bootstrap domain never appears in the
  generated file's router set even though it satisfies `status != SUSPENDED` like any other tenant — a
  predicate that forgot the exclusion would pass every other case and only fail here.
- **Projector runs on every listed trigger, not a subset — injection.** Comment out one of the five
  `ingress.regenerate()` call sites (Decision A's locator) and assert `TenantIngressProjectorTest` (or a
  higher-level slice test) catches the stale file — proves the five-site list is complete, not just that
  each individually-tested site works.
- **Presigning is genuinely per-domain, not per-instance-still — live defect.** Before this CR, a second
  tenant's presigned URL is signed for the *first* tenant's endpoint (or the bootstrap default) — this is
  red on the untouched tree by construction. Two tenants, same object key, assert the two presigned URLs'
  host components differ and each matches its own tenant's domain.
- **A resolved-but-suspended tenant's Host still gets the bootstrap presigner, not its own** — the
  presigning path does not itself need to know about `SUSPENDED` (the request never reaches `MediaResource`
  at all once `TenantSuspensionFilter` fires first), so this is a **negative control**: assert the filter,
  not the presigner, is what's doing the refusing — a regression here would show up as the filter test
  suite, not this one, going red.

## Definition of done

Tests green across contracts/backend/web (`./gradlew check`, `npm run check`), `docker compose config -q`
validates the prod compose file, the full trust-chain guard glob green
(`for f in scripts/validate_*.py; do python3 "$f"; done`), and `docs/coverage/` updated for
`uc.admin.create-tenant`'s new routing/presigning criterion. The full-regenerate invariant ("never
incremental") and the five-site trigger list are both promoted to permanent guards if either is cheap to
express structurally (e.g. a guard that greps `TenantHandler.kt` for every public mutating method and
asserts each calls `ingress.regenerate()`) — otherwise this CR states explicitly why a one-shot proof
suffices.

## Retrospective

- **The CR named a wire code that does not exist, and the locator it handed over would have said so.**
  Decision B specified `400 invalid_domain` "already in the `Failures` catalog"; there is no such code
  anywhere in the tree, and the CR's own `grep -n "invalid_domain\|domain_taken\|domainExists"` returns hits
  for the second and third terms only. Create rejects a malformed domain at the *boundary* (the DTO's
  `@Pattern`) as `400 invalid_request` — which is what change-domain now does too, so the outcome is right,
  but only because implementation re-derived it. Root cause: the locator was written into the CR and not run,
  the exact failure `changes/README.md` § *run it, don't estimate* already names. **Next time:** a CR that
  names a `code` treats it as a code-confirmable fact like any other — run the grep in the refine session and
  paste what it printed, not what it was expected to print.
- **A CR can prescribe a mechanism that does not survive contact, and Decision C's did.** "Each gain
  `@Context HttpHeaders`" compiles, passes every authenticated path, and turns all 16 anonymous guest share
  tests into `401` — the field changes how RESTEasy Reactive instantiates the resource, whose
  constructor-injected `SecurityIdentity` is then resolved for a caller who has none. Caught only because
  `./gradlew check` runs the whole suite; the touched class alone was green. The mechanism moved into
  `RequestTenantDomain`, whose KDoc now carries the measurement. Root cause is not the wrong guess — it is
  that the CR specified the *keystroke* (`@Context HttpHeaders`) where the decision was really "the resolved
  tenant's domain reaches the presigner", which is the distinction `docs/ai/README.md` § *decisions over
  keystrokes* draws. **Next time:** when a CR names a specific injection/annotation mechanism, read it as a
  suggestion and re-decide it at the call site; only the *property* is binding.
- **What the CR got exactly right, and it is worth naming because it is the part that is hard.** The bite test
  for trigger completeness was specified as a procedure ("comment out one of the five, assert a test catches
  it") rather than as an assertion — so it could actually be run, five times, and it caught all five. It also
  showed the way to a *permanent* form: `validate_tenant_ingress_triggers.py` derives the routability-changing
  store methods from `TenantStore` instead of listing them, so it reds on a new one too (bite-tested both
  ways), and it is pinned as guard-gap case 14. A CR that says how to *falsify* a claim is worth more than one
  that says what to assert.
- **Process, both directions.** The session honoured the phase order, the full guard glob per phase, and small
  per-phase commits on `main`; the one convention it strained is *each phase ends verifiable* — the contracts
  commit necessarily leaves the backend uncompilable, which is now written down as a property of
  contracts-first rather than left as a thing each session rediscovers. On the human side there was nothing to
  correct: the brief pinned the three findings the refine session had paid for (full regenerate, DB-resolved
  domain, K8s out of scope) and each of the three was a place where the obvious shortcut is wrong.
