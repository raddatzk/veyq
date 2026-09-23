---
id: cr-backend-test-fixtures
title: One test-fixtures object seeds the backend slice tests
status: planned
satisfies: []
adrs: []
invariants: []
---

## Goal

Inbox #171, backend half. Every backend slice test seeds its own data:
- 50 files each define `seedUser`;
- `seedMemberScope`, `seedNode` and `seedTenant` are copied the same way;
- `token()` returns `"Bearer …"` in 20 files and the raw token in 2.

The cost is concrete: a new `NOT NULL` column on `veyq_user` means editing the 60 test files that construct a
`VeyqUser`.

After this CR:
- one `TestFixtures` object seeds users, tenants, member scopes and nodes, and mints the bearer header;
- a test keeps a local helper only for data no other test needs;
- a guard keeps `VeyqUser()` construction in the fixture.

It is test-only: no production line changes.

## Context & constraints

- **Refined state:** [developer-styleguide](../docs/crosscutting/developer-styleguide.md) §10 Testing, *"Test
  data comes from one fixture per surface, not a local copy"* (written in this refine).
- **Measured on** `main` at `54fe415`, in worktree `claude/beautiful-yonath-d0124b`. Re-run the locators.
  Spot-check the row the design leans on hardest: that every `seedUser` wraps its writes in
  `QuarkusTransaction.requiringNew()` (`grep -rn -A3 'fun seedUser' backend/src/test | grep -c
  QuarkusTransaction.requiringNew` → 50 at refine).
- **Sequencing, per the owner: land this when no other large backend CR is in flight.** It touches most slice
  tests, and every live backend CR adds or edits one.
  - Before the first edit, run `grep -ln 'SliceTest\|seedUser' changes/cr-*.md` and ask the owner which of
    those CRs are in flight.
  - At refine the textual overlaps were `cr-request-tenant-and-tenant-bound-recovery`,
    `cr-recovery-commitment-stored-hashed` and `cr-wrap-methods-survivor-whitelist` (all in
    `RecoverySliceTest`, `PasswordChangeSliceTest` or `RotationSliceTest`), and #157's scope-gate CR once it
    exists.
- **Not a split into phases for its own sake.** The phases exist so that each commit stays reviewable and ends
  green.

## Decisions

1. **A plain Kotlin `object TestFixtures` in a new test package, `com.veyq.app.testing`.**
   - All 50 `seedUser`s persist through static Panache calls inside `QuarkusTransaction.requiringNew()`, and
     none uses `@Transactional` or an `EntityManager`. So a CDI bean buys nothing.
     `InstanceReleaseSliceTest`'s companion object already seeds this way outside CDI.
   - `bearer(userId)` is the one helper that needs a bean. It resolves `SessionService` through
     `Arc.container()`, as `RateLimitPostureSweepTest.kt:70` already does, so a test that injected `sessions`
     only for `token()` drops the injection.
   - The file name must be unique among test files, because `validate_coverage.py` keys backend refs by file
     name and fails on duplicates.
2. **Default parameters, not builders.** The refine measured the variation axes:
   - **`seedUser`:** `id` (random), `email` (`u-$id@example.com`), `serverRole`, `identityPublicKey` (and the
     other key blobs), `requireGroupAddConsent`. It returns the id.
     - The email prefixes some copies use (`ib-`, `pd-`, `probe-`, …) are asserted nowhere, so they collapse.
       Locator: `grep -rnE '"(sp|pd|so|ib|cd|probe|user-rate-limit)-' backend/src/test`, whose one hit is a
       tenant `ownerEmail`.
     - The idempotent `(userId, email, role): Unit` variant stays with its two callers if it cannot be
       expressed without a flag only they use.
   - **`seedMemberScope`:** `scopeKind`, and whether the tenant id is returned too.
   - **`seedNode`:** `wrappedContentKey` is an **explicit parameter with no default**, because the copies
     disagree:
     - the sharing tests set it for `kind != FOLDER`;
     - the media tests set it for `MEMORY`;
     - `BlobBatchE2ETest` always sets it.

     Each call site passes what its copy set, so no test's data changes.
   - **`seedTenant`:** `status`, `domain`, and an optional owner (which adds the `TENANT` scope and the `OWNER`
     membership). A variant that carries a `TestMailbox` stays local or passes it through.
   - **The rule for everything else:** a variant the fixture can only express through a flag that a single file
     uses stays local.
3. **`token()` becomes `bearer()` everywhere.**
   - The two raw-token files are `ScopesSliceTest` and `ConsentGroupAddSliceTest`. They wrap **every** call as
     `"Bearer ${token(x)}"`, 109 sites at refine (`grep -o '"Bearer \${token(' <file> | wc -l`), so the rewrite
     is mechanical.
   - The 20 `"Bearer …"`-returning `token()`s and the 11 `bearer()`s are byte-identical to the fixture's.
4. **Account-specific users keep their wrap seeding local but create the user row through the fixture.** This
   covers the `@BeforeEach` users in `LoginSliceTest`, the users with key wraps in
   `Recovery/PasswordChange/RotationSliceTest`, and Recovery's cleanup of existing rows. After it, `VeyqUser()`
   is constructed in one test file, which is what the guard checks.
5. **The guard: `scripts/validate_test_fixture_single_home.py`.** It enforces that `VeyqUser()` is constructed
   in `backend/src/test` only in `TestFixtures`'s file. That is the predicate the measured cost is about: 60
   files at refine, via `grep -rl 'VeyqUser()' backend/src/test`.
   - **Order:** the guard is red on the untouched tree and lands in the phase that makes it green. Record its
     untouched output.
   - **Its limit**, for the docstring. It sees only the user entity, and three things stay invisible:
     - a copied `seedNode`;
     - a local `bearer`;
     - a user inserted by SQL string.

     Those copies cost only their own file when the schema changes, which is why they are not guarded.

## Inventory

- **Every definition to fold:**
  `grep -rnE 'fun (seedUser|seedMemberScope|seedNode|seedTenant|seedActiveTenant|bearer|token)\(' backend/src/test`.
- **The guard's predicate:** `grep -rl 'VeyqUser()' backend/src/test`.
- **`@Test` baseline** (run before the first edit and paste the number): `grep -rc '@Test' backend/src/test
  --include='*.kt' | awk -F: '{s+=$2} END {print s}'`. It was 673 at refine; that number is context, not the
  baseline.
- **Guards that name test files:**
  - `validate_guard_gaps.py` (`BACKEND_TEST_CLASS = "ShareNoteSliceTest"`) finds that file by name and injects
    a function before its last `}`. Keep the name, and keep the file ending on the class brace.
  - Locator: `grep -rn 'SliceTest\|seedUser' scripts/`.

## Phases

- [ ] 1 fixture + `features/account`, `features/admin`, `shared/`: Decisions 1–4 for those trees. · Done when:
      `./gradlew check` is green with the `@Test` count unchanged.
- [ ] 2 the remaining feature trees (content, media, sharing, spaces, tasks, platform), one commit per feature.
      · Done when: `./gradlew check` is green with the `@Test` count unchanged, and `grep -rl 'VeyqUser()'
      backend/src/test` lists only the fixture.
- [ ] 3 guard (Decision 5). Bite it by snapshot-and-restore: add a `VeyqUser()` to one slice test, read the red
      line naming that file, and restore. · Done when: every `scripts/validate_*.py` is green, the new guard
      included, and the bite is recorded. Inbox #171 (backend half) → done.

## Definition of done

- Before the first edit, run the baseline and paste its output: `for f in scripts/validate_*.py; do python3
  "$f"; done`, and the `@Test` count.
- The full `./gradlew check` is green with the **same** `@Test` count. A dropped test is a defect here, not
  cleanup.
- The guard is green on the finished tree, and was red before and red when bitten.
- This file is deleted once done. It satisfies no use-case, so there is no coverage change.

## Retrospective

_(filled at implement)_
