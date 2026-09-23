---
id: cr-request-tenant-and-tenant-bound-recovery
title: One request tenant, and a recovery that reaches only its own tenant's accounts
status: planned
satisfies: [uc.account.recover-access]
adrs: ["0054", "0055", "0066", "0048", "0003"]
invariants: [operator-blind E2EE, self-hostable by default, API-first]
---

## Goal

Today a tenant owner receives recovery links for accounts that are not theirs. `RecoveryHandler.request` picks
the SMTP relay by the request's Host but looks the account up across the whole instance. So the owner of
tenant A asks with `Host: a.example` for any address, and A's relay carries the token of an account in tenant B,
or of the Server Owner. The second factor holds, but the token has gone through a third party (ADR 0054 §4), and
every tenant owner has an instance-wide existence oracle (§6). This happens because Host → tenant is resolved in
four places and one of them trusts the raw header. After this CR:
- one request-scoped **`RequestTenant`** resolves Host → tenant, and handlers receive the resolved tenant
  (id + registered domain), never a host;
- recovery mails only a member of that tenant;
- recovery answers before it does anything that depends on the address, so the answer's timing no longer tells
  a known address from an unknown one;
- the server stores the recovery commitment only as its SHA-256;
- one email normalization and one user-by-email lookup replace the six and four copies.

## Context & constraints

- **Refined state this builds** (written in the refine commit, read these, don't restate them):
  [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) § Decision 1 (the hashed
  commitment), §4 (the relay of a tenant the account belongs to; a tenantless Server Owner has no recovery),
  §6 (identical includes timing);
  [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md) Preconditions,
  steps 2 + 10, A1; [scopes-and-tenancy § Tenant resolution](../docs/crosscutting/scopes-and-tenancy.md);
  [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) (the tag row).
- **Measured on** worktree branch `claude/interesting-mestorf-21bcdf` at `7c1e310` plus the refine commit. Every
  locator below was run there. Re-run them; don't trust the lists.
- **Wire shape does not change.** Clients keep sending the raw tag at birth, at rotation and at `complete`. The
  server hashes it. `scripts/validate_rotation_shape.py` anchors on the *contract* field `recoveryMasterKeyTag`,
  and a description edit leaves that anchor untouched.
- **Out of scope:**
  - Tenant suspension (inbox #157). `TenantSuspensionFilter` keeps calling `TenantHostResolver` directly and
    keeps its behaviour. The web space switcher is host-independent, so suspension cannot live on the Host.
  - The own-member case, settled in refine: a tenant owner's relay carrying recovery mail for their *own*
    members is covered by the two factors.
  - A recovery path for a tenantless Server Owner (accepted in refine: none, ADR 0054 §4).
  - The login challenge per email (#175).
- **Neighbours that touch the same code.** Land this **before #157**, because #157 rewrites the scope gates
  inside the `ScopesHandler` methods whose signatures this CR changes. #152 (Admin surface: `SetupResource`,
  `TenantHandler`), #175 (`LoginHandler`), #172 (`RecoveryStore.applyReset`), #171 (`RecoverySliceTest`'s
  `seedUser`) and #173 (`shared/tenant` → `features` imports) overlap only textually. Whichever lands second
  rebases. Add no *new* `shared → features` import. If #173 landed first, use its query-only `TenantEntity`.

## Decisions

1. **`RequestTenant` in `backend/.../shared/tenant/` is the only way a resource learns its tenant.** It yields a
   small value (tenant id + *registered* domain) or null. It resolves through
   `TenantHostResolver.hostOf` + `tenantFor` (`TenantHostResolver.kt:22,39`), which stays the one home of the
   header rule and the domain lookup. `RequestTenant` absorbs `RequestTenantDomain` (delete it) and
   `TenantHostResolver.domainFor`. **Two traps travel with it**, and both are measured in the KDoc it replaces
   (`RequestTenantDomain`, § *Why a bean rather than `@Context HttpHeaders`*):
   - It reads the request through `CurrentVertxRequest`. A `@Context HttpHeaders` field on `SharingResource`
     turned all 16 anonymous guest share tests into `401`.
   - It answers null when no request is in scope.

   The filter stays on the resolver. Whether a request-scoped bean is active in a pre-matching filter after its
   worker hop (`TenantSuspensionFilter.kt:59`) has not been measured, so don't find out here.
2. **Handlers take the tenant, not a host.** `ScopesHandler` (4 methods) and `AccountProfileHandler` (2) take a
   `tenantId: String?`. `RecoveryHandler.request` takes the resolved value, because the link needs the registered
   domain. `ScopesStore.findTenantIdByDomain`, `AccountProfileHandler.tenantIdByDomain`, both resources'
   hand-parsed `activeHost()` and their `@Context HttpHeaders` fields are deleted. `ScopesResource`'s field only
   feeds `activeHost()` (`grep -n 'headers\.' .../ScopesResource.kt` → line 228 alone).
3. **Mail settings are keyed by tenant id.** `TenantMailSender.settings(domain)` becomes `settings(tenantId)` over
   the existing `TenantStore.mailSettingsFor` (`TenantStore.kt:333`), and `mailSettingsByDomain` is deleted.
   `TenantInviteMailer.canSend` / `sendInvite` and `RecoveryMailer` take the tenant id plus the registered domain
   (the domain only for the link and the From address). Every invite call site already holds the tenant
   (`TenantHandler.kt:185,200,209,299,306,350`). `TenantStore.domainExists` delegates to the resolver's lookup.
   After this, no mail is looked up by a domain string, so a header value cannot pick a relay.
4. **Recovery reaches only a member of the resolved tenant.** "Member" is
   `MembershipStore.isMember(userId, tenantId)` (`MembershipStore.kt:31`: any membership row with that
   `tenantId`). That predicate is safe here because a tenant owner cannot make an outsider belong:
   `ScopesHandler.kt:257` refuses a group add of a non-member, so a membership comes only from the person
   accepting an invite. A non-member folds into `RequestOutcome.NO_ACCOUNT` ("unknown *here*", A1). No new
   outcome is added.
5. **The answer comes before anything keyed by the address.** `request` keeps its synchronous part as it is:
   transport check (→ `503`), then the limiters. After that it hands the rest (lookup, membership, mint-commit,
   send) to a background task and answers `202`. Today the send runs inline with a 10 s bound
   (`TenantMailSender.kt:76`, `.get(SEND_TIMEOUT_SECONDS …)`), which is exactly the oracle.
   - Split the background part into its own handler method that returns the `RequestOutcome` synchronously.
     The HTTP path fires it; tests call it directly. That is what keeps the negative cases below deterministic.
   - Run it on the worker pool. The precedent is `TenantSuspensionFilter.kt:59`.
   - It must not inherit a transaction. It keeps "mint commits before send", and logs failures without the
     token (`deliver` already does).
   - The limiters stay before the hand-off. They are what bounds the queued work (ADR 0054 §6).
   - Invites stay synchronous. Their caller is an authenticated owner who is shown the delivery result, so
     there is no oracle to close.
6. **The commitment is stored hashed.** Writers are `AccountStore.createAccount` (setup + both accept paths) and
   `RotationStore` (`grep -rn 'recoveryMasterKeyTag =' backend/src/main/kotlin`). The reader is
   `RecoveryHandler.complete`, which compares the hash of the presented tag in constant time. Hash with
   `Tokens.hash` semantics (hex SHA-256 of the UTF-8 base64 string). That keeps today's comparison of the exact
   string (`RecoveryHandler.kt:113`) and gives it the same treatment as every token. Rename the Kotlin property
   so it carries "hash", leaving `@Column(name = "recovery_master_key_tag")` as it is. The column is unchanged,
   and the compiler then lists every writer.
7. **Existing rows are rewritten by a Kotlin Flyway migration** (`BaseJavaMigration`, version = next free; `V50`
   at refine, `ls backend/src/main/resources/db/migration | sort -V | tail -1` → `V49`). No SQL spelling hashes
   in both dialects. Measured in `h2-2.4.240-sources.jar`: `HashFunction` offers `HASH`/`ORA_HASH`, and
   MySQL mode adds only `UNIX_TIMESTAMP`, `FROM_UNIXTIME`, `DATE`, `LAST_INSERT_ID`. MariaDB has `SHA2(…, 256)`
   and no `HASH` (remembered). Quarkus 3.36.0's `FlywayProcessor` carries `addJavaMigrations` /
   `javaMigrationClasses` (measured with `strings` on the deployment jar). Whether it discovers a Kotlin class in
   `db.migration` has **not been run**, and the tests in phase 4 settle it. **The migration computes SHA-256 itself
   and never calls `Tokens`.** An applied migration must not change meaning when application code changes
   (backend/CLAUDE.md § Migrations), so a test pins its output equal to `Tokens.hash` instead.
8. **One email normalization, equal to the client's salt rule.** The rule is
   `packages/crypto/src/v1.ts:243` (`email.trim().toLowerCase()`). One function in `shared/identity` implements
   it. `AccountStore.findByEmail` and `createAccount` normalize for themselves, and every other user-by-email
   lookup goes through `findByEmail`. The rate-limiter keys and the login-challenge key use the same function.
   The JSON deserializer already trims every request email field; the handlers still normalize, because the
   rule must not depend on how a value arrived.

## Inventory

Each locator was run at refine, and its hits on the untouched tree are listed with their fate. The implementer
spot-checks the entry this CR leans on hardest, the rule-C list.

- **Rule A: a header read outside the resolver.**
  `grep -rn -E '"[Xx]-[Ff]orwarded-[Hh]ost"|getHeader(String)?\("[Hh]ost"|HttpHeaders\.HOST' backend/src/main/kotlin`
  - `RecoveryResource.kt:84` and `AccountProfileResource.kt:54` are deleted.
  - `TenantHostResolver.kt:43` is the resolver's own constant and stays.
  - A bare `"host"` literal is deliberately **not** the predicate: `Entities.kt:104` is the SMTP host column.
- **Rule B: a tenant looked up by domain.** `grep -rn -E '(find|count|list|stream)\("domain' backend/src/main/kotlin`
  - `TenantStore.kt:64` (`domainExists`) delegates to the resolver.
  - `TenantStore.kt:339` (`mailSettingsByDomain`) and `AccountProfileHandler.kt:59` are deleted.
  - `TenantHostResolver.kt:40` stays.
  - `TenantStore.kt:252` is an `update(…)` that writes the column, not a lookup, so the predicate spares it on
    purpose.
- **Rule C: a user looked up by email.** `grep -rn -E 'VeyqUser\.(find|count|list|stream)\("email' backend/src/main/kotlin`
  - `TenantStore.kt:138`, `RecoveryStore.kt:37` and `LoginHandler.kt:37,63` move to `AccountStore.findByEmail`.
  - `AccountStore.kt:20` stays.
  - Lookups on other entities by email (`LoginChallengeEntity`, invites) are not this rule.
- **Handlers taking a Host.** `grep -rn 'host: String?' backend/src/main/kotlin/com/veyq/app/features/spaces backend/src/main/kotlin/com/veyq/app/features/account`
  plus `RecoveryHandler.request(email, domain)`.
  - `ScopesHandler` ×4 and `AccountProfileHandler` ×2.
  - **Spared:** `MediaHandler` / `SharingHandler` take `domain: String?`, but that value is already the
    registered domain. They keep the parameter, which is now fed by `RequestTenant`.
- **Signing-domain consumers.** `grep -rn 'RequestTenantDomain' backend/src/main/kotlin/com/veyq/app/features`
  → `MediaResource` and `SharingResource`.
- **Mail by domain.** `grep -rn 'canSend(\|sendInvite(\|sendRecoveryLink(\|\.settings(' backend/src/main/kotlin backend/src/test/kotlin`
  - Both mailers, `TenantHandler`, `RecoveryHandler`, `SmtpInviteMailSendTest` and
    `TenantInviteMailerDevFileTest`.
  - `TenantsResource` matches only through `resendInvite(`, so it is not a site.
- **Normalization copies.** `grep -rn -i -E 'email[A-Za-z]*\.(trim\(\)\.)?lowercase\(\)|normalize\(email' backend/src/main/kotlin`
  - `TenantHandler` ×3, `SetupResource`, `InviteMailRateLimiter`, `RecoveryRateLimiter`, `RecoveryHandler` and
    `LoginHandler`.
  - The second angle, `grep -rn 'lowercase()' backend/src/main/kotlin | grep -i mail`, must name nothing more.
- **The tag in tests.** `grep -rn 'recoveryMasterKeyTag' backend/src/test | grep -v '"recoveryMasterKeyTag"'`
  - Only entity seeds and stored-value assertions change: `RecoverySliceTest:279,283`,
    `PasswordChangeSliceTest:125`, `RotationSliceTest:65,181,233`.
  - The JSON request bodies keep sending the raw tag.
- **Tests pinning the refactor.** They must stay green, edited only for Host plumbing.
  `grep -rln 'X-Forwarded-Host\|"Host"' backend/src/test` plus
  `grep -rln 'TenantHostResolver' backend/src/test` →
  - `ScopesSliceTest`, `ConsentGroupAddSliceTest`, `MemberIdentitySliceTest` (profile),
    `MediaPresignDomainSliceTest`, `SharePresignDomainSliceTest`, `StoreOriginPresignSliceTest`,
    `TenantSuspendSliceTest`, `TenantChangeDomainSliceTest` (case-insensitive Host) and `RecoverySliceTest`.
  - Add `ShareSliceTest` (anonymous guests, Decision 1's trap), which no Host grep finds.
- **Prose naming symbols this CR deletes.**
  `grep -rn 'RequestTenantDomain\|mailSettingsByDomain\|findUserByEmail\|isEmailMember\|findTenantIdByDomain\|tenantIdByDomain\|activeHost\|domainFor' docs changes wiki scripts .claude backend/src packages/crypto/src web/src admin-web/src`
  - Docs: `0055-…:61` (§4) and `0066-…:36,37,62`.
  - Test KDocs: `RecoverySliceTest.kt:221` and `MediaPresignDomainSliceTest.kt:67`.
  - Code KDoc: `BlobStore.kt:132`.
  - All remaining hits are code that phase 2 deletes. `SmtpInviteMailSendTest`'s `tenantIdByDomain` is a local
    test helper, not the deleted symbol.
- **"Not a secret" about the tag.** `grep -rn -i 'not a secret' contracts/api/auth.openapi.yaml packages/crypto/src/v1.ts`
  → `auth.openapi.yaml:997` and `v1.ts:165`. ADR 0054 was corrected in refine.

## Phases

- [ ] 1 contracts: descriptions only. `requestPasswordRecovery`: mails only an account that is a member of the
      tenant this request's Host resolves to, and answers before the address is looked up.
      `completePasswordRecovery` and `recoveryAuthTag`: the server compares against a stored SHA-256. **Delete**
      the "Not a secret" sentence; replace it with "reveals nothing about the master key, but is what the reset
      accepts". · Done when: `npm run generate:api` + `npm run check` (web) green, the generated Kotlin is
      unchanged, and the trust-chain guards are green.
- [ ] 2 backend: one resolution, one lookup (behaviour-preserving). **Guard first:**
      `scripts/validate_lookup_single_home.py`, one guard with rules A/B/C from the inventory, each rule matching
      the spellings listed there. Its docstring carries the three fields (changes/README § trust-chain guards).
      Its limit names an alias constant (`const val H = "Host"` read as `getHeaderString(H)`), and
      `validate_guard_gaps.py` pins that gap. Run it on this tree and **record the red**: that is the live-defect
      bite, and its list must equal the inventory's rule A–C hits. Then Decisions 1–3 and 8. Recovery still looks
      the account up across the whole instance at the end of this phase, because phase 3 changes that.
      · Done when: the guard is green, each rule has been bitten once by snapshot-and-restore (a hand parse back
      in `RecoveryResource`, a `find("domain"…)` in a handler, `VeyqUser.find("email"…)` in `LoginHandler`), and
      the **full** `./gradlew check` is green with the suites in the "pinning" row unedited.
- [ ] 3 backend: recovery behaviour (Decisions 4 + 5). Both bites are **live defects**: write them on the phase-2
      tree, see them red, then fix.
      - *Cross-tenant.* `USER` is a member of tenant B only. The direct delivery call for tenant A →
        `NO_ACCOUNT`, no mail, no `account_recovery` row. **Control** on the same predicate: the call for tenant B
        → `MAILED` and the mail arrives. Expected first red: the outcome assertion (`MAILED` where `NO_ACCOUNT`
        is expected).
      - *Timing.* The tenant's SMTP is a socket that accepts and never greets. `POST /api/recovery/request` for a
        member answers `202` well under `SEND_TIMEOUT_SECONDS` (10 s). The lever is the hung server, and no later
        phase removes it. **Control:** the background part ran. The member's `PENDING` row appears, awaited with
        a bound.
      - `RecoverySliceTest.requestIsIndistinguishableForKnownAndUnknownAddress` loses its lever. It seeds `USER`
        with no membership (`seedUser` at `:264`) and asserts `deliveredTo` right after the request. Under the
        hand-off the positive races and the negative is vacuous. Rewrite it:
        - HTTP asserts the identical `202` + body;
        - the positive becomes `awaitBody` for a *member*;
        - the negative moves to the direct delivery call;
        - `seedTenant` grants `USER` membership where a test asks as a known address.
      - `transportIsAnsweredBeforeTheRateLimiterSoTheTwoCannotBePaired` keeps its lever (the limiter stays
        synchronous). Confirm that it still reds if the transport check is moved below the limiter.

      · Done when: both bites were recorded red and are now green, and the full `./gradlew check` is green.
- [ ] 4 backend: hashed commitment (Decisions 6 + 7). Live defect first: a test takes the value the database
      holds for an account born over HTTP and presents it as `recoveryAuthTag` to `complete`. Today that is
      `Applied`; it must become `NotProven`. **Control:** the raw tag still resets. Migration tests:
      - Discovery: `flyway_schema_history` holds the version with `success = true`. That is the only assertion
        that sees a migration Flyway silently never picked up. Bite it by renaming the class so Flyway ignores
        it.
      - Rewrite: run the migration against a seeded raw row, and the row reads `Tokens.hash(raw)`.
      - The migration's digest equals `Tokens.hash` for a fixed input.

      Run it once against the MariaDB dev stack (`infra/docker-compose.yml`) and read its history row, since the
      test DB is H2. · Done when: the full `./gradlew check` is green and the MariaDB history row is recorded in
      the retrospective.
- [ ] 5 prose + coverage. **Edit** `packages/crypto/src/v1.ts:165` (the "Not a secret" sentence, as in phase 1).
      ADR 0055 §4 and ADR 0066 name `RequestTenant` instead of the deleted symbols: an edit to the symbol only,
      and the decisions stand. `backend/CLAUDE.md` § Migrations gains one rule: a data rewrite needing a function
      outside the H2 ∩ MariaDB subset is a Kotlin `BaseJavaMigration`, self-contained and never calling app
      code. Re-run the prose locator: phases 2–4 wrote KDocs and may have added sites. Coverage for
      `uc.account.recover-access`:
      - new criteria `request-reaches-only-this-tenants-accounts`, `request-answers-before-the-address-is-looked-up`,
        `commitment-stored-hashed`;
      - `request-is-non-enumerable` and `commitment-proves-possession` are **edited** to say "this tenant" and
        "the stored hash".

      · Done when: `python3 scripts/validate_coverage.py` and every `scripts/validate_*.py` are green, and
      `npm run check` is green in `web/` and `packages/crypto`.

## Definition of done

- The full `./gradlew check` and `npm run check` are green. `for f in scripts/validate_*.py; do python3 "$f";
  done` reports every guard it ran and none red. Take the baseline as that loop's output *before* phase 1;
  phase 2 adds one guard.
- `scripts/validate_lookup_single_home.py` is the permanent form of this CR's structural proof. It was seen red
  on the live defect and bitten per rule, and its alias gap is pinned in `validate_guard_gaps.py`.
- The recovery e2e suite (`web/src/features/account/recovery/recovery.e2e.test.ts`, via
  `scripts/run_web_e2e.py`) ran once locally, and its log shows the suite executed. It is the only place where
  birth → hashed tag → reset → sign-in runs end to end with the real client, and where the hand-off meets a real
  Mailpit. With no remote there is no CI run (inbox #26); say exactly that.
- Coverage is updated as in phase 5. Inbox #156 is closed with a pointer to the ADR 0054 sections, and this file
  is deleted after what it taught has moved to where it binds.

## Retrospective

_(filled at implement)_
