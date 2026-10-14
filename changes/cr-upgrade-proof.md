---
id: cr-upgrade-proof
title: An upgrade is proven - a predecessor's backup, written by its own client, restored onto the candidate and read back
status: planned
satisfies: [uc.admin.back-up-and-restore-instance]
adrs: ["0057", "0056", "0040"]
invariants: [operator-blind E2EE everywhere, self-hostable by default]
---

## Goal

A run proves that a candidate build upgrades an instance of its predecessor with the content intact: the predecessor's
own jar and e2e suites write sealed content, ADR 0056's tool captures it, the capture is restored onto the candidate,
which migrates it up, and the candidate's suites open that content and assert it. The predecessor is a configured git
ref - the previous `main` commit now, the last release later, by one default. The CI job exists but stays **disabled
until the first release**. Owner decisions 2026-10-14 (inbox #419).

## Context & constraints

- [ADR 0057](../docs/decisions/0057-release-identity-and-deployment-recipe.md) Addendum (2026-10-14) owns the decision;
  [uc.admin.back-up-and-restore-instance](../docs/features/admin/use-cases/uc.admin.back-up-and-restore-instance.md) A9 and
  its criterion `a-predecessors-backup-upgrades-with-its-content-intact` (coverage file, `status: planned`) say what is
  proven. ADR 0056 §7 is the older-backup restore it exercises.
- **Machinery that exists:** `scripts/run_web_e2e.py` - per-file databases, `VEYQ_JAR`, groups that carry state across a
  boot through `$VEYQ_GROUP_DIR`, `restore_hook` (capture, destroy, install, restore), the older-backup simulation
  (`simulate_newer_target`, `assert_older_backup_restored`), the run-level sweeps; `scripts/instance_backup.py`. What is
  missing: a boot per **build** (two jars in one run) and suites of **another ref** as the seeder.
- **Before the first release** (`release: none` in `scripts/applied_migrations.sha256`) a baseline may change
  (`backend/CLAUDE.md` § Migrations), so a red proof informs and names its cause; it gates nothing. No git remote exists,
  so CI has never run: the job is written and disabled, the proof is a local run.
- The old ref's suites are code from that ref: they run from a worktree of it, with its own `node_modules` and generated
  client, never mixed with the candidate's tree.
- **Invariant:** the proof's instances are throwaway; nothing it does reaches the dev instance's databases or buckets
  beyond what `run_web_e2e.py` already does (its own `veyq_e2e_<n>` databases, the compose stack of the main checkout).

## Inventory (commands)

- `grep -n "def restore_hook\|def run_group\|def boot\|VEYQ_JAR\|VEYQ_GROUP_DIR\|simulate_newer_target\|assert_older_backup_restored" scripts/run_web_e2e.py`
- `find web/src -name "restore.*.e2e.test.ts"` (the existing capture/read group), `scripts/instance_backup.py`,
  `scripts/instance_backup_selftest.py`, `scripts/validate_e2e_discovery.py` (imports the runner's `discover()`).
- `grep -n "release:" scripts/applied_migrations.sha256`; `grep -rn "quarkus.flyway" backend --include='*.properties' | grep -v /build/`.
- CI: `grep -nE "^  [a-z-]+:$|needs:|if:" .github/workflows/container.yml .github/workflows/ci.yml`; any workflow guard
  that reads them (`grep -ln "workflows" scripts/validate_*.py`).
- Docs: `infra/README.md` § An upgrade, step by step ("which of these steps has ever been executed"),
  `backend/CLAUDE.md` § Migrations, the coverage file above.

## Decisions

1. **One orchestrator entry, not a second runner.** The proof is a mode of `scripts/run_web_e2e.py` (or a thin script
   that drives it) with two inputs: the predecessor ref (`--upgrade-from <ref>`, default: the first parent of `HEAD`
   while `release:` is `none`, else that release's ref `v<major>.<minor>`) and the candidate (this checkout). One
   function resolves the default, so the switch to the release is that function's one branch, already written.
2. **Building the predecessor.** A detached worktree of the ref (in the run directory, removed after), its fast-jar
   built there, its web dependencies installed and its client generated there. A ref without the seeder suite (any
   commit before this CR) cannot be a predecessor: the run says so and stops. **So the seeder lands first, in a commit
   of its own, and the first proof runs against that commit** (`--upgrade-from <that hash>`); from then on `HEAD^1`
   always has it.
3. **Seed and read.** A **seeder** suite writes sealed content covering every application today - a Memory with a
   chapter and media (cover, date, place), a Files folder with files, a drop box with a guest's file, a share - and
   records what it wrote (names, counts, hashes) into the group directory; a **reader** suite of the candidate opens
   it after the upgrade and asserts each item, never only "no error". Both are new `*.e2e.test.ts` files of one group,
   gated like the others; the seeder runs from the predecessor's worktree, the reader from the candidate's. The
   record they share is the contract between two builds: a plain JSON whose shape the reader validates.
4. **The upgrade step** is the existing capture → destroy → install → restore, with the install being the
   **candidate's** jar; the candidate then boots, migrates, and must report `configSecretState: OK` before the reader
   runs.
5. **Verdicts.** The run's summary states the predecessor ref, both builds' highest migration, and the outcome. Red
   before the first release prints the cause (a Flyway checksum of a changed baseline is named as such) and exits
   non-zero, but the documented use is informational until `release:` is set.
6. **CI.** A job in `container.yml` that `publish` needs, running the proof against the default predecessor, written
   now with `if: false` and a comment naming the change that enables it (setting `release:`). Any workflow guard that
   reads these files must stay green with it (a disabled job claims no coverage, ADR 0040).

## Phases

- [ ] 1 scripts + web e2e: Decisions 1-5, the seeder committed first, then the proof run locally on the dev machine.
  - Tests: the runner's pure parts (default ref resolution for `release: none` and for a set release; the record's
    validation) unit-tested in Python next to the existing selftests; `validate_e2e_discovery.py` still green.
  - Bites: the reader fails when one seeded item is missing or altered (edit the record); the run fails when the
    candidate's `configSecretState` is not OK; a changed baseline between the refs is named as the cause.
  - Done when a local run against the seeder's own commit (Decision 2) is green and its report is recorded in the
    use case's criterion comment.
- [ ] 2 CI: Decision 6.
  - Done when the job is in `container.yml`, disabled, and every `scripts/validate_*.py` is green.
- [ ] 3 docs: `infra/README.md` § An upgrade (what the proof covers and what it does not: the Helm leg, #428),
  `backend/CLAUDE.md` § Migrations (the release enables the job), the ADR 0057 Addendum's "not yet built" removed, the
  criterion's comment names the run.
  - Done when every `scripts/validate_*.py` is green.

## Definition of done

- A local proof run against the seeder's commit is green, with its summary recorded.
- The bites of phase 1 each turned the run red.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- `npm run check` is green in `web/` (the new suites compile and lint).

## Retrospective

_(filled at implementation)_
