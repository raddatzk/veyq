---
id: cr-instance-backup-and-restore
title: Capture an instance's three legs, restore them onto an empty one, and prove a Memory still opens
status: done
satisfies: [uc.admin.back-up-and-restore-instance]
adrs: ["0013", "0040", "0050", "0053", "0055", "0056"]
invariants:
  - Operator-blind E2EE — every leg of the capture is ciphertext the operator already could not read
  - Self-hostable by default — the procedure runs against the bundled stack with no central service
  - All docs in English
---

## Goal

`docs/architecture/README.md` §10 sets the durability bar as an executable sentence — *a backup taken by the
documented procedure restores onto an empty instance, and afterwards every Memory opens with the keys their
owners already hold* — and that row still reads **half held** because nobody has written the procedure down or
run it. [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) decided what the
procedure captures (three legs, cold, store leg logical) and
[uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
decided its flow and failure modes. This CR turns both into a tool, a runbook and a test: a
`scripts/instance_backup.py` with `capture` / `verify` / `restore`, a `## Backup & restore` section in
`infra/README.md`, and a **web e2e restore rehearsal** in which one instance is captured, its source destroyed,
and a *second, structurally empty* instance restored — after which a person signs in with the password they
already had and reads back the exact bytes they uploaded before the capture.

It also builds the deployment artifact that restore step 10 points at. Today no image declares a `VOLUME`, no
compose mounts `config-secret.key`, and there is no production Compose file at all, so a containerised instance
regenerates its KEK on every recreate and walks into `MISMATCH` unaided — a defect ADR 0056 § Consequences
surfaces and `Dockerfile:69-77` already describes. This CR ships `infra/docker-compose.prod.yml` with leg (c) on
a named volume, and a permanent guard so the three files that have to agree about where that key lives cannot
drift apart again.

## Context & constraints

- **Decided, not up for renegotiation here:** [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md)
  (three legs; cold means the *writer* is stopped; the store leg is the S3 object namespace; `rclone` is invoked,
  not vendored; same domain only; the config-secret state is the final step; a backup precedes an upgrade).
- **The behaviour and its failure modes:** [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md),
  main flow steps 1–12 and A1–A8.
- **What may be claimed:** [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) — the
  coverage surfaces are `backend` and `web`, and *a test no CI run executes makes no coverage claim*. This is the
  binding constraint on this CR's Definition of done, not a formality (§ Open risk).
- **The runbook's home is `infra/`, not `docs/`:** [`docs/features/admin/_index.md:8`](../docs/features/admin/_index.md)
  — the use-case owns goal/failure-modes/acceptance, `infra/` owns the commands.
- **What the KEK's absence costs:** [ADR 0050](../docs/decisions/0050-operator-config-secret-encryption.md)
  amendment + [ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md) — a restore missing
  leg (c) boots, serves content, and refuses invites and password resets with `503 mail_unavailable`.
- **The e2e machinery this extends:** `scripts/run_web_e2e.py`, its two guards
  `scripts/validate_e2e_discovery.py` + `scripts/validate_e2e_canaries.py`, and
  [ADR 0053](../docs/decisions/0053-wire-assertion-canary-sweep.md)'s canary sweep.
- **Invariant at risk — none, by construction, and that is worth asserting rather than assuming.** Every leg is
  material the operator already held; the restore hands over no key. The canary sweep already runs per suite
  against the suite's database, so the post-restore suite sweeps the *restored* database for free. That is the
  operator-blindness invariant surviving the most privileged routine an operator has, checked rather than argued.

### The phase order is not contracts → backend → web, and that is the point

This CR touches **no contract and no backend code**. `configSecretState` and `503 mail_unavailable` — the two
server facts the leg-3 assertion reads — already exist (`contracts/api/tenants.openapi.yaml:1381,1441`;
`contracts/api/auth.openapi.yaml:163,183`; `contracts/api/tenants.openapi.yaml:253,315,321` — measured,
`grep -rn 'configSecretState\|mail_unavailable' contracts/`). The weight sits in `scripts/` + `infra/` + one web
e2e group. So the phases below are ordered by **dependency**, not by surface, and no empty `contracts` phase is
carried. Do not read the absence of a backend phase as an oversight: it is the finding that the server half of
the durability bar was never a code gap.

### The CR carries two done-whens, and they are named separately on purpose

This slice covers **both** the procedure and the deployment artifact that gives its restore step 10 a target.
The two are not one proof:

- **The procedure's proof is an assertion** — a Memory reopens after a restore (D5/D6, web e2e).
- **The prod compose's proof is a hand-run recreate** — an instance's `configSecretState` is still `OK` after
  `up -d --force-recreate app` (D8). It carries **no coverage claim for this use-case**: surviving a container
  recreate is not one of the five acceptance criteria, and pretending otherwise would put a ref under a sentence
  it does not exercise — the exact failure `scripts/validate_coverage.py`'s docstring says no guard can catch.
  What keeps it honest instead is a permanent guard (D9), not a coverage row.

Keeping the two done-whens distinct is the whole cost of bundling them; paying it in the phase list is cheaper
than discovering at implement time that "the CR is done" means two different things.

`infra/garage.toml`'s missing `metadata_snapshots_dir` is in scope for the same reason: ADR 0056 §3 attaches it
to the volume-copy fast path, and this CR is where that fast path gets documented.

**What genuinely stays out:** an upgrade runbook (ADR 0056 §6 makes this CR its precondition, not its author),
a supported domain change (ADR 0056 §4), and the upload-session GC that `V11__media.sql:9` still owes — ADR 0056
§ Consequences is explicit that a backup carries the instance's existing garbage and that a restore is not where
it gets collected.

## Decisions this CR makes

### D1 — One script, three subcommands, no third-party Python dependency

`scripts/instance_backup.py` with `capture`, `verify`, `restore`. One file rather than three because the three
share the backup-directory layout, the manifest and the credential plumbing, and because the runbook then reads
as three lines. It sits **outside** the `validate_*` glob deliberately (it is an operator tool, not a
trust-chain guard); its module docstring says so, in the shape `scripts/mutation_meta_check*.py` established.

`scripts/` has no third-party dependency and gains none: 18 `validate_*.py` + 3 `mutation_meta_check*.py` +
`run_web_e2e.py`, no `requirements.txt`, no `pyproject.toml` anywhere in the repo (**measured**:
`ls scripts/; ls scripts/requirements.txt scripts/pyproject.toml pyproject.toml requirements.txt` → all four
absent). External work is shelled out to, exactly as ADR 0056 §3 decided and as `run_web_e2e.py` already does
with `docker compose`.

### D2 — The backup is a directory with a manifest; `infra/backups/<UTC-ISO8601>/` is the default

```
infra/backups/<UTC-ISO8601>/
  manifest.json     # domain, veyq version, flyway version, object count + byte total, capture start/end, leg-3 present?
  database.sql      # mariadb-dump WITH DDL and the flyway history
  objects/          # the rclone mirror of the bucket, one file per object key
  config-secret.key # leg (c), 0600
```

`.dockerignore:24-25` already excludes `backups` and `infra/backups`, and **neither directory exists**
(measured — `git ls-files | grep backups` is empty). Those two lines are a plan nobody executed; this CR is what
makes them mean something, and the default destination is chosen to match them rather than to invent a third
path.

The manifest is not decoration — it is what lets `verify` and `restore` refuse before doing damage:
- **A3 (different domain) becomes a gate, not prose.** `restore` requires `--domain <d>` and refuses unless it
  equals the manifest's. ADR 0056 §4 declined to document a hand-written `UPDATE`; a refusal is the shape that
  decision implies. `veyq_tenant.domain` is `UNIQUE` (`V7__tenants.sql:11,15`) and is baked into outgoing mail
  (`SmtpTenantInviteMailer.kt:55` — `link = "https://$domain/tenant/accept#$token"`), so a silent host change
  produces an instance nobody can reach holding links nobody can follow. Both measured.
- **A4 / leg-3 absence become readable before a restore**, which is the whole reason the store leg is logical.

### D3 — The dump carries DDL and the Flyway history; that is what makes step 7 true

`mariadb-dump <db> --single-transaction --default-character-set=utf8mb4` — deliberately **not** the
`--no-create-info` form the canary sweep uses (`scripts/run_web_e2e.py:364`), which exists to compare literals,
not to restore. `flyway_schema_history` travels with the dump, so the restored instance boots at V44 and
`quarkus.flyway.migrate-at-start=true` (`application.properties:4`) is a no-op rather than a re-derivation —
UC step 7's *"migrations are not re-run against an empty database to produce it"*. **Measured**: 44 migrations
(`ls backend/src/main/resources/db/migration/*.sql | wc -l`), and no `outOfOrder` / `ignoreMigrationPatterns`
anywhere in `application.properties` (`grep -n flyway` returns exactly one line). This is also ADR 0056 §6's
mechanism: forward is automatic and ungated, backward does not exist, so the backup *is* the rollback.

> **Correction made at implement time: `--databases` is dropped.** The plan wrote `--databases <db>`, which is
> the flag that emits `CREATE DATABASE` + `USE <source name>` — it would make the *target* database's name a
> property of the backup file, and D5's restore rehearsal loads into a deliberately differently-named
> `veyq_e2e_restore_<n>`. The two decisions were in direct conflict and the plan did not notice. Dropping it
> costs nothing D3 actually claims: the DDL and `flyway_schema_history` travel because `--no-create-info` is
> *absent*, not because `--databases` is present. **Measured** — a restore of the live dev capture into an
> empty `veyq_restore_smoke` produced 30 tables, the `veyq.localhost` tenant row, and schema V44.

Two further facts the plan needed and did not have, both measured against the live instance:
**`MAX(version)` on `flyway_schema_history` is wrong** — `version` is a VARCHAR, so it answers `9` on a schema
at `44`; order by `installed_rank`. And **the object key is `<storage_key>/<i>` for `i` in `0 … chunk_count-1`**,
confirmed against the one blob row here (`chunk_count = 70` ⇒ exactly the keys `0 … 69`), with `veyq_blob` and
`veyq_upload_session` the only two tables carrying a `storage_key` in all 44 migrations.

That same live instance is also the CR's free evidence for `verify`'s asymmetry: it holds **102 objects against
70 the rows require**, so 32 orphans — ADR 0056 § Context 2's steady state, sitting there rather than argued for.

Two connection modes, because the runbook has exactly two audiences and no more: the bundled stack
(`docker compose exec -T mariadb mariadb-dump …`, the form `run_web_e2e.py:364` already proves works against
`mariadb:11.4`) and an operator's own database (host/port/user from env). **Measured**: no `mariadb-dump` and no
`mysqldump` on this machine (`which` → not found), so both modes must work through the container.

### D4 — `rclone` runs in a pinned container by default, and its credentials come from the app's own env

**Default:** `docker run --rm --network <compose network> -v <backup>/objects:/data -e RCLONE_CONFIG_… rclone/rclone:<pinned> …`
against the *internal* endpoint (`http://garage:3900`). **Escape hatch:** `--rclone-binary rclone` uses a host
install and the operator's own remote.

Why the container is the default, not the fallback: `rclone` is **not installed on this machine** (measured —
`which rclone` → not found) and the GitHub runner does not ship it either, so a host install would be a new
prerequisite for both the runbook and the `web-e2e` CI job. Every other moving part of this stack is already a
pinned image (`mariadb:11.4`, `dxflrs/garage:v2.0.0`, `traefik:v3.3`, `axllent/mailpit:v1.20.0` — measured from
`infra/docker-compose.yml`), the operator running the bundled stack already has Docker, and pinning the version
makes an old backup's restorability independent of whatever `rclone` the host happens to carry. The internal
endpoint is chosen over the published `19000` because `--network host` does not work on Docker Desktop.

**Credentials are never passed on `argv` and no config file is written.** The remote is defined entirely through
`RCLONE_CONFIG_<REMOTE>_{TYPE,PROVIDER,ENDPOINT,ACCESS_KEY_ID,SECRET_ACCESS_KEY,REGION,FORCE_PATH_STYLE}` in the
child environment, built from the **same variables the backend already reads** —
`VEYQ_BLOB_STORE_{ENDPOINT,ACCESS_KEY,SECRET_KEY,BUCKET,REGION}` (`application.properties:114-121`, measured).
An operator who can start the app therefore already holds everything `capture` needs; no new secret surface, no
second place for credentials to rot.

**`sync` for capture, `copy` for restore — never the reverse.** `sync` deletes at the destination, which is
correct for a capture directory (a re-run must not silently become the union of two captures) and catastrophic
against a live bucket. A5 (*"the target is not empty"*) is the reason: restore refuses a non-empty destination
rather than reconciling it.

> **Phase 1 made this measured — the env-defined-remote form exists and the fallback is not needed.** Against the
> running Garage on 2026-08-10, `rclone/rclone:1.75.0` (the newest tag that pulls; `1.76.0` does not exist)
> mirrored the live `veyq` bucket — **102 objects / 22.174 MiB**, matching `garage bucket info` — to a local
> directory and from there into a *second* bucket, and `rclone check store:veyq store:veyq-spike-restore`
> reported **`0 differences found`, `102 matching files`**. The remote was defined **only** by
> `RCLONE_CONFIG_STORE_{TYPE,PROVIDER,ENDPOINT,ACCESS_KEY_ID,SECRET_ACCESS_KEY,REGION,FORCE_PATH_STYLE}`, passed
> as bare `-e NAME` flags so no value ever reaches `argv`; rclone logs
> `NOTICE: Config file "/config/rclone/rclone.conf" not found - using defaults` and proceeds, so **that NOTICE on
> stderr is normal and must not be read as an error**. `provider=Other` is the working value for Garage.
> Two facts the spike added that the plan did not have: object keys are `<mediaId>/<chunkIndex>`, so the local
> mirror is a directory tree and not a flat list; and the mirrored files land owned by the invoking host user at
> `0644` under a bind mount, so `objects/` needs no ownership fixup.

### D5 — The restore proof is a **web e2e group**, and the source instance is destroyed so a no-op restore cannot pass

**Where it lives.** A Python-only harness would be cheaper and would make **no coverage claim at all** (ADR 0040:
the surfaces are `backend` and `web`, full stop), so §10 would keep saying *half held* and this CR would have
missed its own point. The assertion belongs on `web`, where a live suite already signs in, uploads and reads
back bytes. The Python is the fixture; the vitest assertions are the claim.

**The shape.** `scripts/run_web_e2e.py` learns exactly one new concept: a **group** — several discovered suites
sharing a stem that run against **one instance session**, with a hook between members. Everything else is
unchanged, and a group of one is byte-for-byte today's behaviour.

```
restore.a-capture.e2e.test.ts   # birth an instance, upload media, remember the password
      ↓  (orchestrator: kill app → capture → DESTROY the source → restore onto a fresh target → boot)
restore.b-reopen.e2e.test.ts    # sign in with that password, open the Memory, compare bytes
```

**Why a no-op restore cannot look like a successful one.** This is the bite-lever question asked before the
design was called sound, and the answer is structural rather than asserted:
- The restore target is a **fresh** database (`veyq_e2e_restore_<n>`), a **fresh** bucket, and a **fresh** key
  file path. It is empty by construction, so "the wipe did not wipe" is not a failure mode that exists — the
  same move ADR 0056 §2 makes when it stops the app instead of ordering the capture.
- The **source is destroyed after the capture**: its database dropped, its bucket deleted, its key file removed.
  A part-B run that silently pointed at the source would fail rather than pass, which is the only way "nothing
  was restored" and "everything was restored" can be told apart from outside.
- Part B's oracle is the **byte-exact round-trip**, not a row count: a restored database whose objects never
  arrived yields a presigned URL that `404`s with no server-side error (ADR 0056 § Context 2 — *this* is why the
  assertion must be on the fetched bytes, mirroring `media.e2e.test.ts:49`'s
  `Array.from(opened)).toEqual(Array.from(data))`).

**Part B signs in for real; it does not inject a vault.** *Correction to the substrate this CR was scoped
against, and to the coverage file's own `gaps.web` text:* `web/scripts/devSeed.mts` is **not** the fixture — no
e2e suite reads it. **Measured**: `grep -rn 'loadDevSeed\|vaultFromSeed' web/src` returns nothing; the only
consumers are `web/scripts/seed-*.mts` and `bootstrap-user.mts`, and `web/src/test/e2eBootstrap.ts:3-7` records
why (the seeded contract needed a `docker exec … UPDATE veyq_tenant`, which is not executable from CI). Every
suite bootstraps through `birthInstance()` / `onboardMember()` instead. This is a **better** fixture for this
criterion, not a worse one: `vaultFromSeed()` injects the Master Key directly and would bypass the password-
derived unlock that the sentence *"with the keys their owners already hold"* is actually about. So part A hands
part B the actor's email + password through a file in the run directory, and part B performs a real sign-in —
Argon2, KEK, unwrap. Fix `gaps.web`'s text in the same edit; it currently names a fixture that no longer exists
in that role.

### D6 — Leg 3 is a second group with a positive control on the same predicate

`three-legs-or-it-is-not-a-backup` needs a run that restores legs (a) and (b) and **not** (c). It cannot share
the first group's run, because the first group's whole claim is that a complete restore is complete.

The trap that would make this bite green for the wrong reason: `ConfigSecretKeyGuard.state()` returns `MISMATCH`
only when a fingerprint is **already stored**; with none stored it *adopts* the current key and returns `OK`
(`ConfigSecretKeyGuard.kt:44-48`, measured). And the adopt fires on the first configured read. So if part A
never causes one, the leg-3 injection produces `OK` — a bite that proves the opposite of what it claims. Hence:
**part A asserts `configSecretState: OK` before the capture** (the positive control) and part B asserts
`MISMATCH` plus a `503 mail_unavailable` on a member invite (the negative), both on the same predicate and the
same field.

> **The stated dependency was wrong twice, and the second error would have made the group green for the wrong
> reason.** The CR said *"`birthInstance()` points tenant mail at Mailpit, which seals an SMTP password and
> therefore adopts"*. It does not: `e2eBootstrap.ts` passes `username: null` **on purpose**, with the reason on
> the line — that keeps every other suite off the sealed-password path, which would need the KEK. So:
> 1. **The adopt does happen, by a different route.** `TenantMailSettingsHandler.update` returns
>    `row.toView(keyState.state())` unconditionally, and `state()` is what adopts. Worth knowing precisely,
>    because an "optimisation" that stopped returning the state on the PUT would silently remove the adopt —
>    and the pre-capture assertion is exactly what would catch it.
> 2. **The `503` could not have fired at all.** `TenantMailSender.sendable` is
>    `username.isNullOrBlank() || (passwordCiphertext != null && state() == OK)` — an auth-less relay sends with
>    no KEK whatsoever. **Measured**: the first run of the leg-3 group failed on
>    *"the invite must be refused, not accepted"*. So part A configures mail **with** a username and password of
>    its own, which is what gives the missing key something to cost. A bite table row that had been trusted
>    rather than run would have shipped a group asserting a `503` the server had no reason to send.

### D7 — `metadata_snapshots_dir` is configured away, not documented away

`infra/garage.toml` sets `metadata_dir` and `data_dir` and has **no** `metadata_snapshots_dir` (measured), so a
`garage meta snapshot` writes into the very volume the fast path copies. Set it to a path on its own named
volume.

> **The defect was already live on this machine, so this needed no forgery** (`changes/README.md` § where the
> defect is still live). Before the change, `infra_veyq-garage-meta` held `snapshots/2026-08-06T11:25:45Z` —
> a snapshot sitting inside the volume it snapshots. After it, `garage meta snapshot` reports
> `Snapshot created` and `2026-08-10T07:10:15Z` lands in `infra_veyq-garage-snapshots` while the metadata
> volume gains nothing — **checked by listing both volumes, not by re-reading the config**. The old directory
> stays where it is; the runbook says it is safe to delete once. **Measured, two angles on the same question:** the key exists in the pinned binary —
`docker cp veyq-garage:/garage <tmp> && strings <tmp> | grep -o metadata_snapshots_dir` → 1 hit (the image has
no shell, so `docker exec … grep` is not available), and the control on the same predicate (`metadata_dir`,
`data_dir`, `db_engine`, `metadata_fsync`) resolves identically, so the hit is the key and not an accident of
the search. And the CLI half: `docker exec veyq-garage /garage meta --help` lists
`snapshot` ("Save a snapshot of the metadata db file"), while `garage block --help` lists only
`info | list-errors | purge | retry-now` — **there is no block-level snapshot**, which is exactly why the
runbook must present the volume copy as a same-host rollback path and never as the procedure §10 measures.

### D8 — `infra/docker-compose.prod.yml` stands beside the dev file, does not override it, and terminates TLS

**Standalone, not an override, and run under an explicit project name.** Three measured reasons, each of which
alone would settle it:
- The dev file has **no app service at all** — it carries dependencies only, because the backend is run from the
  IDE (`infra/docker-compose.yml`, measured: services are `mariadb`, `garage`, `garage-init`, `traefik`,
  `mailpit`). An override that *adds* the app would inherit a file whose entire premise is that the app is
  elsewhere.
- It publishes `3306`, `19000`, `18080` and ships `MARIADB_PASSWORD=change-me` /
  `MARIADB_ROOT_PASSWORD=change-me-root` (measured). A prod file must publish `80`/`443` and nothing else, and
  must not inherit a known password by accident.
- **The project name comes from the compose file's directory, not the repo root** — which is why the live
  volumes are `infra_veyq-mariadb-data` / `-garage-meta` / `-garage-data` (measured: `docker volume ls`). Two
  compose files in `infra/` therefore share a project by default, and a `down -v` on the dev stack would destroy
  the production volumes. The runbook always spells the project: `docker compose -p veyq -f infra/docker-compose.prod.yml …`.

**TLS is not a deployment preference here, it is a precondition — and two measured facts say so:**
`veyq.session.cookie.secure=true` is the shipped default (`application.properties:65`, whose own comment reads
*"Set true behind TLS in production"*), so over plain HTTP the refresh cookie is set and never sent back; and
every invitation link is built as `https://$domain/tenant/accept#$token`
(`SmtpTenantInviteMailer.kt:55`). A prod compose that serves HTTP produces an instance whose "remember me" is
silently broken and whose mail points at a scheme it does not answer. So Traefik terminates TLS and routes both
the app and the bucket path same-origin — which is the "fuller deployment" the dev file's own Traefik comment
already anticipates (`infra/docker-compose.yml:76-77`).

**Secrets are declared without defaults.** Every secret (`MARIADB_PASSWORD`, `MARIADB_ROOT_PASSWORD`, the store
access/secret key, the Garage `rpc_secret` and admin token, the ACME account email) comes from `infra/.env`,
which `.gitignore:206` **already excludes** — another line, like `.dockerignore:24-25`, that was written for a
file nobody created yet. `infra/.env.example` is the tracked template. A missing variable must fail the `up`, not
default to a dev value: a prod stack that boots with `change-me` is worse than one that refuses to boot.

**Leg (c) is a named volume — at `/var/lib/veyq`, NOT at `/work`.** *Correction to this decision, made at
implement time.* The plan reasoned from the `WORKDIR` (`/work/`, at **both** runtime targets — measured) and the
relative `@WithDefault("config-secret.key")`, and both facts are right. What it missed is what else lives there:
`/work` holds `quarkus-app/`, the application itself. A named volume mounted over it is seeded from the image
**once** and never refreshed, so `up -d` with a newer tag would keep running the *first* image's jar — an upgrade
that silently does nothing, in the exact place ADR 0056 §6 says the backup exists to make upgrades safe. So the
compose sets `VEYQ_SECURITY_CONFIG_SECRET_FILE=/var/lib/veyq/config-secret.key` and mounts the volume there —
the absolute-path shape `VeyqConfig.kt` already names, and the one D9's false-positive control was written to
accept. The `Dockerfile` creates `/var/lib/veyq` (0700, uid 10001) in **both** runtime targets for one reason
only: Docker seeds a fresh named volume from the image directory it covers, ownership included, and a root-owned
mount would leave the server unable to write its own key.

The image is `ghcr.io/<owner>/veyq`, target `runtime-jvm`, as `.github/workflows/container.yml:196,203,243`
publishes it — but the owner is not knowable from this repository (`git remote -v` is empty), so `VEYQ_IMAGE` is
a **required** variable rather than a guessed default. That is the right shape anyway: a self-hosted instance
must be able to name the tag it can roll back to.

**Two further corrections the hand-run forced, both about Traefik:**
- **`traefik:v3.3` cannot talk to this host's Docker daemon at all.** Engine 29.6.2 / API 1.55 makes its docker
  client fail with an *empty* message (`Failed to retrieve information of the docker client and server host:
  Error response from daemon: `), so it loads **zero** routers and answers `404` for everything while the
  container looks healthy. Measured: `164579` such lines in the running **dev** Traefik, whose bucket route has
  therefore been silently dead here — invisible because the Vite dev server proxies `/veyq` itself. `v3.6` reads
  the daemon fine. Both compose files are bumped; that the dev stack's same-origin route is repaired is a side
  effect of the prod file's own requirement.
- **Traefik's docker provider is not project-aware.** Both stacks' proxies discovered *every* container on the
  host, defined the same `veyq-blobs` router with different configurations, and Traefik dropped it for **both**
  (`Router defined multiple times with different configurations`). Each proxy is now scoped with
  `--providers.docker.constraints=Label(veyq.stack,<dev|prod>)`. This is not only a two-stacks-on-one-box
  nicety: an internet-facing proxy that auto-publishes whatever else the machine runs is an exposure in its own
  right.

**Secrets left `garage.toml` entirely, which the plan did not anticipate.** The tracked config carried
`rpc_secret` and `admin_token` as dev throwaways, and prod needed real ones. Rather than a second config file
(duplication) or hoping an env override wins over a config value (**unmeasurable** — both spellings parse, and
which one wins is not observable from outside), the secrets were removed from the file: the pinned binary reads
`GARAGE_RPC_SECRET` / `GARAGE_ADMIN_TOKEN` (measured in its own strings, and confirmed by the dev stack coming
up and serving all 102 objects afterwards). One config file, no secret in a tracked file, and prod supplies real
values. The `garage-init` sidecar needs the same variable, since it authenticates its RPC with it.

**`Dockerfile` gains no `VOLUME`, deliberately.** A `VOLUME /work` creates an *anonymous* volume when nothing is
mounted, which is worse than the current state, not better: the operator sees data persisting across a plain
restart and concludes the key is safe, while `--rm` and `compose down -v` still discard it and no `docker volume
ls` name identifies it. The defect must stay visible where nothing mounts the path — detection is already the
remedy ADR 0050's amendment chose. The `Dockerfile:69-77` comment stays; its stale `VeyqConfig.kt:224` ref is **not** repointed at `:259` but
replaced by a locator that cannot rot ("the `@WithDefault` in `VeyqConfig.kt`'s `Security` interface") — which
is the same argument one step further: a line number in prose rots whether or not you have just corrected it.

**`infra/garage-init.sh` needs no change.** Its credentials are env-overridable with dev defaults (`:10-14`), so
prod passes real values through the environment and the idempotence gate at `:33-36` behaves identically. One
limit to state in the runbook rather than discover: `:41` hardcodes `layout apply --version 1`, so the script
initialises a *fresh* node only — which is exactly the restore target's shape, and not a partly-configured one.

### D9 — `scripts/validate_config_secret_persistence.py`: three files that must agree, tied together by nothing today

The one-shot proof behind D8 is *"the mount point equals the directory the key resolves into"*. That is a
structural invariant across three files that no build, test or type checks against each other, so
`changes/README.md` § promote one-shot proofs makes it a permanent guard rather than a paragraph.

**What it asserts:** for every service in every compose file under `infra/` that runs the veyq **app** image,
the path `veyq.security.config-secret-file` resolves to inside that container lives under a **named** volume
mount. Resolution follows the real rule, not a paraphrase of it:
1. if the service sets `VEYQ_SECURITY_CONFIG_SECRET_FILE`, that value wins;
2. otherwise the `@WithDefault` in `VeyqConfig.kt`, resolved against the `Dockerfile` `WORKDIR` of the target the
   service uses.

Three predicate traps, each already visible and therefore cheap to avoid — enumerate the spellings, do not match
the one in front of you:
- **`WORKDIR` appears twice** (`runtime-jvm` and `runtime-native`). A guard anchored on one of them passes a
  Dockerfile whose other target drifted.
- **`veyq-garage-init:v2.0.0` matches a naive `image: *veyq*`.** Identify the app service by its build target
  (`runtime-*`) or its published image, and exclude the init sidecar explicitly and by name.
- **An absolute `VEYQ_SECURITY_CONFIG_SECRET_FILE` pointing at a mounted secret is legitimate** (the K8s /
  docker-secret shape `VeyqConfig.kt:253-256` names). A guard that only knows the WORKDIR default reports that
  correct configuration as a violation, and the repair it invites is to break the deployment. *This turned out
  to be the shape the prod compose itself uses* (see D8's correction), so the "false-positive control" is the
  main path — which is why the guard also accepts a **host bind mount** and a **docker secret**, both of which
  persist a recreate exactly as well as a named volume. The CR said "named volume"; rejecting the other two
  would have punished correct configuration, which is the failure this very bullet names. What stays rejected:
  no mount, an **anonymous** volume (persistence-shaped and not persistent), and a path this guard cannot
  resolve.

**Docstring, three fields** (`changes/README.md` § trust-chain guards): what it asserts; **the defect that bought
it** — no image declared a volume and no compose mounted the key, so a recreated container regenerated its KEK
and walked into `MISMATCH`, which ADR 0056 § Consequences surfaced and this CR fixed; and **its limit** — it sees
compose files in *this tree* only. It cannot prove the host actually persists the named volume, and it says
nothing about a deployment that is not compose (Kubernetes, a bare `docker run`, a systemd unit). For those the
`veyq_instance` fingerprint tripwire remains the only detection, by design.

Add a case to `scripts/validate_guard_gaps.py` (`CASES`, `:239`; `check_case(case_id, script, forge, expect_pass)`,
`:266`) that forges the closed hole — remove the mount from the prod compose in a throwaway copy — and asserts
the guard rejects it. That is what stops the new limit from silently widening later.

## Inventory & locators

Run these at implement time; do not trust the outputs frozen below beyond the go/no-go they already served.

**Angle A — the prose that claims veyq has no backup, keyed on the word:**

```bash
git ls-files -z | xargs -0 grep -rniIl "backup" | grep -v node_modules
```

**Angle B — the claim that the config-secret key file must be persisted, keyed on the symbol:**

```bash
git ls-files -z | xargs -0 grep -rniIl "config-secret-file\|config-secret\.key\|configSecretFile\|VEYQ_SECURITY_CONFIG_SECRET" | grep -v node_modules
```

**The disagreement is the finding, and it is the trap this CR was warned about.** A hits `README.md`,
`wiki/en/running-your-instance.md`, `docs/architecture/README.md` and three web source files; B hits
`Dockerfile`, `infra/README.md`, `scripts/run_web_e2e.py`, `application-e2e.properties` and four backend test
files — **and the two sets share almost nothing**. A prose-only sweep would have corrected the wiki and left
`Dockerfile:69-77` and the orchestrator untouched, which is precisely how the previous CR fixed one sentence in
`infra/README.md` and left seven Kotlin files saying the old thing. Two documentation directories are one angle
twice; a word and a symbol are two.

Third locator, for the phase that renames nothing but *adds* suites — the guards that anchor on the e2e
population, before touching `run_web_e2e.py`:

```bash
grep -rn "discover()\|NoE2eFilesError\|recordCanaries" scripts/validate_e2e_*.py scripts/run_web_e2e.py
```

| Surface | What changes | Notes |
|---|---|---|
| `scripts/instance_backup.py` | **new** — `capture` / `verify` / `restore` | outside the `validate_*` glob on purpose; docstring says why |
| `scripts/run_web_e2e.py` | grouped suites + the restore hook | `discover()` must keep its signature and return paths — see below |
| `web/src/features/admin/restore*.e2e.test.ts` | **new** — two groups, four files | `birthInstance()`, not `devSeed` |
| `scripts/validate_config_secret_persistence.py` | **new** guard — mount point × WORKDIR × `@WithDefault` | D9; joins the `validate_*` glob with nothing to register |
| `scripts/validate_guard_gaps.py` | one new case pinning D9's closed hole | `CASES:239` |
| `infra/docker-compose.prod.yml` + `infra/.env.example` | **new** — app, TLS, no defaulted secrets, `/work` volume | D8; `.gitignore:206` already excludes `infra/.env` |
| `infra/garage.toml` + `infra/docker-compose.yml` | `metadata_snapshots_dir` + its volume | D7 |
| `Dockerfile` | stale `VeyqConfig.kt:224` ref in the `:69-77` comment | no `VOLUME` — D8 |
| `infra/README.md` | **new** `## Backup & restore` **and** `## Running in production` sections | `docs/features/admin/_index.md:8` owns this seam |
| `docs/coverage/.../uc.admin.back-up-and-restore-instance.yaml` | gap texts corrected; status per § Open risk | D5's devSeed correction lands here |
| `README.md`, `wiki/en/running-your-instance.md` | stop saying no procedure is written down | **no DE half exists, and that is by design** — the operator page says so in its own first paragraph (technical, English-only); `wiki/de/` holds only the four end-user pages |
| `docs/architecture/README.md` | §10's row; and `:290`'s suite tally | see below |
| `docs/inbox.md` #26 | what remains after this slice | the upgrade runbook + the CI-run follow-up (§ Open risk) |

**Two guard constraints, from `changes/README.md` § before reshaping a symbol:**
- `validate_e2e_discovery.py:91` asserts *textually* that `main()` contains `else discover()`, and `:63-72` that
  `discover()` returns a non-empty list of `.e2e.test.ts` paths. So grouping is applied to `files` **after**
  `files = [normalize(a) for a in argv] if argv else discover()` (`run_web_e2e.py:501`); `discover()` itself is
  not reshaped. Verified against the guard's source, not assumed.
- `validate_e2e_canaries.py` requires **every** discovered suite to import *and* call `recordCanaries`. All four
  new files must, including the post-restore ones — which is a feature: the sweep then runs against the
  **restored** database and proves it holds ciphertext.

**`docs/architecture/README.md:290` is wrong twice, and the second one matters more than the first.**
- It says *"only **five** suites exist"* and there are six (measured: `ls web/src/**/*.e2e.test.ts | wc -l` → 6).
  Do not update the tally to ten. Replace it with a sentence that carries no count — a frozen number in prose is
  exactly what `changes/README.md` § the CR carries the locator exists to prevent, and it has already rotted here.
- The same row says *"CI now runs e2e"* and that the orchestrator debt is *"green in CI"*. **In this repository
  that is false**: there is no remote and no run has ever happened (§ Open risk). The sentence was true where it
  was written and the repo move carried it across a substrate change without re-measuring — the exact failure
  `docs/ai/README.md` § the pipeline names, *a measurement is only as true as the tree it ran against*. Fix it in
  the same edit, because this CR's own Open risk depends on that claim being false, and leaving two contradictory
  statements in the tree makes whichever one a reader finds first the answer.

## Phases

- [x] **1 spike — make D4 measured** *(done — transcript folded into D4)* · The only deliverable is a transcript: `rclone` in the pinned container,
      defined purely through `RCLONE_CONFIG_*`, listing and mirroring the running Garage's `veyq` bucket.
      **Done when:** an object PUT by `media.e2e` is mirrored to a local directory and back into a *different*
      bucket, with no config file and no credential on `argv`. If the env-remote form does not exist in the
      pinned version, fall back to the `0600` generated `rclone.conf` (D4) and record that in the CR before
      Phase 2 starts.
- [x] **2 tool** — `scripts/instance_backup.py` `capture` / `verify` / `restore`, the manifest, the domain gate,
      the two DB connection modes. · **Done when:** a hand-run capture of the live dev instance produces the D2
      layout; `verify` exits 0 on it; `verify` exits **non-zero, naming the object**, when one file is deleted
      from `objects/` and 0 again when it is put back (the negative and its positive control on one predicate).
- [x] **3 infra — dev stack** — `metadata_snapshots_dir` + its volume; `infra/README.md` `## Backup & restore`
      carrying the real commands. · **Done when:** the compose stack comes up clean, `garage meta snapshot`
      writes **outside** the metadata volume (checked by listing the volume, not by reading the config), and
      every command printed in the README has been executed as written.
- [x] **4 prod compose** — `infra/docker-compose.prod.yml`, `infra/.env.example`, the `## Running in production`
      section, the `Dockerfile` comment's stale ref. · **Done when:** `docker compose -p veyq -f
      infra/docker-compose.prod.yml up -d` comes up from a written `.env` on a **clean** machine state, an
      instance is birthed through it over TLS, and after `up -d --force-recreate app` the tenant mail settings
      still report `configSecretState: OK`. **Its own bite:** the same recreate with the `/work` volume removed
      must report `MISMATCH` — the negative and its positive control on one field, which is also what proves the
      volume is load-bearing rather than decorative. Must not disturb the dev stack: `docker volume ls` shows
      `infra_*` and `veyq_*` side by side and the dev stack still serves.
- [x] **5 guard** — `scripts/validate_config_secret_persistence.py` + the `validate_guard_gaps.py` case. ·
      **Done when:** green on the tree; **red, naming the service**, with the `/work` mount deleted; **red** with
      `Dockerfile`'s `WORKDIR` changed; **red** with `VeyqConfig.kt`'s `@WithDefault` changed; and **green** for
      a service that sets an absolute `VEYQ_SECURITY_CONFIG_SECRET_FILE` into a mounted path (the false-positive
      control — without it the guard is one that punishes correct configuration). Restore by snapshot-and-copy,
      confirm with `git diff --quiet -- <path>`, never `git checkout`.
- [x] **6 orchestrator** — grouped suites + the restore hook in `run_web_e2e.py`. · **Done when:**
      `for f in scripts/validate_*.py; do python3 "$f"; done` is green (both e2e guards included) **and** a full
      `python3 scripts/run_web_e2e.py` run passes with the existing six suites unchanged — the group-of-one path
      must be a no-op before a group of two is trusted.
- [x] **7 web e2e** — the two groups (D5 restore rehearsal, D6 leg-3). · **Done when:** the full orchestrator run
      is green and the § Bite-proofing table below has been executed, each row red on the named assertion.
- [x] **8 docs + coverage** — the two-angle locator's sites; `docs/coverage/…yaml`; `docs/inbox.md` #26; §10 and
      `:290`; `README.md:16` may now drop *"no production Compose file"* but **not** *"no upgrade procedure"*. ·
      **Done when:** `python3 scripts/validate_coverage.py` and the whole `validate_*` glob are green over the
      **committed** tree.

### What Phase 3's "every command executed as written" actually covered

The capture and verify blocks ran **verbatim** against the live dev instance (`infra/backups/<stamp>`, three
legs, `verify` exit 0). The restore block cannot: its step 1 (`CREATE DATABASE veyq`) correctly errors with
`Can't create database 'veyq'; database exists` on any machine that still holds the instance it is restoring —
which is the honest behaviour, not a defect. So the restore block was executed with **`--db-name` and
`--store-bucket` pointed at scratch names and nothing else changed**, because destroying the owner's live dev
instance to prove a runbook is not a trade this CR gets to make. The real restore path onto a genuinely empty
instance is Phase 7's job, which is where the CR already located that proof.

**One defect the hand-run found that no reading would have:** `restore` was handed a *repo-relative* backup path
and `docker run -v foo/bar:/data` does not read that as a host directory — it tries to create a **named volume**
and fails on the `/`. The capture path had hidden it, because its default destination is absolute. Fixed by
resolving the mount, and the fix carries the reason on the line. The same run surfaced that a mid-restore
failure leaves the target half-loaded, so the failure now says so — otherwise the retry's refusal
(*"already holds 30 tables"*) reads as an unrelated problem.

## Bite-proofing

Every row is an injection into the orchestrator's restore hook, run against the same suites. **Read the full
output and assert the named test goes red — never that "something failed"**, and restore by snapshot-and-copy,
never `git checkout` (`changes/README.md` § bite-proof a guard).

| Lever | Survives the tool? | Oracle | Must go red on |
|---|---|---|---|
| skip the DB load | Flyway **repairs the schema** but not the rows — so the injection must be *skip the load*, never *drop the schema* | part B sign-in | the named auth assertion |
| skip the object push | nothing repairs it — `garage-init.sh:33-48` recreates bucket/key/layout, never objects | part B byte round-trip | the byte comparison, **not** a 500 or a boot failure |
| skip the key file | the server **generates a new one** rather than failing (`ConfigSecretSeal` read-or-generate) — which is why the verdict is `MISMATCH`, not `ABSENT` | leg-3 group | `configSecretState: MISMATCH` + `503 mail_unavailable` |
| restore everything (control) | — | all of the above | nothing — this row must be **green**, and it is what makes the other three mean something |

Phases 4 and 5 carry their own levers in their done-whens rather than here, because they act on a different
mechanism (a mount, not a restore hook) and folding them into this table would blur which run proves which
thing. Both follow the same three rules, and Phase 5's **false-positive control** — a legitimate absolute
`VEYQ_SECURITY_CONFIG_SECRET_FILE` must stay green — is the positive control that the four rows below get from
their last line.

### What the table actually did, when run

All four rows executed as injections into `restore_hook`, each with the injection **confirmed present in the
file before the run** and the orchestrator restored afterwards by `command cp -f` + `cmp` (byte-identical, and
`grep -c BITE` → 0). Three deltas worth keeping:

- **Row 1 reddens EARLIER and more precisely than the table predicted.** It expected the auth assertion; what
  fires is `restore.b-reopen`'s *"part B must run against the RESTORED instance, not a fresh one"*
  (`expect(setup.setupRequired).toBe(false)`), because Flyway rebuilds the schema and an empty schema reads as
  `setupRequired: true`. That assertion did not exist when the table was written and is strictly the better
  oracle — it names the cause instead of a symptom of it.
- **Row 2 reddens on the fetch inside the byte round-trip, not on `toEqual`.** `chunk_get_failed:404` from
  `getChunk`, raised on the `openOriginal` line. Which is the point the table was making — *a valid presigned
  URL that 404s* — arriving one call earlier than "compare the arrays", because there are no bytes to compare.
- **The first attempt at row 1 was a false negative, caught only by reading the output.** The anchor had
  drifted, the patch never applied, and the run came back green — `changes/README.md` § a bite test must tell
  "the guard held" apart from "nothing ran", live. Every later row asserts the marker is in the file before
  running.

A fifth injection the table did not carry, added because the leg-3 group's green needed its own inverse: flip
its hook to `with_key=True` and `legthree.b` must go red on `MISMATCH`. It does — which is what proves that
group's green depends on the key genuinely being absent rather than on something incidental.

The middle column is not commentary. Each row was checked for the three failure modes `changes/README.md`
records: *does the lever reach the mechanism* (yes — all three are omissions inside the hook that performs the
restore, not knobs beside it), *does the tool repair the injection* (the DB row would have been repaired by
Flyway if the injection were phrased as a schema drop — the reason that row's phrasing is load-bearing), and
*does the injected state reach the oracle in the form the oracle searches for* (a missing object arrives as a
`404` on a valid presigned URL, so the oracle must be the bytes; a missing key arrives as `MISMATCH`, so the
oracle must be the field, and only if a fingerprint was adopted first — D6).

## Definition of done

Two done-whens, both required (§ The CR carries two done-whens):

1. **The procedure.** The `validate_*` glob green over the **committed** tree
   (`for f in scripts/validate_*.py; do python3 "$f"; done` — the glob sees files, not commits, so an untracked
   guard is unchecked in CI), `python3 scripts/run_web_e2e.py` green with every suite executed, every command
   printed in `infra/README.md` executed as written, and the § Bite-proofing table run with all four rows landing
   as specified.
2. **The deployment artifact.** Phase 4's recreate proof and its inverse, plus Phase 5's guard green on the tree
   and red on all three forgeries while staying green on the legitimate absolute-path configuration.

`docs/coverage/…/uc.admin.back-up-and-restore-instance.yaml` updated — and see the risk below for what "updated"
is allowed to mean. The new guard **must be committed before its green is believed**: it is `scripts/`, and
`scripts/` is the registry.

### Open risk — accepted knowingly: no CI run is possible in this repo

**Measured:** `git remote -v` is **empty** and `git branch -a` shows only `main` — this repository has no remote
and has never had a CI run. The workflow files exist on `main` (`ci.yml` carries the `web-e2e` job that is the
only thing anywhere that sets `VEYQ_E2E`), but nothing has ever executed them.

ADR 0040 is not negotiable about what follows: **a test no CI run executes makes no coverage claim.** And
`docs/coverage/README.md` § Rules forbids the compromise — *a surface that is covered may not also be a gap* —
so "refs plus a caveat" is not an expressible state. Therefore:

- The coverage file **stays `status: planned` with `gaps.web` and `gaps.backend` intact** when this CR lands.
  What changes is that the gap texts become true: they name the suites that now exist, and state that the single
  remaining condition is a CI run, not a missing test. (`validate_coverage.py:203-206,234-235` — `planned`
  exempts criteria from needing refs and requires at least one gap; measured against the guard's source.)
- **`docs/architecture/README.md` §10 stays "half held".** The bar follows the build, and a local run is not a
  build. This CR narrows the sentence from *"nobody wrote the procedure down"* to *"the procedure is written and
  proven locally; no CI has executed it"*, which is a smaller and honest claim.
- The strongest available proof is the local orchestrator run **with its log read**, not its exit code —
  `docs/ai/README.md` § What counts as verified is explicit that a job that ran nothing and a job whose suites
  all passed render identically.
- **The follow-up is named, not hoped for:** `docs/inbox.md` #26 gains a row for *"push, read the `web-e2e` log,
  then flip the coverage refs and §10"*. It is a doc edit gated on an event, and it is deliberately **not** in
  this CR, because adding a remote and publishing this repository is the owner's call and not a work item an
  implementer should reach for.

This mirrors the shape the mail CR used rather than inventing one, and it is stated here so nobody later reads a
green local run as a coverage claim.

**What the follow-up flip is allowed to claim** — decided now, so the event-gated edit is a mechanical one and
not a fresh judgement call made months later:

| Criterion | After a CI run | Why |
|---|---|---|
| `restore-onto-empty-opens-every-memory` | `web` `e2e` — the D5 group | §10's sentence, the point of the slice |
| `three-legs-or-it-is-not-a-backup` | `web` `e2e` — the D6 group | `configSecretState` + the `503`, with its pre-capture control |
| `the-store-leg-restores-onto-a-different-store` | `web` `e2e` — the D5 group | the restore target is a different bucket with different credentials; **partial** — a different endpoint and a different store *implementation* stay uncovered, and the gap text must say which half is claimed |
| `a-stopped-app-makes-the-dangerous-skew-impossible` | **stays a gap** | the property is *"the app is stopped"*, which neither surface can observe — a backend test **is** the app. It is proven by § Bite-proofing and by `verify`'s asymmetry (an object with no row is tolerated, a row with no object is fatal), and a Python tool is not a coverage surface (ADR 0040) |
| `the-store-leg-is-verifiable-before-the-restore` | **stays a gap** | the observable is `verify`'s exit code, which no web suite can read. Same reason, same evidence — Phase 2's done-when is exactly this criterion, executed rather than claimed |

Two of five staying gapped is the honest count, not a shortfall to design around. The temptation to close them by
inventing a backend endpoint that reports capture health should be resisted: that is an operator tool wearing a
product surface's clothes, which is the shape ADR 0056 §4 already rejected once.

## Retrospective

**Planning.** The CR was executable almost verbatim — five of eight phases landed with no re-decision, and the
done-whens were run as written. What went wrong clusters in one place, and it is the CR's own § upstream of
every trap: **the claims that cost were the ones that never reached for a command**, and all of them are about
a *location* whose current contents nobody looked at.

- **Leg (c) at `/work`** (D8). Reasoned correctly from the `WORKDIR` and the relative `@WithDefault`, and never
  asked what else is in `/work`. `quarkus-app/` is — so the decided mount would have frozen the application at
  its first image, silently, in the exact place ADR 0056 §6 says the backup exists to make upgrades safe. One
  `ls` inside the image was the whole distance.
- **`--databases` in the dump** (D3) directly contradicted D5's deliberately differently-named restore target,
  two screens apart in the same document. Nothing catches a CR that disagrees with itself; only running it does.
- **"`birthInstance()` seals an SMTP password"** (D6) was a claim about a file the CR cited, whose relevant line
  says the opposite with the reason attached. Had the bite table been trusted instead of run, the leg-3 group
  would have shipped asserting a `503` the server had no reason to send.
- **The bite table's row-1 oracle** named an assertion the suite does not reach, because a better one fires
  first. Harmless, and the tell that a table written against an imagined suite ages into a table about a real
  one.

**Execution.** The failures were cheap and mostly self-reporting, with one exception worth naming.

- **A bite ran green because its anchor had drifted and the patch never applied.** Caught only by reading the
  full output — the precise false negative `changes/README.md` warns about, on the first attempt. Every
  subsequent injection asserts its marker is in the file before the run.
- **Two interactive-alias traps** (`rm -i`, `cp -i`): one silently turned a deletion into a no-op and produced a
  second false-green, the other hung a command until a 10-minute timeout. `command rm -f` / `command cp -f` are
  in the working rules for `cp`; the lesson is that they apply to *every* destructive builtin in an injection,
  not just the restore step.
- **`fresh_bucket` created-if-absent instead of purge-then-create**, so a second run collided with the previous
  run's target. It was caught by `instance_backup.py`'s own A5 refusal, on real leftovers — the gate working in
  the wild, which is better evidence than the forged version would have been.
- Smaller: `docker run -v <relative>:/data` silently means *named volume*, not host path; and this machine runs
  Python 3.9, so `str | None` needs `from __future__ import annotations`.

**Process & collaboration — both directions.**

- **Mine.** I let the prod-compose phase discover its own prerequisites: its done-when needs a *built image*,
  and on a machine with no registry that is a 12-minute local `docker build` nobody had budgeted. It belonged
  in the phase list as a step, not as a surprise.
- **Mine.** The CR is ~530 lines before implementation and grew during it. Much of that length is genuinely
  load-bearing (the traps, the controls), but the three planning errors above were each stated *with reasons*
  at length — length that read as diligence and was not evidence.
- **Yours.** Two things about the dev machine were worth knowing and were not in the CR: this instance had **no
  config-secret key file at all** (so the runbook's capture command, run as written, produces a two-leg backup
  and exits 2 — correct behaviour, confusing first impression), and the dev stack's Traefik had been answering
  `404` for **every** route since at least 2026-08-03 because `v3.3` cannot talk to Docker Engine 29. Neither is
  a mistake; both are the kind of local state that costs an hour when a CR assumes the stack is healthy.
- **Ours, and it worked.** Splitting the CR's two done-whens apart in the refine conversation paid for itself
  immediately: when the prod-compose proof turned into its own multi-hour thread, there was never a question
  about whether "the CR is done".

**Next time.** Before a CR fixes a **path** — a mount point, a working directory, a restore target, a config
file it will write into — list what already lives there and paste the output into the CR. Three of this CR's
four planning errors were about a location whose current contents were never inspected, and each was one
`ls`, one `grep` or one `--help` away.
