---
id: cr-session-purge-cost-on-mariadb
title: The session purge's cost and lock footprint on MariaDB are measured, and its family delete stops relying on NOT IN
status: done
satisfies: []
adrs: ["0004"]
invariants: []
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Answered by the owner, 2026-10-01: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists in `docs/inbox.md`.

1. **What counts as "production scale"?** Nothing in the repo defines it, and the measurement needs two seeded
   sizes. **Recommendation:** derive the sizes from the token lifecycle rather than picking round numbers.
   `SessionService.kt:311-312` sets access tokens to 15 min and refresh tokens to 30 d. Every rotation keeps the
   SUPERSEDED refresh row until it expires (ADR 0004 reuse detection, `SessionService.kt:260-264`). So an active
   device leaves about 4 refresh rows per active hour, and they stay for 30 days. The 4-per-hour figure assumes
   the client refreshes whenever the access token lapses; that is *remembered, not measured against `web/`*.
   - **S** ("a family instance"): 40 devices × 8 active h/day × 4 × 30 d ≈ **40 000 refresh rows**, about 1 000
     families.
   - **L** ("a hosting operator"): 100× S ≈ **4 M refresh rows**, about 100 000 families.

   Access tokens and challenges stay small, because the hourly purge removes them after 15 min.
2. **Where is the gate that triggers a deeper rewrite?** **Recommendation:** D3 and D4 are built only when, at
   scale **L**, a concurrent login or refresh (Phase 1, M3) **waits on a purge lock for more than 100 ms**. If
   that never happens, D1 alone ships. The reasoning: the hourly purge's CPU cost is invisible to users, and a
   stalled sign-in is not. So the gate measures the stall, not the statement.
3. **Ship D1 (`NOT EXISTS`) regardless of what Phase 1 finds?** **Recommendation: yes.** It is a one-statement
   HQL change that runs unchanged on H2 and MariaDB. It removes a latent semantic trap: `NOT IN` against a
   subquery that ever yields a NULL deletes nothing, silently. On H2 it was measured at 2 ms against 470 ms
   (*Context*). On MariaDB it is *expected* to plan the same as today; M1 checks that.

## Goal

`SessionPurge.run()` runs hourly in production as one transaction of four deletes (`SessionPurge.kt:21-29`). The
last one, `purgeEmptyFamilies`, is a double `id NOT IN (subquery)` delete (`SessionService.kt:279-285`). Inbox
#218 was filed because this statement was the one killed mid-run at app stops in the test suite, and nobody
knows what it costs on MariaDB at production scale. This CR measures that cost **and the locks the whole
transaction holds**, because at this size the stall is the risk, not CPU. It then replaces `NOT IN` with
`NOT EXISTS` (D1). The two conditional steps (D3: indexes on `expires_at`; D4: short, batched transactions) are
built only if the measurement crosses the owner's gate.

## Context & constraints

- **The statement the suite killed is this one** (measured 2026-09-28, from the main checkout's untracked test
  output of a 2026-09-21 run: `grep -l 57014 backend/build/test-results/test/*.xml` → 11 files). The SQL in the
  message is `delete from veyq_session_family … where sfe1_0.id not in (select ate1_0.family_id from
  veyq_access_token …) and sfe1_0.id not in (select rte1_0.family_id from veyq_refresh_token …)`, and the trigger
  is `IntervalTrigger [id=1_…SessionPurge#run]`. That output is overwritten by the next run, so re-run the grep
  on a fresh `./gradlew check` from before `cr-test-suite-runs-no-scheduled-jobs`.
- **Schema** (measured, `backend/src/main/resources/db/migration/V5__login_sessions.sql`):
  - `family_id` is `NOT NULL` in both `veyq_access_token` and `veyq_refresh_token`. So today's `NOT IN` has no
    NULL trap. It would get one the day that column became nullable, and nothing would fail.
  - Both have an FK to `veyq_session_family(id)`, and the only explicit indexes are the two `token_hash` uniques.
    H2 builds an index for each FK on its own: measured via `INFORMATION_SCHEMA.INDEXES`, which lists
    `FK_ACCESS_TOKEN_FAMILY_INDEX_6` and `FK_REFRESH_TOKEN_FAMILY_INDEX_D`. That InnoDB does the same is
    *remembered*; M0 checks it.
  - **No table has an index on `expires_at`** (`grep -n INDEX backend/src/main/resources/db/migration/*.sql |
    grep -iE 'expires|access_token|refresh_token|session_family|login_challenge'` → only the two `token_hash`
    lines). The three token/challenge deletes therefore scan their whole table.
  - `veyq_device_pairing.delegated_family_id` deliberately has no FK (`V46__device_pairing.sql:20-24`), so
    nothing else blocks a family delete.
- **Isolation level:** nothing overrides it (`grep -rni isolation backend/src/main/resources
  infra/docker-compose.prod.yml charts/veyq/values.yaml charts/veyq/templates/mariadb.yaml` → 0). Production
  therefore runs MariaDB's server default. That this default is REPEATABLE READ is *remembered*; M0 checks it.
  The image is pinned as `mariadb:11.4@sha256:70cc07…` in `infra/docker-compose.prod.yml:64` and
  `charts/veyq/values.yaml:217`.
- **H2, measured at refine.** The harness was a throwaway `@QuarkusTest`, deleted afterwards; Phase 1 rebuilds it
  from this description.
  - Seed: one user; *N* families, each with 1 unexpired access and 1 unexpired refresh token, inserted by JDBC
    batch; plus 20 token-less families.
  - Each variant runs as an HQL `executeUpdate` in its own transaction, then rolls back. Each is timed twice.
  - An optional writer thread autocommits inserts while the timings run: either into an unrelated table
    (`veyq_login_challenge`) or into `veyq_access_token`.
  - **Both variants deleted exactly 20 rows in every run.** That is the equivalence check.

  | Writer | Families | `NOT IN` (today), ms | `NOT EXISTS`, ms |
  |---|---|---|---|
  | none | 1 000 / 4 000 / 16 000 | 13, 6 / 21, 21 / **81, 64** | 19, 5 / 5, 4 / **17, 15** |
  | unrelated inserts | 250 / 1 000 / 2 000 | 82, 2 / 9, 9 / 16, 13 | 30, 1 / 3, 4 / 3, 3 |
  | access-token inserts, scheduler halted | 250 / 1 000 / 2 000 | 286, 146 / 191, 246 / **472, 463** | 21, 2 / 3, 2 / **2, 2** |

  In the last row the writer had grown `veyq_access_token` to about 156 000 rows (`writerCommits=156413`).
  `EXPLAIN` shows why `NOT IN` grows with it:
  - `NOT IN` scans the family table and materializes `SELECT DISTINCT family_id` over the whole token table (via
    the FK index), so it pays for every token row.
  - `NOT EXISTS` scans the family table and does one FK-index lookup per family (`FAMILY_ID = F.ID`), so its cost
    follows the family count.
- **So the ≥ 9 s in `cr-test-suite-runs-no-scheduled-jobs` was not this statement's own work** (inference from
  the measurements above). At 81 ms per 16 000 families, 9 s needs about 1.8 M families, or several million token
  rows. The suite mints sessions from 549 `bearer(` call sites and 31 direct `.issue(` calls (`grep -rn
  'bearer(' backend/src/test | wc -l`; same with `'\.issue('`), which is orders of magnitude short of that.
  What the statement waited on (a row lock, or CPU during the next profile's boot) is **not measured**. It
  stops mattering to the suite once that CR halts the scheduler. The production analogue of "waiting" is
  exactly what M3 measures.
- **Incidental measurement for that CR:** with the scheduler running, the access-token-writer run saw the
  deleted count drop from 20 to 0 halfway through. The startup-fired `SessionPurge` had committed the 20 empty
  families' deletion under the test. With `QUARKUS_SCHEDULER_START_MODE=halted` the count stayed at 20 through
  every run.
- **MariaDB, all remembered; Phase 1 measures each point:**
  - **`NOT IN` today.** Both sides are `NOT NULL`, so MariaDB can turn `NOT IN` into a per-row `<exists>` over
    the FK index (`index_subquery`), or it can materialize the subquery. Either way the cost should be about one
    family-table scan plus index probes.
  - **`NOT EXISTS`.** MariaDB has no anti-join rewrite (MySQL 8.0.17 does), so this runs as a `DEPENDENT
    SUBQUERY` with a `ref` probe on the FK index. On MariaDB, D1 is therefore expected to change semantics
    robustness and H2 cost, and not the plan.
  - **Locks.** Under REPEATABLE READ, InnoDB takes next-key locks on every index record a locking statement
    *scans*, not only on the rows it deletes, and the subquery reads inside a DML statement are locking reads.
    With no usable index, each of the four deletes locks its whole table until `run()`'s single transaction
    commits. A login inserts a family and two tokens. A refresh updates one refresh row and inserts two new
    ones. So both could wait for the whole purge.
- **What must not change:** the reuse-detection window. A SUPERSEDED refresh token stays until it expires
  (`SessionService.kt:260-264`, ADR 0004). `SessionPurgeTest` pins this, and it is the behavioural guard for
  every step here.

## Decisions

- **D1: `purgeEmptyFamilies` becomes `NOT EXISTS`, in HQL:** `delete from SessionFamilyEntity f where not exists
  (select 1 from AccessTokenEntity a where a.familyId = f.id) and not exists (select 1 from RefreshTokenEntity r
  where r.familyId = f.id)`. It is the harness's exact statement: measured equivalent (20/20 in every run) and
  measured faster on H2.
  Rejected alternatives:
  - `LEFT JOIN … IS NULL`: HQL cannot express a multi-table `DELETE`, so this means native SQL per dialect. The
    test database would then run a different statement than production, and `MODE=MariaDB` exists precisely
    to avoid that (`backend/CLAUDE.md` § Migrations).
  - Keeping `NOT IN` with a comment about `NOT NULL`: the trap stays and only moves into prose.
- **D2: measure before building anything conditional** (Phase 1). The gate is owner question 2.
- **D3 (only if the gate trips): index `expires_at`** on `veyq_access_token`, `veyq_refresh_token` and
  `veyq_login_challenge` in a new, append-only migration. The three deletes then take a range scan and lock only
  the expired range plus its boundary gap. A new token's `expires_at` lies in the future, outside that range.
- **D4 (only if the gate trips after D3): short transactions.** Each token delete runs in its own
  `QuarkusTransaction.requiringNew()`. The family delete runs in batches:
  - A plain `SELECT` (a non-locking snapshot read) picks up to *K* token-less family ids.
  - A `DELETE … WHERE id IN (:ids) AND NOT EXISTS … AND NOT EXISTS …` then deletes by primary key and checks
    the predicate again.

  This is the per-step transaction shape `ScopePurge.run()` already uses (`ScopePurge.kt:64-73`). It replaces
  the cr-0053 single-transaction comment in `SessionPurge.kt:18-20`, and that comment is rewritten, not
  deleted: it must say why each step now owns its own transaction.
  Rejected: building D4 without the gate. It adds a loop, a batch size and a re-check that no measurement has
  asked for.

## Phases

- [x] 1 measurement (no repo change) — **a throwaway MariaDB container, never the dev one.**
  - Start the image digest pinned in `infra/docker-compose.yml:8` under a **new container name and port**, so
    that `veyq-mariadb` is not touched.
  - Apply `backend/src/main/resources/db/migration/V*.sql` in version order (`sort -V`) with the `mariadb`
    client. Refine checked that no migration uses a Flyway placeholder (`grep -l '\${'` over them → 0).
  - Seed scale S, and then L, with `INSERT … SELECT … FROM seq_1_to_<n>`. The sequence engine is *remembered* to
    be built in; if it is absent, fall back to a Python-generated batch file (`scripts/` rule: Python). Keep the
    harness in the session scratchpad, not in the repo. Add 20 token-less families, plus expired rows in each
    table, so that every delete has work to do.
  - **M0 (premises):** record `SELECT @@tx_isolation`, and `SHOW INDEX FROM` each of the three token and
    challenge tables. The latter shows whether InnoDB created the FK indexes.
  - **M1 (plan):** run `EXPLAIN FORMAT=JSON` on today's statement and on D1's (native form of each), and on the
    three `expires_at < ?` deletes.
  - **M2 (cost):** run `ANALYZE FORMAT=JSON` on each inside `BEGIN … ROLLBACK`, twice per scale. Then time the
    four statements as one transaction, the way `run()` executes them.
  - **M3 (stall, the gate):** session A runs `BEGIN`, then the four deletes, and does not commit. Session B has
    `SET innodb_lock_wait_timeout=5`. It performs a login (insert one family, one access token and one refresh
    token), then a refresh (update one existing refresh row to SUPERSEDED and insert two new tokens), and times
    each. Then A commits. Record whether B waited, and how long. That duration is the stall bound, because in
    production the stall equals the purge's own duration.
  - **Bite the measurement first** (§ *when the CR's gate is a bespoke measurement, bite the MEASUREMENT*).
    Before trusting a "B did not wait", run M3 once with A holding `SELECT … FOR UPDATE` on the very family row
    that B's refresh touches. B **must** wait, or the probe cannot see a lock at all.
  - · Done when: M0–M3 are recorded for S and L in *Retrospective*, each number with the command that produced
    it, and owner question 2's gate reads either tripped or not tripped.
- [x] 2 backend — **D1, then D3/D4 only if Phase 1 tripped the gate.**
  1. **D1.** Change the HQL. `SessionPurgeTest` must stay green. It passes before and after, so its power is
     proven by injection, not by a red-first run. Snapshot-and-restore `SessionService.kt`, and record which
     assertion fires:
     - (a) Correlate the access-token subquery to itself (`a.familyId = a.familyId`). This drops the per-family
       check, so no family is deleted: **"empty family kept"** must go red.
     - (b) Drop the refresh-token clause. `purge-fam-superseded` holds only a refresh token, so it is now
       selected for deletion. The FK refuses that, or the family goes; either way the test goes red. Record
       which of the two.
  2. **Re-run the H2 harness** (as described in *Context*) against the new statement. **Expected:** the
     `NOT EXISTS` numbers in the table, and 20/20 deleted.
  3. **Only if the gate tripped:**
     - D3's migration: next free `V<n>`, with its reason in a SQL comment.
     - Then re-run M2 and M3. Build D4 only if M3 still trips the gate.
     - D4 needs a test for more than *K* empty families (a batch boundary). Its lever: make the loop stop after
       one batch; the families beyond *K* must remain, and the test must go red.
  4. **After-measurement:** re-run M1–M3 on the same seeded container with the shipped statements, next to the
     before-numbers.
  · Done when: the D1 levers are recorded with the assertion that fired; the H2 after-numbers and the MariaDB
    before/after numbers are in *Retrospective*; and `./gradlew check` is green.

## Definition of done

- `./gradlew check` green. `purgeEmptyFamilies` uses `NOT EXISTS`, and both D1 injection reds are recorded.
- The MariaDB measurement (M0–M3 at S and L, before and after) is recorded with its commands, and so is the
  bite of M3 itself.
- The gate's verdict is recorded. If it tripped, D3 (and D4 if needed) shipped with their own reds. If it did
  not, D3 and D4 are recorded as *measured unnecessary at L*, with the M3 number that says so.
- **Harvest before deleting:**
  - A one-line comment at `purgeEmptyFamilies` says why the statement is `NOT EXISTS`: NULL-safe, and
    measured cheaper on H2.
  - If D3 or D4 shipped, the reason lives in the migration comment or in the rewritten `SessionPurge.kt`
    comment.
  - The Phase 1 numbers do not go to `docs/`. They are a point-in-time cost, and § *an ADR's cost premise
    expires separately* applies. What stays is the decision, at the line that implements it.
  - Inbox #218's status cell is set to done, with a pointer to that comment.

## Retrospective

**Measurement (Phase 1 + after), all on a throwaway `mariadb:11.4@sha256:70cc07…` container (`cr218-measure-aa75`,
no published port, own anonymous volume, removed afterwards), driven by a scratchpad Python harness through
`docker exec … mariadb`. Fixed clock `2026-10-01 12:00`. The host's load average was 30–180 throughout (a parallel
wave), so durations are upper bounds, not a quiet-machine figure.**
- Seeds: **S** = 1 000 families + 20 token-less, 40 160 refresh / 200 access / 80 challenge rows; **L** = 100 000 +
  20 families, 4 016 000 refresh / 20 000 access / 8 000 challenge rows (40 refresh rows per family; per device one
  live access token and an hour's worth of expired rows in every table). The first L seed, one 4 M-row
  `INSERT … SELECT`, killed the container (exit 137); chunked by 200 000 it took 463 s.
- **M0:** `@@tx_isolation` = `REPEATABLE-READ`, lock wait 50 s; InnoDB created `fk_access_token_family` and
  `fk_refresh_token_family`; no `expires_at` index anywhere; the challenge table's key is `nonce`.
- **M1:** today's `NOT IN` → family `ALL` + `index_subquery` on both FK indexes; `NOT EXISTS` → family `ALL` + `ref`
  on both FK indexes. Same cost shape, as remembered. All three token/challenge deletes `ALL`.
- **M2 (one transaction, as `run()` did it, rolled back), S:** 10–11 ms either form, 20/20 families. **L:** 19–31 s
  (refresh-token delete 11–13 s, family delete 8–9 s), 20/20 families either form.
- **M3 bite:** A held `FOR UPDATE` on the probed refresh row → B's refresh hit `1205 Lock wait timeout` (5 s). The
  probe sees a lock.
- **M3 (gate), A holding `run()`'s four deletes:** at **S and L** every probe — challenge insert, login family
  insert, refresh `FOR UPDATE` — waited the full 5 s. Live, with A running `run()` for real and B on the
  production 50 s lock wait: the refresh waited 3.3–5.2 s and the **login deadlocked** (`1213`). **Gate tripped.**
- **D3 alone** (three indexes): the optimizer used `ix_refresh_token_expires` (`range`) but still full-scanned access
  tokens and challenges (80 % / 50 % expired). `run()` 11–15 s; held, all three probes still timed out (the refresh
  now on the family `UPDATE`). **Gate still tripped → D4.**
- **D3 + D4, after** (refresh index only, each step its own transaction, family pick + batch delete): live, B made
  16–26 probe rounds per run, worst wait **login 13–22 ms, refresh 10–20 ms, challenge 4–9 ms**, no errors. Step
  durations: access 137–235 ms, refresh 8.0–10.3 s (range, B unaffected), challenges 19–27 ms, family pick 2.6–2.9 s
  (snapshot read), batch delete 1.5–2.6 ms. The batch delete plans as `range` on `PRIMARY`.
- **H2 harness (throwaway `@QuarkusTest`, removed):** 1 000 / 4 000 / 16 000 families: `NOT IN` 7–238 / 15–18 /
  51–68 ms, `NOT EXISTS` 4–32 / 5 / 9–11 ms, shipped pick+batch 6–63 / 6–7 / 10 ms, 20/20 every run. With an
  access-token writer (~150 000–230 000 rows) at 16 000 families, `NOT IN` hit the 60 s transaction timeout twice
  (`57014` — the suite's symptom, on H2's own) while `NOT EXISTS` took 11–18 ms and the shipped pair 10–32 ms.
- **Levers (`SessionPurgeTest`):** D1 (a) self-correlated access subquery → "empty family kept" (line 65); D1 (b)
  refresh clause dropped → **the FK refuses** (`FK_REFRESH_TOKEN_FAMILY`), all three tests error; re-check dropped
  from the batch delete → the re-check test errors on the FK (line 103); loop stopped after one batch → "a
  token-less family outlived the run" (line 87). Full `./gradlew check` after Phase 2: 924 tests, 0 failed.

What went wrong:
- **Planning — D3's premise was false for two of its three tables.** "The three deletes then take a range scan"
  held only for refresh tokens; the other two are mostly expired at purge time and are full-scanned with or without
  the index. Shipped only `ix_refresh_token_expires` (V59), and the migration says why. Root cause: an index was
  planned from the statement's text, not from the fraction of rows it selects. Next time: EXPLAIN a planned index
  against the seeded shape before freezing it (harvested to `changes/README.md`).
- **Planning — the gate's wording cannot clear for the access-token step.** After D3 + D4 the probes never waited
  more than ~22 ms, but a login that lands inside the access-token delete waits that step out, and the step took
  137–235 ms at L on this loaded host. The gate's > 100 ms is therefore met by a bound, not by an observation.
  Not built further here; proposed as a row (batch that delete by primary key like the families, or measure it on a
  quiet host first).
- **Execution — the first L seed killed the container**, and `--rm` took its logs with it. Next time: a throwaway
  measurement container runs without `--rm` (removed by hand afterwards) and with a memory cap, and seeds in chunks.
