---
id: cr-change-email
title: An account changes its sign-in address - a re-derived auth key, installed when the new address is proven
status: done
satisfies: [uc.account.change-email]
adrs: ["0074", "0003", "0049", "0055"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

A signed-in, unlocked member changes the address they sign in with. The client derives the auth keypair under the new
address's salt and sends it, attested by the current key and with a Secret-Key proof; the server keeps it pending, mails
a link to the new address, and on confirmation swaps email and key in one step, records the address as confirmed,
revokes other sessions and notifies the old address. Decided by the coordinator on the owner's behalf, 2026-10-14
(inbox #197).

## Context & constraints

- [uc.account.change-email](../docs/features/account/use-cases/uc.account.change-email.md) (flow, E1-E6) and
  [ADR 0074](../docs/decisions/0074-address-control-is-recorded-where-nothing-else-proves-it.md) Addendum own the decisions;
  coverage is `docs/coverage/features/account/use-cases/uc.account.change-email.yaml` (`status: planned`, flip it).
- **Measured:** `veyq_user.email` is globally unique (`ux_veyq_user_email`); `authSalt = HKDF(lower(trim(email)),
  instanceSalt, "veyq/v1/auth-salt")` (`packages/crypto/src/v1.ts` `deriveAuthSalt`, the server's `EmailNormalization`);
  the KEKs use stored random salts (unchanged). The email is also the lookup key of the login challenge, recovery, the
  confirmation record and the tenant-invite match (`TenantAcceptHandler`).
- **Mirror, don't invent:** the password change (`ChangePasswordRequest` in `contracts/api/account.openapi.yaml`, its
  attestation and Secret-Key proof, its session revocation) and the email confirmation's lifecycle
  (`veyq_email_confirmation`, `EmailConfirmationPurge`, the mail through the tenant's relay, `503 mail_unavailable`).
- A new Secret-Key-proof **purpose** is a crypto change: a new purpose string pinned by a vector in
  `contracts/crypto/vectors/v1.json` and the conformance tests on both sides (`packages/crypto`, backend). If the
  password change's proof can be reused with a purpose that binds the new address, prefer that, and say which.
- The mail is a fixed copy within ADR 0049's closed variable set (`{{link}}`, `{{domain}}`, `{{expiry}}`): the old-address
  notice cannot name the new address.
- The core baseline migration is still editable (`release: none`): a new table or columns go into it with the pin line,
  and dev databases are recreated (`backend/CLAUDE.md` § Migrations).
- **Invariant:** operator-blind - the server sees the new address and a public key, never the password or key material.

## Inventory (commands)

- `grep -n "deriveAuthSalt" -A3 packages/crypto/src/v1.ts`; `grep -rn "EmailNormalization" backend/core/src/main/kotlin -l`
- `grep -n "ChangePasswordRequest:" -A40 contracts/api/account.openapi.yaml`; `grep -rln "changePassword\|ChangePassword" backend/core/src web/src`
- `grep -n -i "email" backend/core/src/main/resources/db/migration/V202610041200__core_baseline.sql`
- `grep -rln "EmailConfirmation\|email_confirmation" backend/core/src web/src`; `grep -rn "class MailContext\|InviteMailRenderer" -r backend/core/src/main/kotlin -l`
- `grep -rn "secret-key-proof\|secretKeyProof" packages/crypto/src contracts/crypto/vectors/v1.json -l`
- Web: `ls web/src/features/account/email/`; the Emergency Kit writer (`grep -rln "emergencyKit" web/src/features -l | head`).
- Tests: the password change's and the email confirmation's slice tests (`grep -rln "ChangePassword\|EmailConfirmation" backend/core/src/test`), web `features/account/**/*.test.ts`, the e2e suites (`find web/src -name "*.e2e.test.ts" | grep -i "password\|email"`).

## Decisions

1. **Request** (`requestEmailChange`): new address, new auth public key, an attestation by the current auth key over
   (user id, new address, new key), and a Secret-Key proof. Proof wrong → refused, nothing stored. No relay → `503
   mail_unavailable` before anything is stored. Otherwise a pending row (user, new address, new key, token hash, expiry;
   a newer request replaces an older one) and a mail to the new address. The answer is identical for a free and a taken
   address.
2. **Confirm** (`confirmEmailChange{token}`), in a session of the same account: under a lock, check the address is still
   free; swap `email` and `auth_public_key`, set `email_confirmed_at`, delete the pending row, revoke every other session
   (the password change's path), then mail the old address the fixed notice. Taken, expired, used or foreign → a neutral
   refusal, nothing changed.
3. **Web:** Settings → Account → "Change sign-in address" (new address, password, Secret Key - the password change's form
   and its kit field), the "check your mailbox" state, the confirmation landing (the existing email-confirmation route's
   pattern), then "Print a new Emergency Kit".
4. **Purge:** expired pending rows are deleted by the confirmation purge's schedule.

## Phases

- [x] 1 contracts + crypto: Decisions 1-2's shapes; the proof's purpose and its vector if a new one is needed.
  - Done when the trust-chain guards and `packages/crypto`'s check are green.
- [x] 2 backend: Decisions 1, 2, 4.
  - Tests: request stores pending and mails; wrong proof; 503; identical response for a taken address; confirm swaps
    in one transaction and the old key no longer signs in while the new one does; other sessions revoked; old-address
    mail sent with the fixed copy; a race of two confirms for one address leaves one winner; expired/used/foreign link.
    Bitten.
  - Done when `./gradlew check` is green.
- [x] 3 web: Decision 3.
  - Tests: the form derives the key under the new salt (vector-backed) and sends the proof; the states; the kit offer.
    Bitten. An e2e suite: change, confirm (the mail read from the e2e mailbox), sign in with the new address, unlock,
    open content.
  - Done when `npm run check` is green and the e2e suite ran green through `scripts/run_web_e2e.py`.
- [x] 4 docs: the use case's status and coverage (`planned` → refs), ADR 0074 Addendum built, `docs/features/account/_index.md`,
  both wikis' account page, inbox #197 done.
  - Done when every `scripts/validate_*.py` is green, run on a detached worktree.

## Definition of done

- `./gradlew check`, `npm run check` and `packages/crypto`'s check are green; the e2e suite ran green.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- Coverage is no longer `planned`.

## Retrospective

**What was built** (commits `6d16d217` contracts+crypto, `70640781` backend, `0d63d257` web, then docs): the CR as
written, phase by phase. Two choices the CR left to implementation, made in place because they mirror an existing
mechanism rather than decide anything new:
- **The proof could not reuse the password change's statement** — that one binds the new `PASSWORD` wrap, and this
  flow installs no wrap. So: its statement *shape* under a new purpose `veyq/v1/secret-key-proof/change-email`
  (current key, new key, normalized address in the wrap digest's place), and a domain-separated attestation
  `veyq/v1/auth-attestation/change-email` over (user id, normalized address, new key). Both normalize the address
  inside the statement builder on both stacks, and the vectors carry an un-normalized input to pin that.
- **The mail budget is the confirmation's, shared** (`EmailConfirmationRateLimiter`): a change mails an address of the
  caller's choosing, so it could not go unbudgeted, and a second bucket would double what one account can aim at its
  relay.

**What the CR did not see, found in implementation:**
- **The confirmation needs the relay too** — the notice to the old address must be able to leave — so `confirmEmailChange`
  carries the request's tenant gates (`404 tenant_not_found`, `503 mail_unavailable`, nothing changed); the notice
  itself is sent after the commit, and a send that fails then is logged, not answered (nothing left to undo).
- **The swap's uniqueness is an UPDATE, not an insert,** yet the styleguide's insert-half rule fits it exactly: a fast-path
  read under the account lock, and `createOrConflict` re-asking "taken?" for the loser of two accounts racing for one
  address. Bitten: with the replay question removed the loser is a 500. Green on MariaDB (`run_backend_races_on_mariadb.py`).
- **`EveryStoredTimeHasAReaderTest`** refused the reflexive `created_at`: the pending row has none; `expires_at` is its one time.
- **The client names the account by its address in two LOCAL records** (the remember-me record in IndexedDB and the
  session vault in sessionStorage). Left stale, a reload would derive every later proof (a password change's
  attestation) under the old address's salt. The confirmation page moves both to the new address
  (`readdressRemembered`, `readdressSessionVault`) and this session's `account.email`. Other devices are signed out
  and pick up the new address at their next sign-in.
- **"Settings → Account" does not exist**; the address already lives in Settings → Security (`EmailAddressSection`), so
  the change is offered there, and the use case now says so.

**Residuals, stated:**
- The new kit on the landing page takes the Secret Key as typed/pasted/imported and **does not verify it** (the page is
  not unlock-gated, and nothing a session alone can read checks a Secret Key). Importing or scanning the old kit carries
  the exact key; a mistyped one would print a wrong kit.
- A recovery token mailed to the OLD address before the change stays live until it expires; completing it still needs
  the Secret Key, so it buys nobody without the kit anything.
- The UI was proven by unit tests and the live e2e (through the API, not a browser); it was not clicked in a browser.

**Process:** the CR's inventory and decisions were enough to build without a question; the gaps above are the kind a
refine finds only by tracing the address through every place that keys on it — the client-side records were the one
the inventory's greps (server-side `email` uses) could not have shown.
