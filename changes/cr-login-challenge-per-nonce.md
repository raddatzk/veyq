---
id: cr-login-challenge-per-nonce
title: A login challenge is keyed by its nonce — nobody can keep a named account from signing in
status: planned
satisfies: [uc.account.login]
adrs: ["0003"]
invariants: [operator-blind E2EE, API-first]
---

## Goal

Today the server keeps **one** login challenge per email. `LoginChallengeStore.replace` deletes the old one on
every new challenge request (`LoginChallengeEntity.delete("email", email)`), and `LoginHandler.verify` deletes
it under its row lock **before** the signature is checked (`deleteById(normalized)`). So anyone who knows an
address can replace the challenge (ask for a new one) or burn it (send a garbage verify) every second or two,
well inside the per-IP limit, while the victim's device is still running Argon2id. Every fresh login of that
account then fails with 401; refreshing an existing session is unaffected. After this CR a challenge is keyed
by its **nonce**. An email may have several outstanding challenges, the verify request names the nonce it
signed, and a verify consumes only that one challenge, under a row lock on that one row. Single use and the
concurrent-replay guard (audit P2f) hold per nonce.

## Context & constraints

- **The refined behaviour is already written:** [uc.account.login](../docs/features/account/use-cases/uc.account.login.md)
  main flow steps 3–5, **E4**, and the quality notes *Replay-resistant* and *Nobody can keep you from signing in*.
  That includes the decision **not** to cap outstanding challenges per email, and why. This CR builds it.
- **Non-enumerable, unchanged:** a challenge row is stored only for an existing account, so a verify for an
  unknown one finds no row and takes the dummy-key path (`LoginHandler.verifySignature`). Nothing here may
  make an existing and a missing account answer differently, in status or in the timing equalisation.
- **API-first:** the verify request gains a `nonce`, so it is written into
  [`contracts/api/auth.openapi.yaml`](../contracts/api/auth.openapi.yaml) first. It is **required, with no
  fallback**: "no nonce → take the newest challenge for the email" would keep the attack alive for exactly the
  clients that send none. A web bundle cached from before the deploy gets `400` on login until it reloads.
  Native clients are parked (web-first V1), so nothing else sends this request:
  `grep -rn 'verifyLogin\|login/verify' web/src admin-web/src packages scripts | grep -v generated`.
- **The request-body rule** ([developer-styleguide](../docs/crosscutting/developer-styleguide.md), *the schema
  bound and its annotation are one rule*): the new field declares `maxLength` + `contentEncoding: base64` and
  carries the matching `x-field-extra-annotation`, value for value. Copy the shape of its sibling `signature`.
  `scripts/validate_request_bounds.py` enforces this rule, so the field cannot land without it.
- **Migrations are append-only** ([backend/CLAUDE.md](../backend/CLAUDE.md) § Migrations): `V5__login_sessions.sql`
  and its *"One outstanding login challenge per email"* comment stay untouched. The live statement of the rule
  is the entity KDoc, which this CR corrects. Next free number: `ls backend/src/main/resources/db/migration | sort -V | tail -1`.
- **Neighbour touching the same code:** `cr-request-tenant-and-tenant-bound-recovery` moves `LoginHandler`'s
  `VeyqUser.find("email", …)` lookups to `AccountStore.findByEmail` and leaves `LoginChallengeEntity` lookups
  alone. The overlap is textual; whichever CR lands second rebases.
- **Out of scope:** `changePassword.ts` and `recovery.ts` request a challenge only for its `instanceSalt`. Today
  that silently burns any login in flight for the same account. After this CR it adds a row that expires unused,
  which is harmless, so those calls are left as they are.

## Phases

**Order: the wire first, the semantics second.** A required field makes every request that omits it a `400`. So
the client **and** every backend test that posts to `/api/login/verify` must send the nonce in the same phase
as the contract. The handler ignores the field until phase 2, and every surface ends each phase green.

- [ ] 1 contracts + clients — the contract, the two clients, and the backend tests' request bodies.
      - **Contract:** `LoginVerifyRequest.nonce`, required, the base64 shape of `signature`. The `verifyLogin`
        description says the signature is verified over the nonce the request **names**, and that a verify
        consumes only that challenge while others for the same email stay outstanding. The example body gains
        the field.
      - **Web and admin-web clients:** both `login()` functions echo `challenge.nonce`. Locator:
        `grep -rn 'verifyLogin(' web/src admin-web/src | grep -v generated`. `web/src/features/account/login/login.test.ts`
        asserts that the nonce sent is the one the challenge returned, not just *a* string. The type proves only
        the second.
      - **Backend tests:** every body posted to `/api/login/verify` carries the nonce. Locator:
        `grep -rn 'login/verify' backend/src/test`. `AuthRateLimitTest.verify` sends a well-formed dummy nonce,
        so its first three calls still reach the handler (a 401), not the validator.

      · Done when: `npm run generate:api`, `npm run check` in `web/` and `admin-web/`, and `./gradlew check`
        are all green, and so are the contracts guards.
- [ ] 2 backend — the challenge is keyed by nonce.
      - **Guard → red → fix → green** (the defect is live). First add the property test to `LoginSliceTest` and
        watch it fail on the unchanged handler. The victim requests challenge *A*. The attacker requests *B* for
        the same email, sends a verify naming *B* with a garbage signature, and sends one naming a nonce that was
        never issued. The victim's correctly signed verify of *A* must then return **200**. Today it gets **401**,
        because *B* replaced *A*. Check that it fails for that reason, then make it pass.
      - **Migration `V<next>`:** drop and recreate `veyq_login_challenge` with `nonce` as the primary key
        (`VARCHAR`, sized for the server-generated 32-byte nonce, which is 44 base64 characters; a `TEXT` cannot
        be a MariaDB key). Keep `email` and `expires_at` as `NOT NULL` columns. Dropping loses at most five
        minutes of challenges. No main-code query reads by email alone once `replace` is gone. Check with
        `grep -rn 'LoginChallengeEntity' backend/src/main`.
      - **`LoginChallengeStore.replace` → an insert** that deletes nothing. It keeps its own transaction and
        still swallows a failure. The reason in its KDoc changes: the email-PK race is gone, but a failed
        insert for an existing account must still never answer differently from the no-account path
        (non-enumerable).
      - **`LoginHandler.verify(email, nonce, signature)`:** find the row by nonce **and** email with
        `PESSIMISTIC_WRITE`, delete that row under the lock, then verify. A missing row, a row for another
        email, or an expired row all take the existing dummy path. The KDoc and the lock comment say *per
        nonce*. `concurrentVerifyConsumesTheChallengeOnce` and `replayedNonceIsRejected` stay green unchanged
        in substance.
      - Entity KDoc: "One outstanding login challenge per email" becomes several per email, keyed by nonce.

      · Done when: `./gradlew check` green, with the property test seen red first.
- [ ] 3 coverage — [`uc.account.login.yaml`](../docs/coverage/features/account/use-cases/uc.account.login.yaml)
      gets a criterion for E4 (*"a challenge requested, or a verify sent, by someone else for the same email
      never invalidates an outstanding one"*) → the phase-2 test. `nonce-single-use` says *per nonce*.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

- Every phase is done. The property test was **seen red** against the one-per-email handler before the fix,
  for the reason above: *A* displaced, not a boot or a 400.
- `nonce-single-use` (sequential and concurrent) is still green, so the per-nonce lock replaced the per-email
  one instead of dropping it.
- `grep -rn 'delete("email"' backend/src/main/kotlin/com/veyq/app/features/account/login` is empty.
- The trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Retrospective
