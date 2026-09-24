---
id: cr-kdf-params-client-chosen
title: A client derives only with the Argon2id profile it writes — a served profile is checked, never obeyed
status: planned
satisfies: [uc.account.unlock]
adrs: ["0017", "0005", "0054"]
invariants: [operator-blind E2EE]
---

## Goal

Every unlock derives its KEK with Argon2id parameters the server serves beside the wrap, and
`v1.decodeKdfParams` checks nothing but the header length. So a server can make every unlock run up to ~4 GiB
and 2³² passes. That runs in a Web Worker with no timeout, and the worker's error fallback re-runs the same
parameters on the main thread, which freezes the tab. This CR makes the client derive only with the profile it
writes itself ([ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md) rule 5) and
refuse any other profile before it derives.

## Context & constraints

- **The rule:** ADR 0017 rule 5. The refine measured that no downgrade path exists. The auth keypair
  ([ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)) is derived with the local `ARGON` and no
  served parameters, and every served KEK mixes in the Secret Key or a device factor. The defect is the DoS.
  An exact profile was chosen over a ceiling because it is simpler and closes both.
- **Where served profiles enter:** `grep -rn 'decodeKdfParams' web/src admin-web/src packages/crypto/src`. The
  hits are the `PASSWORD` and `DEVICE` unlock, the cold credential, the recovery release, and the e2e bootstrap
  (test-only).
- **The profile the client writes:** `ARGON` in `packages/crypto/src/v1.ts`, with a 16-byte salt at every
  write site (`grep -n 'randomBytes(16)' packages/crypto/src/*.ts`). The `kdf_params` vector pins it, and the
  conformance test pins `ARGON` to that vector.
- **Independent** of every other #161 CR. It may land first.
- **Measured against** `main` @ `a494436` on 2026-09-24.

## Decisions

- **One check, at the one decode.** `decodeKdfParams`, or a checker beside it in `@veyq/crypto`, accepts a
  profile only if it equals `ARGON` field for field (`t`, `m`, `p`, `dkLen`) and has a 16-byte salt. Anything
  else throws a distinct `kdf_profile_refused` **before any Argon2id runs**. It is not a check per caller.
- **The refusal is not a wrong password.** Each caller surfaces it as the unlock failing, without telling the
  user their password or Emergency Kit was wrong. Map it to the nearest existing failure state. No new UI.
- **`argonWorker.ts`'s main-thread fallback stays.** Once the profile is bounded, it re-runs a 32 MiB
  derivation, which is what it exists for.
- **The existing conformance case that accepts a bare 16-byte header** (empty salt) is edited, not kept beside
  the new rule.

## Phases

- [ ] 1 crypto + web — the check, its refusal at each caller, the tests.

      **Bite (live defect, write it first and see it red):** the unlock path, served
      `{ t: 2³¹, m: 32768, p: 1, dkLen: 32 }` with a spy in place of Argon2id, calls the spy today. After the
      fix it rejects with `kdf_profile_refused` and the spy was never called. Control: exactly `ARGON` → the spy
      is called once. Add one negative per field (`t`, `m`, `p`, `dkLen`, salt length), each anchored on
      `kdf_profile_refused` and never on "it threw".
      · Done when: `npm run check` in `packages/crypto`, `web` and `admin-web` green.
- [ ] 2 docs + coverage — `uc.account.unlock` gains *"An unlock refuses a served key-derivation profile other
      than the one the client writes, before deriving"*, covered by the phase-1 bite. Delete ADR 0017 rule 5's
      *"Decided, not yet built"* sentence. Flip the row in
      [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Both phases done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- The phase-1 bite was seen red on the untouched tree.
- `grep -rn 'argon2id(' web/src admin-web/src packages/crypto/src` shows no derivation fed by a served profile
  that bypasses the check.

## Retrospective
