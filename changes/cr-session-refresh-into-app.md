---
id: cr-session-refresh-into-app
title: Session refresh moves out of the login feature into app/
status: planned
satisfies: [uc.account.login]
adrs: ["0004"]
invariants: []
---

## Goal

`withRefresh` is session infrastructure that every feature needs: run an authed call, refresh once on a 401,
retry. Yet it lives in `features/account/login/login.ts`, together with `refreshSession` and its single-flight
promise. So every feature that makes an authed call imports the login feature, which accounts for 13 of the 44
cross-feature import pairs measured at refine. One of those imports closes a cycle today
(`login.ts` ↔ `rememberMe/deviceKeyWrap.ts`, which imports `withRefresh` back from `login`). This CR moves the
refresh half, `refreshSession`, its in-flight promise and `withRefresh`, into a new `web/src/app/sessionRefresh.ts`.
The move changes no behaviour. `login` and `logout` stay where they are.

This was first planned as the prerequisite for inbox #168's sign-out. It no longer is: [ADR 0070](../docs/decisions/0070-a-session-ends-with-a-new-document.md)
ends a session from a module that imports no feature, so no cycle is at stake. It stays a CR of its own because
the owner decided the move on its merits (inbox #173 defers it to #168), and because `cr-one-sign-out-path` then
edits the refresh path in its new home rather than in a file it is about to leave.

## Context & constraints

- **Why a new module and not `app/session.ts`:** `api/client.ts` imports `app/session` for the bearer token
  (`grep -n "app/session" web/src/api/client.ts`). The refresh calls `tokenApi()` from `api/client`, so
  putting it into `session.ts` would create the cycle `session → client → session`.
- **What moves:** `refreshSession`, the private rotation it wraps, the `inFlight` promise and its comment
  (the single-flight argument, [ADR 0004](../docs/decisions/0004-session-tokens.md) § the one-generation
  grace), and `withRefresh`. The refresh behaviour on a rejection stays exactly as it is: drop the tokens and
  rethrow. Changing it is `cr-one-sign-out-path`'s job.
- **The sites.** Importers, source and tests: `grep -rln "withRefresh\|refreshSession" web/src`.
  **The trap is the mocks:** `grep -rn "vi.mock(.*login/login'" web/src`. About a dozen tests mock the whole
  login module to stub `withRefresh`. After the move, a mock left on the old path stubs nothing, and the real
  `withRefresh` runs. That is not a red you can count on: a test can go on passing for the wrong reason. Each
  one moves to the new path, and a mock that *also* stubs `login`/`logout` is split into two mocks.
- **Tests move with the code.** The refresh cases in `features/account/login/login.test.ts` move to
  `app/sessionRefresh.test.ts`. The coverage refs that cite them follow:
  `grep -rn "login/login.test.ts" docs/coverage`. `uc.account.login`'s `refresh-rotates` and
  `refresh-is-single-flight-per-tab` point at the new file. The `login`/`logout` criteria keep the old one.
- **Out of scope:** the other cross-feature imports, and the `activeSpace ↔ treeStore / coverStore /
  scopeRotation*` cycles. Those belong to inbox #173, which decided deliberately not to chase them.

## Phases

- [ ] 1 web — the move, the importers, the mocks, the tests, and the coverage refs.
      · Done when: `npm run check` is green, and these two locators show the move is complete:
        - `grep -rn "withRefresh\|refreshSession" web/src/features/account/login/login.ts` finds nothing;
        - `grep -rn "from '.*account/login/login'" web/src | grep -v '\.test\.' | grep -c withRefresh` is 0.

## Definition of done

`npm run check` is green, the two locators above are empty, and `python3 scripts/validate_coverage.py` and the
trust-chain are green. **No permanent guard:** nothing structural is being asserted beyond *"it lives here
now"*. A feature that later hand-rolls its own refresh would be a new duplication, not this one coming back.
There is no import-cycle guard to add either: `web/eslint.config.js` loads no import plugin, and the cycles #173
decided to leave would make one red on arrival.

## Retrospective

