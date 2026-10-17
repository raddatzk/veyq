---
id: cr-backup-store-leg-is-an-index
title: Every backup is the database plus the store's index; objects only into an optional pool, and back only into an empty store
status: planned
satisfies: [uc.admin.back-up-and-restore-instance, uc.admin.install-and-upgrade-instance]
adrs: ["0056", "0096", "0098", "0091", "0062"]
invariants:
  - operator-blind E2EE — a pool holds ciphertext only, and the manifest names a pool's target, never a credential
  - self-hostable by default — a pool is a directory or any S3-compatible store, through rclone; no vendor
  - ADR 0056 §1 — leg (c) never beside the dump, unchanged by this CR
  - ADR 0056 §2 — the capture is cold; the pool's early copy is the one thing allowed before the app stops, because objects are immutable
---

## Goal

Today a Compose capture (`veyqctl backup`, the backup `veyqctl upgrade` starts with) copies the **whole bucket** into
`objects/` (`backupFormat` 2), while the cluster's backup gate already records only the store's **index** (`backupFormat`
3). The owner decided on 2026-10-17 that every capture is the database plus the index, the key apart as today; that an
**object pool** is optional, named per run (`--objects-to`), copy-only and incremental; that a restore **checks** the
objects against the index — in the live store, or in a pool named with `--objects-from` — and copies objects **only
into an empty store**; and that `veyqctl status` warns while the bundled Garage, which keeps no object versions, has no
pool. This CR builds that in `scripts/instance_backup.py` and `scripts/veyqctl.py`, moves the e2e harness onto it,
proves it live on a throwaway Compose project, and rewrites the Run pages. The decisions live in
[ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) §3 and §7 (edited in place), ADR 0096
decisions 1, 5 and 6, ADR 0098 decisions 4 and 8, ADR 0091 decision 6; read them there, not here.

## Context & constraints

- Use-cases: [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md)
  (steps 3 and 8, A4, A5, A6, A7, A11 — its header says what is built), [uc.admin.install-and-upgrade-instance](../docs/features/admin/use-cases/uc.admin.install-and-upgrade-instance.md)
  (step 2, the new step 3). Their coverage files carry two new criteria each marked *decided, not built*:
  `a-restore-copies-objects-only-into-an-empty-store`, `an-object-pool-only-grows`, `status-warns-while-the-bundled-store-has-no-pool`.
- **Garage keeps no object versions — measured at refine (2026-10-17)**, a throwaway `dxflrs/garage:v2.0.0@sha256:15b4…`
  node on its own network, driven by the pinned `rclone/rclone:1.75.0@sha256:b06a…` (`RCLONE_IMAGE`):
  `rclone backend versioning S:probe` → `"Unversioned"`; `… Enabled` → rc 1, `PutBucketVersioning … StatusCode: 501 …
  NotImplemented: Unimplemented action: PutBucketVersioning`; `lsjson --s3-versions` → `ListObjectVersions … 501`. This
  is ADR 0056 §3's sentence; re-measure it only if `GARAGE` moves off v2.0.0 (`grep -n 'dxflrs/garage' infra/*.yml`).
- **The pool's levers — run at refine against the same throwaway node, outputs pasted:**
  - `rclone copy --immutable S:probe /pool` → rc 0; after `deletefile S:probe/k1/0` and a second copy the pool still
    lists `k1/0`, `k2/0`, `k3/0` — **copy never deletes**;
  - the store's `k2/0` rewritten with other bytes, then `copy --immutable` → **rc 6**, `ERROR : k2/0: Source and
    destination exist but do not match: immutable file modified`, and `rclone cat /pool/k2/0` → `two` (unchanged);
  - `lsjson -R --hash --hash-type md5 --files-only --files-from /files.txt /pool` → only the listed keys, md5 computed
    locally (`--hash` alone on a local dir computes EVERY hash type — always pass `--hash-type md5`);
  - `copy --immutable --files-from /files.txt /pool S:probe2` into an empty bucket → rc 0, and `lsjson --hash` of the
    bucket shows the same md5s;
  - `size --json` of an empty bucket → `{"count":0,"bytes":0,"sizeless":0}`.
- **The store check's probe object is not data**: `StoreCheck.kt:50-51` — `PROBE_KEY` (`veyq-check/probe`, line 192)
  is *"overwritten by every check and never deleted"*. The new empty-store gate ignores exactly that key, the way
  `NOT_DATA` (`grep -n 'NOT_DATA = ' scripts/instance_backup.py`) spares the database's non-data tables. Whether a
  fresh install's first boot leaves the bucket empty is **inferred, not measured**: the format-2 restore already
  refuses a non-empty bucket (`grep -n 'already holds .* object' scripts/instance_backup.py`) and the web-e2e restore
  groups restore into a freshly installed bucket through it. Phase 4 measures it.
- **Why Compose kept format 2 until now** (`grep -n -A6 '^INDEX_FORMAT' scripts/instance_backup.py`): *"`veyqctl`
  restores with the RUNNING release's tool, which after a Compose rollback is the older release's: it must keep reading
  what Compose writes."* That reason holds no longer: **no release has ever been published** (ADR 0096's status line:
  *"What waits on the owner: the remote, immutable releases switched on, the first publish"*), so every release that
  will ever exist ships a tool that reads format 3 — the gate has written it since cr-kubernetes-backup-gate.
- Hard invariants at risk: operator-blindness (a pool is ciphertext; the manifest must never carry a pool credential —
  bite B5) and self-hostability (rclone, any S3 or a directory). ADR 0056 §2's cold capture: the pool's **early** copy
  runs with the app up; that is sound only because objects are immutable and the cold index decides what is required
  (ADR 0096 decision 5 step 4 says so) — the top-up after the stop is what the check reads.
- **Kubernetes is touched by nothing here but the format's shared code**: the gate takes no pool (ADR 0098 decision 4);
  `gate` keeps calling `take_capture(leg=INDEX)`. `chart_meta_check.py` and the tools image's in-image self-test must
  stay green (`grep -n 'instance_backup_selftest' .github/workflows/ci.yml tools/Dockerfile`).
- **Sibling CRs, named, not edited** (planned on `main` 2026-10-17): `cr-report-and-hold` adds `veyq_hold_pin` — an
  object a hold pins whose live blob row is gone is known only to that table, so the *required* keys must later
  include pinned ones; that CR owns the extension, at `Database.blob_index` (the one query that decides which rows
  require an object, its docstring says so). `cr-guest-write-record` takes its rows out of the logical dump. This CR
  builds nothing for either; its index reads `veyq_blob`, correct until holds exist.
- A parallel agent edits ADR 0093, 0097, 0065, 0099, `docs/crosscutting/security-model.md` and
  `site/src/content/docs/run/moderation.md`. This CR stays out of them; O3 names the one sentence of security-model.md
  that this decision reaches and routes it.

## Decisions settled at refine

Each binds this CR's work; the pointer settles it.

1. **One format: every capture writes `backupFormat` 3.** No new number: format 3 already means *"the store leg is an
   index, no `objects/`"*; the pool is a new **optional** `store.pool` block `{target, objects, bytes}` an older
   format-3 reader ignores (`read_manifest` reads named keys only — `grep -n 'def read_manifest' -A28
   scripts/instance_backup.py`). `--store-leg` and the mirror capture are **removed** (nothing writes format 2 any
   more: `grep -rn 'store-leg\|store_leg' scripts/ tools/ charts/` lists the sites); `verify` and `restore` **keep
   reading format 2**, its `objects/` treated as the `--objects-from` source, so a capture taken before this CR stays
   restorable — onto an empty store only (ADR 0056 §7). `Store.mirror_out` stays: `e2e_sweeps.sweep_store` uses it
   (`grep -n 'mirror_out' scripts/e2e_sweeps.py`).
2. **A pool target is a directory or `s3:<bucket>[/<prefix>]`.** A directory is an absolute path, created `0700` by the
   tool and mounted into the rclone container (as today's capture mounts `objects/`); an S3 pool's endpoint, key pair and
   region come from `VEYQ_BACKUP_POOL_ENDPOINT`, `…_ACCESS_KEY`, `…_SECRET_KEY`, `…_REGION` in the environment, passed as
   bare `-e NAME` like the store's (`grep -n 'Bare .-e NAME.' scripts/instance_backup.py`), never an argv element. The
   manifest's `store.pool.target` is the directory path or `s3:<endpoint>/<bucket>/<prefix>` — never a key.
3. **Copy-only, through one builder.** Every rclone call against a pool is `copy --immutable` (store→pool at capture,
   pool→store at restore, with `--files-from` the index's keys where named below) or a read (`lsjson`, `size`). No
   `sync`, `move`, `delete*`, `purge` may name a pool; bite B4 pins that on the builder itself.
4. **Capture with `--objects-to`:** the index is listed cold as today; then `copy --immutable --files-from <the index's
   keys>` store→pool (a key the pool holds is skipped by rclone, so this is the increment); then the pool is listed for
   the index's keys (`lsjson --files-from`, **no** `--hash`: key and size only, because hashing a local pool reads every
   byte at every capture — rclone's own transfer already checks the md5 of what it copied) and any index key missing or
   of another size fails the capture. A failed pool step fails the whole capture (no manifest written, as today's
   `take_capture` writes the manifest last).
5. **`copy-objects --objects-to T`** — a new subcommand: the whole bucket into the pool, no database, for the **hot
   early copy** `veyqctl` runs before it stops the app (ADR 0096 decision 5 step 4). Objects are immutable, so a key
   copied early is the key the cold top-up would have copied.
6. **Restore** (ADR 0056 §7, both forms before the dump is loaded, a refusal writes nothing — the existing order: domain
   gate, key gate, A5, then the store, then `backup_against_target`, then the first write; `grep -n 'populated = db.populated_tables()' -A40 scripts/instance_backup.py`):
   - **no source** (format 3): `compare_index` against the live store, as today; when the store holds **nothing** the
     refusal says *"the store is empty — a restore onto a fresh instance names the pool: --objects-from <target>"* and
     names `store.pool.target` if the manifest has one;
   - **`--objects-from S`** (format 3) or a format-2 capture (its `objects/` as S): refuse a store holding any object but
     `PROBE_KEY`; `compare_index` against **S**'s listing with `--hash --hash-type md5` restricted to the index's keys
     (`--files-from`) — a format-2 capture has no `store.index`, so its required keys are checked by key and size from
     the files; then `copy --immutable --files-from <index keys S holds>` S→store; then `compare_index` against the live
     store once more; then the load. `--objects-from` with a format-2 capture is refused (it holds its objects).
     `--accept-missing-objects` works for both forms and copies what S has.
   - **"Required"** stays what `expected_keys(blobs)` returns — the rows' keys — not every indexed key (O2).
7. **`store-in-use`** — a new read-only subcommand printing `bundled`, `own` or `none` from `Database.store_setting()`
   (`grep -n 'def store_setting' scripts/instance_backup.py`), for `veyqctl status`. veyqctl reaches the database only
   through the release's tool, as it does for every capture (`grep -n 'def backup_tool' scripts/veyqctl.py`).
8. **veyqctl's pool is `.env`'s `VEYQ_BACKUP_OBJECTS_TO`** (and the four `VEYQ_BACKUP_POOL_*` for an S3 pool), with
   `--objects-to` overriding it per run on `backup` and `upgrade`, and `--objects-from` on `restore`. `.env` keeps keys its
   template does not assign (`grep -n 'KEPT_HEADER' scripts/veyqctl.py`), and the template gains them **commented out**
   (`template_assignments` reads only `KEY=` lines — `grep -n 'ASSIGNMENT = ' scripts/veyqctl.py`), so `install` never
   asks for them. Inside `releases/` is refused; inside `backups/` is allowed but the Run page says why another disk.
9. **`backup` / `upgrade` order with a pool:** `copy-objects` (app running) → stop app → `docker cp` key → `capture
   --objects-to` → `verify` → (`backup`: up · `upgrade`: switch, up). A failed early copy stops nothing.
10. **`status` warns, and its exit code stays the health verdict.** While `store-in-use` says `bundled` and no
    `VEYQ_BACKUP_OBJECTS_TO` is set: a warning that the bundled store keeps no object versions, that a lost store volume
    loses every photo and file whatever `backups/` holds, and how to name a pool. `none` warns the same (the first
    store an instance gets is the bundled one by default); `own` is silent; an unreadable setting says so and warns.
    `install` ends with the same line.

**Decided at implement — O3's "retained captures" are the pool's own ledger.** Compose has no capture rotation:
`veyqctl` never deleted a capture in `backups/`, and a capture directory may be moved or encrypted off the host. So the
pool records every capture that named it (`veyq-pool/captures/<stamp>-<capture>.json`, the keys of its index, written
only once every key is in the pool) and rotates *those* by `--pool-keep` (`VEYQ_BACKUP_POOL_KEEP`, default 3 like the
gate's `backup.keep`); the prune deletes every object no kept entry names. A capture outside the newest N can then still
restore without a source (its objects are in the store) and refuses by name with `--objects-from` for whatever the pool
no longer holds. A pool therefore serves one instance. Nothing in `backups/` is deleted.

## Phases

Baseline before the first edit, on a detached worktree of `HEAD` (changes/README § the DoD's baseline):
`for f in scripts/validate_*.py; do [ "$f" = scripts/validate_guard_gaps.py ] && continue; python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done; python3 scripts/instance_backup_selftest.py | tail -1; python3 scripts/veyqctl_selftest.py | tail -1`
— a red after this CR is this CR's only against that list.

- [x] **1 tool — `scripts/instance_backup.py`** (decisions 1–7). Locators: the mirror capture and its readers
  `grep -n 'MIRROR\|INDEX\b\|mirror_in\|OBJECTS_DIR\|BACKUP_FORMAT\|INDEX_FORMAT\|store_leg' scripts/instance_backup.py`;
  every caller of the tool `grep -rln 'instance_backup' scripts/ tools/ .github/ charts/`; every test that builds a capture
  `grep -n 'take_capture\|cmd_capture\|cmd_restore\|backupFormat' scripts/instance_backup_selftest.py`. The module
  docstring is rewritten (the two-formats paragraph, the `objects/` permissions paragraph — a pool directory is created
  `0700` by the tool and its files are written by the rclone container, measured at implement: run it with `--user
  <uid>:<gid>` so they are the operator's, or say why not), the help texts, and `G6` in the self-test is **inverted**.
  New self-test group **P** behind the fake `Store` seam G5 already has (`grep -n 'class .*Store\|def index\|def size'
  scripts/instance_backup_selftest.py`), plus a fake pool. Bites, per test:
  - **B1 live defect** — *a default capture is the index, `backupFormat` 3, `store.leg` index, no `objects/`*: the
    inverted G6 is red on the untouched tree.
  - **B2 live defect** — *a restore with `--objects-from` onto a store holding one object (not `PROBE_KEY`) is refused
    before `load`, naming the count; control: the same fixture with only `PROBE_KEY` loads and copies exactly the index's
    keys* (assert the `--files-from` list equals the index's keys the source holds).
  - **B3 live defect** — *a source object whose md5 differs from the index's refuses naming the key; control: equal md5
    loads.* And one with a missing required key; `--accept-missing-objects` then loads and the closing log names it.
  - **B4 injection** — *every rclone argv the pool builder produces is `copy --immutable` or a read.* Lever: in the
    builder, `copy` → `sync` (snapshot-and-restore); the case must go red **by name**. The live half is phase 4.
  - **B5 injection** — *the manifest of an S3-pool capture carries none of the four `VEYQ_BACKUP_POOL_*` values*; positive
    control on the same run: the fake rclone's environment DID hold the secret (so the absence is not a pool that never
    saw it). Lever: put the access key into the pool's `describe()`.
  - **B6 injection (already in code, unpinned)** — *a format-2 restore into a non-empty store is refused before `load`*:
    no self-test case holds it today (`grep -n 'already holds' scripts/instance_backup_selftest.py` → nothing). Lever:
    remove the count refusal from the format-2 branch.
  - **B7 live defect** — *a format-3 restore without a source onto an EMPTY store refuses naming `--objects-from` and the
    manifest's pool target.*
  - **B8 live defect** — *`capture --objects-to` with a pool listing that lacks one indexed key fails and writes no
    manifest.*
  - G1–G8 (the gate) stay green untouched — the gate shares `take_capture`.
  Done when: `python3 scripts/instance_backup_selftest.py` green with every bite recorded red-then-green in the commit
  body; `python3 scripts/chart_meta_check.py` green; the tools image's self-test path unchanged (`tools/Dockerfile`
  copies the self-test — it must still need no Docker).

- [x] **2 veyqctl — `scripts/veyqctl.py`** (decisions 8–10, ADR 0096 decisions 1, 5, 6). Locators:
  `grep -n 'def cmd_backup\|def cmd_restore\|def cmd_upgrade\|def cmd_status\|def capture\|def backup_env\|def report\|KEPT_HEADER' scripts/veyqctl.py`;
  the template `grep -n 'BACKUP\|^# ' infra/.env.example | head`; guards reading the template
  `grep -ln 'env.example' scripts/validate_*.py` (run each after the template edit). `test_backup_restore` and
  `test_status` in `scripts/veyqctl_selftest.py` grow; the module docstring's `backup`/`restore`/`upgrade` paragraphs
  are rewritten. Bites:
  - **V1 live defect** — *status with `store-in-use` → `bundled` and no pool prints the warning and exits 0 on a healthy
    stack*; controls: pool set → no warning; `own` → no warning; unhealthy → exit 1 with or without it.
  - **V2 live defect** — *`backup` with `VEYQ_BACKUP_OBJECTS_TO` in `.env`: `copy-objects` before `stop app`, `capture`
    after it with `--objects-to` the same target; `--objects-to` on the command line wins; none configured → no
    `copy-objects` and no `--objects-to`* (FakeHost's `ran()` order, as the existing order check does).
  - **V3 live defect** — *`upgrade` the same order, and a failed `copy-objects` stops nothing* (`not host.ran("stop")`).
  - **V4 live defect** — *`restore --objects-from P` hands the tool `--objects-from` with P absolute, and S3 pool
    credentials reach it through `env`, never argv.*
  Done when: `python3 scripts/veyqctl_selftest.py` green, bites recorded; every guard that reads the template green.

- [x] **3 harness — the e2e restore groups move onto a pool** (no run here: an agent never runs
  `scripts/run_web_e2e.py`; the coordinating session runs `python3 scripts/run_pipeline.py --job web-e2e` after this CR
  lands, because the restore groups are the Compose restores' content proof — capture, destroy, restore, sign in, bytes
  compared). Locator: `grep -n 'backup_tool(\|backup_tool_result(\|fresh_bucket(\|def destroy\|def restore_hook\|def capture_destroy_install\|forged' scripts/run_web_e2e.py`.
  `capture_destroy_install` captures with `--objects-to <group_dir>/pool` and every restore it builds gets
  `--objects-from` that pool (the source bucket is purged by `destroy`, so without it every restore refuses — B7 in the
  wild); `verify_capture` additionally asserts the manifest's `store.pool.target`. A new assertion beside
  `assert_populated_target_is_refused`: the same restore into the target **after one object was put into its bucket**
  must be refused naming the store, and succeed once the object is gone (the A5 store half, by its message). The
  newer-backup forgery (`forged`) and the production-project decoy capture still work on format 3 (read them). The
  upgrade-proof path (`upgrade_hook`) uses the same helper. Done when: `python3 -m py_compile scripts/run_web_e2e.py`,
  `python3 scripts/validate_e2e_discovery.py` and `python3 scripts/upgrade_proof_selftest.py` green, and the hand-off
  names `run_pipeline.py --job web-e2e` as the coordinating session's step, with what its log must show (each restore
  group's `--objects-from` restore, the new refusal by message).

- [x] **4 live — a throwaway Compose project** (phases 1–2 on real Docker). Not the dev stack (`veyq-*` containers; the
  dev Traefik publishes 18080, so 80/443 are free — measured `docker ps --format '{{.Names}} {{.Ports}}'` at refine,
  re-check), not `run_web_e2e.py`. Either `scripts/run_stack_e2e.py --app … --admin … --keep` (it installs through
  `veyqctl install --unsigned-local` and leaves an instance with a Memory the browser suite sealed — its `home/` is the
  run directory's) or a `veyqctl install --unsigned-local` into a scratch home with images from `./gradlew backend:image
  admin:image`, content then seeded by hand; the implementer picks and says which. In order, each step's output in the
  record:
  1. `veyqctl status` → the warning (bundled, no pool); set `VEYQ_BACKUP_OBJECTS_TO=<scratch>/pool` → no warning.
  2. `veyqctl backup --key-into …` → manifest `backupFormat` 3, `store.pool.target` the directory, the pool's listing ⊇
     the index's keys; no `objects/`; the downtime line printed.
  3. **The pool never deletes, live (B4's live half):** delete one blob's object from the bucket with the tool's own rclone
     (`Store` plumbing), `veyqctl backup` again → the pool still holds it, and the second capture's index lacks it.
  4. A second scratch home, a fresh `install` under its own `COMPOSE_PROJECT_NAME` (its bucket's `size --json` count
     recorded right after the first boot — the inferred *"a fresh install's bucket is empty"*, now measured; if it holds
     `veyq-check/probe` or anything else, the gate's exclusion list is decided against that output).
  5. `restore` of capture 1 onto it **without** `--objects-from` → refused naming the empty store and the pool (B7 live).
  6. One object put into its bucket, `restore --objects-from <pool>` → refused naming the store; the object removed,
     the same command → restored; `veyq-admin store check` passes, `config-secret` reads `OK`, and the restored bucket's
     `lsjson --hash` equals capture 1's index for every required key.
  7. Garage's versioning re-asked of the project's own Garage (`rclone backend versioning`) → `Unversioned`, so the
     warning's claim is measured on the shipped recipe, not only the throwaway node.
  8. Teardown: `down -v` by each scratch project's name; `docker ps -a` / `docker volume ls` shows nothing of them and the
     dev stack's containers unchanged.
  Who runs it: the implementer (Docker is available to agents on this host; the dev stack is not touched). Done when
  every step's output is in the CR's record.

- [x] **5 rollback on Compose** — **waits for O1.** On the recommended (a): `veyqctl rollback <capture> --key-from F`
  builds the in-place form (O1 spells it out); its self-test order case and its live run (an upgrade between two local
  releases, then the rollback, the Memory's media still opening) join phases 2 and 4's records. On (b) or (c) this phase
  is only the Run page's Rollback section.

- [ ] **6 docs — the Run pages and the durable state.** Locators: the pages that restate what changed
  `git grep -ln 'back-up-and-restore-instance\|0056-instance-durable\|0096-veyqctl\|0098-on-kubernetes' -- site/src/content/docs`;
  the superseded claims `git grep -n -i 'full logical copy\|no incremental\|objects/\|backupFormat. 2\|mirrors\? the bucket\|local mirror\|pushes the mirrored\|still copy the\|not yet built' -- docs site scripts infra ':!docs/inbox.md'`
  (re-run at this phase — phases 1–3 write prose). Known sites at refine:
  - `site/src/content/docs/run/backup-and-restore.md` — § Capture (the index, the pool, `VEYQ_BACKUP_OBJECTS_TO`, the
    hot early copy, *why another disk*, Garage's measured lack of versioning, the volume-copy fast path as the other way
    to keep objects), § Restore (the two forms, the empty-store refusal, `--objects-from`), the hand-run blocks, *How far
    this is proven*; § Under Kubernetes says the pool does not apply to the gate.
  - `site/src/content/docs/run/upgrade.mdx` — Compose step 4 (what the backup now holds, the early copy) and § Rollback
    (per O1).
  - `site/src/content/docs/run/plan.md:73-75` — *"a full logical copy … There are no incremental captures"* is false:
    a capture is the dump plus an index; a pool grows by the new objects.
  - `site/src/content/docs/run/index.md` § Backups — the server's half: the objects are kept by the store or a pool.
  - `site/src/content/docs/run/configuration.md` — `VEYQ_BACKUP_OBJECTS_TO` and the four `VEYQ_BACKUP_POOL_*`.
  - `site/src/content/docs/run/new-instance.mdx` — the home's layout table names no pool directory (ADR 0096 decision 6).
  - `docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md:124,153` — *"lists and mirrors the bucket"*,
    *"the capture's local mirror"* → the index.
  - The *decided, not built* markers this refine wrote: ADR 0056 / 0096 / 0098 status and body, both use-cases' headers
    and steps, the three new coverage criteria's comments, `G6`'s comment in the back-up-and-restore coverage file,
    `docs/architecture/README.md` §10's evidence — each turned into what was built and how it is proven.
  Done when: every `scripts/validate_*.py` but `validate_guard_gaps.py` green; `npm run check` in `site/` green.

- [ ] **7 close** — the inbox row the coordinating session opens for this CR (none exists at refine: `grep -n
  'store-leg-is-an-index\|objects-to' docs/inbox.md` → nothing) marked done; lessons harvested; the CR deleted.

## Implementation record

Phases 1, 2, 5: bites red-then-green, each in its commit's body. Phase 3: **not run here** — `scripts/run_web_e2e.py`
is the coordinating session's, through `python3 scripts/run_pipeline.py --job web-e2e`; its log must show each restore
group capturing with `--objects-to <group_dir>/pool`, every restore with `--objects-from` it, and `A5 store control:
refused, naming the object the store held` before the real restore.

**Phase 4, live, 2026-10-17** — measured: `scripts/run_stack_e2e.py --app veyq:l1-464 --admin veyq-admin:l1-464 --keep`
(GREEN, 146 s) left instance A, project `veyq-e2e-20261017-125442`, release 0.673 installed by `veyqctl install
--unsigned-local`, holding the browser suite's tenant `family.veyq.test` with 6 blob rows; this tree's `veyqctl.py`
drove it (the home's `instance_backup.py` asset is this tree's, produced by the run). Dev stack not touched (its four
containers' `StartedAt` identical before and after). Each step's output, abridged:
1. `veyqctl status` → `the store in use: bundled` and the WARNING, exit 0; with `VEYQ_BACKUP_OBJECTS_TO=<scratch>/pool`
   in `.env` → no warning, exit 0.
2. `veyqctl backup --key-into k1` → `copy-objects` before `stop app`; manifest `backupFormat` 3, `store.leg` index,
   `store.pool` `{target: <pool>, objects: 7, bytes: 6535}`, no `objects/`; index 7 keys (6 required + the store
   check's probe), pool ⊇ index, one ledger entry; `downtime: 9 s`. Pool files `501:0` (Docker Desktop maps the owner;
   the `--user` half is for a Linux host and is not measured here).
3. A blob's object `2825…/0` deleted from A's bucket with the tool's rclone; `veyqctl backup` again → the second index
   lacks it, the pool still holds it (the first capture is retained, keep 3); that capture's `verify` refuses (A4,
   `missing object 2825…/0`) — the check doing its job; `veyqctl` exits 1 after starting the app again.
4. Instance B, a second home, `install --unsigned-local` as project `veyq-cr-pool-b` (an override drops Traefik's
   ports): ends with `the store in use: none` and the warning. **Its bucket right after the first boot: `{"count":0,
   "bytes":0}`** — a fresh install holds no object, not even the probe; the gate's exclusion of `PROBE_KEY` stands for a
   store the app has checked since.
5. `veyqctl --home B restore <capture 1> --domain family.veyq.test --key-from k1` (no source) → refused: *"the store is
   empty — … holds no object, and the backup's rows require 6. … --objects-from <pool> (this capture recorded the pool
   <pool>). Nothing was written."*
6. One object `rclone touch`ed into B's bucket, `restore … --objects-from <pool>` → refused *"already holds 1
   object(s) … Empty the bucket …"*; the object deleted, the same command → `copying 6 object(s) …`, `dropped the 49
   table(s) …`, `restored`, the key placed by `veyqctl`. `veyq-admin config-secret` → `OK`; B's bucket equals capture
   1's index (size and MD5) for all 6 required keys. `veyq-admin store check` on B: CREDENTIALS, PROVISION, BUCKET
   passed, PROBE_PUT failed — *"could not reach https://veyq.test: ConnectException"*: B has no ingress (no published
   Traefik, no DNS for its domain), an artefact of the second instance; the check passed on A after the rollback.
7. `rclone backend versioning STORE:blobs` on A's and B's own Garage (the shipped recipe) → `"Unversioned"` both.
   **The prune, live:** A's object put back from the pool (`Pool.empty_into_store`), the probe deleted from A's bucket
   (A holds no other orphan), `VEYQ_BACKUP_POOL_KEEP=1`, `veyqctl backup` → *"the pool keeps the objects of its newest 1
   capture(s): 6 object(s) / 6518 B; pruned 1 object(s) no kept capture's index names and the ledger entries …"*; the
   probe gone from the pool, `2825…/0` still in it, one ledger entry left.
8. **Phase 5, live:** `VEYQ_RELEASE=0.674`, `veyqctl --source <run>/mirror upgrade --unsigned-local --key-into k4`
   (the stack run's mirror, the same images) → `downtime: 10 s`, release 0.674; `veyqctl rollback
   <…-before-0.674> --key-from k4 --yes` → *"the capture verifies against the live store"*, `dropped 49 table(s)`, 0.673
   started once, *"the objects stay where they are … restored"*; state 0.673, `.env`'s image lines 0.673's,
   `VEYQ_RELEASE` left at 0.674 (said so). Then `veyq-admin config-secret` → `OK`, `veyq-admin store check` → `passed:
   true` (PROBE_PUT, PROBE_GET passed), 6 of 6 required objects with the indexed size and MD5, schema V202610161746,
   `veyq_blob` 6 rows, tenant `family.veyq.test`. **Not done:** a browser opening the Memory's photo after the rollback
   — the stack lane's suite cannot be pointed at an existing instance; the objects' bytes are what the index check and
   the store check prove.
9. Teardown: `docker compose -p <each> down -v --remove-orphans`; `docker ps -a`, `volume ls`, `network ls` filtered by
   either project → nothing; the run directory and the scratch pool deleted.

## Definition of done

- Phases 1–4 and 6 done (5 per O1's answer), each bite recorded red-then-green with its lever.
- `python3 scripts/instance_backup_selftest.py`, `python3 scripts/veyqctl_selftest.py`, `python3 scripts/upgrade_proof_selftest.py`,
  `python3 scripts/chart_meta_check.py` green; every `scripts/validate_*.py` but `validate_guard_gaps.py` green against the
  baseline above; `npm run check` in `site/` green.
- `docs/coverage/` updated for both `satisfies:` use-cases: the new criteria's comments name the self-test cases that
  prove them (a Python operator tool is no coverage surface, ADR 0040); `python3 scripts/validate_coverage.py` green.
- No new permanent guard: the invariant *"a pool is never deleted from"* is pinned by B4 on the one builder every pool
  call goes through, which is a test of the tool rather than a claim about the repository — the same reason the
  tool's other refusals live in its self-test and not in a `validate_*.py`.
- **The coordinating session's step, after landing:** `python3 scripts/run_pipeline.py --job web-e2e`, its log read for
  the restore groups restoring `--objects-from` a pool and the non-empty-store refusal by message. Not "green in CI": there
  is no remote.

## Decided with the owner

Owner 2026-10-17 ("Alles so wie empfohlen"): **O1 (a)** an in-place `veyqctl rollback` (empty the database, start the old
release once, restore with the check only; the objects are still in the same store) — phase 5 is unblocked; **O2** a restore
requires the objects the database rows reference, and reports but tolerates orphans and open upload chunks; **O3 — the
orchestrator's proposal, not "document now, prune later":** the pool **deletes an object as soon as no retained capture's
index names it**, and captures rotate by `keep` — so the pool keeps exactly what the retained backups need and a deletion
reaches the pool within the backup rotation window `security-model.md` promises. This is built in this CR (a pool prune step
after each capture's rotation, with its own bites: an object a retained index names is never pruned; one no retained index
names is gone after the rotation); "an object pool only grows" is replaced by that rule in ADR 0056 § 3, the use-case and
the coverage criterion. **O4** no pool for the Kubernetes gate, as ADR 0098 already says. The options stay below as the record.

**O1 — How does a Compose instance roll back, now that its capture holds no objects?** Today's documented rollback
(`site/src/content/docs/run/upgrade.mdx` § Rollback) installs the old release as **a new project with new, empty
volumes** and restores onto it — which, with an index capture, finds an empty store and refuses (B7). The objects are
still in the failed instance's own Garage volume: the upgrade rewrote none.
- (a) **In place: `veyqctl rollback <capture> --key-from F`.** Refuses unless the capture verifies against the live store
  (`verify --against-store`) and its release's directory is still in `releases/`; then stops the app, empties the
  database (a confirmation at a terminal, `--yes` without one), switches `.env`'s image lines and `state.json` back to
  the capture's release (`upgrade_values` over that release's template), starts it once (it migrates the empty schema;
  the bucket exists, provisioning is idempotent), stops it, restores with the check only, places the key, starts. The
  Garage volume is never touched; objects uploaded under the failed release stay as orphans with no reclaim row. It is
  ADR 0098 decision 9's cluster rollback in Compose's form.
- (b) **A fresh project, objects from the failed one:** `veyqctl --home /opt/veyq-rollback restore … --objects-from-home
  /opt/veyq`, the tool reaching the other project's Garage on its network with its token. Keeps the failed instance
  intact for inspection; doubles the store on disk and couples two homes.
- (c) **Rollback needs a pool** (or a volume copy of the store) — documented, nothing built.
- **Recommendation: (a)** — no second copy of the store, the same shape the cluster already has, and it needs no pool,
  which most operators will not have configured. Phase 5 waits for this.

**O2 — "Every object the index names", or every object the rows require?** The owner's words were *"every object the
index names is present … with a matching hash"*. The index also lists orphans pending reclaim and open upload sessions'
chunks (ADR 0056 Context 2); after an upgrade the new release's reclaim sweep may already have deleted such an orphan, so
the strict reading would refuse a sound rollback over garbage. (a) the rows' keys are required, every other indexed key
reported and tolerated; (b) every indexed key required. **Recommendation: (a)** — ADR 0056 §7's last paragraph is written
so; on (b) that paragraph and decision 6 change, nothing else.

**O3 — A pool that never deletes keeps purged objects forever.** `docs/crosscutting/security-model.md` § Deletion &
erasure promises a hard-purge *"propagated to backups within a documented rotation window"*; a copy-only pool has no
rotation, so a purged object's ciphertext stays in it (unreadable without keys the purge also removed from the rows, but
not erased). (a) Document it: a pool is the operator's archive, its retention is theirs as for any backup medium — one
sentence in security-model.md (the parallel agent's file: the coordinating session adds it after that agent lands) and
on the Run page. (b) Also an explicit, separate `veyqctl prune-pool --keep N` that deletes only keys no retained capture
indexes — never part of `backup` or `upgrade`. **Recommendation: (a) now, (b) as an inbox row** — the owner's *never
delete* holds for every automatic path, and erasure gets an operator-run tool when someone asks for it.

**O4 — A pool for the cluster's gate?** ADR 0098 decision 4 now says it does not apply (a copy in the init container
lengthens every release change; an external pool is a new egress peer for the default-deny policies; the cluster's
store is the operator's to keep durable). **Recommendation: keep it so**; a cluster operator who wants copies uses the
store's replication.

Implementation has the owner's go (2026-10-17).

## Retrospective

(filled at close)
