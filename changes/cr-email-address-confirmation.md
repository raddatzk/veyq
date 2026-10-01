---
id: cr-email-address-confirmation
title: Record address control where nothing else proves it — confirm the address a reset is sent to
status: planned
satisfies: [uc.account.confirm-email-address]
adrs: ["0074", "0054", "0055", "0049", "0003", "0044", "0051"]
invariants:
  - operator-blind E2EE — no content, key or sealed field enters the flow; the address is already server-visible
  - self-hostable by default — no server-level mailer; a tenant without mail refuses with 503, never a copy-link
  - API-first — the three operations are contract-first, generated on both sides
  - ADR 0054's two-factor recovery stays unchanged — recovery never reads the new record
---

## Open for the owner

**Third round answered by the owner, 2026-10-01: every recommendation added since the second answer is taken too** (questions added by the third refine wave and by implementation hand-backs). Rows those questions proposed exist in `docs/inbox.md` (#233–#243).

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

Refined 2026-09-28 from inbox #112, which the owner put on the pre-V1 list the same day for its security
relevance. **ADR 0074 is `Proposed` until these questions are answered.** Each carries a recommendation, and
none is answered here.

1. **What this buys in V1 is narrower than the row's framing: read this first.** Measured against `main`
   (§ Inventory):
   - **No takeover path depends on it.** A reset needs the mailed token *and* the Secret Key (ADR 0054 §1).
   - **No attacker can squat an address.** The two unproven account paths, the bootstrap first owner and
     first-run setup, are both started by the Server Owner, who already writes the database.
   - **What is left is durability.** The tenant's first owner, the account most likely to hold everything, has
     an address nothing ever tested. A wrong or unread one surfaces only on the day a reset is needed, and then
     the reset cannot reach them.

   **Recommendation: keep it in V1 as this CR.** It turns a silent, permanent loss into a visible one. It is
   also the enabler the future self-registration gate builds on (ADR 0074 Decision 3). **The cheaper
   alternative** is a stateless "send a test mail to me" button in the mail settings (ADR 0074 § Rejected). It
   proves the same thing once and is roughly a third of this CR, but leaves nothing to show later and nothing to
   build on. Take it only if V1's line needs the room.
2. **Correcting a wrong address is not in this CR.** No flow can change an account's address (Inventory I5), so this CR can reveal a wrong
   address but not fix it. Changing one means:
   - re-deriving the auth keypair under the new salt (ADR 0003), which needs the password;
   - confirming the new address before it takes effect;
   - clearing the stamp (ADR 0074 §1);
   - facing the old Emergency Kit, which carries the old address (`packages/crypto/src/emergencyKit.ts:12`).

   **Recommendation: a separate inbox row, not V1.** The realistic V1 owner types their own address, and then
   sees it on the join screen, in the Emergency Kit and at every sign-in. A typo that survives all of that is
   rare. An address abandoned years later is the real case, and that is post-V1.
3. **How the link is confirmed.** It needs the account's own session: open the link on a device where you are
   signed in. **Recommendation: keep it.** An anonymous link would let whoever owns a mistyped mailbox confirm
   the wrong address, which silences the exact defect (ADR 0074 §5). The cost is UX: a phone that is not signed
   in cannot confirm. The alternative is a short code typed into the signed-in session. It works across
   devices, but adds a guessing surface and departs from ADR 0049 §1's link.
4. **It refuses nothing.** In particular, recovery keeps working for an unconfirmed account.
   **Recommendation: yes, as decided.** Gating recovery adds no factor and strands the very accounts this is for.
   If the owner wants a gate, name the request it should refuse, and the CR adds it with its own bite.
5. **Where it is shown.** **Recommendation:**
   - a small card in the account's **security** settings (address as the server holds it, plus its state);
   - a one-line offer in the **mail settings** panel after a successful save ("send a confirmation to yourself;
     it also tests this relay");
   - no app-wide banner. After this CR the only unconfirmed accounts are bootstrap owners and a Server Owner who
     joined as one, and both are tenant owners standing in that very panel.

## Goal

The account email is the only channel a password reset can reach (ADR 0054). Nothing records whether the account
holder reads it. For member accounts that is proven anyway, because their invite arrived by mail. For a tenant's
**first owner** it never is: they were admitted by the Server Owner's copy-link (ADR 0055 §3), and a wrong or
unread address surfaces only when a forgotten password needs it. This CR changes that:

- It records address control as a fact on the account (`email_confirmed_at`).
- It sets that fact on the three events that prove it: a redeemed member invite, a completed reset, and a
  confirmation link opened while signed in.
- It shows the fact to the holder.
- It gates nothing.

The semantics are ADR 0074's. The behaviour is `uc.account.confirm-email-address`'s.

## Context & constraints

- **Durable truth:**
  - [ADR 0074](../docs/decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) (what the
    record means, the three events, gates nothing, token, session binding, no copy-link);
  - [uc.account.confirm-email-address](../docs/features/account/use-cases/uc.account.confirm-email-address.md)
    and its planned coverage file.
- **The recovery flow is the template, by design, and is not to be changed:**
  [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) §4 (relay of a member tenant only),
  §5 (veyq-fixed copy) and § Consequences (the token lifecycle). The mirror is the package
  `backend/src/main/kotlin/com/veyq/app/features/account/recovery/`. Recovery **reads nothing new**; it gains
  exactly one write, the stamp in `applyReset`.
- **Mail boundary:** [ADR 0049](../docs/decisions/0049-email-delivery-boundary.md). The mail is built from
  `MailContext(link, domain, expiry)` (`InviteMailRenderer.kt:11`), whose three fields *are* the closed set.
- **No mail, no link:** [ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md) §4. The
  refusal is `Failures.mailUnavailable` (`shared/http/Failures.kt:111`).
- **Field visibility** ([ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)) and
  **rate-limit posture** ([ADR 0051](../docs/decisions/0051-declarative-rate-limit-posture.md)) apply to every
  new field and endpoint.

## Inventory — measured 2026-09-28 against `main` @ `faf9ef4` (worktree, clean)

Every row is **measured** by the command shown unless marked *remembered*. The row the argument leans on hardest
is the first one; an implementer spot-checks it.

| # | Fact | Locator |
|---|---|---|
| I1 | **Exactly two paths create an account.** Setup takes the address from the body and compares it with nothing. Register-and-accept takes it from the invite. | `grep -rn 'createAccount(' backend/src/main/kotlin` → `SetupStore.kt:19`, `TenantAcceptHandler.kt:171` (+ the definition `AccountStore.kt:27`); `TenantAcceptHandler.kt:173` `email = invite.ownerEmail` |
| I2 | **The sign-in accept binds the invite's address to the caller.** | `TenantAcceptHandler.kt:124` `if (caller.email != invite.ownerEmail) return AcceptOutcome.NotInvitedOwner` |
| I3 | **A member invite is mail-only; the bootstrap owner invite is copy-link.** | `TenantHandler.kt:325` + `:379` (`if (!mailer.canSend(tenant.id)) … MailUnavailable`); ADR 0055 §2/§3 |
| I4 | **No confirmation or verification state exists anywhere.** | `grep -rniE "email_?verified\|verified_?at\|verify_?email\|emailVerif\|email_?confirm" backend contracts web/src admin-web/src packages` → no hits |
| I5 | **No flow changes an account's address.** | `grep -n 'email' contracts/api/account.openapi.yaml` → prose only (`:17`, `:142`), no property. Of the email properties `grep -rn '        email:\|ownerEmail:' contracts/api` lists, the request ones are login, recovery, setup and the invite mints; the rest are responses. That split was read schema by schema by a delegated sweep, not re-run here. |
| I6 | **The address feeds only the auth salt and the Emergency Kit.** | `grep -rn -i email packages/crypto/src \| grep -v '\.test\.'` → `v1.ts:174-175`, `accountBirth.ts:64`, `emergencyKit.ts` |
| I7 | **Recovery mails the account address through the `Host` tenant's relay, only to its members.** | `RecoveryHandler.kt:117-120` (`findByEmail(normalized)?.takeIf { membership.belongsToTenant(it.id, tenant.id) }`) |
| I8 | **The reset's single write site, where the stamp goes.** | `grep -n 'fun applyReset' backend/src/main/kotlin/com/veyq/app/features/account/recovery/RecoveryStore.kt` → `:97`; it already flips the token `CONSUMED` at `:108` |
| I9 | **Token helpers and the lifecycle to mirror.** | `shared/crypto/Tokens.kt:16` `random()`, `:23` `hash()`; `V44__account_recovery.sql` (hash + mailed `email` + status + `expires_at`); `RecoveryPurge.kt`; config `application.properties:169-171` |
| I10 | **The generic USER bucket is far too loose for mail.** A dedicated per-account budget is needed, in the shape of `RecoveryRateLimiter.kt`. | `grep -n 'ratelimit' backend/src/main/resources/application.properties` → `user.max-requests=600` per 60 s vs `recovery.per-address-max-requests=3` per 3600 s |
| I11 | **The next migration number.** Run it at implement time: a parallel CR may take the next one first. | `ls backend/src/main/resources/db/migration \| sort -V \| tail -1` → `V58__share_key_recovery_epoch.sql` today |
| I12 | **The web insertion points and their gates.** The fourth tell applies (see the note below the table). | `grep -n "'security'\|'mail'" web/src/views/SettingsHubView.vue` → `:83`, `:96`, `:148` (`TenantMailSettingsPanel`), `:158` (`KeyRotationPanel`); `web/src/router.ts:38` (`/settings/:section?` is `requiresUnlock`); `:44` + `:57` (`/tenant/accept`, `/recover`: plain, session-free routes) |
| I13 | **Fragment-token handling to mirror.** | `web/src/views/RecoverView.vue:61-89` (read on mount **and** `hashchange`, then `history.replaceState`) |
| I14 | **The app shell serves any new path.** No allowlist, so no backend change is needed for a new client route. | `AppShellRoutes.kt` (`router.route().order(Int.MAX_VALUE)…fallback`) |
| I15 | **Hand-allocated GreenMail ports in the backend tests.** A new slice test takes free ones; re-run at implement, because parallel CRs allocate too. | `grep -rhn 'SMTP_PORT *= *[0-9]*' backend/src/test/kotlin \| grep -o '139[0-9][0-9]' \| sort -u` → `13925 13927–13938 13940 13941` |
| I16 | **The fixtures that pass through the stamp sites.** Stamping is additive and the column nullable, so none should turn red. They are the neighbours to re-run. | `grep -rln "tenants/accept\|registerAndAccept" backend/src/test/kotlin` → `TenantAcceptSliceTest`, `MemberIdentitySliceTest`, `MemberInviteManagementSliceTest`, `TenantsSliceTest`, `MemberAddSliceTest`, `RateLimitedEndpointsTest`, `OperatorBlindProbeTest`, `ScopeAccessSliceTest` |
| I17 | **Contract precedents for the new fields.** | `contracts/api/auth.openapi.yaml:718` (`email`: `x-veyq-visibility: pii` + rationale); `:1012-1017` (`token`: `x-veyq-visibility: token`, `maxLength: 512`); `scripts/validate_request_bounds.py` (a bound needs its hand-written annotation) |

**Note on I12 (the fourth tell).** The *card* may sit behind `/settings`' unlock gate, because the actor is
normally unlocked there. The *confirmation page* must not: it must be reachable with a session alone. It must
also not be `layout: 'app'`, or `scripts/validate_shell_route_gate.py` rightly reddens.

**Claims this CR supersedes.** The locator greps fragments across the whole tree; re-run it in phase 4, because
phases 1–3 write new prose:
`grep -rn -i "email later\|mailer is configured\|becomes load-bearing for V1\|returns to V1 with\|send-fact" docs wiki`

- `docs/features/admin/use-cases/uc.admin.create-tenant.md:26-27`: "Delivery is by email **if a mailer is
  configured**". A `PENDING` tenant never has one, so this is **narrowed** to copy-link-only.
- `:36-37`: "The owner may **change their email later**". No such flow exists (I5), so this is **deleted**.
- `docs/decisions/0054-password-recovery-two-factor-release.md:155`: "Email verification (#112) becomes
  load-bearing for V1 … recovery-gating first". **Edited** in place to point at ADR 0074 Decision 3.
- `docs/crosscutting/security-model.md:110`: "the **send-fact** of an invite mail". **Widened** to every mail veyq
  sends (invite, recovery, address confirmation), plus the new `email_confirmed_at`. Recovery's send-fact was
  already missing, a pre-existing gap this closes.
- `docs/inbox.md:85` (the V1 section) says #112 "returns to V1 with **#43** open self-registration". The owner's
  2026-09-28 call contradicts it, and **#43 is at-rest PII encryption, not self-registration**
  (`sed -n 120p docs/inbox.md`). That paragraph is not this CR's row. It is handed to the coordinating session
  (see the refine report).
- **Not a site:** `recovery/Entities.kt:20` ("an account's address can change") is the forward-looking reason
  for binding the mailed address, and ADR 0074 §1 keeps the same rule. The `V44` comment is a shipped migration
  and is never edited.

## Decisions (each settled by ADR 0074 unless it points elsewhere)

- **D1 — Naming.** The product and code term is **confirm**: `email_confirmed_at`, `confirmEmail…`. The inbox
  said *verification*, but "verified" already names identity verification and the login step (ADR 0074
  § Context). Grep for collisions before choosing symbols: `grep -rn -i 'confirm' web/src/shared backend/src/main/kotlin/com/veyq/app/shared`.
- **D2 — The column.** A nullable `email_confirmed_at TIMESTAMP` on `veyq_user`, with no backfill (pre-V1, no
  installed base).
- **D3 — The token table.** `veyq_email_confirmation` is shaped like `veyq_account_recovery` (I9):
  - columns: `id`, `user_id` (FK), `email` (the mailed address, normalized), `token_hash` (**unique** index,
    because redemption is a point read by hash), `status` (`PENDING`/`CONSUMED`/`SUPERSEDED`/`EXPIRED`),
    `expires_at`, `created_at`, plus an index on `(user_id, status)`;
  - config: TTL `PT24H`, retention `P7D`, purge every `1h`, under a `veyq.email-confirmation.*` prefix;
  - purge: a purge job in the `RecoveryPurge` shape.

  **Every terminal state, and what the purge does with it** (the fifth tell):
  - `CONSUMED`: purged after retention.
  - `SUPERSEDED`: purged after retention.
  - `EXPIRED`: set by the sweep, then purged after retention.

  Nothing else is ever minted from a token, so no terminal state strands anything.
- **D4 — Consumption is a compare-and-set.** `update … set status = CONSUMED where id = ? and status = PENDING`
  must affect exactly one row, or the confirmation is refused. This deliberately differs from the invite's plain
  update (`TenantStore.kt:121`, `:143`, `:149`). That difference is a proposed inbox row, not this CR's to fix.
- **D5 — The three operations,** on a **new tag** `account-email` in `contracts/api/account.openapi.yaml`, so the
  contracts phase ends green on every surface (`changes/README.md` § *an operation on a NEW tag*):
  - `GET /api/account/email` → `200 AccountEmail { email, confirmed }`.
    - `email` is the address **as the server holds it**. It is not implied by anything the client has: the
      client remembers what it typed, and the server mails what it stored.
    - `@Authenticated`, `@RateLimit(key = USER)`.
  - `POST /api/account/email/confirmation` → `202` when mailed.
    - Order of checks: `503 mail_unavailable` if the `Host` tenant cannot send; `404 tenant_not_found`
      (`Failures.tenantNotFound`) if there is no tenant for the `Host` or the caller is not a member of it
      (`MembershipStore.belongsToTenant`, the predicate I7 uses); `429 rate_limited` over the per-account
      budget.
    - The send may run synchronously, because the caller is the account itself and there is no enumeration to
      hide. It still commits the mint **before** the send (the `commitThenSend` discipline).
    - If the send fails, it compensates by superseding the row, which ADR 0055 §6 applies to invites. The
      answer is then `503 mail_unavailable`, never a silent `202`.
  - `POST /api/account/email/confirmation/confirm` with `{ token }` → `204`.
    - It answers `404 email_confirmation_invalid` alike for unknown, expired, superseded or spent tokens, for a
      token minted for another account, and for a mailed address that no longer equals the account's.
    - A refusal consumes nothing.
    - `@Authenticated`, `@RateLimit(key = USER)`.

  Field visibility follows I17: `email` is `pii` with a rationale, `token` is `token` with `maxLength: 512` and
  its annotation, and `confirmed` uses the class `validate_field_visibility.py` names for a boolean flag.
- **D6 — The stamp sites.** All three are in the same transaction as the event they record:
  - `TenantAcceptHandler.registerAndAccept`'s **MEMBER** branch;
  - `TenantAcceptHandler.accept`'s **MEMBER** branch (all three sub-branches: rejoin, fresh join, and the
    already-member consume);
  - `RecoveryStore.applyReset`, **iff** the recovery row's `email` equals the account's current email.

  Keyed on `invite.role == MEMBER`, never on a delivery value (ADR 0074 §2). The OWNER branches and setup stamp
  nothing.
- **D7 — Per-account send budget:** 3 per 3600 s, a limiter in the `RecoveryRateLimiter` shape keyed on the user
  id. No per-tenant or per-instance cap: the caller is authenticated, and the population is bounded by
  invitations. State that reason in its KDoc.
- **D8 — The mailer is a sibling of `RecoveryMailer`**, reusing `TenantMailSender` (`canSend`, `settings`,
  `deliver`, `fromAddress`). The copy is veyq-fixed and bilingual. It says what confirming does and that nothing
  happens if it is ignored, and it names neither the person nor the tenant beyond its domain (ADR 0054 §5). The
  link is `https://<registered domain>/confirm-email#<token>`.
- **D9 — Web.**
  - **A card in the `security` section:** the server's address and its state, a "send confirmation" action,
    and the A1/A2/A3 answers rendered by status code. The client's `HttpStatus` vocabulary must carry each one;
    check with `rg -n 'serviceUnavailable\|tooManyRequests\|notFound' web/src/shared`.
  - **The mail-settings panel:** after a save whose result is sendable, it offers the same action once.
  - **A route `/confirm-email`**, plain and **not** `requiresUnlock`, with a view that:
    - consumes the fragment exactly like `RecoverView` (I13);
    - with a session, confirms, and on success links to the settings card;
    - with no session, shows A4 without persisting the token;
    - with the wrong account, shows the generic refusal.
  - i18n EN+DE. The German copy follows `cc.inclusive-language`.

## Phases

- [ ] **1 contracts.** The `account-email` tag with the three operations of D5, their schemas, the visibility
  markers and the request-bound annotation. **Done when:** `npm run generate:api` succeeds in `web/`; the
  contract guards are green (`validate_contracts_common.py`, `validate_field_visibility.py`,
  `validate_request_bounds.py`, `validate_http_failure_projection.py`); and `./gradlew check` is **green**,
  because a new tag's `…Api` interface is implemented by nobody yet. Run it once to see that; do not add a stub.
- [ ] **2 backend.** The migration (I11), the entity, store, handler, resource, mailer, limiter and purge
  (D2–D8), and the three stamp sites (D6). **Done when:** `./gradlew check` is green, with the new slice test
  carrying every bite below. The fixtures in I16 are re-run and green.
- [ ] **3 web.** Generated client use, the settings card, the mail-settings offer, the `/confirm-email` route and
  view, and i18n (D9). **Done when:** `npm run check` in `web/` is green, with unit tests for the card's
  four states and the view's three arrivals.
- [ ] **4 docs.**
  - ADR 0074 → `Accepted`, with the owner's answers folded in.
  - Edit the sites under *Claims this CR supersedes* in the shape named there, after re-running the sweep.
  - `uc.admin.create-tenant`: the first owner is unconfirmed until they confirm.
  - `uc.admin.tenant-mail-settings`: the post-save offer.
  - `uc.account.recover-access` Preconditions: one clause saying reaching the mailbox is what confirmation
    shows in advance.
  - `wiki/{en,de}/account-keys-and-recovery.md`: one short paragraph next to the reset steps
    (`grep -n 'A link we mail you' wiki/en/account-keys-and-recovery.md`).
  - Update the `docs/features/account/_index.md` status.

  **Done when:** `validate_doc_links.py`, `validate_feature_format.py`, `validate_feature_wiring.py` and
  `validate_doc_citations.py` are green.
- [ ] **5 coverage.** `docs/coverage/features/account/use-cases/uc.account.confirm-email-address.yaml` drops
  `status: planned` and fills each criterion with its test refs. Any web e2e is cited only if the `web-e2e` run
  executes it (ADR 0040). **Done when:** `python3 scripts/validate_coverage.py` is green.

## Definition of done

**Baseline first, before the first edit.** Run
`for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`
and `./gradlew check` on the untouched tree, and record both exit states in the retrospective. A red that is
already there is not this CR's. This is a wave-refined CR, so the implementing session runs the guards its
phases name, and the coordinating session runs the whole glob on the merged tree.

**Red first.** The slice test `EmailConfirmationSliceTest` (a new file; name the cases, not the file, in
coverage) is written before the handler. Every **live defect** below must be seen red on the phase-2 tree
before its fix lands, with the red on the named assertion, not on setup. Every **injection** is bitten by
snapshot-and-restore (`changes/README.md` § *bite-proof*), with the restore confirmed by comparing bytes to the
snapshot.

| Bite | Kind | Red observable | Positive control (same predicate) | Lever |
|---|---|---|---|---|
| `memberInviteRedemptionConfirms` | live defect | `GET /api/account/email` → `confirmed == true` after a MEMBER register-and-accept | the same read after an **OWNER** bootstrap register-and-accept → `false` | — |
| `existingAccountMemberAcceptConfirms` | live defect | same, on the sign-in `accept` branch | an OWNER sign-in accept → `false` | — |
| `completedResetConfirms` | live defect | `confirmed == true` after `/api/recovery/complete` | after `/release` alone → `false` | — |
| `confirmNeedsTheAccountsOwnSession` | live defect | B's session presenting A's token → `404 email_confirmation_invalid` **and** A still `false` **and** the row still `PENDING` | A's session with the same token → `204`, `true` | — |
| `supersededTokenConfirmsNothing` | injection | a first token after a second request → `404` | the second token → `204` | delete the supersede call in the store's mint |
| `tokenConfirmsAtMostOnce` | injection | a second confirm with a spent token → `404` | the first confirm → `204` | drop `and status = PENDING` from the CAS update (D4) |
| `noMailRefusesAndMintsNothing` | injection | `503 mail_unavailable` **and** zero rows for the user | a sendable GreenMail tenant → `202`, one row, one mail | move the `canSend` check below the mint |
| `nonMemberIsRefusedAndNothingIsMailed` | injection | the caller not a member of the `Host` tenant → `404 tenant_not_found`, zero mails on that relay | a member on the same host → one mail on that relay | drop the membership check |
| `sendBudgetBoundsMails` | live defect | the 4th request in the window → `429`, the mailbox holds 3 | the 3rd → `202` | — |
| `unconfirmedMemberCanStillRecover` | injection (pins a **non**-change) | a recovery request for an unconfirmed member mints a token and mails it | the same for a confirmed member | add `?.takeIf { it.emailConfirmedAt != null }` to `RecoveryHandler.deliver`'s lookup (I7) |
| `mailCarriesOnlyLinkDomainExpiry` | injection | the delivered body contains the link with the token in the fragment and neither the username nor the tenant name | — (content assertion on the same mail as the `202` control above) | interpolate the tenant name into the body |
| `everyEndpointDeclaresARateLimitPosture` | already live | `RateLimitPostureSweepTest.everyEndpointDeclaresARateLimitPosture` red on a resource without a posture | its green on the finished resource | temporarily remove the resource's `@RateLimit` |

**Why these levers reach their oracle** (checked at refine, per § *check the lever reaches the oracle*):

- Each lever edits the one line the named assertion depends on.
- None passes through a contract pattern that would reject it first. The `404`/`503`/`429` codes all already
  exist or are added in phase 1.
- The GreenMail mailbox is the oracle for "mailed", which the handler cannot repair.

**Structural invariant, and why no new guard.** The closed variable set is held by the `MailContext` type
(`InviteMailRenderer.kt:11`). The new mailer compiles only against it, which is a shape, not a rule. "Recovery
reads no confirmation state" is pinned by the `unconfirmedMemberCanStillRecover` injection above, and a guard
would add nothing over it. So none is promoted, and the retrospective says so.

**Also done when:**

- `./gradlew check` is green in `backend/` and `npm run check` in `web/`.
- The guards named in phases 1, 4 and 5 are green on the committed tree.
- Coverage is filled.
- The wiki paragraph exists in both languages.
- The CR's lessons are harvested and the CR is deleted.

## Retrospective

(filled at implement-session close)
