---
id: cr-authenticated-password-change
title: Change your password from a live session
status: planned
satisfies: [uc.account.change-password]
adrs: ["0003", "0054", "0060"]
invariants: [operator-blind]
---

## Goal

Let a signed-in, unlocked account holder set a new password without going through the mailed recovery flow —
either deliberately (they still know the current password) or because they have forgotten it but are still
signed in, most often via a password-less `DEVICE_KEY` "remember me" wrap. One server-side mechanism serves
both entry points, differing only in whether the request carries an attestation signature proving the caller
still controls the *old* password. Implements [uc.account.change-password](../docs/features/account/use-cases/uc.account.change-password.md).

## Context & constraints

- [uc.account.change-password](../docs/features/account/use-cases/uc.account.change-password.md) — the refined
  use-case this CR builds, including both entry points, the attestation shape, and why `DEVICE`/`DEVICE_KEY`
  wraps are dropped except for the acting device.
- [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md) — the
  unauthenticated sibling this mirrors server-side. Its backend shape is the direct template:
  `backend/src/main/kotlin/com/veyq/app/features/account/recovery/RecoveryStore.kt:103` (`applyReset`) rewrites
  the `PASSWORD` wrap + `auth_public_key` and deletes every `DEVICE`/`DEVICE_KEY` wrap in one call — this CR's
  `PasswordChangeStore.applyChange` follows the same shape, with one deliberate difference: `applyReset` has no
  caller session to spare (recovery has no session at all); this flow does, and must not revoke it.
- [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) — why a new password means a new Ed25519
  auth keypair (Model B), and the derivation this CR's attestation reuses.
- [ADR 0060](../docs/decisions/0060-key-rotation-after-compromise.md) Decision 2 — the precedent for a
  client-side-only cold-credential gate: the server cannot see which wrap opened a caller's vault, so the
  forgotten-password entry point (A1) is enforced by the client choosing to reach it only from an unlocked
  session, exactly as rotation's gate already is. Do not add a server-side check that tries to verify this —
  none is possible, and ADR 0060's own reasoning (§ *the cold-credential gate is client-side, and cannot be
  otherwise*, `contracts/api/account.openapi.yaml:414-419`) already establishes why.
- **What this is deliberately NOT: a rotation.**
  `backend/src/main/kotlin/com/veyq/app/features/account/rotation/RotationHandler.kt` and
  `AccountRotationResource.kt` (`POST /api/account/rotation`) also rewrite the `PASSWORD` wrap, but mandatorily
  together with a **new identity keypair** and a scope-rotation job per shared scope
  (`RotateAccountKeysRequest`, `contracts/api/account.openapi.yaml:782-797` — note its own comment: *"no
  `authPublicKey` — the password does not change"*). Do not reuse or extend that endpoint; it is structurally
  the wrong shape (identity rotation is mandatory there, unwanted here) and its request shape is pinned by
  `scripts/validate_rotation_shape.py` specifically to forbid a mode flag.
- **Sparing the caller's own session has a precedent to copy, not invent.**
  `RotationHandler.kt:69-95`'s `presentedAccessToken` parameter, resolved via
  `SessionService.revokeOtherFamilies(userId, presentedAccessToken)`
  (`backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt:177-180`) is the exact
  mechanism: it revokes every family except the one the request arrived on. Reuse this method, not a new one.
- **Device-wrap re-enrolment already exists — do not build a second one.**
  `POST /api/account/wraps` (`contracts/api/account.openapi.yaml:78-131`, `AddAccountWrapResource` →
  `KeyMaterialStore.addWrap`) already lets an authenticated caller add a `DEVICE`/`DEVICE_KEY` wrap. The web
  phase calls this endpoint after a successful change, on the same device, if the actor opts back into
  "remember me" — it is not a new field on the change-password request.
- **The Secret Key is never retained past unlock.** `web/src/app/vault.ts` holds only `masterKey` /
  `identityPrivate`; `grep -n secretKey web/src/app/vault.ts` returns nothing. Both entry points therefore
  prompt for it fresh — this is a client-side UI requirement, not a server-side one.

## Phases

- [ ] 1 contracts — `contracts/api/account.openapi.yaml`: add `POST /api/account/password`
      (`operationId: changePassword`, `security: [bearerAuth]`) and its request/response schemas.
      - `ChangePasswordRequest`: `passwordWrap` (reuse the existing `RewrittenWrap`/`RotatedWrap`-shaped
        object — `wrappedKey` + `kdfParams`, same base64 + `Size`/`Pattern` annotations as
        `contracts/api/account.openapi.yaml:766-781`'s `RotatedWrap`), `authPublicKey` (the new one, same
        shape as `RotateAccountKeysRequest.identityPublicKey`'s base64 annotation pattern), and an
        **optional** `attestationSignature` (base64 Ed25519 signature over the raw bytes of `authPublicKey`,
        produced with the OLD auth private key — absent on the forgotten-password entry point).
      - Response: `204` on success (nothing to report back — contrast `RotateAccountKeysResult`, which must
        report per-scope status; this flow touches no scope).
      - Failure shapes: `400 invalid_request` (malformed), `401` (missing/invalid/expired/revoked token, the
        generic shape every other endpoint here uses), `403 attestation_failed` (an `attestationSignature` was
        present but did not verify against the current `auth_public_key` — distinct from 401 because the
        caller *is* authenticated, they failed the *additional* old-password proof), `409
        password_change_unavailable` (no `PASSWORD` floor to rewrite — mirrors `RotateAccountKeysResource`'s
        `rotation_unavailable` shape at `contracts/api/account.openapi.yaml:445-452`).
      - Done when: `openApiGenerateSpecs` produces `ChangePasswordRequest`/the new `AccountPasswordApi`
        without error and `python3 scripts/validate_contracts_common.py` stays green (no new shared component
        drift).

- [ ] 2 backend — `backend/src/main/kotlin/com/veyq/app/features/account/password/` (new package, sibling to
      `recovery/` and `rotation/`).
      - `PasswordChangeStore.applyChange(userId, wrappedKey, kdfParams, authPublicKey): Boolean` — mirrors
        `RecoveryStore.applyReset:103-116` exactly for the floor + auth-key rewrite and the
        `DEVICE`/`DEVICE_KEY` delete; returns `false` (nothing written) if there is no `PASSWORD` floor row to
        update, same early-return shape.
      - `PasswordChangeHandler.change(userId, presentedAccessToken, currentAuthPublicKey, newAuthPublicKey,
        wrappedKey, kdfParams, attestationSignature)`:
        1. if `attestationSignature` is present, verify it against `currentAuthPublicKey` using
           `Ed25519Verifier.verify` (the same primitive `LoginHandler.verifySignature` already uses,
           `backend/src/main/kotlin/com/veyq/app/features/account/login/LoginHandler.kt:69-85`) — on failure,
           refuse with `attestation_failed`, write nothing;
        2. call `store.applyChange(...)` in one transaction; on `false`, refuse with
           `password_change_unavailable`;
        3. `sessions.revokeOtherFamilies(userId, presentedAccessToken)` — **never** an unconditional
           revoke-all; this is the one place this flow must NOT copy `RecoveryStore`'s behavior, since recovery
           has no session to spare and this flow does.
      - `PasswordChangeResource` (`@Authenticated`, mirrors `AccountRotationResource.kt`'s shape exactly:
        resolve `userId` from `identity.principal.name`, `presentedAccessToken` from
        `identity.getCredential(TokenCredential::class.java)?.token`, never from the request body).
      - Read the account's **current** `auth_public_key` (needed for the attestation check) the same way
        `LoginHandler.verify` does — `VeyqUser.find(...).authPublicKey`.
      - Done when: `./gradlew check` green, plus a new `PasswordChangeSliceTest` covering: deliberate change
        with a valid attestation (200/204, floor + authKey replaced, device wraps gone, other sessions
        revoked, **caller's own session still valid** — assert this explicitly, it is the one behavior that
        differs from `RecoveryStore`), deliberate change with a wrong attestation (`403`, nothing written —
        bite-test by flipping one byte of a valid signature and asserting the floor is byte-identical to
        before), forgotten-password change with no attestation field (200/204, same rewrite, no verification
        attempted), and no-`PASSWORD`-floor refusal (`409`, nothing written).

- [ ] 3 web — `web/src/features/account/changePassword/` (new, sibling to `recovery/` and `rotation/` if one
      exists — `find web/src/features/account -maxdepth 1 -type d` at implement time to confirm the sibling
      set before naming this).
      - A settings entry point ("Change password") reached from wherever
        [uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md)'s settings panel
        lives (`grep -rn "Change password\|changePassword" web/src/features/account` at implement time to
        confirm nothing already stubs this — the `_index.md` bare-title entry this CR replaces suggests it
        does not, but confirm before assuming).
      - Two forms behind one screen: a "current password" field is present by default (deliberate path) with
        a "I don't remember it" affordance that removes the field and skips signing the attestation
        (forgotten path) — both always ask for the Secret Key.
      - Data layer: derive the old auth keypair from the current password when present (reuse
        `deriveAuthKeypair` from `web/src/shared/crypto/v1.ts`, the same function login uses — do not
        re-implement), sign `authPublicKey`'s raw bytes with it, derive the new auth keypair + `PASSWORD` wrap
        from the new password + Secret Key (reuse the same wrap-building code path
        [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md)'s web
        slice already has for step 8 — locate it with
        `grep -rn "kekPasswordSecret\|PASSWORD.*wrap" web/src/features/account/recovery` before writing a
        second implementation).
      - On success, offer to re-enrol this device's `DEVICE`/`DEVICE_KEY` wrap via the existing
        `POST /api/account/wraps` call (reuse whatever web helper `uc.account.trust-device`'s slice already
        calls — `grep -rn "addAccountWrap\|/account/wraps" web/src/features/account` at implement time).
      - Done when: `npm run check` green, plus a unit test file covering both entry points' data-layer calls
        (attestation present vs. absent) and the re-enrol follow-up call.

- [ ] 4 coverage — `docs/coverage/features/account/use-cases/uc.account.change-password.yaml` (already
      scaffolded `status: planned` by the refine that produced this CR) — drop `status: planned`, replace the
      `gaps:` block with real `web`/`backend` refs for all six criteria once phases 2–3 land. Done when:
      `python3 scripts/validate_coverage.py` green with zero `gaps:` remaining on this file.

## Definition of done

Tests green across contracts (codegen succeeds), backend (`./gradlew check`, including
`PasswordChangeSliceTest`'s four cases), and web (`npm run check`). `docs/coverage/` updated per phase 4, every
criterion covered by a test, `python3 scripts/validate_coverage.py` green. Full trust-chain sweep green
(`for f in scripts/validate_*.py; do python3 "$f"; done`) — baseline: **28 guards, all green**, run before
phase 1's first edit (this CR adds no new guard, so 28 stays the count throughout). The one bite-test this CR's
own correctness rests on: the caller's own session survives phase 2's revoke — assert it by name, not by
absence of an error, per `changes/README.md` § *a bite test must tell "the guard held" apart from "nothing
ran"*.

## Retrospective

To be filled at close — what went wrong in planning and execution, root cause, one concrete "next time," and
the process/collaboration check (language, style, workflow rules, memory notes honored or not).
