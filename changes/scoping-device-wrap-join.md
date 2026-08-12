# Scoping — the device↔wrap join, and "revoke this device everywhere" (inbox #111 slice C)

**Status: grounded, awaiting its decisions.** This is a measured substrate, not a refinement. It exists because
`docs/ai/README.md` § Session topology requires refine to run in a **fresh** session, never on the tail of an
implementation — and slice B's implementation is what produced this. Everything below is a *measurement*; the
§ Open questions are deliberately left open, because answering them from inside B's context is exactly the
failure the topology rule guards against.

**Substrate:** `main` @ `3eeda27`, clean tree, immediately after slice B landed. Every claim here is a command's
output, re-runnable against that tree. Where a line number is quoted it will drift — the file and symbol are
what stay true.

## What C is for

Two things, and they are the same mechanism:

1. **"Revoke this device everywhere"** — end a device's session **and** delete its convenience wrap in one act,
   from another device. `uc.account.sessions` § Out of scope names it; its plain-language section already
   *promises* the user something adjacent that is **not satisfiable today** (*"if it is truly gone, also remove
   its saved key on the device page"* — the device page can only forget **this** device).
2. **Reclaiming an orphaned wrap** — `boot.ts` drops a device's local key with no session to authorise the
   server delete, so the server wrap survives with no handle. It counts against a per-method ceiling until it
   trips a conflict.

Both are blocked on one missing fact: **which wrap belongs to which device.**

## Measured substrate

### The reach already exists — server-side, C is possible today

- `DELETE /api/account/wraps/{id}` is **account-scoped, not device-scoped**: `KeyMaterialStore.deleteWrap`
  checks only the wrap's owner and its method. Any of the account's devices may delete any of the account's
  device wraps by id.
- Every wrap id is already visible to every device: `GET /api/account/key-material` returns them all, and the
  client already uses `id` as its join key.
- Session revoke is likewise account-scoped (`SessionService.revokeFamilyForUser`, scoped in the statement).
- **So nothing server-side is missing.** C is a client-side data problem. Re-run:
  `grep -n "fun deleteWrap" -A 5 backend/src/main/kotlin/com/veyq/app/features/account/keymaterial/KeyMaterialStore.kt`
- ⚠️ `scoping-device-linking.md` used to read *"only untrusts the caller's own wrap"*, which sounds
  device-scoped and would have sent a refine session down a blind alley. Corrected in place — mentioned here
  because the same misreading is easy to re-derive from the endpoint's name.

### The join key, and why only one device can write it

- `wrapId` is held in exactly two local record types — `TrustRecord` (`veyq-trust`, keyed by email) and
  `RememberRecord` (`veyq-remember`, single `'current'` slot) — and the whole lifecycle lives in **two files**:
  `trustDevice.ts` and `deviceKeyWrap.ts`. Locator, two angles:
  `grep -rn "wrapId" web/src | grep -v '\.test\.'` and
  `grep -rn "addAccountWrap\|deleteAccountWrap" web/src backend/src/main | grep -v '\.test\.\|generated'`
- **A wrap id is known only to the device that created it.** No other device can derive the mapping. So the
  join can only be written *by the device itself, at enrol time* — which is a different write moment from
  either of B's two, and a different one from where B's code currently hooks.
- **Every enrolment mints a new id**, including re-enrolling at the same tier (ADR 0005 §9: one convenience
  wrap per device, switching removes and adds). So the join is **mutable state that changes under the
  register**, unlike the family join, where a family id is stable for its lifetime.
- There are **three** local tiers, not two: `DEVICE`, `DEVICE_KEY`, and the session vault — the third has **no
  server wrap at all** and therefore nothing for C to join or revoke.

### The constraint C must not break: anti-withholding

Four code sites and two use-cases state the same rule: **a missing server wrap must never make the client
destroy local key material**, because a blind operator could withhold a wrap to force every trusted device to
wipe. Canonical statement in `unlock.ts` (`unlockWithDevice`), mirrored in `deviceKeyWrap.restoreDeviceKey`,
`restoreVaultFromCache` and `LoginView`; documented as `uc.account.trust-device` E1 and
`uc.account.remember-this-device` E2.

**The boundary, which matters more than the rule:** the client *does* drop local state on **cryptographic**
evidence (an AEAD failure) or when its own local ciphertext is gone — never on the server saying "no such
wrap". Any reclaim design must keep *server absence* a non-signal, and key deletion on either a
locally-verifiable fact or an explicit user act. Re-run:
`grep -rn "anti-withholding\|withhold" web/src docs/features/account`

### The one existing precedent for coupling a revoke to a wrap deletion

`RecoveryStore.applyReset` deletes **every** `DEVICE` + `DEVICE_KEY` wrap of the account in the same
transaction as `revokeAllForUser`, and **ADR 0054** explains *why* it must: a `DEVICE_KEY` wrap takes no
password, so a device trusted before a reset would keep password-less access afterwards. That is the nearest
built thing to C, it is account-wide rather than per-device, and its reasoning is the strongest existing
argument that a session revoke alone is not enough.

Note what is **absent**: **ADR 0004 does not contain the word "wrap" at all.** There is no ADR for the
device↔wrap join and none for "revoke this device everywhere". `uc.account.sessions` § What a revoked session
can still do is currently the only place stating that key material survives a revoke.

### Where the docs are silent, and where they over-promise

- `uc.account.trust-device` § Untrust is strictly **this-device** framing; a lost or stolen device is nowhere
  in its flows. Its "Out of scope" does **not** name C.
- `uc.account.remember-this-device` has **no removal section and no "Out of scope" section at all**, yet
  asserts in passing that the remember-me wrap is *"portable and server-revocable"* — a promise with no
  surface behind it. That sentence is either C's requirement or a correction; deciding which is refinement's
  job.
- `uc.account.sessions`' bilingual section carries the user-facing promise quoted at the top. It is already
  published-facing state, so C either satisfies it or it must be corrected before assembly into `wiki/`.

## Open questions — do NOT answer these from B's context

1. **The sharpest one: C's deletions are user-INTENDED, and B's merge cannot carry that.** B's register is safe
   under union-merge for exactly one reason, stated in its own code and enabler: *every removal is derived from
   a server-authoritative set, never a user's intent.* "Forget this device's wrap" is intent. Three shapes are
   available — tombstones in the register; keeping the wrap join *outside* the register in a structure with
   different merge semantics; or an argument that C's removal is also derived (from the wrap list the server
   already serves, which **is** authoritative). The third would preserve B's whole design and is the one worth
   attacking first — but note it collides head-on with anti-withholding, since "the server no longer lists this
   wrap" is precisely the signal the client is forbidden to act on. **That collision is the crux of slice C.**
2. **Where the join is written.** B's write moments are unlock and sessions-view. The wrap join can only be
   written at enrol time, in `trustDevice.ts` / `deviceKeyWrap.ts` — modules that today know nothing about the
   register and run *before* the vault is necessarily open. Is the register even the right home, or does the
   join belong to whatever already owns the local record?
3. **What "revoke this device everywhere" does when it half-succeeds.** #111 decided the order (family first,
   then wrap). Undecided: what the user is told and what state the register is left in when the family dies and
   the wrap delete fails — and whether that residue is recoverable or silently permanent.
4. **Reclaiming pre-existing orphans.** Wraps orphaned *before* C ships have no register entry and the wrap
   table has **no timestamps**, so there is no age heuristic. Is there an honest surface for "these N wraps are
   not recognised by any device" — and does offering it violate anti-withholding by teaching the user to act on
   the server's account of what exists?
5. **Scope of the use-case.** Does C extend `uc.account.sessions`, or does it belong to `uc.account.trust-device`
   / `uc.account.remember-this-device` — which is where "remove a device's key" actually lives today, and which
   currently has no removal section at all? An ADR may be owed: nothing decides the wrap↔session coupling except
   ADR 0054, and only for the reset path.

## Do not re-derive

- The contract/implementation mismatch on the DELETE whitelist (`DEVICE` vs `DEVICE`+`DEVICE_KEY`) is **fixed**
  as of `3eeda27`. The contract now names both.
- `MAX_WRAPS_PER_METHOD` is a **per-method** cap, so orphaned `DEVICE_KEY` wraps compete only with other
  `DEVICE_KEY` wraps. Find it, don't quote a line number:
  `grep -rn "MAX_WRAPS_PER_METHOD" backend/src/main`
- `RECOVERY` wraps are filtered out of key material, so the served list is never "all your unlock methods".
