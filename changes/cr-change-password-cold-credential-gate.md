---
id: cr-change-password-cold-credential-gate
title: Change-password proves a cold credential — today it is the bypass of ADR 0060's gate
status: planned
satisfies: ["uc.account.change-password"]
adrs: ["0060", "0003", "0054", "0026"]
invariants:
  - "operator-blind E2EE — the Secret-Key proof runs on-device; the server verifies only what it can hold, an Ed25519 signature over public keys"
  - "ADR 0060 Decision 2 — a flow that mints a cold credential may not be reachable without the material the attacker lacks"
---

## Goal

`POST /api/account/password` mints a **new cold credential** (a `PASSWORD` floor plus its `auth_public_key`)
while proving possession of **neither** of the two factors that floor is made of. The current password is
optional by one click, and the Secret Key that the new floor is wrapped under is never checked against the
account at all. Both gaps are closed here: the attestation becomes **required** — the one half the server can
verify itself — and the client runs the same live cold-credential proof rotation already runs before it writes
anything. The "I don't remember my current password" entry point goes away with them; the forgotten case is
[uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md), which exists for
it and does not hand the escape hatch to the attacker.

## Context & constraints

**This is a defect against an existing decision, not a new one.** ADR 0060 Decision 2 gates rotation on
material *"the attacker lacks"* — the Secret Key, which a linked device never receives — and says in its own
words that without that gate *"building rotation makes veyq worse than not having it"*. Change-password hands
that attacker the missing material:

1. A `DEVICE_KEY` (remember-me) holder — a stolen unlocked phone, the case
   [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) already accepts as
   content-readable — opens this screen, clicks *"I don't remember my current password"*, and types a new
   password and **their own** Secret Key.
2. `changePassword` wraps the victim's real Master Key under that foreign KEK
   (`web/src/features/account/changePassword/changePassword.ts:60`); the new
   `auth_public_key` is derived from the password **alone** — the Secret Key does not enter it
   (`web/src/features/account/changePassword/changePassword.ts:52`).
3. The server cannot check either — it is holding ciphertext — and applies
   (`backend/src/main/kotlin/com/veyq/app/features/account/password/PasswordChangeHandler.kt:73`),
   sparing only the attacker's own session.
4. The attacker now **holds a cold credential** for the account, so rotation's own gate — the
   `proveColdCredential` at `web/src/features/account/rotation/rotateKeys.ts:79` — passes for
   them, and rotation rewrites `PASSWORD` **and** `RECOVERY` under their Secret Key over a fresh Master Key
   (`web/src/features/account/rotation/rotateKeys.ts:142`). The victim's Emergency Kit is
   now worthless and `uc.account.recover-access` is gone. Permanent, irreversible.

Step 4 is what makes this urgent rather than untidy: the escalation ends in a state no ADR accepts.

Everything above is the same mechanism read two ways, which is why the fix is small:

- `web/src/features/account/rotation/rotateKeys.ts:79` — `proveColdCredential` **already
  is** the missing check, comment and all: derive the floor KEK from what was typed *now*, open the stored
  `PASSWORD` wrap, and require the result to equal the Master Key the vault holds. It is not new work; it is
  work one screen does and its sibling does not.
- [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) §3–4 owns that derivation.
- [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) — why the attestation is the only half a
  blind server can verify: it holds `auth_public_key`, so an Ed25519 signature over the new key is checkable
  without learning a password.
- [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md) + **the `RECOVERY` wrap is
  withheld from `GET /api/account/key-material`**
  (`backend/src/main/kotlin/com/veyq/app/features/account/keymaterial/KeyMaterialHandler.kt:43`,
  one `grep`) — this is why the forgotten path cannot be repaired in place: without the current password there
  is nothing on the client that the typed Secret Key can be checked against. Removing it is not a preference,
  it is the only shape available without a new server-side decision (see *Open*).
- [uc.account.recover-access](../docs/features/account/use-cases/uc.account.recover-access.md) §A2 — on an
  instance with no mail transport recovery answers `503 mail_unavailable`
  ([ADR 0055](../docs/decisions/0055-mail-is-a-precondition-not-a-capability.md)). The cost of removing the
  forgotten path lands there and nowhere else; see *Open*.
- `web/src/shared/ui/SecretKeyField.vue` — the shared Secret-Key control, landed by `cr-shared-secret-key-field`
  (since closed and deleted) and already the panel's input. It warns on a kit carrying a **different account's**
  e-mail and deliberately never blocks, because only the wrap can decide whether a key is right
  ([uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) § *A kit from the wrong account
  warns*). This CR is the other half of that sentence: it supplies the wrap that decides. Nothing about the
  control changes here.

**Not in scope:** the attestation's replay shape (it signs the new key, not a nonce — argued and unchanged in
[uc.account.change-password](../docs/features/account/use-cases/uc.account.change-password.md) § Quality
notes), and rotation itself, which is correct today.

## Phases

- [ ] 1 contracts — `attestationSignature` moves into `ChangePasswordRequest.required`; its description and the
      operation's 403 stop saying "absent on the forgotten-password path" ·
      Done when: `npm run generate:api` + the contracts guards green. **`web`'s `npm run check` is red from
      here until phase 3** — the generated model makes the field non-optional and
      `web/src/features/account/changePassword/changePassword.ts:53` still types it
      `string | undefined`. That is the tightening working; do **not** stub it green.
- [ ] 2 backend — `attestationSignature` becomes non-null through `PasswordChangeResource` →
      `PasswordChangeHandler`; the `if (attestationSignature != null)` branch and its "absent on the forgotten
      path" reasoning go. Slice tests below ·
      Done when: `./gradlew check` green in `backend/`
- [ ] 3 web — extract `proveColdCredential` out of `rotateKeys.ts` into a shared account-level proof and call it
      first in `changePassword`; `currentPassword` becomes required; the panel loses `forgotten`, the three
      `forgotAction`/`forgottenNote`/`rememberAfterAll` i18n keys (EN+DE) and gains a link to recovery; the
      completed change calls `setUnlocked(…, 'cold')` for the reason
      `web/src/features/account/rotation/rotateKeys.ts:175` already gives ·
      Done when: `npm run check` green in `web/`
- [ ] 4 docs — `uc.account.change-password`: A1 and the "two entry points, one mechanism" note go; the
      precondition gains the proof; the postcondition **"the Emergency Kit is still valid"** stops being an
      assumption about what the actor typed and becomes something the flow checked. ADR 0060 gets one line
      naming change-password as inside Decision 2's scope ·
      Done when: `python3 scripts/validate_doc_links.py` + `validate_feature_format.py` + `validate_adr_*.py`
      green
- [ ] 5 coverage — `uc.account.change-password.yaml`: `same-mechanism-two-entry-points` and
      `forgotten-path-needs-no-attestation` are **deleted, not gapped** (the behaviour is gone, not untested);
      `attestation-proves-old-password-when-present` loses "when present"; `nothing-else-re-encrypted`'s
      Emergency-Kit half gets the proof's test ref; one new criterion for the Secret-Key proof ·
      Done when: `python3 scripts/validate_coverage.py` green

Locators (run, don't estimate — and run the **baseline** before the first edit):

```
ls scripts/validate_*.py | wc -l && for g in scripts/validate_*.py; do python3 "$g" >/dev/null 2>&1 || echo "RED: $g"; done
grep -rn "forgotten\|forgotAction\|forgottenNote\|rememberAfterAll" web/src
grep -rn "attestationSignature" contracts backend/src web/src packages
grep -rn "proveColdCredential\|unlockKekAsync" web/src "--include=*.ts" | grep -v test
```

The second and third are the same set keyed differently — the i18n/UI side and the wire side. Where they
disagree is a site one of them does not know about; read it before deleting anything.

## Bite tests

Three, each with its method named (§ *the CR says PER bite-test how it will be bitten*):

1. **Live defect, backend.** Post a change **without** `attestationSignature` and assert the `PASSWORD` wrap is
   byte-identical to before. Today it applies, so this reddens on the untouched tree — write it before phase 2
   and record the red. `PasswordChangeSliceTest.changeBody(attestation = null)`
   (`backend/src/test/kotlin/com/veyq/app/features/account/password/PasswordChangeSliceTest.kt:109`)
   already posts raw JSON, so the request survives the contract tightening and reaches the server.
   `forgottenPasswordChangeWithNoAttestationAppliesTheSameRewriteWithoutVerifying` is its inverse and is
   deleted, not adapted. **Read which assertion reddens:** a red on `seedAccount`/`seedWrap` is setup, not the
   bite.
2. **Live defect, web.** Call `changePassword` with a Secret Key that does not open the current floor and
   assert `accountPasswordApi().changePassword` was **not called**. Today the mock records a call carrying a
   wrap the account can never open — that call is the defect, and `not.toHaveBeenCalled()` is what reddens.
   Ordered before phase 3's panel edit, against the least-changed tree.
3. **Injection, web.** The proof must compare the opened Master Key against the one the vault holds, not merely
   succeed at AEAD — the half that catches a floor rewritten underneath the session. Lever: in the extracted
   proof, replace the `matches` comparison with `true`, run, restore (snapshot-and-restore; never
   `git checkout`). Green from the first run by construction, so it is an injection — and it is the property
   most likely to be dropped in the move out of `rotateKeys.ts`, which is why it is bitten rather than trusted.
   The same injection must redden **both** callers' tests; if only rotation's goes red, the extraction did not
   actually reach change-password.

## Open

**One, and it is a product call, not an implementation detail.** Removing the forgotten path costs exactly one
population: someone on a **mail-less instance** who has forgotten their password and is still signed in via
`DEVICE_KEY`. Today they self-serve; afterwards they have no route back, because
[uc.account.recover-access §A2](../docs/features/account/use-cases/uc.account.recover-access.md) refuses with
`503 mail_unavailable`.

The recommendation is to accept it: that person is already one sign-out from a permanent lockout on such an
instance, so this removes a lucky escape hatch rather than creating a lockout class — and the same hatch is the
attacker's takeover ramp. The alternative is to make the Secret Key checkable **without** the current password,
which needs one of two server-side decisions this CR deliberately does not take:

- release the `RECOVERY` wrap to an authenticated session (amends
  [ADR 0054](../docs/decisions/0054-password-recovery-two-factor-release.md)'s withholding, and drops cold
  unlock from two factors to one), or
- store a commitment over the Secret Key (new key-derived material server-side — an operator-blind call).

Either is its own ADR and its own CR. **Decide the recommendation before phase 3**; phases 1–2 hold either way.

## Definition of done

`./gradlew check` green in `backend/`, `npm run check` green in `web/`, every `scripts/validate_*.py` green
against the baseline the first locator produced, and `docs/coverage/` carrying the new criteria. The three bite
tests are run in the order above, with the live-defect reds recorded before their fixes.

No permanent guard is promoted. The structural claim here — *the cold-credential proof runs on every screen
that mints a cold credential* — has exactly two sites, both named in the fourth locator, and a third would
arrive with its own CR and its own review. A guard over two call sites is ceremony; the bite tests are the
proof (§ *promote one-shot proofs*, weighed and declined).

## Retrospective

(filled at close)
