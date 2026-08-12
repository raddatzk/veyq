---
id: cr-account-sealed-device-register
title: The sealed device register — a stable device id, its label, and the family join
status: done
satisfies: [uc.account.sessions]
adrs: ["0004", "0005", "0026", "0040", "0044"]
invariants:
  - Operator-blind E2EE — no device name, and above all no device↔family link, may reach the server
  - Product language — Memory / Chapter (not touched here, but the panel copy lives in the same i18n tree)
  - API-first — contracts under contracts/ are the source of truth; never hand-write DTOs
---

## Goal

The sessions view built by the previous increment lists every active login and revokes any of them, but every
row is anonymous: four opaque ids and two dates. This CR gives those rows names, without the server ever
learning one. It adds a **Master-Key-sealed device register** — one opaque blob per account, synced with an
optimistic-concurrency counter exactly like the identity-pin store — holding a **stable client-side device
id**, a renameable **label** per device, and the **device↔session-family join** that lets a session row find
its device. Reading it needs an unlocked vault; the list and the revoke keep needing only a session, so a
locked device loses nothing but the names.

Scope boundary, because the inbox row and the use-case disagreed and the use-case is right: this CR carries
device↔**family**. Device↔**wrap** — and with it "revoke this device everywhere" and any reclaim of an
orphaned convenience wrap — is the *next* increment and is deliberately not started here.

## Context & constraints

- **Use-case:** [uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md) — main flow
  3 (label from the register) and the reconcile in main flow 4, plus **E4** (a missing or stale register
  renders unnamed and blocks nothing). **E3 is explicitly not this CR** — it is reachability for a locked
  caller, it is sequenced after this and the wrap-join increment, and the use-case records why the locked
  ceiling does not rise with either.
- **Enabler (the design this work order implements):**
  [en.account.device-register](../docs/features/account/enablers/en.account.device-register.md). Every "why"
  question below is answered there rather than here — in particular the merge rule, which is the one part of
  this CR that is easy to get subtly and silently wrong.
- **The shape to copy:** [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md)
  and its built stack. Copy its *defences*, not only its subject — see the inventory.
- **Contracts:** `contracts/api/account.openapi.yaml` (the new pair of operations lives beside the identity-pin
  pair), `contracts/api/auth.openapi.yaml` (read-only here: `SessionFamily` is what must **not** grow),
  `contracts/crypto/vectors/v1.json` (the AAD context gets a vector, as `sym_identity_pins` has one).
- **ADRs:** [0004](../docs/decisions/0004-session-tokens.md) (families are unnamed, and why a name on one is a
  linkability regression), [0005 §9](../docs/decisions/0005-unlock-methods-and-device-trust.md) (one
  convenience wrap per device — the reason a `wrapId` is not a device identity),
  [0026](../docs/decisions/0026-client-trust-tiers.md) (what a served client can be trusted with),
  [0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) (a test no CI run executes makes no
  coverage claim — this repo still has no remote, so the new criteria land as `gaps:` like the existing four),
  [0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md) (every non-opaque contract field
  declares its visibility class).
- **Invariant most at risk, and it is not the obvious one.** The blob itself is easy to keep blind. The risk
  is that "put the name where the family is" is the shorter road, and it is precisely the regression ADR 0004
  removed the mechanism for. Phase 1 promotes that prose rule to a guard, so the shorter road stops
  compiling.

## Inventory

Facts stated as commands, to be run at implement time. Counts are deliberately absent — they were read in
refinement to decide the work was worth doing, and the implementer needs the **sites**.

| # | What | Command |
|---|---|---|
| 1 | The reference contract pair to copy (operations, schemas, 409 shape) | `grep -n "identity-pins\|IdentityPins" contracts/api/account.openapi.yaml` |
| 2 | The reference backend slice, all four files + its migration | `ls backend/src/main/kotlin/com/veyq/app/features/account/identitypins/ && grep -rln "identity_pins" backend/src/main/resources/db/migration/` |
| 3 | The reference web module + the retry/merge helper to copy | `grep -n "mutateAndSave\|loadStore\|isStaleVersion\|MAX_WRITE_ATTEMPTS" web/src/features/account/identityPins.ts` |
| 4 | The reference test's **defences** (fake server, the 409 shaping, the union-merge proof) — read before writing this CR's tests | `grep -n "fakeServer\|stale()\|describe(" web/src/features/account/identityPins.test.ts` |
| 5 | Every persistent client-side store, to place the device id **outside** the ones that get wiped | `grep -rn "localStorage\.\|idbRun\|sessionStorage\." web/src --include=*.ts --include=*.vue \| grep -v "\.test\."` |
| 6 | The two wipes the device id must survive | `grep -rn "clearDeviceKey\|forgetAllTrust\|clearSessionVault" web/src --include=*.ts --include=*.vue \| grep -v "\.test\."` |
| 7 | Where the client already holds its own family id, and the one path where it does not | `grep -n "familyId" web/src/app/session.ts web/src/features/account/login/login.ts` |
| 8 | The sessions surface this CR extends (panel, api module, its test) | `ls web/src/features/account/sessions/` |
| 9 | Where an unlock completes — the hook point for write moment 1 | `grep -rn "restoreDeviceKey\|unlockWith\|setUnlocked" web/src/features/account/unlock/unlock.ts web/src/app/vault.ts` |
| 10 | **The gate of this surface** (see below — locate it, then leave it alone) | `grep -n "requiresUnlock" web/src/router.ts web/src/views/SettingsHubView.vue` |
| 11 | Guards that anchor on the contract file being edited | `grep -rn "account.openapi.yaml\|IdentityPins" scripts/*.py` |
| 12 | Every i18n key the sessions panel already owns, so the new copy joins them rather than starting a second tree | `grep -n "sessions\." web/src/i18n/index.ts` |
| 13 | Defect 1's two sides, to confirm which is stale before editing either | `sed -n '268,300p' contracts/api/account.openapi.yaml && grep -n "DEVICE_METHODS" -A2 -B8 backend/src/main/kotlin/com/veyq/app/features/account/keymaterial/KeyMaterialStore.kt` |

### The gate — located, and deliberately untouched

`changes/README.md` § *Upstream of every trap* requires the gate of a surface to be located beside the
insertion point, not just the insertion point. Row 10 above is that line, and the finding is recorded here so
implementation does not re-open it: `/settings/:section?` carries `requiresUnlock`, and for **this** CR that is
**correct** — the names are Master-Key-sealed, so a locked caller could not read them if it did reach the
panel. Do not relax it, do not add a session-only route, and do not treat this as the E3 gap: E3 is a
*different* increment about reaching the panel at all, it is sequenced after this one, and the use-case
already records that this CR does not raise its ceiling.

## Decisions taken in refinement — do not re-derive

1. **Two maps, not one:** `devices: {deviceId → {label, lastSeenAt}}` and `families: {familyId → deviceId}`.
   They decay at completely different rates; folding them ties a device's lifetime to its newest family.
2. **`version` is on the wire, not in the plaintext** — the server compares it and cannot open the blob.
   (Note the reference module's own inconsistency here: the pin store's crypto vector shows a `version` inside
   the plaintext while the module seals only `{pins}`. Do not copy the inconsistency.)
3. **The device id is minted client-side, opaque and random, and stored in non-secret client-local storage**,
   outside every store that inventory row 6 finds — otherwise each session expiry mints a new device.
4. **Removals split by where their truth lives.** External truth (the server's family list) → apply as a
   **frozen id-delta**. Internal truth (the register's own contents, i.e. eviction) → **recompute inside the
   retry**, on the merged map. This is the rule that makes union-merge safe and it is the single most
   test-worthy line in the CR.
5. **No manual "forget this entry."** It would be the one user-*intended* deletion, which union-merge
   resurrects. Absent by decision, not by omission.
6. **Growth is bounded by evicting least-recently-seen devices**, with the bound set above the per-method wrap
   ceiling that inventory row 11's neighbourhood defines, so a real device is never evicted by a limit meant
   for wraps. A wrong bound costs a label, never a row and never a revoke.
7. **Two write moments, both idempotent, both best-effort:** after an unlock that followed a login (additive
   only), and when the sessions view opens unlocked (it holds the authoritative list, so it reconciles,
   prunes and evicts). **No write queue** — the view that shows the register is the view that repairs it,
   which is what makes E4 a design property instead of an error path.
8. **It is the unlock, not the login.** The blob is Master-Key-sealed and at login there is no Master Key;
   "one PUT per login" as first written is not buildable. The client already holds its own family id from the
   login response — inventory row 7 finds both that fact and the one path (a cold boot restore, where no
   login happened in this page load) on which it is absent; write moment 2 covers that path via the
   server-set current marker, so **no contract change is needed either way**.
9. **Rename lives on the sessions row**, with copy (EN+DE) that says a **device** is being renamed. A device
   is one-to-many with its sessions and the rename reaches all of them.
10. **Blob cap: plain `TEXT`, no MEDIUMTEXT widening.** The register is small. Pick a `maxLength` that is a
    multiple of 4 and within the `TEXT` ceiling that `scripts/validate_maxlength_storage.py` defines, and
    **do not** add a `MEDIUMTEXT_BACKED` entry — that guard fails a stale allowlist entry that matches no
    over-cap field, so an unnecessary entry is a red build, not a harmless one. This is where the reference
    diverges: the pin store needed a widening migration for its 1 MiB cap, and copying that would be copying
    a cost we do not have.

## Phases

- [x] **1 contracts** — `GET`/`PUT /api/account/device-register` beside the identity-pin pair (same
      `{blob, version}` request/response shape, same `409 stale_version`, its own tag); the AAD context
      `device-register` pinned as a vector in `contracts/crypto/vectors/v1.json` the way `sym_identity_pins`
      is; **defect 1's contract fix** (below); and the **new permanent guard** (below), written **first** and
      its red recorded before the fix it guards. ·
      Done when: generation succeeds on both surfaces and
      `for f in scripts/validate_*.py; do python3 "$f"; done` is green, including the new guard.
- [x] **2 backend** — the store/handler/resource/entity quartet plus its Flyway migration, mirroring the
      identity-pin slice: one row per account keyed by the account id, a compare-and-set on `version`, and a
      sealed `Stale` result that *is* the `409 stale_version` failure (so it routes through the shared error
      writer that `validate_http_failure_projection.py` enforces). ·
      Done when: `cd backend && ./gradlew check` green, with a slice test covering born-empty, first write,
      bump, stale-on-first-write, stale-after-write **with the store proven untouched**, per-account
      isolation, and the unauthenticated refusal.
- [x] **3 web** — the device-id module; the best-effort label guess; the `deviceRegister` module (seal/unseal,
      load, and the retry-with-merge helper ported from the pin store); the two write moments; the panel
      changes (named rows, unnamed fallback preserved, rename); EN+DE copy in the existing `sessions.*` key
      tree. ·
      Done when: `cd web && npm run check` green, with the test cases listed below.
- [x] **4 coverage** — `docs/coverage/features/account/use-cases/uc.account.sessions.yaml` gains the new
      criteria; they land as `gaps:` with the existing four for the same single reason (ADR 0040 — no CI run
      has executed here), naming the suites so the later flip is mechanical. ·
      Done when: `python3 scripts/validate_coverage.py` green.

### What phase 3 must test, and why each one

Ported from the reference test's defences, not just its subject:

- **The union-merge proof, in this register's shape** — a concurrent device's entry survives our write, and
  the version advances. The pin store's test does exactly this; it is the reason its merge is trustworthy.
- **The frozen-id-delta rule, as its own case, with a positive control.** Drive a 409 whose re-read base
  contains a family created *after* our list was read, and assert it **survives**. This is the case that
  distinguishes "remove these ids" from "keep only what I saw", and without it both implementations pass
  every other test. A negative here anchors on the specific observable (that family id still present), never
  on "no throw".
- **Eviction recomputes on the merged map**, not on the pre-merge one — the mirror image of the case above,
  and the reason decision 4 is split rather than uniform.
- **A rename survives a 409** and the later write wins.
- **E4 by construction:** a register that is absent, unreadable or unknown to a family renders that row
  unnamed, and the revoke button stays enabled. The unlocked, fully-named mount is the **control** for that
  case — a panel that renders nothing named would satisfy the locked assertion perfectly.
- **No name on the wire:** the sessions request/response path carries no label, asserted over the shape.

## The permanent guard this CR promotes

The invariant: **a session family carries no per-family ciphertext and no name-shaped field.** It is stated
in prose in the auth spec and in `uc.account.sessions` § Quality notes ("*this is why a per-family name field
must not come back*"), and **nothing enforces it** — while this CR is the exact moment the temptation is
live, because it is building naming and the family is the shorter road.

- **Predicate:** `SessionFamily`'s property set is exactly the four it has today, and none of them is
  `contentEncoding: base64`.
- **Why not the wider predicate:** "no base64 field anywhere in `auth.openapi.yaml`" was the first shape
  reached for in refinement and it is **wrong** — that spec legitimately carries base64 fields for the login
  challenge/response and recovery material, so the guard would have been red on arrival. It was caught by
  running the predicate before writing it down, and it is recorded here so it is not reached for again.
- **Why `validate_field_visibility.py` does not already cover it:** a sealed name field would declare a
  visibility class and pass. That guard asks *"is this field declared?"*; this one asks *"may this field exist
  at all?"*.
- **Bite:** add a `sealedName` (base64) property to `SessionFamily`, confirm the injection **landed** by
  grepping the marker out of the file, run the guard, assert it fails **by name**, then restore from a
  byte-snapshot taken before the injection — never `git checkout`, since the baseline at that point already
  carries this CR's own uncommitted contract edits.
- **Docstring:** the three required fields, per `changes/README.md`. Its *limit* is real and must be stated:
  it guards the **contract**, so it cannot see a name smuggled through a path param, an error message, or a
  column with no contract field — and it says nothing about the register's own blob.

## Defect 1 — folded in, because it lives in the file this CR edits

`account.openapi.yaml`'s DELETE-wrap operation description says only a `DEVICE` wrap is removable; the store
whitelists `DEVICE` **and** `DEVICE_KEY`. **The contract is the stale side**, confirmed three independent
ways before touching it: a backend slice test asserts a `DEVICE_KEY` wrap is removable, three web call sites
depend on it, and the same spec's own overview section already describes the wider behaviour. Fix the
operation description (and the handler KDoc that repeats it) to match the code. Inventory row 13 is the
command that shows both sides.

**Defect 2 is deliberately *not* here.** `boot.ts` drops the local device key without deleting the server
`DEVICE_KEY` wrap — correctly, because at that point there is no session to authorise the delete. The
resulting orphan cannot be identified afterwards: the wrap table has **no timestamps**, so there is no age
heuristic, and the client deliberately refuses to clean up on a missing wrap (anti-withholding, so a blind
operator cannot force devices to wipe). A real fix needs a handle on "which wraps are mine" — which is the
device↔**wrap** join, i.e. the next increment. Fixing it here would mean inventing a heuristic that the next
increment then deletes. The 20-per-method ceiling is the standing backstop until then.

## Definition of done

Tests green on both surfaces (`cd backend && ./gradlew check`, `cd web && npm run check`), the full guard glob
green over the **committed** tree (`for f in scripts/validate_*.py; do python3 "$f"; done` — the glob sees
files, not commits, so an untracked guard is unchecked), and `docs/coverage/` updated for
`uc.account.sessions` with every new criterion carrying a test ref or an explicit `gaps:` reason.

The structural invariant this CR rests on — no per-family ciphertext — is promoted to a permanent guard rather
than proven once, and that guard's bite is recorded (injection confirmed landed, failure observed **by test
name**, restore verified against the pre-injection snapshot).

Note on the phrase "green in CI": this repo has no remote, and the trust-chain workflow does not exist on the
default branch, so the honest claim is a **local** run over the committed tree. Say that, not "green in CI".

## Retrospective

- **Process, and the biggest one: refine and implement ran in the same session.** `docs/ai/README.md` § Session
  topology names exactly this CR type — multi-surface, novel abstraction, its own doc as the safety net — as one
  that **stays split**. I flagged it and proceeded on the user's "los", which was their call to make; the cost is
  that the clean-room proof was spent, and it is *unmeasurable from inside*. The tell showed up twice: the
  existing panel test's `rowButtons` indexing silently assumed one button per row, and PrimeVue's `InputText`
  needed a stub the CR never mentioned. A fresh implementer would have fed both back as "the CR under-specified
  phase 3's test harness"; I just fixed them, and the CR is now unfalsifiably "complete". **Both directions:**
  the "los" arrived without a decision on the split, and I should have put the trade as a concrete choice at
  that moment rather than flagging it and continuing — a one-line question would have cost nothing.
- **Planning: the first guard predicate was wrong, and only the bite caught it.** "No `contentEncoding: base64`
  anywhere in `auth.openapi.yaml`" is the shape I reached for first; that spec legitimately carries ten such
  fields (login challenge/response, recovery), so the guard would have been **red on arrival**. Root cause is
  `changes/README.md`'s own *"the easiest command encodes what you already believe"* — applied to a **guard's
  predicate** rather than to a locator, which is a case that file does not currently name. Fix: the predicate was
  run before it was written down, and it is recorded in the guard's docstring so it is not reached for again.
- **Planning: `adrs: [0004, …]` unquoted** — bare YAML read `0026` as octal `22`. `validate_changes.py` caught it
  on the first sweep, which is the guard doing exactly its job; the miss was mine for not reading the template's
  own comment, which says so verbatim two lines above where I typed.
- **Execution: a fixture coupling the CR should have located, not discovered.** Adding a second button to a list
  row could have broken every existing index-based assertion in `SessionsPanel.test.ts`. It did not — but only
  because the register mock defaults to nameless, which was a design choice made *after* seeing the risk, not
  before. That is luck wearing design's clothes.
- **Next time:** when a CR adds a control to a row of an **existing** list, its inventory carries the command
  that finds that list's test indexing (`grep -n "findAll\('.*row.*button'\)" <test>`) as a locator line — the
  same way § Upstream of every trap already requires the *gate* to be located beside the insertion point. The
  blast radius of a new button is the existing test's index arithmetic, and that is a `grep`, not a surprise.
