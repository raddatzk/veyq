---
id: cr-store-provisioning-from-a-declared-type
title: The backend provisions the bundled store from a declared store type
status: planned
satisfies: ["uc.admin.back-up-and-restore-instance"]
adrs: ["0067", "0013", "0056", "0062", "0064"]
invariants:
  - "Operator-blind E2EE — the provisioner touches bucket/IAM/CORS *metadata* only and never an object's bytes; phase 2 puts it on its own seam, and no phase gives it a read or write path over objects."
  - "Self-hostable by default — `docker compose up` on the prod recipe must yield an instance that serves media with no operator step in between. Phase 4 removes the last artifact the recipe could not pull; the live smoke in the Definition of done is what proves the result."
  - "Exactly one arm deletes store objects (ADR 0062 §3, pinned by `scripts/validate_store_reclaim_arity.py`). Bite test 7 injects a `blobStore.delete` into the provisioner and requires that guard to redden."
  - "No vendor-locked client in the data path (CLAUDE.md § dependencies). `S3BlobStore` stays the AWS SDK client; every Garage-specific call in this CR is plain HTTP against the Admin API, behind the type, on the provisioning seam — never in `BlobStore`."
  - "A restore never reconciles two populated instances (uc A5). Phase 5 redefines *empty* from `no tables` to `no data` and phase 6 pins both halves — the fresh instance that must now be accepted, and the populated one that must still be refused."
---

## Goal

A fresh veyq instance today comes up with an object store that is **running but not usable**: Garage has no
layout, no bucket, no access key and no grant. A one-shot `garage-init` compose service closes that gap, and it
needs a container of its own only because the official Garage image has no shell — an image that is `build:`-only
and unpublished, which is why [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)'s Helm
chart cannot deploy it. This CR moves that bring-up into the backend, driven by a **declared store type** on the
store connection ([ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md)): `garage`, which
veyq can bring from *running* to *usable* end to end over Garage's Admin API, and `s3` (the default), which
limits veyq to the steps that are genuinely S3 so an unknown store is never poked. Provisioning runs in the
background, retries with a bound and never gates the boot; its outcome becomes readable on the Server-Owner
instance surface, so a broken store is something the operator is told rather than something they discover at
their first photo. `garage-init` is deleted from **every** stack, so development runs the production shape — and
because a store is then no longer usable without a backend, this CR also carries the one consequence that
follows: a restore target is a freshly installed instance, and the gate that defines one moves from *no tables*
to *no data*.

## Context & constraints

Durable truth this must honor — links, not copies:

- [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md) — the decision. It was
  **corrected in place during this CR's refinement**, on points grounding measured wrong, and the corrected
  file is current: `PutBucketCors` is a step the provisioner *gains* and not one it inherits (nothing in the
  repository writes one today); Decision 3 now names per-step idempotence and the layout-version obligation
  that the old script's `--version 1` shortcut hid; the per-tenant remedy in Consequences now states its
  collision with ADR 0062 §6 instead of reading as free; and a Consequence now carries the restore-target
  change phase 5 implements. **Read the ADR, not this summary.**
- [ADR 0013 §1](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) — a byte path is a hard
  requirement, which is why `BlobStoreStartupCheck` stays exactly as it is. [§4](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
  carries the **measured** table of what a bucket CORS policy can express on the pinned Garage v2.0.0, and its
  verdict: on the bundled store a multi-tenant instance has exactly one working policy, `*`. The provisioner
  writes that policy. Do not "improve" it into an enumerated origin list — §4 exists to stop that.
- [ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md) — §3 (one arm deletes
  objects) and §6 (a reclaim row carries an opaque key and nothing else). Both bind this CR; see *Traps*.
- [ADR 0063](../docs/decisions/0063-database-driven-tenant-ingress.md) — the startup-observer lesson Decision 3
  follows: a `StartupEvent` observer that can fail on an unreachable dependency restart-looped a production
  stack into serving nothing.
- [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md) — the chart this unblocks. Kubernetes
  gates V1, so this CR is on that path. Note also what it already deferred: a Kubernetes-native backup story.
  Phase 5 is not that story; it removes the one thing that would have made a cluster restore *impossible*
  rather than merely undocumented.
- [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) — why the admin CLI is not the bootstrap's
  home, and why the new read lands in `admin-web/`.
- [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) +
  [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md) —
  the restore, its A5, and the two sentences in the ADR that name `garage-init.sh` as the restore target's
  author. Phase 5 owns all of it.

**`satisfies:` names one use-case, and deliberately not two.** The restore half of this CR changes A5 of
`uc.admin.back-up-and-restore-instance`, so it owes that use-case coverage and says so. The **provisioning**
half has no use-case and gets none: `uc.admin.setup-server` describes only the Server Owner's account and key
birth, and filing a store criterion there would be the error `changes/README.md` § coverage names — a criterion
under the use-case that *triggers* it rather than the one that *owns* it. So phases 1–4 make **no coverage
claim**, and the Definition of done names what carries their proof instead, concretely, because a claim nothing
backs is a lie with a reason attached.

### Decisions this CR is built on, each with the line that settles it

| Decision | Settled by |
|---|---|
| `BlobStore` stays presign/stat/delete; provisioning is a separate seam | ADR 0067 Decision 2 + `scripts/validate_store_reclaim_arity.py` (pins `StoreReclaimSweep` as the sole caller of `blobStore.delete`) |
| Config is checked at boot, the network work is not | `BlobStoreStartupCheck.kt:32-33` — `hasStore()` is `mode == TEST \|\| endpoint.isNotBlank()`, no network. It stays byte-for-byte. |
| Only three classes inject `BlobStore`, all via the CDI client proxy | `grep -rn 'BlobStore' backend/src/main/kotlin --include=*.kt \| grep -i 'inject\|constructor\|val blobStore'` — producer at `BlobStore.kt:218-258` |
| Garage's Admin API does every bootstrap step | Measured against the pinned v2.0.0: `POST /v2/CreateBucket` (JSON body) → 200, `POST /v2/DeleteBucket?id=<id>` → 200. **A bogus path also returns 400, so a 400 is not proof an endpoint exists — only a successful call is.** |
| The admin API is reachable from the app container | `infra/garage.toml:29-30` binds `[::]:3903`; prod `app` and `garage` share the default network (the app already reaches `http://garage:3900`, `docker-compose.prod.yml:106`). Prod does **not** publish 3903 — correctly. Dev publishes it as `19003:3903` (`docker-compose.yml:51`). |
| The app does not hold the admin token today | `grep -n 'GARAGE_ADMIN_TOKEN' infra/docker-compose*.yml infra/.env.example` — set on the `garage` service only, and already declared in `.env.example:41`. |
| Prod's `app` never depended on `garage-init` | `docker-compose.prod.yml:92-96` — `mariadb: service_healthy` + `garage: service_started`. So Decision 3 *preserves* today's ordering semantics rather than relaxing them. |
| The restore's *empty* gate is zero tables, in code | `scripts/instance_backup.py:660-666` — `db.table_count()`, hard refusal. The object gate is `store.size()` at `:667-672` and is **unaffected**: an initialised empty bucket holds zero objects. |
| The app cannot provision without also migrating | `backend/src/main/resources/application.properties:4` — `quarkus.flyway.migrate-at-start=true`, one line, no profile gate anywhere (`grep -rn 'migrate-at-start' backend/src/main/resources/ infra/`). |
| The e2e orchestrator needs a usable store **before** its first boot | `scripts/run_web_e2e.py` — `compose_up()` runs at `main():902`, before any `boot()`; `run_group` calls `fresh_bucket(inst.bucket)` (`:820`) before `boot(...)` (`:851`). |

## Inventory

Run the locators; do not rebuild the set from them. **Spot-check the one row this CR's argument leans on
hardest** — the CORS row — with one command before the first edit:
`grep -rni 'putbucketcors\|bucketcors' --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build .`

**Locator, angle A — the artifact's own name:**
```
grep -rn --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build -l 'garage-init\|garage_init' .
```
**Angle B — the vendor, not the init artifact** (the angle that found `infra/.env.example`'s
`GARAGE_ADMIN_TOKEN` and `scripts/instance_backup.py`, neither of which angle A returns):
```
grep -rni --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build -l 'garage' .
```
**Angle C — the guard's predicate, run before the guard is written:**
```
python3 - <<'PY'
import pathlib, yaml
for f in sorted(pathlib.Path("infra").glob("*.yml")):
    d = yaml.safe_load(f.read_text()) or {}
    for n, s in (d.get("services") or {}).items():
        if isinstance(s, dict):
            print(f, n, s.get("image"), "build" if "build" in s else "-")
PY
```

| Surface | What changes |
|---|---|
| `contracts/api/instance.openapi.yaml` | A new Server-Owner read for the provisioning state. |
| `backend/.../config/VeyqConfig.kt` | `BlobStore` gains `type` and a nested `garage` block. |
| `backend/.../shared/blobstore/` | The new provisioning seam + its Garage implementation + the background runner. `BlobStore.kt` and `BlobStoreStartupCheck.kt` are **not** modified. |
| `backend/.../features/admin/instance/` | The new read's handler, beside the release read. |
| `backend/src/main/resources/application*.properties` | The type + admin config per profile. `application-prod.properties` carries zero `veyq.blob-store.*` lines today and stays that way — prod is env-driven. The `garage-init.sh` provenance comments at `application-dev.properties:16` and `application-e2e.properties:32` name a file that will not exist. |
| `backend/.../shared/blobstore/StoreReclaim.kt` | One comment at the tombstone. No schema change. |
| `admin-web/src/features/instance/` | A panel beside `ReleaseVersionPanel.vue`, and the generated client regenerated. |
| `infra/garage-init.sh`, `infra/garage-init.Dockerfile` | Deleted. |
| `infra/docker-compose.prod.yml`, `infra/docker-compose.yml` | The `garage-init` service removed from **both**; the app (prod) gains the type + admin endpoint + token. |
| `infra/.env.example` | `GARAGE_ADMIN_TOKEN` gains a second consumer; the store type is declared. |
| `.run/Run Backend.run.xml` | The dev backend's env gains the type + `http://localhost:19003` + `veyq-dev-admin-token`. **This is the user's file; they re-import it.** |
| `.run/Bootstrap DB + Garage.run.xml` | Comment only — its executable text names no service. Note IntelliJ strips comments on rewrite; the file says so itself. |
| `scripts/run_web_e2e.py` | `wait_garage_init()` goes; something must make the store usable before the first suite. See phase 4. |
| `scripts/validate_app_volume_ownership.py`, `scripts/validate_config_secret_persistence.py` | `NOT_THE_APP`'s `"garage-init"` member becomes genuinely dead once the service is gone from **both** compose files. Remove that member — and **keep** the `veyq:`-vs-`veyq-` regex discrimination and its rationale comment, which `veyq-admin` still needs. |
| `scripts/validate_release_identity.py` | One new assertion + its docstring limit. Its `:125` comment about `veyq-garage-init:` needs a live example or rewording. |
| `scripts/instance_backup.py` | The `table_count()` gate at `:660-666`. |
| `docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md` | A5. |
| `docs/coverage/features/admin/use-cases/uc.admin.back-up-and-restore-instance.yaml` | The A5 criterion's refs. |
| `docs/decisions/0056-*.md` | `:50` and `:91`, which name `garage-init.sh` as the restore target's author. |
| `infra/README.md` | The dev-store section, the restore walkthrough (`:290, :304`), and `:213`'s in-image-recipe limit, which **shrinks** — the recipe's residual repo dependency becomes `garage.toml` alone. |

## Phases

- [ ] **1 contracts** — a Server-Owner read for the provisioning state, in
      `contracts/api/instance.openapi.yaml`. That file is the right home by its own description: *"the
      operator's view of the software — which bits are running"*, Server-Owner-only, already carrying `403
      not_server_owner`. Do **not** extend `InstanceRelease`: its schema doc says in so many words that a
      second field there would only restate the first. A new operation and a new schema.
      **Decide the vocabulary, and decide it once** — the closest precedent for a server-self-description enum
      is `configSecretState` (`contracts/api/tenants.openapi.yaml:1606-1618`, `[OK, ABSENT, MISMATCH]`), whose
      shape is worth copying: each value says what the operator can *do*, not what the code did. A `type: s3`
      instance never attempts vendor bring-up, and that is a distinct, correct, permanent state — not a
      failure and not an `OK` — so the vocabulary needs a value for it. Enumerate every state the runner can
      terminate in and give each a value in the same paragraph: not-attempted, in-flight, succeeded, gave-up.
      A vocabulary naming only the case that prompted the thought is the fifth tell in `changes/README.md`.
      · **Done when:** `npm run generate:api` + `npm run check` green in `web/` **and** `admin-web/`, and the
      contracts guards green. **This phase deliberately cannot end green in `backend/`** — the generated
      `InstanceApi` gains a method the moment the spec does, so `InstanceResource` stops compiling until phase
      2. That is the one expected case (`changes/README.md` § phases); do **not** add a stub to buy a green
      tick.

- [ ] **2 backend** — the type, the seam, the runner, the read.
      - **`VeyqConfig.BlobStore` gains `type`.** It would be the **first enum-typed config member in the
        codebase** — measured: `grep -n 'enum' VeyqConfig.kt` returns nothing, and `grep -rn
        '@WithName\|@WithConverter' backend/src/main/kotlin` returns nothing repo-wide, so property names are
        derived kebab-case from the method path (the class KDoc at `:12-13` states the rule). A member named
        `type()` yields `veyq.blob-store.type`; a member named `storeType()` would silently yield
        `veyq.blob-store.store-type`. Default `s3`, per Decision 1 — an unknown store is never poked.
      - **Garage's admin endpoint and token go in a nested block** (`veyq.blob-store.garage.*`), so the
        members are structurally type-scoped rather than free-floating. Structural precedents to copy:
        `VeyqConfig.Tasks.Lease` (`:281-285`) and `RateLimit.Recovery` (`:291-302`).
      - **A `StoreProvisioner` seam, not a `BlobStore` method** (Decision 2). `BlobStore.kt` is not edited by
        this CR at all.
      - **Each step is individually idempotent — no short-circuit on "the bucket exists"**, and the layout step
        generalises past `--version 1`. Both are ADR 0067 Decision 3; the reasons live there and are not
        repeated here.
      - **The key alias is `veyq-app`.** Not cosmetic: `scripts/run_web_e2e.py:539` grants against that exact
        alias, and phase 4 makes the backend its only minter.
      - **The runner never gates the boot** (Decision 3, following ADR 0063's incident). The tree has **no**
        existing non-blocking startup pattern to copy: `grep -rn '@Startup' backend/src/main/kotlin` returns
        nothing, and all three `@Observes StartupEvent` observers (`BlobStoreStartupCheck.kt:22`,
        `TenantIngressProjector.kt:50`, `InstanceStore.kt:36`) are synchronous and *can* fail the boot. This
        introduces the first one — give it a named seam rather than an anonymous thread.
      - **It must not run in `%test`.** The hermetic slice suite has no network and uses the in-memory stub;
        `BlobStoreStartupCheck.hasStore`'s `LaunchMode.TEST` escape is the shape to follow.
      - **The read's handler** lands beside the release read, with the same Server-Owner gate.
      - **One comment at `StoreReclaim.kt`'s entity**, carrying the trap *and* the ADR 0062 §6 tension ADR
        0067's Consequences now states — otherwise the next reader adds the forbidden column believing it free.
      · **Done when:** `./gradlew check` green — the **full** run, not the touched class (the `%test` H2 mem DB
      is JVM-shared across test classes).

- [ ] **3 admin-web** — a panel beside `ReleaseVersionPanel.vue`, reading the new operation through the same
      generated client (`admin-web/src/features/instance/release.ts` is the shape; its `.test.ts` is the
      testing shape, including the `403 not_server_owner` and `500` cases). Reuse the existing UI — the
      styleguide's § *Reuse first* applies and the release panel is the nearest built screen.
      · **Done when:** `npm run check` green in `admin-web/`.

- [ ] **4 infra, orchestrator + guard** — in this order *inside* the phase, because the defect is still live
      and a live defect is a stronger bite than any injection (`changes/README.md` § guard → red → fix →
      green).
      1. **Write the assertion first and record its red.** Add it to `scripts/validate_release_identity.py`
         rather than as a new guard: that file already owns the release-recipe concept
         (`RECIPE = ("infra/docker-compose.prod.yml", "infra/.env.example")`, `:115`) and already discriminates
         `veyq:` from `veyq-<something>:` (`IMAGE_TAG`, `:127`, reason at `:124-126`). The assertion: **every
         service in the release recipe runs an image that is either an upstream pin or one
         `.github/workflows/container.yml` publishes — the recipe builds nothing.** Derive the published set
         from the workflow's matrix (`:149`); do not list it (`changes/README.md` § *a guard derives its anchor
         set from the code*). Its **limit**, in the docstring, because an unwritten limit silently shrinks
         `validate_guard_gaps.py`'s reach: it is scoped to the release recipe, so it says nothing about
         `infra/docker-compose.yml` — a chart deploys the recipe, not the dev stack. Keeping the scope narrow
         is deliberate even though this CR also cleans the dev file: a future dev-only `build:` is legitimate
         and must not be forbidden by a guard whose subject is releases. Its **defect**: the unpublished
         `veyq-garage-init` image that blocked ADR 0064's chart. Run it on the untouched tree and record that
         it names `garage-init` — measured now, that is the only `build:` stanza in either compose file.
      2. **Delete `garage-init` everywhere**: the service from both compose files, plus `infra/garage-init.sh`
         and `infra/garage-init.Dockerfile`. Give the prod `app` the store type, `http://garage:3903` and
         `GARAGE_ADMIN_TOKEN`. Remove the prose comment above the removed prod service that states the
         `--version 1` limit — that obligation now lives in ADR 0067 Decision 3. Remove `"garage-init"` from
         both `NOT_THE_APP` sets, **keeping** the `veyq:`-vs-`veyq-` regex discrimination and its rationale
         comment, which `veyq-admin` still needs; `validate_release_identity.py:125`'s comment cites the
         soon-nonexistent image as its example and needs rewording rather than deletion (the discrimination it
         explains is still live).
      3. **Dev gets the type and the admin config** — `application-dev.properties`, `.run/Run Backend.run.xml`
         (`http://localhost:19003`, `veyq-dev-admin-token`, published at `docker-compose.yml:51`). A
         `--profile s3 up` now leaves the store unprovisioned and the first backend start brings it up, which
         is the point: the **fresh** path runs against a real Garage on every clean dev cycle.
         `application-e2e.properties:38-45` explains at length why `public-endpoint` is deliberately unset —
         read it before touching that file; setting it fails `media.e2e.test.ts` in the **PUT**, long after a
         green boot.
      4. **The e2e orchestrator needs a usable store before its first suite, and now nothing but a backend can
         make one.** Measured: `compose_up()` runs at `main():902` before any `boot()`, and `run_group` calls
         `fresh_bucket(inst.bucket)` (`:820`) before `boot(...)` (`:851`) — and `fresh_bucket` grants against
         the `veyq-app` alias (`:539`) that only a provisioner now mints. `wait_garage_init()` (`:249`) must go
         regardless: left in place it polls `docker inspect` on a container that never appears and every run
         dies after 120 s with `STORE INIT TIMEOUT`, which is a hang, not a clean failure.
         **Decision: `compose_up()` ends with one throwaway backend boot whose only job is to provision**, then
         waits on the new instance read (phase 1) reporting the succeeded state. It costs seconds and a scratch
         database — the orchestrator already creates per-suite databases — and it buys the thing the old
         sidecar never gave: **every e2e run exercises the real provisioner**, so the fresh path has a live
         proof outside unit tests. The rejected alternative is re-implementing layout/bucket/key/grant in
         Python through the existing `garage()` helper (`:521-523`): cheaper to write, and it puts a second,
         drifting copy of the bring-up in the test harness — which is the shape that would make a green e2e run
         stop meaning the provisioner works. **If the throwaway boot proves awkward, say why in the
         retrospective before switching**; the criterion is whether the run still proves the provisioner, not
         whether it is shorter.
      · **Done when:** the full `scripts/validate_*.py` glob green, `docker compose -f
      infra/docker-compose.prod.yml config -q` and `-f infra/docker-compose.yml config -q` clean, the
      assertion green where step 1 recorded it red, and **`python3 scripts/run_web_e2e.py` green**.

- [ ] **5 restore** — the one consequence that only bites now that a store is unusable without a backend. All
      four sites say the same thing and none may move without the others.
      - **`scripts/instance_backup.py:660-666`** — the *empty* gate becomes **no data** rather than **no
        tables**. Keep `store.size()`'s object gate (`:667-672`) exactly as it is: an initialised empty bucket
        holds zero objects, so it was never the problem. Decide and write down what "no data" is measured on —
        and pick a predicate that cannot be satisfied by a *populated* instance, which is A5's whole purpose.
        Two things to check while there rather than after: whether the dump loads onto an already-migrated
        schema (`grep -n 'add-drop-table\|mariadb-dump' scripts/instance_backup.py` and read the flags), and
        the half-restore message at `:681-687`, which tells the operator to "empty the target database" — its
        advice changes with the gate.
      - **`uc.admin.back-up-and-restore-instance.md:72`** — A5's own words. This is a correction to durable
        state: say which **shape** it takes — edited, narrowed, or replaced — not only which sentence
        (`changes/README.md` § *a phase that supersedes prose*). And `grep -rn` a distinctive fragment of the
        old claim across the whole tree before writing the site list; a claim is prose, so no symbol-keyed
        locator finds its copies, and this one is quoted in at least the use-case, the tool's docstring
        (`instance_backup.py:58`) and its two error messages.
      - **`docs/decisions/0056-*.md:50, :91`** — both name `infra/garage-init.sh` as what recreates a restore
        target's layout, bucket and key. After phase 4 that file does not exist. Edit **in place**;
        `docs/decisions/README.md:20-22` forbids `Amended:` lines and addendum sections, and three ADRs were
        just swept for exactly that form. The *"the restore target is cheap"* argument is unaffected and stays
        — it is cheaper still, since the target is now just a normally installed instance.
      - **`infra/README.md`** — the dev-store section (`:21, :35-42`), the restore walkthrough (`:290, :304`,
        whose `# garage-init recreates layout+bucket+key` comment and `--version 1` caveat both go, and whose
        manual `CREATE DATABASE veyq;` step the new gate makes unnecessary), and `:213`'s in-image-recipe
        limit, which **shrinks** to `garage.toml` alone.
      **Re-run the fragment sweep at this phase, not only at refine** — phases 1–4 wrote new prose into the
      same corpus, so the handed-over site list is a floor, not the set:
      `grep -rn --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build 'garage-init' .`
      · **Done when:** the full glob green, `grep -rn 'Amended:' docs/decisions/0056-*.md` returns nothing, and
      that sweep returns only sites this CR intends to keep.

- [ ] **6 coverage** — `docs/coverage/features/admin/use-cases/uc.admin.back-up-and-restore-instance.yaml`.
      **Two criteria, not one**, and the second is the one an inventory keyed on "A5" would miss:
      - `restore-onto-empty-opens-every-memory` — its text says *"restores onto an empty instance"*, and this
        CR changes what *empty* means. Both halves of the redefinition need a ref or an explicit `gaps:` entry
        with a reason: the freshly installed instance that must now be **accepted**, and the populated one that
        must still be **refused**. A single ref covering only the first is the acceptance half of a gate whose
        entire job is refusal.
      - `the-store-leg-restores-onto-a-different-store` — *"The bucket, access key and layout are recreated
        rather than restored, because they were never durable state."* The claim stays true and its **author
        changes**, from a script that will not exist to the backend. Check whether its refs still exercise what
        they say once phase 4 lands.
      · **Done when:** `python3 scripts/validate_coverage.py` green.

## Bite tests — and how each is bitten

Marked per `changes/README.md` § *the CR says PER bite-test how it will be bitten*. The live defects run
first, against the least-changed tree there will ever be.

1. **Live defect — the release recipe builds an image nobody publishes.** Phase 4 step 1. Red on the untouched
   tree, naming `garage-init` in `infra/docker-compose.prod.yml`; green after step 2. Free, and it is
   simultaneously the discovery, the bite and the fix-list.
2. **Live defect — a restore is refused onto a freshly installed instance.** Run the current
   `instance_backup.py restore` against a target whose app has started once: it refuses on `table_count()`.
   Red before phase 5, green after, and it is the defect in exactly the form the operator meets it. Free.
3. **Injection — provisioning never gates the boot.** Lever: point `veyq.blob-store.garage.admin-endpoint` at a
   dead port; assert the backend still answers `GET /api/setup` with `200`. Drive the **configuration**, not the
   container: `docker compose up -d` restarts a stopped container, so "stop garage" is a lever the tool repairs
   into a green run (`changes/README.md` § *check the lever survives the tool*, which cost `cr-e2e-orchestrator`
   exactly this).
4. **Injection — each step is individually idempotent.** Lever: run the provisioner twice against a store the
   first run provisioned; assert the second is a clean no-op. Then the sharper half, which catches the
   `garage-init.sh` early-return being copied: provision, remove **only** the CORS policy, run again, assert
   the policy is back. A provisioner that short-circuits on `bucket info` passes the first case and fails this.
5. **Live defect — the layout step generalises.** Assert the provisioner succeeds against a node whose layout
   is already applied. Red before the generalisation exists. Name the fixture in the phase: a real Garage, or a
   faked Admin API — **and if the fake is chosen, note that a bogus Admin API path returns `400` on the real
   v2.0.0**, so a fake answering `400` for "unknown" is indistinguishable from a real endpoint that rejected
   the body. Only a successful call proves an endpoint exists.
6. **Injection — the state field reports the failure, not `OK`.** Same dead-port lever as (3); assert the new
   read returns the gave-up value. The test belongs on the **property** (what the operator can read), never on
   the mechanism (that a retry ran) — a test written against the shape would be green on code that reports
   nothing.
7. **Injection — the provisioner does not become a second delete arm.** Lever: add a `blobStore.delete` call
   inside the provisioner and assert `python3 scripts/validate_store_reclaim_arity.py` reddens. Snapshot and
   restore: `cp <file> /tmp/snap` **before** the injection, `command cp -f /tmp/snap <file>` after (the
   `command` prefix skips an interactive `cp -i` alias). **Do not confirm the restore with `git diff --quiet`**
   — the baseline here is uncommitted CR work, so it reports "still dirty" whether the restore worked or not.
   Compare the file's bytes to the snapshot.
8. **Injection — the A5 gate still refuses a populated instance.** The mirror of (2), and the one that keeps
   the relaxation honest. Lever: point a restore at a target holding real rows; assert it refuses, by the
   specific message, not merely by "it threw" — a bare rejection is equally produced by a connection failure.
   Every negative assertion owes a positive control on the same predicate, and (2) is it.
9. **Injection — the new recipe assertion bites on a shape that never occurred here.** Rename the prod `app`
   service's image to a `veyq-`-prefixed name the workflow does not publish; assert the guard reddens by name.
   Assert the injection **landed** before believing the run — the new text is present **and** the file's bytes
   differ from the snapshot; "the old text is gone" is the wrong check for any injection that wraps or appends.

**Do the levers survive this CR's own phases?** (2) is the only one at risk — its lever is the very gate phase
5 relaxes, so it must run **before** phase 5, which the ordering above already does. (8) is its permanent
replacement and levers on data, which no phase removes.

## Definition of done

- **Baseline, run before the first edit** — the command, not a number in this file, which would rot and would
  be confirmed by this CR's own work:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED: $f"; done`. A red after this
  CR that was not red then is this CR's.
- `./gradlew check` green (full run), `npm run check` green in `web/` and `admin-web/`, the whole
  `scripts/validate_*.py` glob green over the **committed** tree, and `python3 scripts/validate_coverage.py`
  green.
- **`python3 scripts/run_web_e2e.py` green** — not a formality here: phase 4 rewrites how that script gets a
  usable store, and a green run is the only thing that proves the rewrite.
- All nine bite tests demonstrated, each in the form its entry names.
- `docs/coverage/` updated for `uc.admin.back-up-and-restore-instance`. Phases 1–4 make **no coverage claim**;
  what carries them is the bite tests, the new guard assertion, and the live smoke below.
- **A live smoke of the result, which is the only thing that proves the goal.** A first-ever `up` of the prod
  recipe on fresh volumes, with no manual store step: the app starts, the new read reports the succeeded state,
  and a media upload round-trips. `changes/README.md` § *a build step that emits an executable artifact is not
  done until the artifact RUNS* is the general form; here the artifact is a deployment, and "the compose file
  validates" is the green that does not earn it. **Rehearse the restore on the same stack**, since phase 5's
  whole subject is a procedure: install, stop the app, restore a capture, start, open a Memory with media.
- **Commit path confirmed before phase 1**, with a throwaway commit. This Mac signs through the 1Password SSH
  agent and it idle-locks mid-session; a locked agent fails every commit with `1Password: failed to fill whole
  buffer`, and a phased CR that cannot commit holds "each phase ends verifiable" open across every phase it
  finishes. Do not reach for `--no-gpg-sign` — ask.
- Commits go straight to `main`, small and conventional, split by phase. This repo has no remote and no CI run
  has ever executed, so a local run over the committed tree is the strongest available proof — a done-when may
  not say "green in CI".

## Traps

- **`fresh_bucket`'s `veyq-app` alias is an undocumented contract** between `scripts/run_web_e2e.py:539` and
  the bring-up. It was `garage-init.sh:47`'s; after phase 4 it is the backend provisioner's, and a mismatch
  breaks the e2e suite at a place that looks nothing like provisioning.
- **`S3BlobStore` caches presigners in a `ConcurrentHashMap` keyed by resolved endpoint and never invalidates
  it** (`BlobStore.kt:113-117, :137`). Correct while the connection is fixed at boot, and the first thing that
  must change if it ever is not. Nothing here may make store settings runtime-editable.
- **ADR 0062 §6 vs. the per-tenant remedy** — ADR 0067's Consequences now states the collision. The comment
  phase 2 writes at `StoreReclaim.kt` must carry the tension, not just the trap.
- **`type: s3` must reach none of the Garage code.** That is what bounds the cost ADR 0067 accepts in its
  Consequences; a shared path that "mostly" branches is the version of this that stops being bounded.
- **Relaxing the A5 gate is the only place this CR can make something worse rather than merely different.**
  Bite tests (2) and (8) are a pair on purpose; neither alone demonstrates the change is correct.
- **Out of scope, do not reopen:** per-tenant object stores (ADR 0067 defers them to `docs/inbox.md` #41 with
  the shape preserved and the trust decision named), a Kubernetes-native backup/restore story (ADR 0064 defers
  it — phase 5 removes an impossibility, it does not write that story), and the Helm chart itself (its own CR,
  later in `docs/inbox.md` #26 slice 4). Decision 5 of ADR 0067 is
  [`cr-store-own-address-signing-mode`](cr-store-own-address-signing-mode.md), deliberately a separate CR.

## Retrospective

<!-- Filled at implement time. What didn't go well — planning, execution, AND process/collaboration (did we
honor the conventions we already agreed?). Root cause, concrete fix. Both directions. End with one
forward-looking "next time". -->
