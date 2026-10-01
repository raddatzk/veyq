---
id: cr-crypto-mutation-gate-green
title: The shared-crypto mutation gate is green on main again, and says what it measured
status: planned
satisfies: []
adrs: ["0045"]
invariants: ["operator-blind E2EE everywhere"]
---

## Open for the owner

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Kill the mutants, or lower the break?** The row asked which one. **Recommendation: kill them, keep
   `break: 95`.** Measured below, 40 of the 44 undetected mutants are real assertion gaps in the package's own
   suite. They include whole exports that no package test calls (`hybridSecret`, `hybridPublic`, both identity
   deserializers) and the drop-key decoder's bounds checks, which landed today (`8b1356e`) into a gate that was
   already red. A red gate cannot show a new regression, and that is how those five got in. A lower break would
   make that the permanent state.
2. **The four mutants that are not assertion gaps: `// Stryker disable` with a stated reason, or leave them as
   score headroom?** **Recommendation: disable each one with its reason on the line**, as `emergencyKit.ts:38`
   already does. `config.thresholds`' own comment keeps the 5-point margin for a *future* equivalent. Four
   known ones spending it today would leave a single new survivor able to red the gate.
3. **Error-message literals (`accountBirth.ts:52`, `seal.ts:200`, `v1.ts:515`): assert them, or disable
   them?** **Recommendation: assert them.** It is cheap, and it is how `82de829` took the kit codec to 100 %.
   For `readFixedLp` (below) it also has a point: a test that matches only `/drop key/` cannot tell *truncated*
   from *malformed* from *trailing bytes*, and that looseness is why two of its bounds mutants survive.
4. **How does the gate stay green with no CI to run it?** `mutation.yml` is PR-only and path-gated, and there
   is no remote, so it has never run. Each crypto CR since the package split landed without it. **Recommendation:
   one sentence in `web/CLAUDE.md`'s crypto bullet, the place that already owns `npm run mutation`: a commit
   that touches `packages/crypto/src` records the gate's exit code.** Adding it to `npm run check` is
   rejected: 3½ minutes per check, measured.

## Goal

Inbox #184. `npm run mutation` in `packages/crypto` exits 1 on untouched `main`, so it has no signal. After this
CR it exits 0 on `main`. Every mutant it still does not kill is either killed by a direct test in the package or
disabled at its line with the reason it cannot be killed. The config's and the manifest's *"100 %"* claims
are replaced by what the run measured.

## Context & constraints

- [ADR 0045](../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md): the gate, its scope and its
  break. `packages/crypto/stryker.config.mjs` and `stryker.mutate.json` carry the stale claims.
- **Measured on** `faf9ef4`, in worktree `worktree-agent-a988acad05eff2335`, on 2026-09-28, with node v23.11.0.
  Command: `cd packages/crypto && npx stryker run stryker.config.mjs --force`, which took 3 min 26 s. Re-run
  it. **Stryker mutates the source in place**, so run nothing else against `packages/crypto` while it runs
  (`web/CLAUDE.md`).
- **Results.** The total is **90.48 %** and the exit is **1**. Of 462 mutants, 411 were killed, 7 timed out,
  22 survived and 22 had no coverage.

  | File | Score | Survived | NoCoverage |
  |---|---|---|---|
  | `v1.ts` | 84.69 % | 8 | 22 |
  | `seal.ts` | 92.90 % | 12 | 0 |
  | `accountBirth.ts` | 96.00 % | 1 | 0 |
  | `primitives.ts` | 97.44 % | 1 | 0 |
  | `emergencyKit.ts` | 100 % | 0 | 0 |

  The row's per-export survivor list is stale. `authSign`, `authVerify`, `sha256Digest`, `sharesPwToken`'s arrow
  and `identityPublicFromPrivate` are killed today, killed by the tests that later crypto CRs added.
- **The row's hint is refuted: the survivors are not a per-test-mapping artefact.** The same run with
  `--coverageAnalysis all` (every test against every mutant) returns the same set, 23 survived and 22
  NoCoverage (90.26 %). The hand injection that went red in `cr-admission-is-a-key-holder-act` is the *whole*
  condition of `seal.ts:175`, which Stryker also kills. The survivor on that line is a different mutant, the
  sub-condition `typeof value !== 'string'` → `false`. Several mutants share each of those lines, and the two
  were taken for one.
- **There is ONE runner artefact, and it is a different one.** `primitives.ts:12` `'NFC'` → `""` is reported
  *Survived* with **`testsCompleted: 0`**. Injected by hand, `s.normalize('')` throws a `RangeError` at module
  load, 4 of 6 test files fail to import, and vitest exits 1. Stryker's vitest runner counts a file that fails to
  import as zero failing tests. The mutant is killed in fact and reported as surviving.
- **The remembered 100 %.** It was measured in `web/`, before `d9ef2e9` moved `v1.ts` into the package, at
  134 mutants. `v1.ts` now has 196 mutants. The row's hypothesis still holds for part of it: the uncovered
  exports are called from 8–10 `web/` + `admin-web/` files each, and from no package test (`grep -rlw hybridPublic
  web/src admin-web/src`). That part is **inferred** and was not re-measured in `web/`. It does not change
  the fix.

## Inventory

The locator is the report. After a `--force` run:

```
jq -r '.files | to_entries[] | .key as $f | .value.mutants[]
  | select(.status=="Survived" or .status=="NoCoverage")
  | "\(.status)\t\($f):\(.location.start.line)\t\(.mutatorName)\ttests=\(.testsCompleted // 0)"' \
  packages/crypto/reports/mutation/mutation.json
```

At refine, it sorted into four kinds (line numbers are from `faf9ef4`):

| Kind | Where | What kills it |
|---|---|---|
| **No package test calls it** (NoCoverage 21, Survived 2) | `v1.ts` `deserializeIdentityPublic`/`deserializeIdentityPrivate` (464–486, 18 mutants); `hybridSecret`/`hybridPublic` (399/406: ObjectLiteral NoCoverage + ArrowFunction survivor each); `sharesPwToken`'s params object (107) | A round trip `serializeIdentity* → deserializeIdentity*` against `generateIdentity()`, plus a truncated and an over-long input refused. `hybridPublic(priv)` must equal the identity's public half, and `openAs` under `hybridSecret(priv)` must open an `asymWrap` to `hybridPublic(priv)`. `sharesPwToken` with small Argon params must return 32 bytes that differ from the same call with another salt. |
| **Asserted too loosely** (Survived 11) | `v1.ts:175` `email.trim()`; `v1.ts:515/518` `readFixedLp` bounds (5); `seal.ts:135/136` the `^`/`$` anchors of `CANONICAL_UUID`/`SHARE_ID` (4); `seal.ts:137` `'ORIGINAL'`/`'PREVIEW'` | `deriveAuthSalt(salt, '  Alice@Example.org ')` equals the vector's output. A per-mangle *exact* message (`truncated` / `malformed` / `trailing bytes`), including a buffer cut inside a length prefix. An id and a share id with a leading and a trailing extra character are refused. A context carrying each of the four roles is accepted. |
| **Message literal** (Survived 2, NoCoverage 1) | `accountBirth.ts:52`, `seal.ts:200` (the `', '` of the extra-field list), `v1.ts:515`'s template | Assert the message: two extra fields name both, joined. |
| **Not killable** (Survived 4) | `seal.ts:161/166/175` `typeof … !==` → `false`: **equivalent**, because `Number.isSafeInteger` and `ROLES`/`SCOPE_KINDS.includes` already reject every non-number / non-string. `primitives.ts:12` `'NFC'`: **runner artefact** (above) | `// Stryker disable next-line ConditionalExpression: <reason>` / `StringLiteral: <reason>`, per Decision 2. |

`seal.ts:151/156/188` (`typeof value !== 'string'` in front of a *regex* `.test`) look like the equivalents but
are **not**. `.test` coerces its argument, so `new String(uuid)` passes the regex, and `utf8()` then encodes it
like the primitive. They are killed by refusing a boxed string (Decision 1). They count in the "asserted too
loosely" class, not in the four.

## Decisions

1. **Kill in the package, not in `web/`.** The gate's runner is the package's own vitest, and a `web/` test can
   never reach it (the manifest's `_comment` says why). New cases go into `v1.conformance.test.ts` where they pin
   a vector, and next to the code otherwise: `dropKey.test.ts` for `readFixedLp`, a new `identity.test.ts`
   for the identity codec and the hybrid halves. **The Argon cost is real**, about 2.6 s per production call
   (`accountBirth.ts`'s manifest note). So a new Argon-backed case passes explicit small params, the way
   `v1.conformance`'s KEK cases pass each vector's own `params`. Otherwise Stryker pays that cost once per
   mutant.
2. **Disable with a reason, per line, only for the four measured "not killable" mutants.** Each reason states
   the measured fact: for an equivalent, *why* no input distinguishes it; for `'NFC'`, *"reported Survived with
   0 tests completed: the mutant throws at module load, which the vitest runner does not count as a failure"*.
   A fifth disable needs its own measurement. Never use a file-level disable.
3. **Keep `break: 95`.** Replace the config's comment and the manifest's *"v1.ts 100 %"* with the measured
   numbers of the green run. Delete the sentence *"there is none"* about a known gap, and the *"Bite-proven
   (in web/, before the move)"* paragraph. Re-bite it here instead (Definition of done).
4. **One sentence in `web/CLAUDE.md`** (Q4), if the owner takes that recommendation.

## Phases

- [ ] 1 crypto: Decisions 1–3, in that order.
      1. Run the baseline first and paste its table: exit 1, 90.48 %, or whatever `main` then says.
      2. Write the kill tests one class at a time. After each class, re-run with `--force` (the incremental
         cache keeps stale verdicts: `stryker.config.mjs`' own comment).
      3. Then add the four disables. Then fix the comments.

      · Done when: `npm run mutation` exits **0** with `--force`, and `npm run check` in `packages/crypto` is
      green. **No survivor or NoCoverage mutant remains without a disable comment on its line**; the `jq`
      above prints nothing. `web/` and `admin-web/` `npm run check` are green; no source changes there are
      expected.

## Definition of done

- Before the first edit, run the guard baseline and paste it: `for f in scripts/validate_*.py; do python3 "$f";
  done`. It stays green.
- **Red first:** the Phase 1 baseline run's exit 1 is recorded here.
- **Bite the gate.** Use snapshot-and-restore (copy the file aside and copy it back, never `git checkout`),
  with two levers:
  - **(a)** Delete one new kill test, the identity-codec round trip. The run must drop below 95 and exit 1.
  - **(b)** Remove one disable comment, on `seal.ts:175`. The run must list that mutant as Survived.

  Lever (b) proves each disable is load-bearing, not decorative.
- `scripts/mutation_meta_check.py` passes on the green report. Run it the way `mutation.yml` does.
- Inbox #184 → done. This file is deleted after harvest.

## Retrospective

_(filled at implement)_
