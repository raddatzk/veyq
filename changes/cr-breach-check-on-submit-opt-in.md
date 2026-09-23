---
id: cr-breach-check-on-submit-opt-in
title: The breached-password check asks only on submit, only over the final password, only when ticked
status: planned
satisfies: []
adrs: ["0065"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

Four dialogs choose a password: `ChangePasswordPanel`, `TenantJoinView` (register) and `RecoverView` in web,
and `SetupView` in admin-web. Each one fires a Have I Been Pwned range query at every typing pause
(`usePwnedWarning`: a 600 ms debounce over a `watch`). That sends the SHA-1 prefix of every **partial** password
to a third party, and a short partial password, or a few keystrokes added to one already recovered, is all but
identified by its 20-bit prefix. It also runs without asking, which breaks the third-party rule. After this CR
the check is an unticked box in each of those dialogs, with a one-sentence note on what leaves the device. When
the box is ticked, the check runs once, on submit, over the final password. A hit, or a check that could not
run, stops that first submit with a message saying which; submitting again with the same password goes ahead.

## Context & constraints

- **The rule is already written:** [security-model](../docs/crosscutting/security-model.md) § *third-party
  services are a separate, consented boundary* → *The breached-password check is the second instance* (refined
  2026-09-23, inbox #166). This CR builds it. The rule has no use-case of its own and a crosscutting doc
  carries no coverage file, hence `satisfies: []`. Phase 3 points the rule at the tests that pin it, as
  [cc.security.csp](../docs/crosscutting/cc.security.csp.md) § *Verification* does for its rule.
- **Two copies, on purpose:** `web/src/shared/pwned.ts` + `web/src/features/account/passwordWarning.ts` and the
  identical pair under `admin-web/src/` (`diff` them: they match today). [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)
  shares only the crypto package between the two apps. A breach lookup is not key material, so it does not move
  into `packages/crypto`, and the two copies change in step. The implementer `diff`s them again at the end, and
  they must still match.
- **Reuse, not new UI** ([ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first): the
  box is the PrimeVue `Checkbox` + label the apps already use (`grep -rln '<Checkbox' web/src admin-web/src`),
  placed where the `pwnedWarning` `<Message>` sits today. The stop messages reuse that `<Message severity="warn">`.
- **The CSP needs no change:** `connect-src` still allows `https://api.pwnedpasswords.com`. The comments that
  describe the call change wording only (`grep -rn -i 'pwned' web/src/shared/security/csp.ts admin-web/vite.config.ts docs/crosscutting/cc.security.csp.md`).
  If `cr-client-hygiene` has already moved admin-web's policy into a module, the comment lives there.
- **Out of scope:** the password-length policy (`passwordPolicy.ts`), which is untouched and not a third-party call.

## Decisions

1. **The debounced watcher goes.** The composable keeps its name only if that still reads true; it exposes the
   box state (default `false`, never persisted) and one async submit guard, `(password) → proceed?`:
   - box unticked → proceed, **no request**;
   - the password and the box are unchanged since the last stop → proceed, **no new request** (the second press);
   - otherwise query once: clean → proceed; hit → stop with the count; could not run → stop with "the check could
     not run".
   Changing the password or unticking the box clears a pending stop.
2. **`pwnedCount` stops folding failure into zero.** It returns the count, or `null` when the check could not
   run (network, CSP, a non-2xx, a firewalled admin container). The `Add-Padding` header stays. Today's fail-open
   `0` is exactly what made "not checked" look like "clean" to someone who asked for the check.
3. **Copy, EN + DE in both catalogs:** the box label, the one-sentence note (only the first 5 characters of a
   hash of the password go to haveibeenpwned.com, never the password), the hit message (the count, plus
   *submit again to use it anyway*), and the could-not-run message. German follows
   [cc.inclusive-language](../docs/crosscutting/cc.inclusive-language.md). `password.pwnedWarning` is reworded,
   or replaced if its key no longer fits.

## Phases

- [ ] 1 web — `pwned.ts` returns `number | null`. The composable becomes the submit guard of decision 1. The
      three dialogs render the box + note and `await` the guard before their existing submit work. Locator for
      the sites: `grep -rn 'usePwnedWarning\|breachCount' web/src`, which also finds
      `ChangePasswordPanel.policy.test.ts`, whose mock changes shape.
      - **The bite comes first, against today's tree:** a composable test with fake timers types a password and
        advances well past 600 ms. It asserts `fetch` was **not** called, and that assertion fails today. Then:
        unticked submit → no `fetch`; ticked submit → exactly one `fetch`, whose URL carries the prefix of the
        **final** password's SHA-1; a hit → stop, then the same password → proceed with no second `fetch`;
        `fetch` rejects → stop with could-not-run; a changed password after a stop → a fresh query.
      - **Wiring:** each dialog's existing test file (`ChangePasswordPanel.policy.test.ts`,
        `TenantJoinView.passwordPolicy.test.ts`, `RecoverView.test.ts`) gets one case: box ticked, check reports
        a hit → the dialog's action is **not** called on the first submit, and **is** on the second. That is the
        slip a composable test cannot see, a dialog that forgets to `await` the guard.
      · Done when: `npm run check` (web) green, with the typing bite seen red first.
- [ ] 2 admin-web — the same change to its pair and to `SetupView.vue`. `SetupView` has no test file; it gets
      one with the wiring case above. `admin-web/src/i18n/i18n.test.ts` will fail on any new key missing in one
      locale, so no extra check is needed. The two composables and the two `pwned.ts` `diff` clean against web's.
      · Done when: `npm run check` (admin-web) green.
- [ ] 3 docs — the security-model paragraph names the tests that pin it (one sentence, like `cc.security.csp`'s
      *Verification*). The CSP comments found by the locator above say *opt-in, on submit*.
      · Done when: the trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Definition of done

- Every phase is done; the "no request while typing" test was seen red against the debounced watcher.
- `grep -rn 'setTimeout' web/src/features/account/passwordWarning.ts admin-web/src/features/account/passwordWarning.ts`
  is empty, and so is the `diff` between the two copies of each file.
- A live pass in one dialog (web, `ChangePasswordPanel`), read from the network list, not the DOM: typing sends
  nothing, an unticked submit sends nothing, and a ticked submit sends one `range/<5 hex>` request.

## Retrospective
