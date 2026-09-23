---
id: cr-recovery-commitment-stored-hashed
title: The server stores the recovery commitment only as its SHA-256
status: planned
satisfies: [uc.account.recover-access]
adrs: ["0054", "0060"]
invariants: [operator-blind E2EE, self-hostable by default, API-first]
---

## Goal

`veyq_user.recovery_master_key_tag` holds the recovery commitment exactly as the client sent it, and
`RecoveryHandler.complete` compares the presented tag against it directly (`RecoveryHandler.kt:113`). The tag
reveals nothing about the Master Key, but the reset accepts it. So a database read plus the holder's mailbox is
enough to complete a reset. The attacker opens no content, but takes over the login and locks the holder out.
After this CR the server stores only the tag's SHA-256 and compares hashes. Rows that already exist are
rewritten by a migration, which before V1 means a rewrite of one column.

## Context & constraints

- **Refined state this builds** (read it, don't restate it):
  [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) § Decision 1 (the paragraph on
  storing only the hash), Context 3 and Consequences;
  [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md) step 10;
  [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) (the tag row).
- **Measured on** worktree branch `claude/interesting-mestorf-21bcdf` at `7c1e310` plus the refine commit.
  Re-run the locators; don't trust the lists.
- **Wire shape does not change.** Clients keep sending the raw tag at birth, at rotation
  ([ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md)) and at `complete`, and the server hashes
  it. `scripts/validate_rotation_shape.py` anchors on the *contract* field `recoveryMasterKeyTag`, and a
  description edit leaves that anchor untouched.
- **Split out of `cr-request-tenant-and-tenant-bound-recovery`.** The two are independent and can land in
  either order. They overlap only in text: `RecoveryHandler` (`complete` here, `request` there), `AccountStore`
  (the tag here, email normalization there) and `RecoverySliceTest`. So do #172 (`RecoveryStore.applyReset`) and
  #171 (`RecoverySliceTest`'s `seedUser`). Whichever lands second rebases.

## Decisions

1. **The commitment is stored hashed.** The writers are `AccountStore.createAccount` (setup and both accept
   paths) and `RotationStore`. The reader is `RecoveryHandler.complete`, which compares the hash of the presented
   tag in constant time. Hash with `Tokens.hash` semantics: hex SHA-256 of the UTF-8 base64 string. That keeps
   today's comparison of the exact string (`RecoveryHandler.kt:113`) and treats the tag like every token. Rename
   the Kotlin property so it carries "hash" and keep `@Column(name = "recovery_master_key_tag")`. The column does
   not change, and the compiler then lists every writer.
2. **Existing rows are rewritten by a Kotlin Flyway migration** (`BaseJavaMigration`, at the next free version:
   `V50` at refine, `ls backend/src/main/resources/db/migration | sort -V | tail -1` → `V49`). No SQL spelling
   hashes in both dialects:
   - `h2-2.4.240-sources.jar` (measured): `HashFunction` offers `HASH`/`ORA_HASH`, and MySQL mode adds only
     `UNIX_TIMESTAMP`, `FROM_UNIXTIME`, `DATE`, `LAST_INSERT_ID`.
   - MariaDB has `SHA2(…, 256)` and no `HASH` (remembered).
   - Quarkus 3.36.0's `FlywayProcessor` carries `addJavaMigrations` / `javaMigrationClasses` (measured with
     `strings` on the deployment jar). Whether it finds a Kotlin class in `db.migration` has **not been run**;
     phase 2's discovery test settles it.

   **The migration computes SHA-256 itself and never calls `Tokens`.** An applied migration must not change
   meaning when application code changes (backend/CLAUDE.md § Migrations), so a test pins its output equal to
   `Tokens.hash` instead. If discovery fails, stop and come back: the alternative of clearing the column was
   rejected in refine, because it would silently take recovery away from existing accounts.

## Inventory

- **Writers and reader.** `grep -rn 'recoveryMasterKeyTag' backend/src/main/kotlin`: `AccountStore`
  (`createAccount`), `RotationStore`, `RecoveryStore.recoveryTag` → `RecoveryHandler.complete`. The rest are
  pass-throughs from the generated request models (`SetupResource`, `SetupHandler`, `SetupStore`,
  `TenantsResource`, `TenantAcceptHandler`, `AccountRotationResource`, `RotationHandler`) and change nothing.
- **The tag in tests.** `grep -rn 'recoveryMasterKeyTag' backend/src/test | grep -v '"recoveryMasterKeyTag"'`
  - Only entity seeds and stored-value assertions change: `RecoverySliceTest:279,283`,
    `PasswordChangeSliceTest:125`, `RotationSliceTest:65,181,233`.
  - The JSON request bodies keep sending the raw tag.
- **"Not a secret" about the tag.**
  `grep -rn -i 'not a secret' contracts/api/auth.openapi.yaml packages/crypto/src/v1.ts` → `auth.openapi.yaml:997`
  and `v1.ts:165`. ADR 0054 was already corrected in refine. Other "not a secret" hits in the tree are about the
  instance salt and are not this claim.

## Phases

- [ ] 1 contracts: descriptions only. `completePasswordRecovery` and `recoveryAuthTag` say the server compares
      the tag against a stored SHA-256. **Delete** the "Not a secret" sentence and replace it with "reveals
      nothing about the master key, but is what the reset accepts". · Done when: `npm run generate:api` +
      `npm run check` (web) green, the generated Kotlin is unchanged, and the trust-chain guards are green.
- [ ] 2 backend: Decisions 1 + 2. **Live defect first.** A test takes the value the database holds for an
      account born over HTTP and presents it as `recoveryAuthTag` to `complete`. Today that is `Applied`; it must
      become `NotProven`, and that assertion is expected to fail first. **Control** on the same predicate: the raw
      tag still resets. `RotationSliceTest:65` now asserts `Tokens.hash(NEW_REC_TAG)`. Migration tests:
      - *Discovery:* `flyway_schema_history` holds the version with `success = true`. Only this assertion sees a
        migration that Flyway silently never picked up. Bite it by renaming the class so Flyway ignores it.
      - *Rewrite:* run the migration against a seeded raw row, and the row then reads `Tokens.hash(raw)`.
      - *Pin:* the migration's digest equals `Tokens.hash` for a fixed input.

      Run the migration once against the MariaDB dev stack (`infra/docker-compose.yml`) and read its history
      row, because the test database is H2. · Done when: the full `./gradlew check` is green and the MariaDB
      history row is recorded in the retrospective.
- [ ] 3 prose + coverage.
      - **Edit** `packages/crypto/src/v1.ts:165` the same way as phase 1.
      - `backend/CLAUDE.md` § Migrations gains one rule: a data rewrite that needs a function outside the
        H2 ∩ MariaDB subset is a Kotlin `BaseJavaMigration`, self-contained and never calling app code. This CR
        creates the first one.
      - Coverage for `uc.account.recover-access`: a new criterion `commitment-stored-hashed`, and
        `commitment-proves-possession` is **edited** to say "the stored hash".

      · Done when: `python3 scripts/validate_coverage.py` and every `scripts/validate_*.py` are green, and
      `npm run check` is green in `web/` and `packages/crypto`.

## Definition of done

- The full `./gradlew check` and `npm run check` are green. `for f in scripts/validate_*.py; do python3 "$f";
  done` reports every guard it ran and none red. The baseline is that loop's output taken *before* phase 1.
- The recovery e2e suite (`web/src/features/account/recovery/recovery.e2e.test.ts`, via
  `scripts/run_web_e2e.py`) ran once locally, and its log shows the suite executed. Only there does a real
  client's tag go the whole way: birth → hashed at rest → reset → sign-in. With no remote there is no CI run
  (inbox #26); say exactly that.
- Coverage is updated as in phase 3. This file is deleted after what it taught has moved to where it binds. Inbox
  #156 closes once this CR and `cr-request-tenant-and-tenant-bound-recovery` are both done.

## Retrospective

_(filled at implement)_
