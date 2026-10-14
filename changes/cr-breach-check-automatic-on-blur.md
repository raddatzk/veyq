---
id: cr-breach-check-automatic-on-blur
title: The breached-password check runs by itself when the password field is left, and a hit needs "Use it anyway"
status: planned
satisfies: [uc.account.change-password, uc.account.recover-access, uc.admin.setup-server]
adrs: ["0003"]
invariants: [operator-blind E2EE everywhere]
---

## Goal

The breached-password check of the four password-choosing dialogs is no longer an unticked box.

- **When it runs:** automatically, over the finished password, **when the password field is left**, and once more on
  submit if the password changed since. It never runs while typing.
- **A hit** shows a warning that needs an explicit **"Use it anyway"** before that password is submitted.
- **A check that could not run** stops nothing. The dialog says in one line that the password could not be checked,
  and goes ahead.

The four dialogs are server setup (admin-web), tenant join, password change and access recovery. Owner decisions
2026-10-14.

## Context & constraints

- [security-model § the breached-password check](../docs/crosscutting/security-model.md) (edited in place by this
  refinement): only a 5-hex prefix, over a finished password, on blur and submit, never a debounce. It is automatic and
  the one stated exception to opt-in, with the `Origin` disclosure stated. It advises and never gates.
- The current implementation is `web/src/features/account/passwordWarning.ts` (`useBreachCheck`) and
  `web/src/shared/pwned.ts`. `admin-web/` has its own copies by design (ADR 0065: no shared UI package). Both change
  the same way.
- Commit `3da59f20` removed a debounced watcher for exactly the reason security-model states. Its tests ("nothing
  while typing") stay and must keep passing.
- **Invariant:** operator-blind. The request goes client-direct to HIBP, never through veyq. Unchanged.

## Inventory (commands)

- `grep -rln "useBreachCheck\|pwnedCount\|pwnedCheck\|pwnedWarning\|pwnedUnavailable" web/src admin-web/src`. That
  covers the composable, the four dialogs, their tests and the i18n keys in both packages.
- The CSP `connect-src` for `api.pwnedpasswords.com` in both packages: `grep -rn pwnedpasswords web/src admin-web/src`.
  It stays.

## Decisions

1. **The composable** loses `enabled`.
   - It exposes `checkNow()` for the field's blur, the submit guard `proceed()` and a `state`:
     `idle` | `checking` | `clean` | `hit(count)` | `unavailable` | `accepted`.
   - One query per distinct finished password: a blur on an unchanged password does not query again. The query only
     runs if the password is non-empty and passes the dialog's own policy check, so nothing is sent for a password
     the dialog would refuse anyway.
2. **On submit:**
   - **Clean**, or a hit the user explicitly **accepted** for this exact password: go ahead.
   - **Unavailable:** go ahead, and the one-line note stays visible.
   - **Changed since the last check:** check once, then apply the rules above.
   - **Edited password:** clears any acceptance.
3. **"Use it anyway"** is an explicit control inside the warning (a button or a checkbox). Pressing submit again is
   no longer the acceptance.
4. **The note** under the password field is one sentence saying what leaves the device (the first 5 characters of the
   password's hash, to Have I Been Pwned). It replaces the checkbox and its hint. The checkbox's i18n keys go.
5. **Both packages** change identically. Each keeps its own copy.

## Phases

- [ ] 1 web + admin-web: Decisions 1–5 in the composable and the four dialogs.
  - Tests in both packages (on the request itself, as today) cover:
    - nothing while typing;
    - one prefix of the finished password on blur;
    - no second query for an unchanged blur;
    - a changed password queries again on submit;
    - nothing for a password the policy refuses;
    - a hit blocks the submit until "Use it anyway";
    - an edit clears the acceptance;
    - unavailable goes ahead with the note.
  - Each negative is bitten. The old "nothing unticked" cases are replaced, not deleted silently.
  - Done when `npm run check` is green in `web/` and `admin-web/` and the join or change dialog has been tried in a
    real browser (the web e2e suite, or the Browser pane on a stack of its own; the owner's dev database is not to be
    touched).
- [ ] 2 docs: security-model's **Verification** paragraph names the new tests, and the wiki's privacy and password
  pages (EN and DE) say the check is automatic, what it sends, and the `Origin` disclosure.
  - Done when the trust-chain guards are green.

## Definition of done

- `npm run check` is green in both packages.
- Every `scripts/validate_*.py` is green, run on a detached worktree.
- The browser try is recorded.

## Retrospective

_(filled at implementation)_
