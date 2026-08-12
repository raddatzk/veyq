# Scoping — E3's locked entry point: reaching "sign out everywhere else" without a Secret Key (inbox #111)

**Status: grounded, awaiting its decisions.** This is a measured substrate, not a refinement. It exists because
`docs/ai/README.md` § Session topology requires refine to run in a **fresh** session, never on the tail of an
implementation — and slice C's implementation is what produced this. Everything below is a *measurement*; the
§ Open questions are deliberately left open, because answering them from inside C's context is exactly the
failure the topology rule guards against.

**Substrate:** `main` @ `a4f96f7`, clean tree, immediately after slice C landed. Every claim here is a command's
output, re-runnable against that tree. Where a line number is quoted it will drift — the file and symbol are
what stay true.

## What E3 is for, and what it is not

`uc.account.sessions` E3 promises a **locked** device still shows the full session list and can revoke from it.
The panel honours that — it takes no vault dependency and disables nothing. What is missing is a **way in**:
on the fresh or foreign device the flow exists for, the locked state cannot be reached at all.

It is emphatically **not** "make the sessions panel reachable while locked". Inbox #111 already rejected both
obvious routes, and slice C confirmed the reason from the other side: the locked ceiling **did not rise** with
either naming increment, exactly as predicted, because every name and both joins live in the Master-Key-sealed
register. A locked caller gets an anonymous list, on which per-row revoke is guessing by date. So the one action
that is complete without names — *sign out everywhere else* — is the whole of E3, and it needs no panel.

## Measured substrate

### The blocker is the form's own validation, and it is still there

- The Secret-Key field is rendered `v-if="needsSecretKey"` and carries `required`
  (`web/src/views/LoginView.vue`, the `unlock.secretKey` label block). On a device holding no password DEVICE
  wrap for the entered email, the field is shown and the form **cannot be submitted** without it.
- `needsSecretKey` is resolved from **device-local trust only** — `isTrusted(email)`, an IndexedDB read — not
  from "is there an open Master Key". Defaults to `true` on any error and with no email. Re-run:
  `grep -n "needsSecretKey\|resolveDeviceState" web/src/views/LoginView.vue`
- So on the lost-laptop case (fresh browser, foreign machine) email + password alone cannot even be *sent*.
  This is the measurement inbox #111 made and it still holds at `a4f96f7`.

### "Session live, vault locked" IS reachable today — but only down failure paths

`login()` sets the tokens **before** any unlock (`setTokens(...)` in `login.ts`, then `setAccount`), and
`runSubmit` sets `sessionReady = true` immediately after. A thrown `unlockMasterKey` therefore leaves the screen
in exactly the target state — session up, vault locked — and re-renders the form with `unlock.failed` in red and
the sign-out button visible. The two ways there are a wrong Secret Key and a stale device wrap. Both are
failures, which is why inbox #111 called the state unreachable *where it matters*: nothing offers it on purpose.

**Consequence for the design, and it is not obvious:** `sessionReady = true` alone does **not** produce a
distinct locked screen. The form still renders every field, `needsSecretKey` is still true, and the submit
button still says "Sign in". A locked *state* therefore needs its own flag; it is not a state the existing refs
already encode. Re-run: `grep -n "sessionReady" web/src/views/LoginView.vue`

### The anchor the decision named is real

- The sign-out button already exists, `v-if="sessionReady"`, inside the `v-if="!vaultState.unlocked"` form —
  i.e. it renders *only* in the locked-with-session state. That is the neighbour the new action was decided to
  sit beside.
- A `type="button"` genuinely bypasses `required`: HTML5 constraint validation runs on submit only. The decided
  mechanism works; nothing here contradicts it.

### Server-side, nothing is missing — and the residual is already argued

- `POST /api/sessions/revoke-others` is `@Authenticated` and needs no unlock. The spared family is resolved by
  the **server** from the presented access token, never named by the request
  (`SessionResource.revokeOtherSessionFamilies` → `SessionService.revokeOtherFamilies`), so a locked caller
  cannot eject itself by getting the exclusion wrong — there is no exclusion to get wrong client-side.
- It carries an explicit `@RateLimitExempt` whose stated reason is that it is idempotent and only ever
  **reduces** reachable sessions, so volume buys an attacker nothing. That is the same residual inbox #111
  accepted ("whoever holds the password can repeatedly eject the account's other sessions"), already written
  down at the endpoint. **E3 changes reachability in the UI, not the posture** — and that is measured, not
  assumed: a password-only holder can call the endpoint directly today.
- Re-run: `grep -rn "revokeOtherFamilies\|RateLimitExempt" backend/src/main/kotlin/com/veyq/app/features/account/login/`

### There is already dead copy for this screen

`login.lockedTitle` ("Signed in") and `login.lockedNote` ("Your session is active. Unlocking your encrypted
memories with your Secret Key arrives in a later change.") exist in **both** locales and are referenced by
**nothing** — leftovers from an earlier increment, and the note is now false prose. Re-run:
`grep -rn "lockedTitle\|lockedNote" web/src | grep -v i18n/index.ts`
Whether E3 revives them or deletes them is a decision, not a detail: the second string is a promise about a
change that has since shipped.

### The publication gate is still open

`wiki/` carries no sessions content — the only hit for a session-ish phrase is one incidental clause in
`wiki/en/is-my-data-safe.md` about a device you are still signed in on. So `uc.account.sessions`' bilingual
section (which promises *"You don't need your Secret Key for any of this"*) has **not** been assembled, and the
obligation E3 carries is still satisfiable rather than already broken. Re-run:
`grep -rli "sitzung\|abmelden\|signed in\|sign out" wiki/`

### One thing the sign-out neighbour does that its label does not say

`logout()` drops the session vault **and** calls `clearDeviceKey()` — removing this device's server `DEVICE_KEY`
wrap and its local key — before revoking the family. The DEVICE trust wrap is deliberately left. So the button
the new action sits beside already does more than "sign out", and on the foreign machine E3 targets that is
probably right rather than wrong. Recorded because a refine session reading only the label would not know.

## Open questions — do NOT answer these from C's context

1. **What does the locked state offer besides the one action, and how does the user leave it?** After
   *sign out everywhere else* succeeds on a foreign machine, the caller holds the account's **only** live
   session, on a vault it cannot open. Does the flow end there, offer the ordinary sign-out, or sign itself out
   automatically? Each answer is a different promise about what "I cut everything off" meant.
2. **Must the locked entry force `SESSION` scope?** The whole flow is "I am on someone else's machine". The
   form's remember-me toggle drives both the refresh-cookie lifetime and which convenience wrap is enrolled;
   nothing today stops a user reaching the locked entry with it on. Forcing `SESSION` (and enrolling no wrap) is
   the obvious call and is therefore exactly the kind of thing that gets assumed rather than decided.
3. **Can the user continue to unlock from the locked state if the Secret Key turns up?** The state is entered by
   *declining* to unlock, not by failing to. A dead end is defensible; so is a way back. Undecided, and it
   decides whether this is a mode or a screen.
4. **Where does the locked state live?** `LoginView.vue` is already one adaptive screen carrying login, unlock,
   kit import, QR scan and remember-me. A fourth mode inside it, or its own component the view switches to, is a
   structure question this scoping deliberately does not settle — but note the neighbour button, the error
   plumbing and `sessionReady` all live in that file today.
5. **Revive or delete `login.lockedTitle` / `lockedNote`?** See above. If revived, the note needs rewriting; if
   deleted, that is a two-locale removal in the same CR rather than a leftover for someone else.
6. **Does E3 owe a locked *list* after all?** This scoping assumes not, following #111 and C's confirmation that
   the ceiling does not rise. The counter-argument nobody has written down: an anonymous list with dates is not
   nothing to a person who knows they signed in on exactly one other machine yesterday. Attack it or record why
   it loses — do not let it stay unexamined a third slice running.

## Do not re-derive

- **The locked ceiling does not rise with naming.** Both joins (device↔family, device↔wrap) are inside the
  Master-Key-sealed register, so a locked caller never reads either. Confirmed twice now, by B and by C. Do not
  plan a richer locked screen for "after naming"; there is no such thing.
- **Server posture is unchanged by E3** and the endpoint's own `@RateLimitExempt` reason already carries the
  argument. Do not re-litigate the residual.
- **`revoke-others` spares the caller by server-side token resolution.** There is no client-side exclusion to
  build, and building one would be the cr-account-sessions trap (a fan-out that includes the caller's own).
- **`uc.account.sessions`' bilingual section is still out of `wiki/`.** The window has not closed.
