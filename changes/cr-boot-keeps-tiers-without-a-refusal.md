---
id: cr-boot-keeps-tiers-without-a-refusal
title: A boot refresh that fails without refusing the session keeps the remember-me key and the session vault
status: planned
satisfies: [uc.account.sign-out, uc.account.remember-this-device]
adrs: ["0070", "0005", "0004"]
invariants: [operator-blind E2EE]
---

## Open for the owner

1. **What does a boot that could not ask the server show?** The tiers stay either way; this is only about the
   screen.
   - (a) The sign-in page with the existing *"This instance can't be reached right now. Please try again."*
     banner (`common.unreachable`, rendered by `AppShell.vue` in the public shell). A reload is the retry, and
     the reload restores the session with no prompt once the instance answers.
   - (b) A dedicated "reconnecting" screen that re-runs `boot()` on its own.
   - (c) The bare sign-in page, as today, minus the deletion.

   **Recommendation: (a).** It reuses a state and a string that exist, it tells the truth (the instance did not
   answer), and it does not invite the user to type a password into an instance that is down. (b) is a new
   screen and a retry loop for an outage that a reload already covers; (c) hides why the prompt appeared, which
   is exactly what makes a user re-enter a password they did not need to.

Nothing else is open: the line itself is already decided (uc.account.sign-out E1, ADR 0070 Decision 3); this CR
extends it to the one caller it did not reach.

## Goal

At start-up `boot()` in `web/src/app/boot.ts` revives a remembered session with `refreshSession()` and, when that
fails **for any reason**, deletes both local convenience tiers — the session vault and the remember-me key. A
refresh that failed because the instance was restarting, a proxy answered 502, or the uplink dropped says nothing
about the session, yet it costs the user the key: the next start asks for the password (and on a remember-me
device the key is gone for good until they sign in again). This CR makes boot drop the tiers **only when the
server refused the session** — the same predicate (`isSessionRefusal`) that already decides it for a tab that
holds a session — and shows everything else as *unreachable*, keeping the tiers for the next boot.

## Context & constraints

- **The rule it extends:** [uc.account.sign-out](../docs/features/account/use-cases/uc.account.sign-out.md) E1
  (only an authoritative rejection ends a session; not a network failure, a 5xx, a 408/429, or a
  `403 tenant_suspended`) and E2 (a tab with no session runs no ending);
  [ADR 0070](../docs/decisions/0070-a-session-ends-with-a-new-document.md) Decisions 3 and 4. Decision 3 is
  phrased for a tab that *holds* a session; boot holds none yet, so nothing written decides it (inbox #186).
- **What the tiers are:** [uc.account.remember-this-device](../docs/features/account/use-cases/uc.account.remember-this-device.md)
  (E2: *"no silent self-wipe on a single server response"*); storage names in `web/src/app/convenienceTiers.ts`.
- **Web only.** No contract, no backend: the refresh endpoint's answers are unchanged, only the client's reading
  of them at boot.
- **Measured against** `main` @ `faf9ef4` on 2026-09-28 (each line re-runnable):
  - measured (`sed -n 48,58p web/src/app/boot.ts`): `refreshSession().then(() => true).catch(() => false)` —
    every rejection becomes `restored = false`, and the `else` branch runs `clearSessionVault()` and
    `clearDeviceKey(false)`. The rejection value is discarded, so no status reaches a decision.
  - measured (`grep -n 'export async function isSessionRefusal' -A3 web/src/app/sessionRefresh.ts`): the
    predicate exists and is exported — `isAuthoritativeRejection(e) && !(await isTenantSuspended(e))`.
  - measured (`grep -n 'isAuthoritativeRejection' -A4 web/src/shared/httpError.ts`): a 4xx other than 408/429;
    status `0` (a non-HTTP failure) and every 5xx are not rejections.
  - measured (`sed -n 56,66p web/src/app/sessionRefresh.ts`): `rotateSession` rethrows the original error after
    its own refusal handling, so boot can read the same value.
  - measured (`cat web/src/app/boot.test.ts`): boot's suite covers only the setup-status branch; no case reaches
    the refresh branch.
  - **Every tier-drop site, and whether it is keyed on a failure that says nothing about the session** —
    `grep -rn 'clearDeviceKey\|clearSessionVault\|dropConvenienceTiers\|dropRememberKey' web/src --include='*.ts' --include='*.vue' | grep -v '\.test\.'`:
    - `app/boot.ts` — **the defect** (any refresh failure).
    - `app/sessionEnd.ts` — the ending; reached from a refusal, an explicit sign-out or another tab's ending.
      Correct.
    - `features/account/login/signOut.ts`, `features/account/trustDevice/DeviceTrustControl.vue` — explicit user
      actions. Correct.
    - `views/LoginView.vue` (125, 133) — a successful sign-in replacing the other tier by the toggle's choice.
      Correct, and it is what reconciles a tier this CR keeps: a later password sign-in with *remember me* off
      runs `clearDeviceKey()` (server wrap and local key), with it on `enrolDeviceKey` replaces the prior wrap.
    - `features/account/unlock/unlock.ts:170` — drops the session vault when the cached Master Key does not fit
      the served key material. Keyed on a successful response, not a failure. Out of scope.
    - `features/account/rememberMe/deviceKeyWrap.ts` `restoreDeviceKey` — drops the local key when a served
      wrap does not open (tamper). Keyed on a response, not a transport failure; see *Out of scope*.
  - Second angle, keyed on the swallow rather than the drop —
    `grep -rn "catch(() => false)" web/src/app web/src/features/account --include='*.ts' | grep -v '\.test\.'`:
    the boot line is the one that feeds a deletion.
- **Out of scope, measured and left:**
  - `restoreVaultFromCache()` after a *successful* refresh swallows a failed key-material fetch
    (`sed -n 52,53p web/src/app/boot.ts`): the tab stays locked for that load, but nothing is deleted, so the
    next boot restores. Not destructive.
  - `restoreDeviceKey` dropping the key on a wrap that does not open: a hostile server can end the session with
    a 401 anyway and the tiers go with it (E1), so this hands it no new lever — but uc.account.remember-this-device
    E2's wording ("no silent self-wipe on a single server response") reads broader than the code. Proposed as a
    separate inbox row, not fixed here.

## Decisions

- **Boot drops the tiers only on `isSessionRefusal(e)`.** Boot keeps the refresh's rejection value and asks the
  existing predicate — one definition of *the server refused this session*, shared with E1, never a second copy
  of the status list in boot.
- **Anything else is *unreachable*.** The tiers stay; `setUnreachable(true)` (Open question 1, recommendation
  a). A `403 tenant_suspended` answered to the refresh sets the *suspended* notice instead, exactly as the
  setup-status branch above it already does (`isTenantSuspended` → `setSuspended(true)`), because it is the same
  answer from the same domain.
- **No ending at boot, as before.** A refusal at boot still drops the tiers locally and still runs no ending
  (E2): `rotateSession` already calls `endSession()`, which is a no-op in a tab that holds no session.
- **The dev auto-unlock still runs after a non-refusal** — it is DEV-gated and harmless; nothing changes there.
- **ADR 0070 amendment (phase 0).** Decision 3 gains one sentence: *"At boot, before a tab holds a session, the
  same line decides whether the device's local convenience tiers are dropped: only a refusal drops them; a boot
  that could not get an answer keeps them and says the instance is unreachable."*

**Why keeping the tiers is safe.** The rule the drop enforces is that no tier outlives *its session*. A session
the server has not refused has not ended; the next boot that reaches the server either restores it or receives
the refusal and drops the tiers then. A hostile server gains nothing: it could always answer 401 to drop the
tiers (by design), and answering 503 instead only leaves the device as it was. A device that never reaches its
instance again keeps a key that opens nothing without the server-held wrap.

## Phases

- [ ] 0 docs — ADR 0070 Decision 3 amendment (the sentence above).
      · Done when: `python3 scripts/validate_adr_citations.py` and `python3 scripts/validate_doc_links.py` green.
- [ ] 1 web — `boot()` keeps the rejection, asks `isSessionRefusal`, drops the tiers only then, otherwise marks
      unreachable (or suspended). Locator: `grep -n 'refreshSession\|clearDeviceKey\|clearSessionVault' web/src/app/boot.ts`.

      **Order: red first.** The defect is live, so the bites are written and seen red on the untouched
      `boot.ts` before it changes (`changes/README.md` § where the defect is still live).

      **Bites** (in `web/src/app/boot.test.ts`, one harness for all):
      - **Harness.** `getSetupStatus` resolves `{ setupRequired: false, … }`; the refresh endpoint is stubbed at
        `tokenApi` (`vi.mock('../api/client')`, as `sessionRefresh.test.ts` does), **not** by mocking
        `./sessionRefresh` — the real `refreshSession` → `isSessionRefusal` path must run, or a predicate swap
        inside it would pass unseen. Both tiers are seeded as **real stored state**: the remember-me record
        written to a fresh fake IndexedDB (`globalThis.indexedDB = new IDBFactory()`, as
        `deviceKeyWrap.test.ts` does) under `REMEMBER_KEY`, and a session vault via `cacheSessionVault`. The
        assertions read that state back (`isRemembered()`, `loadSessionVault()`), never a spy on
        `clearDeviceKey` — a fix that switched to `dropConvenienceTiers` would leave such a spy uncalled and green.
      - **Keeps (live defect — red today):** the refresh rejects with `httpError(503)`, `httpError(502)`,
        `httpError(500)`, `new TypeError('Failed to fetch')`, `httpError(408)`, `httpError(429)` → both tiers
        still present, `serverState.unreachable === true`. And `httpError(403, 'tenant_suspended')` → both tiers
        present, `serverState.suspended === true`. Each lever is the rejection value, fixed in its own case.
      - **Drops (control — green today and after):** `httpError(401)`, `httpError(400)`, `httpError(403)`
        (no code), `httpError(404)` → both tiers gone, `unreachable === false`. This is what proves the harness
        can see a drop at all (§ a bite test must tell "the guard held" apart from "nothing ran").
      - **Both directions of the predicate.** Temporarily replace the boot-side call with `true`: every *keeps*
        case goes red. Replace it with `false`: every *drops* case goes red. Record both in the commit body; the
        tree is restored by snapshot, not `git checkout` (§ bite-proof a guard).
      - **E2 unchanged:** the existing `sessionRefresh.test.ts` case *"a 401 at boot … is the ordinary
        not-signed-in path"* stays green — no `location.replace` at boot.

      · Done when: `npm run check` (web) green, and every bite above was seen red where stated.
- [ ] 2 docs + coverage:
      - uc.account.sign-out E2 gains: *"At boot the same line decides the local convenience tiers: a refusal
        drops them, and a boot that got no answer keeps them and shows the instance as unreachable."*
      - uc.account.remember-this-device E2 gains the transport half: *"An instance that does not answer at start-up
        is not a reason to drop the key: the next start that reaches it restores the session."*
      - `docs/coverage/features/account/use-cases/uc.account.sign-out.yaml` gains criterion
        `boot-keeps-tiers-without-a-refusal` → `src/app/boot.test.ts`.
      - The EN/DE *What this means for you* paragraph of uc.account.remember-this-device, if it promises anything
        about restarts, is checked against the new behaviour (`grep -n 'restart\|Neustart' docs/features/account/use-cases/uc.account.remember-this-device.md`).
      · Done when: `python3 scripts/validate_coverage.py` green and the full guard glob green.

## Definition of done

- Baseline, run **before the first edit**:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done` (measured at refine
  on `faf9ef4`: no output — every guard green) and `cd web && npm run check; echo rc=$?` — **not measured at
  refine** (the refine worktree had no `node_modules`), so the implementer records its exit code first; a red
  there is not this CR's.
- Every *keeps* bite was seen red on the untouched `boot.ts`, and both predicate swaps were seen red.
- `grep -n 'catch(() => false)' web/src/app/boot.ts` returns nothing: no rejection value is discarded before the
  tier decision.
- A one-shot proof suffices, no new guard: the invariant is behavioural (which answer drops the tiers) and the
  bite suite pins it in both directions; a structural guard over `boot.ts` would be a grep for WHAT it says.

**Invariants held:** a session refusal still drops both tiers (no tier outlives a session the device was told is
dead); no ending runs in a tab without a session (E2); operator-blind unchanged — nothing new is sent or stored.

**Not proven:** that a real browser keeps the IndexedDB record across the restart after a real 502. jsdom's fake
IndexedDB stands in; the owner's live check is a reload with the backend stopped, then a reload with it started —
the second should open with no prompt.

## Retrospective
