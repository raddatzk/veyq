---
id: cr-pairing-add-a-device-web
title: Add a device — the web half of the trusted-displays direction
status: in-progress
satisfies: [uc.account.onboard-device]
adrs: ["0059", "0058", "0028", "0026", "0005", "0004", "0003"]
invariants:
  - Operator-blind E2EE — the server relays two opaque public values and one ciphertext it cannot open
  - No code ever names a server (ADR 0059 §4)
  - Auth ≠ unlock — a session grants no plaintext (ADR 0003)
  - No route is session-gated but not unlock-gated (scripts/validate_shell_route_gate.py)
  - No new, deprecated or unmaintained dependency
---

## Goal

Build the **web** half of *add a new device* — the direction where a signed-in, unlocked device displays a
code and the new device scans it. The server half is already built and unchanged by this CR: five
`POST /api/pairing/*` endpoints, `SessionService.issueDelegated`, and `scripts/validate_pairing_shapes.py`.
This CR builds both screens of that one direction — the trusted device's *show a code → compare → confirm →
delegate → seal*, and the new device's *scan → answer → compare → collect → open → unlock → enrol* — plus the
shared tail (steps 5–7 of the use-case) that *sign in with a QR code* will reuse rather than rebuild.

It lands alone. It does **not** build the QR-sign-in direction, and it does **not** assemble the `wiki/`
section (see § The wiki bolt).

## Context & constraints

The durable state this builds on, all of it already written:

- [uc.account.onboard-device](../docs/features/account/use-cases/uc.account.onboard-device.md) — the flow,
  both main flows and E1–E5. § *Main flow — add a new device* is this CR's spec.
- [ADR 0059](../docs/decisions/0059-device-linking.md) — the ceremony. §1 (two directions, two entry points),
  §2 (the grant carries key *and* session), §3 (one envelope to the ephemeral key), §4 (no code names a
  server), §5 (compare, and the rule underneath), §7 (a deliberate gesture), §9 (the new device writes its own
  label), §10 (`DEVICE_KEY`, never `DEVICE`).
- [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) — the safety-number digest
  §5 reuses.
- [en.account.device-register](../docs/features/account/enablers/en.account.device-register.md) — write
  moment 1, and the single-writer rule §9 keeps intact.
- [ADR 0058](../docs/decisions/0058-revoke-never-takes-key-material.md) — why the copy may not promise that
  signing the device out takes the key back.
- `contracts/api/auth.openapi.yaml` tag `pairing` — the wire, **unchanged by this CR**.
- [ui-web-styleguide](../docs/crosscutting/ui-web-styleguide.md) § *Reuse first*.

**Three shapes are inherited from the server half and are not re-decided here.** If one of them turns out to
be wrong, that is an escalation, not something this CR resolves:

1. The channel is addressed by a client-minted **handle** that is deliberately not a public key.
2. `delegate` is its own call **before** `seal` — the grant is sealed client-side, so its plaintext must be in
   the trusted device's hand first.
3. The collect that returns the sealed grant **spends the channel whoever calls it** (D7 is the consequence).

**Measured facts about the built server half** (substrate: `main` @ `d897ea0`, clean tree):

| Fact | Where |
|---|---|
| Channel TTL `PT10M`, purge every 5 min | `backend/src/main/resources/application.properties:122-124` |
| `PENDING → ANSWERED → SEALED → COLLECTED`; also terminal: `SUPERSEDED`, `EXPIRED` | `…/account/pairing/Entities.kt:64-92` |
| **No `ABANDONED`, no abandon endpoint** — the three-attempt ceiling and the abandon are counted on the client | `…/pairing/Entities.kt:82-87` |
| `collect` marks `COLLECTED` **iff** `envelope != null`; reading the answer spends nothing | `…/pairing/PairingHandler.kt:105-109` |
| `delegate` needs `ANSWERED && userId == null`; `seal` needs `ANSWERED && userId == caller` | `…/pairing/PairingHandler.kt:70, 92` |
| `PairingChannel` carries **no status** — presence of `answer`/`envelope` is the whole state | `contracts/api/auth.openapi.yaml`, `validate_pairing_shapes.py` |

## Decisions

### D1 — The ephemeral transport key is a full `Identity` bundle, not a bare hybrid pair

The new device mints its single-use key with the existing `generateIdentity()`. Consequences, all reuse:

- the `answer` is `b64std(serializeIdentityPublic(pub))`; the trusted device reads it with
  `deserializeIdentityPublic`;
- the seal is `asymWrap({ x: pub.kemX, m: pub.kemM }, …)`, the open is
  `asymUnwrap(hybridSecret(priv), hybridPublic(priv), …)`;
- **`fingerprint()` applies unchanged** — which is what D2 rests on.

The Ed25519 half is unused by the wrap. It exists so the digest input is exactly the one ADR 0028 already
pins, rather than a new serialization invented for a single-use key. A bare `HybridPublic` would save 36 bytes
and cost a new wire serialization *and* a new digest input — both of which would then owe pinning, and both of
which turn this into a `contracts → web` CR. **This decision is what makes slice 2 a pure web slice.**

Sizes measured against the contract's own limits: `answer` = 1260 B → **1680** b64 chars (`maxLength` 8192);
`envelope` at a 700-byte plaintext → **2468** b64 chars (`maxLength` 8192). No contract change is needed and
none is made.

### D2 — The check number is `fingerprint()`'s first group: five digits

Both devices derive `fingerprint(answeringPublicBundle)` and display its **first group of five digits**. This
is not a new derivation — it is the vector-pinned digest (`contracts/crypto/vectors/v1.json#fingerprint`) plus
a display rule, so there is nothing new to pin.

ADR 0059 §5's *"your new device shows 4821"* is illustrative prose, not a pinned length; five digits is the
existing rendering unit and needs no new slicing rule. Five is enough because of what the compare actually
defends: the photographed-QR race, where the attacker answers the channel the victim's device could not. The
attacker cannot see the intended device's number — it never reached the server — so there is no target to
grind, and the number only has to be *distinguishable*, not unforgeable. (Two groups, ten digits, is free if
that reads better; it changes no reasoning.)

**The new device derives its number locally at mint time**, so the comparison works even when its `answer` was
refused. A `404` on the answer additionally replaces its screen with D9's terminal message.

### D3 — No crypto vector for the grant envelope, and therefore no contracts phase

*Measured, two angles.* Wrap contexts at call sites in `web/src` versus `wrap.*.context` in the vector file
**disagree**, and the disagreement is the finding: `content-key`, `media-meta`, `node-meta`, `scope-epoch` and
`share-key-recovery` are live and appear **0×** in `contracts/crypto/vectors/v1.json`. So "a new wrap context
needs a vector" is false in this repo; the vectors accreted per-CR with no stated rule.

The grant envelope is the least drift-exposed wrap here: it is sealed to a **one-time** key, opened within ten
minutes, leaves **no stored ciphertext** to outlive a version, and shares its KEK with no sibling context — so
`sym_device_register`'s domain-separation case has no analogue. A context drift between two client versions
fails **loudly** at `asymUnwrap`. Contrast the check number, whose drift would be a *silent* mismatch read as
an attack — which is exactly why that one is pinned, and it already is (D2).

**Escalation, stated so it can be overruled cheaply:** if the envelope should be pinned anyway, this CR gains a
phase 0 (`contracts`) and owes two hand-written `it(…)` blocks in `web/src/shared/crypto/v1.conformance.test.ts`
— that file is case-by-case, not a generic loop, so a new vector covers nothing automatically.

### D4 — The envelope plaintext is versioned utf8 JSON

`asymWrap` over `utf8(JSON.stringify({ v: 1, email, masterKey, refreshToken, label }))` under a new context,
mirroring how `identity-pins` and `device-register` seal utf8 JSON. `masterKey` is b64std. The label is bounded
by `en.account.device-register`'s own rules; the 8192-char ceiling leaves ~5 kB of plaintext headroom
(measured), so no extra cap is invented.

**`email` was added at implement time and the reason binds slice 3.** The onboarded device never types an
address, and **no authenticated read hands the caller their own back** (measured: `account.openapi.yaml` has no
such field; `account.email` is set only from the typed login field or from a session-vault restore). Without it
the shared tail cannot run at all — `enrolDeviceKey`, `cacheSessionVault` and write moment 1's `localWrapId`
are every one of them keyed by email. It leaks nothing: the envelope is sealed to a key only the granted device
holds, and that device is being handed the Master Key.

### D5 — The QR carries `veyqpair:<handle>` and nothing else

The handle is the inherited form; the scheme prefix exists only so one scanner can dispatch (D6), mirroring
`emergencyKit.ts`'s `veyqkit:`. A unit test pins the payload as **exactly** prefix + handle — the client-side
twin of ADR 0059 §4, which `validate_pairing_shapes.py` covers only on the wire. Its negative (the payload
contains no `://`, no scheme, no host-shaped token) ships with the positive control that a real handle
round-trips through encode → decode unchanged.

### D6 — One scan entry on the sign-in form, dispatched on the prefix

`KitScanner.vue` becomes decoder-agnostic — it emits the raw decoded string, and `parseKit` moves to its
caller — and `LoginView`'s **existing** scan entry dispatches: `veyqkit:` → the Emergency-Kit flow unchanged,
`veyqpair:` → the pairing join. This reuses the camera lifecycle, the `scanSupported` capability gate, the
overlay and the `unlock.scan*` copy, and it adds no second button whose difference a user cannot see.

**The component has no tests today.** So the refactor lands with that component's **first harness**, the same
move E3 made for `LoginView` — refactoring an untested component with no safety net is the risk this CR is
choosing to retire, not to take.

*Rejected:* a separate pairing scanner entry. It duplicates the permission and no-camera handling that
`KitScanner.vue:19-31` and `LoginView.vue:75` already solve, in the one place where a second, subtly different
solution is worst.

**The decoder under D6 is settled, and not in this file.** `jsqr` is de facto abandoned upstream and is
**kept deliberately**; the measurement, the reason and the three triggers that reopen it are the standing
exception recorded in [`web/CLAUDE.md`](../web/CLAUDE.md) § Conventions. What that binds here: do not swap the
decoder inside this CR, and do not widen jsQR's reach past the single scan entry D6 describes.

### D7 — The poll's stop condition, and the consequence nobody may undo

**The trusted device polls `collect` only while awaiting the `answer`, and stops the instant it has one. It
never polls again.** After `seal`, a `collect` from *any* caller returns the envelope and marks the row
`COLLECTED` — so a trusted device that keeps polling eats its own grant before the new device arrives.

The consequence is deliberate and is the part that gets re-broken: **the trusted device cannot show "the new
device arrived."** Its terminal state is *"sealed — finish on the new device."* A later "did it work?" poll
re-opens the defect, so this ships as a comment on the line that stops the loop, not only in this file.

**The new device polls** from after its `answer` until `envelope` is present. Its loop is bounded by the
10-minute TTL and a `404` ends it (D9). The three existing poll modules
(`grantDeliveryPolling.ts`, `scopeRotationPolling.ts`, `mediaRevisionPolling.ts`) are the structural template —
fire once immediately, pause on `document.hidden`, re-fire on `visibilitychange`, idempotent `start()` — but
**none of them can be reused**: all three gate on an unlocked vault, and this is the first loop in the client
that runs with neither session nor vault.

### D8 — E2 abandons client-side, and its positive control is a fresh handle

There is no abandon endpoint and no `ABANDONED` status. Declining the comparison means: do not call
`delegate`, do not call `seal`, discard the handle, let the row expire. *"Start over"* mints a **new** handle.

Every negative here owes a positive control on the same predicate:

| Negative | Positive control, same predicate |
|---|---|
| numbers differ → `delegate` and `seal` are **never called** | numbers match → both are called, in that order |
| no retry in place → the restart's handle **≠** the abandoned one | the restart's ceremony completes normally |
| declined *stay signed in* → **no wrap** is enrolled | accepted → a `DEVICE_KEY` wrap is enrolled, and the ordinary remembered device still enrols |
| the trusted device stops polling after the answer | before the answer it **did** poll (≥1 `collect`) |
| the new device writes its own register entry | the trusted device writes **no** entry for it |
| no password / Secret Key reaches the new device | the ordinary first sign-in still demands both |

A bare *"an error was shown"* is refused throughout: it is produced equally well by a broken form, and it is
the exact shape the mismatch case must not be allowed to fake.

### D9 — Every terminal state, and why the client cannot branch on any of them

`EXPIRED`, `SUPERSEDED` and `COLLECTED` all answer `404 pairing_channel_invalid`, deliberately
indistinguishable (uc E1). The client therefore **cannot** and **must not** branch on them: a `404` on any
pairing call is one terminal message — *"this code is no longer valid — start again"* — and never a retry.
`PENDING`/`ANSWERED`/`SEALED` are read only through the presence of `answer`/`envelope`, because
`PairingChannel` deliberately carries no status.

### D10 — Where each half lives, and the gate that decides who arrives

- **Trusted half** — a `pairing` item in `SettingsHubView.vue`'s **personal** category plus one arm in its
  `activeView`. The gate is `/settings/:section?`'s `requiresUnlock: true` in `web/src/router.ts`, and here it
  is **correct**: the trusted device is unlocked by premise. No router change.
- **New-device half** — **no route at all**, offered on the sign-in form. `LockedSignOut.vue` is the shape
  (routeless, offered from that form), except this one has no session either. `validate_shell_route_gate.py`
  stays green precisely because the CR adds no `layout: 'app'` route; the shortcut it forbids is the one this
  half would otherwise reach for.

### D11 — The slide confirmation is built here, from scratch

ADR 0059 §7 wants a deliberate gesture, not a tap. *Measured:* no slide/swipe-to-confirm exists anywhere in
`web/src`, and PrimeVue's imported set has nothing behind it — the current documented pattern for a
destructive confirm is `window.confirm` (`SessionsPanel.vue:30-32`). So this is a shared primitive built from
pointer events and a progress track, reused by slice 3. **This is not a wrapper around an existing control**,
and the estimate says so rather than discovering it at implement time.

### D12 — Slice 2 owns the shared tail

Use-case steps 5–7 — open the envelope, take over the session, first unlock, register **write moment 1** with
the handed-over label, `DEVICE_KEY` enrolment or a declined *stay signed in* — are identical in both
directions. They are built here; slice 3 reuses them.

### The wiki bolt

*Measured:* `wiki/` carries nothing on device linking — the only QR mentions in `wiki/en/is-my-data-safe.md`
and `wiki/de/sind-meine-daten-sicher.md` are ADR 0028's safety-number comparison. The use-case's plain-language
section promises **both** directions, so assembling it after slice 2 alone would publish a promise half the
buttons cannot keep. **Slice 2 may land alone on the condition that the `wiki/` section is assembled only
after slice 3.**

## Inventory

Locators, not tallies — run them at implement time and read the list.

| What | Command |
|---|---|
| Settings section list + its render arm | `rg -n "NavCategory\|categories = computed\|activeView\|id === '" web/src/views/SettingsHubView.vue` |
| **The gate** for the trusted half | `rg -n "requiresUnlock\|layout: 'app'\|beforeEach" web/src/router.ts` |
| **The gate** for the new-device half (the routeless precedent) | `rg -rn "LockedSignOut" web/src` |
| Scanner + its couplings to break | `rg -n "jsQR\|parseKit\|KitData\|unlock.scan" web/src/features/account/emergencyKit/ web/src/views/LoginView.vue` |
| QR encoder call sites (confirm the PDF-only claim) | `rg -n "qrcode\|toDataURL\|toCanvas" web/src` |
| Poll-loop template + every gate they take | `rg -n "setInterval\|visibilitychange\|POLL_INTERVAL_MS" web/src` |
| Register write moments | `rg -n "rememberThisDevice\b\|rememberThisDeviceWrap\|reconcileWithSessions\|renameDeviceOfFamily\|noteThisDevice\|noteWrapOfThisDevice" web/src` |
| `DEVICE_KEY` enrolment + the decision site | `rg -n "enrolDeviceKey\|enrolConvenienceWrap\|remember = ref\|isRememberSupported" web/src` |
| Crypto pieces D1 reuses | `rg -n "generateIdentity\|serializeIdentityPublic\|deserializeIdentityPublic\|hybridPublic\|hybridSecret\|fingerprint" web/src/shared/crypto/v1.ts` |
| i18n insertion points — **flat namespaces, there is no `account.` prefix** | `rg -n "^  [a-zA-Z]+: \{" web/src/i18n/index.ts` |
| Every guard anchoring on what this touches | `rg -ln "router.ts\|LoginView\|requiresUnlock\|web/src" scripts/*.py` |
| The generated client is **stale** — pairing ops are absent | `rg -li pairing web/src/api/generated \|\| echo "absent → npm run generate:api"` |
| Unauthenticated API-factory precedents | `rg -n "recoveryApi\|publicSharesApi\|sessionApi" web/src/api/client.ts` |
| Test-tree blast radius | `rg -ln "KitScanner\|deviceRegister\|deviceKeyWrap\|LoginView" web/src --glob '*.test.ts'` |

## Phases

- [x] **1 web — plumbing.** `npm run generate:api` (the tree is stale; the contract already carries all five
      ops, so this is a regeneration, not a spec change), an **unauthenticated** `pairingApi()` factory beside
      the `recoveryApi()`/`publicSharesApi()` precedents, a `pairing` module (mint handle, encode/decode the
      `veyqpair:` payload, mint the transport bundle, derive the check number, seal/open the envelope), an
      on-screen QR renderer, and the `KitScanner` decoder refactor **with that component's first test**.
      *Done when:* `npm run generate:api && npm run check` green, and unit tests pin the payload format (D5,
      both directions), the envelope round-trip, and the check-number pair — equal bundles → equal digits,
      distinct bundles → distinct digits.
- [x] **2 web — the trusted half.** The settings section: open a channel, show the QR, poll for the answer,
      derive and show the check number, the slide confirmation naming the device and the consequence, then
      `delegate` → `seal` → stop.
      *Done when:* `npm run check` green, with tests for D8's first, second and fourth rows and for D7's stop
      condition (a `collect` call count that does **not** grow after `seal`).
- [ ] **3 web — the new-device half and the shared tail.** BLOCKED, and not on anything this CR can decide.
      The sign-in-form scan entry and its dispatch, mint → `answer` → show the number → poll `collect` → open →
      unlock → session takeover → register write moment 1 → `DEVICE_KEY` enrol or decline.
      *Done when:* `npm run check` green, with tests for D9's single terminal message, D8's third, fifth and
      sixth rows, and a regression control that the sign-in form's existing paths are unchanged.

      **The blocker, measured at implement time.** `delegate` returns the refresh token in the response
      **body**, and this client cannot keep a body token: `boot()` (`web/src/app/boot.ts`) revives a session
      only through `refreshSession()` with an empty body, i.e. from the httpOnly cookie, and `TokenResource`
      sets a cookie only when the request already carried one. `session.refreshToken` is in memory. So the
      onboarded device would be signed in for **exactly one page load**, hold a `DEVICE_KEY` wrap it can never
      reach again, and have no password to fall back to — which contradicts the use-case's own postcondition.
      Persisting the token client-side was rejected as the fix: it would be the first multi-tab-unsafe session
      in this client, and its failure mode is not "sign in again" but ADR 0004's clone case — two tabs
      presenting the same token revoke the family of a device that has no password to recover with. **The
      correction belongs to the delegated session's transport (contracts → backend), i.e. to a slice-1 follow-up
      and its own refine, not to this web slice.** Phase 3 resumes once that lands.
- [x] **4 coverage.** Narrow the `web:` gap in
      `docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml` to what slice 3 still owes, and
      state the E2E limit rather than leaving it a silently missing ref. **Keep `status: planned` and both
      gaps** — `scripts/validate_coverage.py` forbids test refs under `planned`, and the standing reason (no CI
      run has ever executed here) is unchanged by this CR.
      *Done when:* `for f in scripts/validate_*.py; do python3 "$f"; done` green.

## Definition of done

All three web phases green under `npm run check` (after `npm run generate:api` — a green `check` on a stale
client proves nothing about the pairing ops), the coverage file updated as phase 4 states, and the whole guard
glob green: `for f in scripts/validate_*.py; do python3 "$f"; done`. `validate_shell_route_gate.py` and
`validate_pairing_shapes.py` must be green **and** unchanged — this CR touches neither the router's shell
routes nor the pairing wire.

**No new dependency.** `jsqr@1.4.0` and `qrcode@1.5.4` are already present and already used; nothing is added
and nothing is swapped (D6).

**On promoting a proof to a guard:** the two structural invariants at risk here already have permanent guards
(`validate_pairing_shapes.py` for ADR 0059 §4 on the wire, `validate_shell_route_gate.py` for the route
class). The two this CR adds — the QR payload carries no server (D5) and the trusted device stops polling
(D7) — are client behaviours a `scripts/` validator would have to parse TypeScript to see; both ship as unit
tests plus, for D7, a comment on the line that stops the loop. No third guard is added, and this sentence is
the reason.

## Retrospective

- **Planning — the CR specified a session takeover it had never followed to its consumer.** D12 spends three
  words on "take over the session", and on the web that step cannot be built as written: `delegate` returns the
  refresh token in the body, and this client persists a session only through the httpOnly cookie. The root
  cause is one level above a wrong fact — the *shape* was inherited. Slice 1 chose the response shape with no
  consumer in front of it, and this refine listed it under "three shapes not re-decided here" without asking
  what the one client that reads it would do with the value. `changes/README.md` § *the claim was inherited*
  describes exactly this failure for facts; it applies to handed-over **values** too, and that is the general
  form worth keeping.
- **Planning — D4's field list was designed from the ADR's sentence, not from the code that consumes it.** ADR
  0059 §3 says "the Master Key, the refresh token and the label", so the CR wrote those three. The shared tail
  needs a fourth: `enrolDeviceKey`, `cacheSessionVault` and write moment 1's `localWrapId` are each keyed by
  email, and the onboarded device has no way to learn one. Same root cause as above, and the same one-line fix
  would have caught both.
- **Execution — two real defects, and both were only visible because the test was written about the property
  rather than the happy path.** `SlideToConfirm`'s keyboard path accumulated `1/10` ten times to
  0.9999999999999999, so a control that visibly reached 100% confirmed nothing. And `KitScanner`'s first
  harness silently ran the *permission-denied* branch for three cases, because happy-dom's `srcObject` setter
  rejects a stand-in stream and the component treats that as "no camera" — the assertions then read "nothing
  was scanned", which is exactly what a passing negative looks like. The harness now says so at the stub.
- **Process — the stop-and-ask was right, and it was the user's instruction that produced it.** All three
  findings were discoverable at refine; none needed code to see. The session's framing (report a finding about
  the CR rather than bridge it) is what turned a silent D4 patch and a one-page-load device into two decisions
  the owner actually made. Worth keeping as the default, not as a special instruction.
- **Next time:** at refine, for **every value one surface hands another, write the consuming call site beside
  it** — one line, in the CR. Both misses above die at that line, and it is the cheapest possible form of the
  "run the locator" rule applied to a payload instead of to a symbol.
