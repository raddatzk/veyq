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

**This CR's phase 3 is not "slice 3", and one of the two live formulations is wrong.** `docs/inbox.md` #12
lists what remains as *(1) slice 2's new-device half + the shared tail, (2) slice 3 (sign in with a QR code)* —
two items. The coverage file's web gap instead opens its remaining list with *"What is still owed here, all of
it slice 3's"* and then enumerates both under that one label. **The coverage sentence is the false one**, and it
contradicts this CR's own D12 (*"They are built here; slice 3 reuses them"*) as well as the DoD, which requires
all three web phases green. Phase 3 corrects it; nothing else in the gap's substance changes.

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

**Measured facts about the adoption verb**, which did not exist when this CR was written (substrate: `main` @
`8f00dac`, clean tree). Its *reasons* are durable state and are read there, not re-derived here — ADR 0004
§ Transport, § web refresh cookie lifetimes and § Consequences (why it is a verb of its own, and why it is
deliberately **not** narrowed to delegated families):

| Fact | Where |
|---|---|
| `TokenAdoptRequest { refreshToken, sessionScope }` — **both required**, `sessionScope` ∈ `SESSION \| PERSISTENT` | `contracts/api/auth.openapi.yaml:745-780` |
| `200` → `SessionResponse { accessToken, family: { id } }`, `refreshToken` **omitted**, refresh rides `Set-Cookie` (declared on the op) | `contracts/api/auth.openapi.yaml:150-192` |
| `400` when the request already carries a refresh cookie; `401` for a refused token, undifferentiated | same |
| Token ops emit **no `Authorization` header** — the auth spec declares no security on them — so `tokenApi()` works from a session-less device and **no `publicTokenApi()` factory is needed** | `web/src/api/generated/auth/apis/TokenApi.ts:77-101`; contrast `publicPairingApi()` at `web/src/api/client.ts:71` |
| The generated client is **stale again** — `adoptSession` is absent, phase 1's regeneration predates the verb | `rg -n adoptSession web/src/api/generated` → empty |

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

### D12 — Slice 2 owns the shared tail, and every value in it is named with its consuming call site

Use-case steps 5–7 — open the envelope, adopt, first unlock, register **write moment 1** with the handed-over
label, `DEVICE_KEY` enrolment or a declined *stay signed in* — are identical in both directions. They are built
here; slice 3 reuses them.

**This decision used to be those three words and nothing else, and that is what blocked phase 3.** So it is
written out here in the form this CR's own retrospective asks for: for every value one step hands the next, the
call site that consumes it, with the signature it actually has. Measured on `main` @ `8f00dac`.

The order is fixed by ADR 0059 §2 and cannot be rearranged: **answer → adopt → unlock → register → enrol.**

| # | Step | Value in | Produced by | Returns / effect |
|---|---|---|---|---|
| 0 | *stay signed in* | — | the join card's own control, **before any call** (D15) | `sessionScope: SESSION \| PERSISTENT` |
| 1 | open the envelope | `priv` from `mintTransportKey()`; `envelope` from `collectPairingChannel(handle)` | `openGrant(priv, envelope)` — `pairing.ts:111` | `PairingGrant { email, masterKey, refreshToken, label }` |
| 2 | adopt | `grant.refreshToken`, `sessionScope` | `tokenApi().adoptSession(…)` — **absent from the generated tree today**, see phase 3 | `SessionResponse { accessToken, family: { id } }`; **no** body `refreshToken`; `Set-Cookie` |
| 3 | install the session | `res.accessToken`, `null`, `res.family.id` | `setTokens(accessToken, refreshToken, familyId?)` — `app/session.ts:17` | void; sets `session.familyId` — the reason adopt returns it |
| 4 | install the account | `grant.email` | `setAccount(email)` — `app/account.ts:7` | void; sets `account.email`, which step 6 reads |
| 5 | first unlock | `grant.masterKey`, `grant.email`, `grant.label` | **no exported function takes a Master Key** — `openVault(material, masterKey)` is module-private at `unlock/unlock.ts:28` (D13) | `setUnlocked(mk, identityPrivate)` + fires `noteThisDevice()` |
| 6 | write moment 1 | `deviceId()`, a label, `session.familyId`, `await localWrapId(account.email)` | `noteThisDevice()` — `unlock/unlock.ts:60`, private, **takes no arguments** (D14) → `rememberThisDevice(thisDeviceId, fallbackLabel, familyId, wrapId)` — `deviceRegister.ts:289` | `reg.devices[id] = { label: prior?.label ?? fallbackLabel }` |
| 7a | enrol (accepted) | `grant.email`, `masterKey()` | `enrolDeviceKey(email, masterKey)` — `rememberMe/deviceKeyWrap.ts:52` | `Promise<string>` — the new wrap id |
| 7b | record the wrap | that wrap id | `rememberThisDeviceWrap(thisDeviceId, fallbackLabel, wrapId)` — `deviceRegister.ts:314` (write moment 2) | void |
| 7c | declined | `grant.email`, `masterKey()` | `cacheSessionVault(email, masterKey)` — `rememberMe/sessionVault.ts:47` | void; and **no wrap of either kind** |

**Reading that table produced three findings, and each is a decision below rather than a discovery at implement
time.** They are the same class the retrospective already named — a value with no consumer — found this time by
running the locator instead of by building against it:

- **`grant.label` has no consuming call site at all** (row 6) → **D14**.
- **`grant.masterKey` has no consuming call site either** (row 5) → **D13**.
- **The tail it would "reuse" is not a module.** `enrolConvenienceWrap()` is a `<script setup>` local inside
  `LoginView.vue:104`, reading the component's own `remember` / `noPersist` / `email` / `password` refs, and its
  declined branch calls `trustThisDevice(email, password, mk)` (`trustDevice/trustDevice.ts:27`) — a **password**
  this device never learns. So the tail is *extracted*, not reused → **D13**, **D15**.

**Two values that need no work, stated so nobody re-derives them.** `res.family.id` reaches write moment 1
through `session.familyId` because the tail's unlock happens in the **same page load** as its adopt — the
cold-boot hole `noteThisDevice`'s own comment describes does not apply here. And `grant.email` survives the
reload without being persisted separately: both stores the tail writes are keyed by it
(`cacheSessionVault` → the `sessionStorage` record; `enrolDeviceKey` → the IndexedDB `RememberRecord`), and
`restoreVaultFromCache()` (`unlock.ts:105`) calls `setAccount(source.email)` from whichever one answers.

### D13 — The tail is a module, and unlocking from a handed-over Master Key is a new exported entry

The tail lives in `web/src/features/account/pairing/onboardTail.ts` — one function taking the opened grant and
the answered scope, running rows 2–7 of D12's table in that order. It is in `pairing/` and not in `login/`
because its two callers are this direction's join card and slice 3's, and D12 promises slice 3 reuses it.

It needs one thing that does not exist: **an exported unlock that takes a Master Key.** Measured — `unlock.ts`
exports `unlockMasterKey(password, secretKeyB64)`, `restoreVaultFromCache()` and `unlockWithDevice(email,
password)`; the shape the grant fits, `openVault(material, masterKey)`, is module-private (`unlock.ts:28`) and
every export reaches it through a factor this device does not have. So `unlock.ts` gains one export that
fetches the key material and opens the vault from a Master Key it is handed. `restoreVaultFromCache()` is its
template and its proof that the shape is right: it already does exactly this — `setAccount(email)` **before**
`openVault`, because `noteThisDevice()` reads `account.email` — for the cached-key case.

`openVault` stays the single choke point. Nothing here adds a second way into the vault; it adds a second way
to *reach* the one that exists.

### D14 — The handed-over label is threaded into write moment 1, and no fourth write moment is created

*Measured, and this is the finding D12's table was written to produce.* `rememberThisDevice(thisDeviceId,
fallbackLabel, familyId, wrapId)` (`deviceRegister.ts:289`) writes `label: prior?.label ?? fallbackLabel`, so on
a device with no prior entry — every onboarded device — `fallbackLabel` **is** the label. Its only caller,
`noteThisDevice()` (`unlock.ts:60`), takes no arguments and hardcodes
`guessDeviceLabel(i18n.global.t('sessions.unknownDevice'))`. D4 therefore seals a `label` that, as the code
stands, is opened and thrown away, and the device would appear in the sessions list under a user-agent guess.

The fix is to thread it: the new unlock entry (D13) carries an optional label through `openVault` into
`noteThisDevice`, which passes it where it passes the guess today. Every other unlock path keeps the guess,
unchanged.

*Rejected — a separate write after unlock.* It is a **fourth write moment**, which
`en.account.device-register` and the coverage criterion `the-onboarded-device-writes-its-own-register-entry`
both forbid by name, and it would race the fire-and-forget moment 1 for the same key. *Rejected — pre-seeding
the register before unlock:* the register is Master-Key-sealed, so there is no "before unlock" to write in.

### D15 — *Stay signed in* is answered on the join card before adopt, and it decides two things at once

ADR 0059 §2 and ADR 0004 § web refresh cookie lifetimes: the device **being onboarded** answers it, before its
session exists, because the lifetime is fixed at adoption and no later refresh can move it. The same answer is
ADR 0005 §22's tier choice. Both arms are the criterion (D8's third row):

- **Accepted** → `sessionScope: PERSISTENT`, then `enrolDeviceKey(grant.email, mk)` → `rememberThisDeviceWrap`.
- **Declined** → `sessionScope: SESSION`, then `cacheSessionVault(grant.email, mk)` and **no wrap at all**.

**The declined arm is not `enrolConvenienceWrap`'s declined arm.** That one also calls `trustThisDevice(email,
password, mk)` to keep a password `DEVICE` wrap; this device has no password, and ADR 0005 §22 carries the
carve-out explicitly — a linked device's Off tier is that recipe *minus* the `DEVICE` wrap, so closing the
browser ends it and the next visit is a fresh pairing, not email + password. `trustThisDevice` is never called
on this path, and that absence is asserted, not assumed.

**Where the question cannot be asked**, the answer is `SESSION` with no wrap. `LoginView.vue:59` gates the
existing checkbox on `isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)`; with no IndexedDB or the
capability off, `enrolDeviceKey` has nowhere to write, and the declined arm is the only reachable tier. The
card shows the choice only when it can honour it — which is what the existing form already does.

### D16 — Every terminal state of the tail, and the one call that may never be retried

D9 enumerates the *pairing* terminal states; the tail adds two more, on a different verb, and the fifth tell in
`changes/README.md` is why they are enumerated here rather than folded into D9's single message:

| State | Cause | What the client does |
|---|---|---|
| `404` on any `/api/pairing/*` | expired, superseded, or already collected — indistinguishable by design | D9's one terminal message; **never** a retry |
| `401` on `adopt` | the grant token was unknown, expired, superseded, or its family revoked | the same terminal message as D9 — but the grant is now spent, so *"start again"* means a **new ceremony on the trusted device**, not a reload here |
| `400` on `adopt` | **this browser already carries a refresh cookie** — adoption is only for a device that holds no session here | a **different** message, because the remedy is different: this browser is already signed in somewhere. Do not fold it into D9's |
| anything else | transport | leave the poll running; only a `404` ends it (D7's template) |

**And the one that is not an error state at all: `adopt` may be presented exactly once.** The rotation supersedes
the token, so a second presentation is ADR 0004's clone case and takes the whole family down — the device that
just arrived, with no password to recover by. So the adopt call is **not** wrapped in `withRefresh`, is not
reached by any generic retry, and a lost response is a terminal state and not a case to try again. This is
D7's hazard in a second place: a call whose retry eats the ceremony. Like D7 it ships as a comment on the call
site, because it is the line someone later "hardens" with a retry.

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
| **Phase 3 — the gate that decides who arrives at the join entry.** Two angles, and the router is deliberately the second: `/login` carries no `meta` at all, so the gate is the **form's own** `v-if` | `rg -n "needsSecretKey\|isTrusted\|scanSupported\|rememberSupported" web/src/views/LoginView.vue` **and** `rg -n "name: 'login'\|requiresUnlock\|beforeEach" web/src/router.ts` |
| Phase 3 — is the generated client current for `adopt`? (empty = regenerate) | `rg -n "adoptSession\|TokenAdoptRequest" web/src/api/generated \|\| echo "absent → npm run generate:api"` |
| Phase 3 — the tail's consuming call sites, D12's table re-run as one command | `rg -n "export (async )?function (openVault\|noteThisDevice\|rememberThisDevice\|rememberThisDeviceWrap\|enrolDeviceKey\|cacheSessionVault\|trustThisDevice\|setTokens\|setAccount\|localWrapId)\b" web/src -A3` |
| Phase 3 — what phase 1 built and nothing yet calls (the toolkit this phase consumes) | `rg -n "parsePairingPayload\|mintTransportKey\|transportAnswer\|openGrant\|answerPairingChannel" web/src --glob '!*.test.ts' --glob '!src/api/generated/**'` |
| Phase 3 — every retry wrapper that must **not** reach `adopt` (D16) | `rg -n "withRefresh" web/src \| wc -l && rg -n "export async function withRefresh" -A12 web/src/features/account/login/login.ts` |

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
- [ ] **3 web — the new-device half and the shared tail.** **Unblocked 2026-08-13**: the transport correction
      this phase waited on landed as `POST /api/token/adopt` (contracts + backend, `b15d8d0`), and the CR that
      built it is consumed. What the blocker paragraph used to say is now durable state in ADR 0004
      § Transport / § web refresh cookie lifetimes and in the coverage file's backend gap — including the part
      of it that turned out to be **false** (nothing in this client serializes refresh, so a script-held token
      would not have been the *first* multi-tab-unsafe session; that defect predates linking and is inbox #123,
      which this phase inherits like every other web session and does **not** fix).

      **What the phase builds, in the order ADR 0059 §2 fixes.**

      1. **Regenerate first.** *Measured:* `rg -n "adoptSession" web/src/api/generated` is **empty** — phase 1's
         regeneration predates the adopt contract, and `web/src/api/generated/` is gitignored, so a green
         `npm run check` here would be a green run against a client that has never seen the verb
         (`web/CLAUDE.md` § Prove your work names exactly this trap).
      2. **The entry and its dispatch (D6).** `LoginView.vue:254-265` is the site: `acceptsScan` widens from
         `parseKit(text) !== null` to either payload, and `onScanned` dispatches — `veyqkit:` → `applyKit`
         unchanged, `veyqpair:` → the join card. `KitScanner.vue` needs no further change; phase 1 already made
         it decoder-agnostic (`props: { accepts?: (text: string) => boolean }`, emits `found: [string]`).
      3. **The join card**, routeless, replacing the form the way `LockedSignOut.vue` does
         (`LoginView.vue:317-323` is the mechanism, a `v-if` inside the sign-in `Card`): mint the transport key,
         show the check number derived **locally at mint time** (D2), `answer` the channel, poll `collect` until
         an `envelope` is present, then run the tail.
      4. **The grant poll.** A sibling of `pairingAnswerPolling.ts`, not a reuse of it: same structure (fire
         once immediately, skip hidden ticks, re-fire on `visibilitychange`, idempotent `start`, `404` → one
         terminal call), opposite stop condition — it stops on `envelope`, where the trusted device's stops on
         `answer`. The three older loops still cannot be reused, for the reason `pairingAnswerPolling.ts:3-10`
         already records.
      5. **The tail (D12–D16).** `onboardTail.ts`, the new Master-Key unlock export (D13), the label threaded
         into write moment 1 (D14), the *stay signed in* answer taken before adopt and driving both the scope
         and the tier (D15), and the terminal-state handling (D16).
      6. **One coverage correction, because durable state is wrong today.** The web gap says *"What is still
         owed here, all of it slice 3's"* over a list whose first item is **this** phase. Phase 3 and slice 3
         are different things (see the note below); the sentence is relabelled, the gap is otherwise narrowed
         to what actually remains, and **`status: planned` and both gaps stay** — `validate_coverage.py` forbids
         test refs under `planned`, and the standing reason (no CI run has ever executed here) is unchanged.

      *Done when:* `npm run generate:api && npm run check` green, plus
      `for f in scripts/validate_*.py; do python3 "$f"; done` green, with:
      - D8's **third** row both ways — declined → `trustThisDevice` and `enrolDeviceKey` are **neither** called
        and no wrap of either kind exists, with the accepted arm enrolling a `DEVICE_KEY` and the ordinary
        remembered device still enrolling as the control that the path itself works;
      - D8's **fifth** row — the onboarded device writes its own register entry, **under the label it was
        handed** (D14; the negative that this is the UA guess is what a happy-path test cannot see), and the
        trusted device writes none for it;
      - D8's **sixth** row — no password and no Secret Key reaches this device, with the ordinary first sign-in
        still demanding both as the positive control;
      - **the ordering ADR 0059 §2 forces** — adopt is called **before** unlock, register and enrol, asserted
        as an order and not as a set, since every one of them "happened" in a wrong-order implementation too;
      - D16's three terminal states told apart — `404` and adopt's `401` reach the terminal message, adopt's
        `400` reaches a different one, and **adopt is called exactly once** across a failed tail (the assertion
        is a call count, because a retry here revokes the family rather than failing);
      - `the-locked-entry-adds-no-shell-route`'s behavioural half **for this entry**: it is offered on the
        locked card, choosing it attempts no unlock, and every path already on that card behaves as before —
        E3's two ways out **and** the Emergency-Kit scan through the widened dispatch;
      - `validate_shell_route_gate.py` green **and unchanged** — this phase adds no route at all.
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

**No contracts phase and no backend phase, still.** `POST /api/token/adopt` is already on the spec and built;
phase 3 consumes it and changes neither surface. What phase 3 owes the contract is a **regeneration**, not an
edit.

**Explicitly out of scope, and not to be quietly absorbed.** Inbox **#123** — nothing in this client serializes
refresh, so two tabs can revoke their own family — is a real, older defect that every web session carries; the
adopted session inherits it exactly like the rest and this CR neither fixes nor hides it. Inbox **#124** —
`Set-Cookie` is undeclared across the contracts — is fixed *only* where `adopt` already declares it; the sweep
across login/refresh/logout is that row's. And the `wiki/` section stays unassembled until slice 3 (§ The wiki
bolt).

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
