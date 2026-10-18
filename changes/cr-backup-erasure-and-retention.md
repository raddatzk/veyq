---
id: cr-backup-erasure-and-retention
title: Every capture is kept at most B days, and a restore re-applies what was erased after it
status: planned
satisfies: ["uc.admin.back-up-and-restore-instance"]
adrs: ["0056", "0098", "0096", "0093", "0101", "0089", "0062"]
invariants:
  - Compose and Helm move in step - every retention and every re-application exists on both targets, or the CR says why one cannot have it
  - the ledger lives outside the three legs, so no restore or rollback rewrites it
  - re-applying is idempotent and runs before the instance serves; it never decrypts and never deletes an object itself (the store reclaim stays the one deleter)
---

## Goal

Build [ADR 0056](../docs/decisions/0056-instance-durable-state-and-cold-logical-backup.md) decision 8. Backups are
not exempt from erasure (GDPR Art. 17, Art. 5(1)(e)), and the EDPB's erasure report asks for erasures to be tracked
and applied again on a restored system (CEF 2025 § 4.2.6). Today Compose's captures are kept until the operator deletes
them, only the cluster's gate rotates — by count —, the dump holds members' addresses, and a restore brings back every
account, tenant, scope, link and medium erased after its capture; the security model promised a rotation window nobody
documented. This CR makes B an instance setting, prunes every capture veyq's tooling wrote once it is older than B on
both targets, and keeps a small erasure ledger beside leg (c)'s key file that the application re-applies at every start
before it serves. By the owner rule that a legal duty wins over what was decided, the duty is not open; the shape is.

## Context & constraints

- Decisions: ADR 0056 §§ 2, 3, 7 and 8; [ADR 0098](../docs/decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)
  (the gate and its `keep`); [ADR 0096](../docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md)
  (`veyqctl`); [ADR 0062](../docs/decisions/0062-store-object-reclaim-is-a-durable-work-list.md) (the one deleter of
  objects); [ADR 0089](../docs/decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
  (B is an instance setting); [ADR 0093](../docs/decisions/0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
  § 10 (N + B in the guest-write record's disclosure);
  [ADR 0101](../docs/decisions/0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 8.
- **With `cr-account-erasure`:** that CR adds the account and tenant erasers; whichever of the two lands second wires
  those kinds into the ledger (both CRs say so). `cr-instance-privacy-notice` renders B and comes after this.
- **Every pod a CR adds is asked what it reaches and what it writes** (`changes/README.md`): the chart's daily prune
  reaches the database (to read B) and writes only the backup claim.

### Measured substrate (worktree at `a1898747`, clean)

| Fact | Locator |
|---|---|
| A Compose capture's directory, and that only the gate prunes — by count | `grep -n 'DEFAULT_BACKUP_ROOT\|def prune\|rmtree\|untouched by all of this' scripts/instance_backup.py` |
| `veyqctl` keeps its captures under its home and never prunes them | `grep -n 'BACKUPS_DIR\|rmtree\|before-' scripts/veyqctl.py` |
| The chart's `keep` and the claim the gate writes | `grep -n 'keep\|existingClaim' charts/veyq/values.yaml`; `grep -n 'VEYQ_BACKUP_KEEP\|/backup' charts/veyq/templates/app-deployment.yaml` |
| The pool ledger rotates by count | `grep -n 'POOL_KEEP_DEFAULT\|def prune_pool\|LEDGER =' scripts/instance_backup.py` |
| The dump leaves out only the guest-write record's rows | `grep -n 'DUMP_SCHEMA_ONLY' scripts/instance_backup.py` |
| The restore's object check (what it refuses as missing) | `grep -n 'accept-missing-objects\|accept_missing' scripts/instance_backup.py` |
| What re-fires by itself after a restore (predicate purges) | `grep -rn '@Scheduled\|StartupEvent' backend/core/src/main/kotlin backend/apps/memories/src/main/kotlin \| grep -i 'purge\|sweep'` |
| Hard erasures whose trigger is not in a restored row | `grep -n 'fun eraseScope' backend/core/src/main/kotlin/me/raddatz/veyq/core/spaces/scope/ScopePurge.kt`; `grep -n 'fun tombstone' backend/core/src/main/kotlin/me/raddatz/veyq/core/sharing/SharingStore.kt`; `grep -n 'fun deleteOwn' backend/apps/memories/src/main/kotlin/me/raddatz/veyq/memories/media/MediaShareGuestWrites.kt`; `grep -rln 'NodeSubtreePurge\|MediaTrashPurge' backend/*/src/main backend/apps/*/src/main`; the object delete: `grep -n '@Scheduled' backend/core/src/main/kotlin/me/raddatz/veyq/core/blobstore/StoreReclaimSweep.kt` |
| Where leg (c)'s key lives and that both targets persist it | `grep -n 'config-secret\|configSecret' infra/docker-compose.prod.yml charts/veyq/templates/app-pvc.yaml` |
| No erasure ledger exists | `grep -rni 'erasure.ledger\|erasure_ledger' backend scripts` (none) |
| The guest-write record's N, the pattern B follows (table, panel, CLI) | `grep -rn 'guest_write_retention\|guest-write-record' contracts/api/instance.openapi.yaml admin-web/src/cli/commands.ts \| head` |
| The promise this makes true | `grep -n 'Deletion & erasure' -A8 docs/crosscutting/security-model.md` |
| Run pages that speak for backups and retention | `grep -rn -i 'keep\|retention\|B days\|call it B' site/src/content/docs/run/backup-and-restore.md site/src/content/docs/run/moderation.md site/src/content/docs/run/upgrade.mdx site/src/content/docs/run/kubernetes.md` |
| The selftests that hold the tooling | `sed -n 12,45p scripts/instance_backup_selftest.py`; `ls scripts/veyqctl_selftest.py` |

## Decisions this CR takes

1. **B lives in the database**, table and operations shaped like the guest-write record's N (admin-web panel,
   `veyq-admin backup-retention show|set`); the capture tooling reads it over the connection it dumps through, 30 when
   it cannot. One prune function in `scripts/instance_backup.py` deletes complete captures older than B (and `.partial`
   ones, as today) under a root, used by the gate, by `veyqctl` and by the chart's daily job; `keep` stays as a second
   bound.
2. **Where each target prunes:** `veyqctl` at every `backup`, `upgrade` and `start`, plus `veyqctl backup prune` for a
   daily timer the Run page gives; `veyqctl status` warns while no capture under its home is younger than B. The gate at
   every start; a chart `CronJob` (daily, the gate's image and claim) runs the same prune. The pool's ledger drops
   entries older than B, and its prune follows.
3. **The ledger's kinds are a sealed set** — `ACCOUNT`, `TENANT`, `SCOPE`, `NODE`, an application's kinds (Memories:
   `MEDIA`), `SHARE`, `STORAGE_KEY`, and decision 7's `BLOCK`, `LIFT`, `CASE` — and the re-applier is a `when` over it, so a kind without a re-applier does not
   compile (a format that cannot break quietly, rather than a guard). An application contributes its kinds and their
   erasers through a core interface, as it does a `ScopeEraser`.
4. **An entry is written in the erasure's transaction to an outbox table**; a sweep (every minute and at start) appends
   unsent rows to `erasures.log` in the state directory, fsyncs, and marks them sent. A line older than B is dropped by
   an atomic rewrite at start. The outbox's time has the sweep as its reader (`EveryStoredTimeHasAReaderTest`).
5. **Re-application runs at start, before readiness**: the readiness probe (and Compose's healthcheck) answers not-ready
   until every line was read and every listed object whose row exists was erased through its kind's eraser. A
   `STORAGE_KEY` line erases nothing by itself: it tells the restore's object check that the key was erased, not lost,
   and a row that still names it with no root line of its own (an old version a Depot replace dropped) is counted in the
   start's log line and served as a missing object is (ADR 0056 Context 2).
6. **The restore reads the ledger**: `veyqctl restore` and `rollback` hand it the file from the state volume (the way
   it already places the key as the runtime user), the gate mounts the state claim read-only for it; with no ledger the
   object check runs as today and says so.
7. **Moderation survives a restore too** (owner 2026-10-18, by the legal-duty rule: TCO Art. 3 and a DSA Art. 17 measure
   must not be undone by a restore). Cases, measures, orders and holds live in the database, so a capture taken before a
   report or a block would bring a blocked item back unblocked and lose the case. The ledger therefore also carries,
   from the same outbox: `BLOCK` and `LIFT` (item kind and id, the basis — measure or authority order — and the time),
   the moderation's erasures as ordinary erasure lines, and a `CASE` stub (case id, category, opened at, its retention
   date). At start, per item the **last** `BLOCK`/`LIFT` line wins: an item whose last line is a block and that the
   restored database does not block is blocked again, with its basis; a lift is never re-blocked. A `CASE` stub whose
   case the restored database lacks is recreated as a stub (`origin = RESTORED`, no reports, no sealed content) so the
   queue shows that a case existed and the log's retention still holds; `veyqctl status` and the start's log line count
   such stubs, and the Run page says that the case's sealed parts are lost with a capture older than them. Dropping
   lines older than B stays correct here: every restorable capture is younger than B, so anything older is in it.
   Whether CSAM ciphertext kept in a capture is possession under § 184b StGB is a legal question (ADR 0093 Still open),
   not decided here; captures expire at B either way.

## Open questions (owner) and legal items

**Decided (Q21–Q23).** Owner 2026-10-18, one by one: all three as recommended.

- **Q21 — B's default and range.** **Recommendation: 30 days, settable 7 to 365**, the Run page asking for a reason in
  the record of processing above 30 (as for N above 7). Thirty is what the moderation Run page already recommends for a
  backup holding guest addresses.
- **Q22 — Is the newest capture pruned at B too?** **Recommendation: yes** — a capture kept "until a newer one exists"
  has no bound when no new one is taken, and the ledger's own retention then has none either. The cost: an instance not
  backed up for B days has no capture; `veyqctl status` warns, the upgrade takes one anyway, and the Run page gives the
  daily `veyqctl backup` timer for disaster recovery.
- **Q23 — Where the ledger lives.** **Recommendation: a file beside leg (c)'s key**, which both targets already persist
  and no restore touches. The alternative, an object in the store, also survives a lost application host when the store
  is external, but makes the backend write objects itself (ADR 0013: it never streams bytes) and the reconcile exclude a
  prefix. Limit stated either way: a restore onto a new host re-applies only the ledger it is given, so the Run page puts
  the file on the list of what to carry with leg (c).
- **Legal:** the 30-day recommendation is the research's for the guest-write record (ADR 0093 § Legal basis item 4);
  a B above it is the operator's to justify. The EDPB report is read through the research (ADR 0093 § Legal basis,
  item 4: "belegt"), not re-read here.

## Phases

- [ ] **0 docs** — `uc.admin.back-up-and-restore-instance` (still `status: planned`) gains the criteria: every capture
  pruned at B on both targets; the ledger re-applied before the instance serves; the object check honours erased keys —
  with their gaps naming this CR's suites. · Done when: feature-format, coverage, doc-link, change guards green.
- [ ] **1 contracts** — the B setting's read and write on the admin channel (`x-veyq-channel: admin`), classified in
  `OperatorBlindProbeTest` and the admin-channel sweep. · Done when: `npm run generate:api`; the contracts guards;
  `./gradlew check` red only on the day-one tests the new operations reach (name them).
- [ ] **2 backend** — the B table; the outbox, the sweep, the file and its rewrite; the sealed kinds and their
  re-appliers, Memories' `MEDIA` through the core interface; the entries at each hard erasure the locator lists and at
  the store reclaim; re-application before readiness. · Done when: `./gradlew check` green. **Bites, with controls:**
  erase a scope, copy its rows back from a dump taken before (the restore's shape), start → gone before the first
  request answers (control: a scope erased after nothing → no change); a re-applied line run twice → no error, no
  second reclaim row; a kind added to the set without a re-applier → does not compile (bite by hand once, recorded in
  the retrospective); a line older than B → gone after the start's rewrite (control: one younger stays).
- [ ] **3 tooling** — the age prune in `instance_backup.py` (gate and capture roots), the pool ledger's age, the
  restore's `--erasures`; `veyqctl` pruning, `backup prune`, the status warning, handing the ledger to `restore` and
  `rollback`; the chart's `CronJob`, its values (`backup.prune.schedule`), the gate's read-only state mount; the
  selftests gain a retention group (captures past B gone on a fake clock, the newest too; `keep` still bounds) and a
  ledger group (an erased key reported as erased, not missing; no ledger → today's refusal). · Done when:
  `python3 scripts/instance_backup_selftest.py` and `python3 scripts/veyqctl_selftest.py` green; `helm lint` and the
  chart guards green; one live run on a throwaway Compose project (capture, erase a scope, restore, start: the scope is
  gone before the first answer) — on a detached worktree, never the shared dev stack.
- [ ] **4 admin-web** — the B panel beside the guest-write record's, and the CLI verbs. · Done when: `npm run check`
  green in `admin-web/`; a browser pass of setting B.
- [ ] **5 docs** — ADR 0056's status (§8 built), the security model's § Deletion & erasure; site:
  `run/backup-and-restore.md` (the deletion concept: B on both targets, what prunes when, copies off the host deleted
  by B and opened only to restore, the ledger carried with leg (c)), `run/moderation.md`'s backups paragraph (B is now
  enforced), `run/upgrade.mdx` (an upgrade's capture is pruned at B), `run/kubernetes.md` (the CronJob); coverage refs.
  · Done when: coverage, wiki-sources and the site check green.

## Definition of done

Every phase's Done-when; `uc.admin.back-up-and-restore-instance`'s new criteria carry refs or name their suites in its
gaps as the file's planned status requires; every `scripts/validate_*.py` except `validate_guard_gaps.py` green.
Implementation waits for the owner's go.

## Retrospective

_Filled at implementation._
