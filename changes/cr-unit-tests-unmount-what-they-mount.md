---
id: cr-unit-tests-unmount-what-they-mount
title: Every mounted test component is unmounted before its file tears down — the PrimeVue TabList timer stops aborting `vitest run`
status: done
satisfies: []
adrs: []
invariants: []
---

## Open for the owner

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Keep the teardown bite as a permanent test, or run it once?** It is deterministic (3/3 red, 3/3 green at
   refine, under a second each) and it pins one line of shared setup that any later edit of `setup.ts` can drop
   without a single test noticing — which is exactly how this flake survived four sightings.
   **Recommendation: keep it** (`web/src/test/autoUnmount.test.ts`). Rule 3 of `docs/ai/README.md` asks what the
   defect cost; measured, it cost a diagnosis pass on 2026-09-21, 09-23 and 09-24, each time as "the merge broke
   something".
2. **Is a flake that needs CPU contention to fire worth a CR at all?** The row's own grounding (2026-09-21) asked
   whoever picks it up to weigh that first. **Recommendation: yes, now** — the reason it was parked was that
   nothing could be made red, and that is no longer true (see *Context & constraints*: the lever reddens on
   demand without any load). The fix is one line in the shared setup.

## Goal

A full `vitest run` in `web/` sometimes ends `Vitest caught N unhandled errors` with every test green (inbox #148:
1 red in 3–4 full runs, attributed to `MemoryDetail.test.ts` or `MemoryDetail.optimistic.test.ts`, stack
`Timeout._onTimeout` → `Proxy.updateInkBar`). The mechanism is now measured: PrimeVue's `TabList` arms a **Node**
timer in `mounted()` that it never clears, and a component the test left mounted runs `updateInkBar` after vitest
has deleted the DOM globals, where `getOuterWidth` throws `ReferenceError: HTMLElement is not defined`. After this
CR the shared test setup unmounts every `@vue/test-utils` wrapper after each test, so a late timer finds its refs
cleared and returns without touching the DOM, and a permanent test proves the setup does that.

## Context & constraints

- **Measured at refine** (worktree at `faf9ef4`; installed `primevue` 4.5.5, `vitest` 4.1.9, `happy-dom` 20.10.6,
  `@vue/test-utils` 2.4.11):
  - **The timer is Node's, not happy-dom's, so the row's grounding is wrong on this point.** vitest's happy-dom
    environment copies window keys onto the global only where Node has no such key or the key is in its `KEYS`
    list (`node_modules/vitest/dist/chunks/index.DC7d2Pf8.js:242-248`), and `setTimeout` is in neither
    (`grep -o setTimeout` on that chunk → 0). So PrimeVue's `setTimeout` is Node's own, and `happyDOM.abort()`
    (`:329-334`), which the 2026-09-21 grounding said "cancels pending timers", never sees it.
  - **What throws, and when.** Teardown runs `abort()`, then `win.close()`, then deletes every populated key from
    the global (`:391-393`, the happy-dom `setup` path). `updateInkBar` (`primevue/tablist/index.mjs:121-135`) calls `getOuterWidth`, whose
    first line is `element instanceof HTMLElement` with no `typeof` guard (`@primeuix/utils` source
    `dom/methods/getOuterWidth.ts`). A timer that lands after the key deletion and before the worker exits throws
    `ReferenceError`. Contention widens that window, and this is why the row saw it only under load. **Inferred, not observed in a real run:** the row never recorded the error *message*, only the stack. The lever reproduces that stack frame for frame, and no other throw site in `updateInkBar` was found reachable.
  - **Why every earlier attempt stayed green:** the grounding called `updateInkBar` after `unmount()` and after
    emptying `document.body`. It never deleted the globals, and only that deletion makes the call throw.
  - **The lever (scratch, not committed):** a test that mounts `Tabs`/`TabList`, leaves it mounted, and in
    `afterAll` does vitest's own teardown steps (`await happyDOM.abort(); window.close()`; delete `HTMLElement`,
    `Element`, `document`, `getComputedStyle` from `globalThis`; wait 400 ms). **3/3 runs exit 1** with
    `ReferenceError: HTMLElement is not defined` and the row's exact stack
    (`Timeout._onTimeout` → `Proxy.updateInkBar` → `@primeuix/utils/dist/dom/index.mjs`). With
    `enableAutoUnmount(afterEach)` in the same file: **3/3 exit 0.** Unmounting first clears `$refs.inkbar`, and
    `updateInkBar`'s first check (`if (!inkbar) return`, `:126`) makes the timer a no-op.
  - **Probe of the real window (scratch):** Node timers set at the end of a file's last test fired up to +270 ms
    after it, and `document` was still defined in every observed callback. So the unguarded window exists but is
    narrow, which matches "rare, and only under load".
  - **Upstream:** `TabList.mounted()` stores no handle for its timer (`:48-51`), so its `beforeUnmount` (`:62-65`)
    cannot clear it. 4.5.5 is the newest 4.x (`npm view 'primevue@4' version`); the current major is 5.0.1.
    **Remembered, not measured:** whether 5.x stores the handle. The fix below does not depend on it.
- **Locators (measured):**
  - Who mounts PrimeVue Tabs: `grep -rln "primevue/tabs\|primevue/tablist" web/src` → `MemoryDetail.vue` only.
  - Test files that exercise it: `grep -rl MemoryDetail web/src --include='*.test.ts'` → three files:
    `MemoryDetail.test.ts` (0 `unmount()`), `MemoryDetail.optimistic.test.ts` (1, at :275, in one of its tests),
    `views/ShareView.picker.test.ts` (1). **Every one of them leaves at least one wrapper mounted at file end.**
    The row's 2026-09-24 sighting in `MemoryDetail.optimistic.test.ts`, *"a file that unmounts"*, was therefore not
    evidence against unmounting: that file unmounts in one test, and its last test (:292) does not.
  - Blast radius of the setup change: `grep -rln "from '@vue/test-utils'" web/src --include='*.test.ts'` → 62
    files; manual `.unmount()` calls in non-e2e tests → 23 in 15 files (`grep -rn '\.unmount()' web/src
    --include='*.test.ts' | grep -v e2e`). Non-VTU mounts (`createApp(` in tests) → 0. A double unmount
    (a manual `unmount()` followed by the auto-unmount) printed no warning and no error in the scratch run.
  - `enableAutoUnmount` exists in the installed VTU and throws if it is called twice
    (`node_modules/@vue/test-utils/dist/vue-test-utils.cjs.js:8243-8254`). Today it is called 0 times
    (`grep -rn enableAutoUnmount web/src`).
- **The row's second candidate is closed:** it described `ECONNREFUSED ::1:3000` as "a second candidate source
  for this row's symptom". The inbox #149 network net in `web/src/test/setup.ts` now blocks those requests. A full
  `npm run check` at refine had **0** `ECONNREFUSED` lines (`CHECK EXIT: 0`, 152 files / 1350 tests passed).
- **Gate baseline:** `npm run check` in `web/` on `faf9ef4` → exit 0 (measured once, under load from sibling
  sessions, and no unhandled error that run).

## Decisions

- **D1: fix it in the shared setup, not in the three files.** `enableAutoUnmount(afterEach)` goes in
  `web/src/test/setup.ts`, beside the ToastService registration. What was left mounted is a property of any test
  that mounts; the three current files are only where PrimeVue happens to punish it. A per-file `unmount()`
  would leave the next `Tabs` user, or the next component with an unguarded timer, to be found the same way.
- **D2: rejected alternatives, each for a measured reason.**
  - *vitest `onUnhandledError` filter on the `updateInkBar` stack:* this silences the symptom and keeps the
    mounted-past-teardown state that produces it. It would also hide the next library that does the same thing.
  - *Patching `TabList.mounted` to keep the timer handle:* this patches a third-party object from test setup, and
    it fixes one component where D1 fixes the class.
  - *Fake timers in the MemoryDetail tests:* they change what the tests measure (flushes, debounces) in order to
    fix a teardown problem.
  - *Bumping to PrimeVue 5:* a major-version migration for a test flake, and whether 5.x fixes this is
    unmeasured.
- **D3: the manual `unmount()` calls stay.** They sit where a test asserts behaviour *on* unmount (for example
  `MemoryDetail.optimistic.test.ts:275`, "revokes the preview objectURL on unmount"). The double unmount was
  measured harmless, and removing the calls would change what those tests prove.

## Phases

Single phase (web only).

- [x] 1 web —
  1. **Red first (live defect).** Add `web/src/test/autoUnmount.test.ts`, starting with the
     `// @vitest-environment happy-dom` docblock the component tests use, because `vite.config.ts` sets no default
     environment. The `setup.ts` hook itself also runs in node-environment files (the e2e suites), where it
     registers an `afterEach` that has nothing to unmount. It mounts a minimal `Tabs` → `TabList` →
     two `Tab` component (PrimeVue plugin installed) and leaves it mounted. Its `afterAll` repeats vitest's
     teardown steps as the scratch lever did: `await happyDOM.abort()`, `window.close()`, delete `HTMLElement`,
     `Element`, `document` and `getComputedStyle` from `globalThis`, then wait 400 ms on a Node timer. Its header
     comment says why the file has to be its own file: it destroys its worker's DOM, and that is safe only
     because vitest isolates files. Run `npx vitest run src/test/autoUnmount.test.ts` on the **unchanged**
     `setup.ts`. **Expected: exit 1 with exactly one unhandled `ReferenceError: HTMLElement is not defined`**
     whose stack names `updateInkBar`. Record it. A red on anything else (an import, the mount) is not the
     bite (§ *a live-defect red can land on the wrong STEP*).
  2. **Fix.** Add `enableAutoUnmount(afterEach)` to `web/src/test/setup.ts` (import `afterEach`, already imported
     there, and `enableAutoUnmount` from `@vue/test-utils`). Keep the comment short: it names the mechanism
     (a library timer outliving the teardown into deleted globals) and cites this file's test as the proof,
     not the inbox row.
  3. **Green.** The same command exits 0.
  4. **Re-bite by injection on the fixed tree.** Comment out the new line. The test must redden with the same
     error. Restore by snapshot, not `git checkout` (§ *bite-proof by snapshot-and-restore*).
  5. **Blast radius.** Run `npm run check` **three** times in a row. It must be green each time with no
     `Unhandled` line. A test that relied on a wrapper surviving into the next test would show up here. The
     locator above found none (no mount in `beforeAll`, no module-scope mount); if one appears, give it its own
     `mount` per test rather than opting it out.
  · Done when: steps 1–4 are recorded with their exit codes, and three consecutive `npm run check` runs exit 0.

## Definition of done

- The red of step 1 and the injection red of step 4 are recorded with the assertion that fired.
- `npm run check` in `web/` is green three times in a row. Three is not proof that the flake is gone, because
  the lever is. The runs prove only that the setup change broke nothing across the 62 files that mount.
- **Harvest before deleting the CR:** the mechanism belongs in the comment at the `enableAutoUnmount` line and in
  the header of `autoUnmount.test.ts`, both already required above. Inbox #148's status cell is set to done with
  a one-line pointer to the test file. No `docs/` state changes: the test setup has no state doc.

## Retrospective

- **Planning held, step for step.** The step-1 red landed exactly as predicted (exit 1, one unhandled
  `ReferenceError: HTMLElement is not defined`, stack `Timeout._onTimeout` → `Proxy.updateInkBar` →
  `@primeuix/utils`), the fix turned it green 3/3, the injection (the setup line commented out) reddened it the same
  way, and three `npm run check` runs went 153 files / 1351 tests green with 0 `Unhandled` lines. What made the
  difference was the refine's lever: it reproduced the teardown instead of waiting for contention, so the implement
  needed no judgement call. Nothing to fault.
- **One locator now answers differently, by design:** `grep -rln "primevue/tabs\|primevue/tablist" web/src` returns
  `src/test/autoUnmount.test.ts` beside `MemoryDetail.vue`. Whoever re-runs it for "who mounts Tabs" reads the
  test file as the fixture, not a second user.
- **Next time:** a flake that needs load is refined the way this one finally was: rebuild the environment step
  the flake races against, rather than adding runs under load.
