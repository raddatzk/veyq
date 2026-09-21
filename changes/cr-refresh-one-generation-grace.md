---
id: cr-refresh-one-generation-grace
title: A benign re-presentation of a refresh token rotates instead of killing the session
status: planned
satisfies: [uc.account.login]
adrs: ["0004"]
invariants:
  - Operator-blind — the rule reads only opaque hashes, an integer generation and a status; no new field,
    no new wire value, nothing about the device.
  - Reuse detection stays closed — a clone is still caught, at distance 0 or at distance >= 2.
---

## Goal

Today a refresh token that is presented once too often revokes the whole session family, and the client
produces that by entirely ordinary means: a refresh whose **response never reaches it** (the rotation is
already committed, so the browser's cookie holds the stale token and the next page load presents it), or
several authed calls that 401 together and each fire their own refresh. Both are measured, live, and end in
a full re-login plus the loss of the remember-me tiers. This implements [ADR 0004](../docs/decisions/0004-session-tokens.md)
§ *the one-generation grace* — a superseded token exactly one generation behind its family's `CURRENT`
rotates, re-minting under that same number; distance 0 and distance >= 2 still revoke — and, on the client,
gives refresh the shared in-flight promise it never had.

## Context & constraints

- [ADR 0004](../docs/decisions/0004-session-tokens.md) § *the one-generation grace* — the rule, its distance
  table, why it does not increment, and why the bound is not a time window.
- [uc.account.login](../docs/features/account/use-cases/uc.account.login.md) — owns the refresh criteria
  (`refresh-rotates`, `refresh-reuse-detection`, `unknown-refresh-dos-safe`).
- `contracts/api/auth.openapi.yaml` — the prose that states the old, unconditional clone rule.
- **`onboardTail.ts` is out of bounds for phase 3.** Its boxed comment (the `adopt` call) forbids
  `withRefresh`, a generic retry and any interceptor reaching that line. The single-flight must not.
  Locator that proves it was honoured: `git diff --stat` names no `onboardTail`.
- **H2 cannot bite the deadlock half.** The test profile is H2 and gap locks are an InnoDB construct, so the
  `SQLSTATE 40001` retry is bitten by injection, never by a real deadlock.

## Phases

- [ ] **1 contracts** — narrow the unconditional clone prose to the distance rule, including the two `adopt`
      sites where a second presentation is now fatal *at distance 0* rather than unconditionally.
      Locator: `grep -n "clone\|superseded" contracts/api/auth.openapi.yaml`.
      Description-only: no schema, no operation, no status code changes.
      · Done when: `npm run generate:api && npm run check` green in **both** `web/` and `admin-web/`, and
      `python3 scripts/validate_contracts_common.py` green. `./gradlew check` is unaffected (descriptions
      reach the generated server interface as javadoc only) — run it anyway at the end of phase 2.

- [ ] **2 backend** — the rule in `SessionService.refresh`, the `mint` change, and the retry.
      · Done when: `./gradlew check` green.
  - `refresh` reads the family's `CURRENT` row and branches on `current.generation - presented.generation`:
    `1` -> rotate, re-minting **at `current.generation`** and superseding the orphan; `0` and `>= 2` ->
    revoke, as today.
  - `mint` takes the generation to write rather than deriving it, so the grace path can re-use a number.
    Locator for its callers: `grep -n "mint(" backend/src/main/kotlin/com/veyq/app/features/account/login/SessionService.kt`.
  - A retry around the rotation transaction on MariaDB **1213 / SQLSTATE 40001**, bounded, so a retryable
    deadlock stops surfacing as a `500` from a public endpoint.
  - **Bite tests, by kind:**
    - **B1 live defect** — `refreshAfterALostResponseRotatesInsteadOfRevoking`: rotate once, *discard* the
      successor, present the original again; expect `200` and the family still `ACTIVE`. Expected first
      failing assertion on the untouched tree: the status code (`401` today).
    - **B2 live defect** — `concurrentRefreshOfOneTokenKeepsTheFamily`: three threads on a `CyclicBarrier`
      present one token; expect no `401` and the family `ACTIVE`. Idiom to copy:
      `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce` (`:93`), which is the tree's only
      `CyclicBarrier` and is aimed at the login challenge, not at refresh. Expected first failing assertion:
      the family status.
    - **B3 live defect** — `graceDoesNotIncrementSoTheNextStaleCallerIsStillAtDistanceOne`: present the same
      stale token **twice in sequence**; expect both `200`. This is the regression test for the ADR
      correction — an incrementing grace passes B1 and fails here. Expected first failing assertion: the
      *second* presentation's status.
    - **B4 live defect** — `theOrphanedSuccessorIsAClone`: after a grace rotation, present the successor the
      grace orphaned; expect `401` **and** the family `REVOKED`. This is distance 0, the new detector.
    - **B5 supersedes an existing test — NARROWED, not deleted.** `replayedSupersededRefreshRevokesFamily`
      (`LoginSliceTest.kt:156`) replays after **one** rotation, i.e. at distance 1, which is exactly the
      case that must now succeed. It is rewritten to chain three rotations and replay the oldest (distance
      2). The criterion it covers keeps its name; only the distance changes.
    - **B6 injection** — the retry. Lever: a supplier that throws `PessimisticLockException` on its first
      call and succeeds on the second; assert one retry and a `200`. Second lever, same seam: a supplier
      that throws every time; assert it gives up rather than looping.

- [ ] **3 web** — one shared in-flight refresh per tab, in `web/src/features/account/login/login.ts`.
      · Done when: `npm run check` green in `web/` and `admin-web/`.
  - The idiom **and its test shape already exist one directory over**: `web/src/features/memory/placeName.ts:51`
    and `placeName.test.ts:35` (*"shares one in-flight request between callers that arrive together"*). Copy
    the shape; do not invent a second one.
  - Delete `admin-web`'s `withRefresh` — locator: `grep -rn "withRefresh(" admin-web/src | grep -v "export async function"`
    returns no invocation, only the definition and two doc-comment mentions.
  - **B7 live defect** — two `withRefresh` calls whose inner call rejects with a 401 at the same time issue
    **one** `POST /api/token/refresh`. Lever: count calls on the mocked `refreshSession`. Red today (two).

- [ ] **4 coverage** — `docs/coverage/features/account/use-cases/uc.account.login.yaml`: narrow
      `refresh-reuse-detection`, add the grace and the distance-0 criteria, add the web single-flight ref.
      · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` green; `npm run check` green in `web/` and `admin-web/`; every trust-chain guard green via
the CI glob itself — `for f in scripts/validate_*.py; do python3 "$f"; done` — compared against a run of the
same command on the CR's base commit, not against a remembered tally; `docs/coverage/` updated for
`uc.account.login` with `python3 scripts/validate_coverage.py` green.

No permanent guard is promoted. The structural invariant this rests on ("exactly one `CURRENT` row per
family") is already enforced by the code's own shape — `mint` has two call sites and rotation supersedes
before it mints — and B2/B3/B4 are the behavioural proof; a validator over it would assert a property of
runtime rows, which the buildless glob cannot read.

## Retrospective

<!-- filled at close -->
