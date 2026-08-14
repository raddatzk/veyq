---
id: cr-pairing-qr-sign-in-web
title: Sign in with a QR code — the new device displays, the trusted device scans and transcribes back
status: planned
satisfies: [uc.account.onboard-device]
adrs: ["0059", "0026", "0028", "0004", "0005", "0040"]
invariants:
  - operator-blind E2EE — the server relays a routing handle, one opaque answer and one ciphertext
  - ADR 0059 §4 — no code names a server, and no client follows one out of a scan
  - ADR 0059 §6 — the code proof gates the SEALING on the trusted device, never a server-side release
  - no session-gated-but-not-unlock-gated shell route (`scripts/validate_shell_route_gate.py`)
  - en.account.device-register's single-writer rule — no fourth write moment
  - the jsQR exception in `web/CLAUDE.md` holds only while our own codes stay low-version
---

## Goal

Build the second direction of [ADR 0059](../docs/decisions/0059-device-linking.md), *sign in with a QR code*,
on `web/` only. The device that wants in mints its transport keypair, opens a pairing channel and displays that
channel's handle under its **own marker**; the signed-in device scans it and displays a six-digit **pairing
code**; the account holder types that code into the new device, which sends its public key together with a
proof of the code as the channel's one relayed answer; the trusted device verifies that proof against the code
it displayed, and only **then** takes the device's name and the slide and runs `delegate` → `seal`. From the envelope on it is
`runOnboardTail` unchanged — the tail slice 2 built for exactly this.

Nothing on the wire changes shape. The whole slice is web plus a prose correction, and the two facts that make
that true were both measured against durable state that said otherwise; this refine corrected those documents
(ADR 0059 §6, the use-case's QR steps 2/5 and E2, the coverage criterion) before this CR was written.

## Context & constraints

- [ADR 0059](../docs/decisions/0059-device-linking.md) §1 (the two directions), §4 (no server address in a
  code), §5 (transcribe, not compare — and the general rule under it), §6 (the proof gates the sealing, and the
  two named-wrong alternatives), §7 (the deliberate slide), §8 (single-use, one outstanding per account).
- [uc.account.onboard-device](../docs/features/account/use-cases/uc.account.onboard-device.md) § Main flow —
  sign in with a QR code, and E1–E5.
- [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) — why no server-side release may exist.
  [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) — the digest reused for the
  *other* direction's check number, deliberately **not** reused for this direction's code.
  [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) — why nothing here may claim
  coverage yet.
- [`contracts/api/auth.openapi.yaml`](../contracts/api/auth.openapi.yaml) — the five `/api/pairing/*` verbs and
  the seven schemas `scripts/validate_pairing_shapes.py` pins as exact property sets.
- [`web/CLAUDE.md`](../web/CLAUDE.md) § Conventions — the granted jsQR exception, whose stated premise is that
  *we only ever decode low-version codes our own client produced*. D1 below is what keeps that true.
- Working rules: [`docs/ai/README.md`](../docs/ai/README.md), [`changes/README.md`](README.md),
  [`web/CLAUDE.md`](../web/CLAUDE.md).

## Decisions

### D1 — the code carries the **handle**, under its own marker, and never the transport key

The use-case previously said this direction *"displays the public key as a code"*. It cannot, and the reason is
size. Measured:

```
$ node -e "const {ml_kem768}=require('@noble/post-quantum/ml-kem.js');
  console.log('ml-kem768 pub =', ml_kem768.keygen(new Uint8Array(64)).publicKey.length)"
ml-kem768 pub = 1184
# serializeIdentityPublic = lp(kemX)+lp(kemM)+lp(sign), lp is u32be-prefixed (v1.ts:30)
#   → 4+32 + 4+1184 + 4+32 = 1260 bytes → 1680 base64 chars

$ cd web && node -e "const QR=require('qrcode');
  for (const n of [44, 1680]) { const p='veyqpair:'+'A'.repeat(n); const q=QR.create(p);
    console.log(p.length,'chars → QR version', q.version, '/', q.modules.size, 'modules') }"
53 chars → QR version 3 / 29 modules
1689 chars → QR version 28 / 129 modules
```

Version 28 is a 129×129 code nobody scans reliably off a laptop screen, and it sits **past version 23** — the
range `web/CLAUDE.md`'s jsQR exception explicitly says our codes never reach. That bound is not decoration: it
is one of the three things that made keeping an abandoned decoder defensible at all, and the alternatives it
was weighed against were rejected for self-hostability. Putting the key in the QR would quietly withdraw it.

Nothing is lost by relaying the key instead: in **this** direction the scan authenticates nothing (§5 — the
attacker authors the QR, therefore the key), so the out-of-band channel buys no key authenticity here. The
**code** is what authenticates the key, and D3 binds them.

**Its own marker.** The payload is byte-identical to the other direction's (`marker + handle`), so without a
distinguishing marker a mis-scan is silent and destructive: the sign-in card's existing scan entry would mount
`JoinDeviceCard`, post a transport-key answer, and **burn** the QR-sign-in ceremony — the answer slot is
write-once (D4). Pin `veyqsignin:` as the second marker, with `veyqpair:` unchanged. `signInPayload` /
`parseSignInPayload` mirror the existing pair; the handle still comes from **`mintHandle()`**, so the
leading-`/` re-mint that keeps a payload from ever reading `marker://` (`pairing.ts:47-51`) carries over for
free — do not mint the handle any other way here.

The marker is the only direction signal anywhere, and it is deliberately client-side: the server sees a handle
it can only hash, so it still cannot tell the directions apart.

### D2 — the answer carries key **and** commitment as one opaque value, through new sibling functions

The channel relays exactly `answer` and `envelope` (`PairingChannel`, pinned as an exact set by
`scripts/validate_pairing_shapes.py`). `seal` takes no code and must never take one (§6). So the key and its
proof travel together in the one slot:

```
answer = b64std( lp(serializeIdentityPublic(pub)) || lp(commitment) )
```

**Do not extend `transportAnswer`/`readTransportAnswer` to cover both.** Measured, `deserializeIdentityPublic`
(`web/src/shared/crypto/v1.ts:338-347`) reads three length-prefixed fields and **asserts nothing about the end
of the buffer** — so an appended commitment would parse *silently* in the other direction's reader too. Two
separate functions make the two wire forms non-interchangeable by construction:

```ts
export function signInAnswer(pub: v1.Identity['pub'], code: string): string
export function readSignInAnswer(answer: string): { pub: v1.Identity['pub']; commitment: Uint8Array }
```

`readSignInAnswer` must reject a value that does not consume its buffer exactly — that is the assertion the
inherited reader lacks, and the reason this pair exists.

**The consuming call site, with its real signature** (`changes/README.md` § sixth tell): the `pub` that
`readSignInAnswer` yields is handed straight to
`sealGrant(pub: v1.Identity['pub'], grant: PairingGrant): string` (`pairing.ts:94`) — the same type
`readTransportAnswer` produces today, so `sealGrant` is untouched.

**Accepted, not overlooked:** the two directions' answers differ in byte length (1680 vs ~1740 base64 chars),
so an operator can distinguish them by size. That is already true of the shapes the ADR itself describes, and
size is named opaque metadata in the root `CLAUDE.md`. It is a size, never a content signal, and no code path
reads it.

### D3 — the commitment: what it binds, with what, and how the trusted device checks it

```
commitment = sha256Digest( utf8('veyq/v1/pairing-code') || lp(serializeIdentityPublic(pub)) || utf8(code) )
```

Domain-separated in `fingerprint`'s own shape (`v1.ts:321-322`), and the key is length-prefixed so no
concatenation of a differently-split (key, code) pair can collide. Every primitive is already exported and
pinned — `utf8` (`v1.ts:11`), `concat` (`:13`), `lp` (`:30`), `sha256Digest` (`:155`),
`serializeIdentityPublic` (`:306`) — so **no `contracts/crypto` vector is added**, matching what slice 2
measured: five live `asymWrap`/digest contexts appear 0× in `contracts/crypto/vectors/v1.json`.

The trusted device recomputes it from **the code it minted** and **the key it just read off the channel**, and
compares. A plain byte comparison: the value being compared is public, and an attacker able to time it is
already executing on the trusted device.

§6's two named-wrong alternatives are what this shape avoids, and neither may reappear: the server never sees
the code and never gates on it, and the code is **not** mixed into the envelope's encryption — `sealGrant` is
unchanged, so the holder of the ephemeral private key has no six-digit offline search.

### D4 — exactly one committed proof; there is no attempt counter to build

ADR 0059 §6 said *"three attempts"*. That is not buildable on this wire, and this refine corrected the ADR, the
use-case (step 5), and the coverage criterion rather than letting the CR quietly diverge. Measured, twice:

```
$ grep -n "recordAnswer" -A 6 backend/src/main/kotlin/com/veyq/app/features/account/pairing/PairingStore.kt
71:    fun recordAnswer(id: String, answer: String) {
72-        DevicePairingEntity.update(
73-            "answer = ?1, status = ?2 where id = ?3 and status = ?4",
74-            answer, DevicePairingEntity.ANSWERED, id, DevicePairingEntity.PENDING,
75-        )
```

The answer slot is **write-once** — a second post finds `status != PENDING` and is refused
(`PairingHandler.answer`). And `PairingChannel` is `{answer, envelope}` and deliberately status-free, so there
is **no back-channel** on which the trusted device could tell the new device *"wrong, try again"*. A retry
would need a field this relay must not have.

So: the transcription is **editable on the new device until it is sent**, which is where every typo is caught
for free; there is exactly one send; a sent wrong proof abandons the pairing, and retrying means a fresh
ceremony. `backend/.../pairing/Entities.kt`'s companion docstring currently asserts *"§6's three-attempt ceiling
is counted on the trusted device"* — the trusted device cannot count it, and phase 1 corrects that comment
where it sits.

**E2 on the new device is a timeout, not a rejection.** It has no way to learn it was refused; it sees the
channel go quiet and then 404 on expiry (`grantEnvelopePolling`'s `onGone`). The copy must tell the account
holder to start again rather than to keep waiting — the use-case's E2 now says so.

### D5 — the pairing code: six digits, freshly drawn, and this direction shows no comparison number

Freshly drawn from `v1.randomBytes` with rejection sampling (no modulo bias), **not** derived from any key —
§5's entire point is that anything derivable from the QR is already the attacker's. So `checkNumber` /
`fingerprint` are deliberately *not* reused here, and this direction displays **no** comparison number on either
screen: comparing is theatre where the attacker authored one of the screens. Six digits against exactly one
attempt within the channel's TTL.

Display it in the treatment the check number already uses (`letter-spacing`, `tabular-nums` —
`AddDevicePanel.vue:238-244`). The input on the new device is net-new: measured,
`grep -rniE "\botp\b|InputOtp|verification.?code" web/src` returns nothing and `web/src/shared/ui/` holds only
`SlideToConfirm.vue`. Build it from `InputText` in-style; do **not** add a PrimeVue component for it.

### D6 — the entry points on both devices

**New device — the sign-in card** ([uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md)
E3's card, `web/src/views/LoginView.vue`). A secondary action beside E3's existing ways in, swapping in a
**routeless** card exactly as `joinHandle` swaps in `JoinDeviceCard` (`LoginView.vue:81`, `:353`). No route is
added — see the Definition of done for the comparing form of that condition.

**It must NOT be gated on a camera.** Measured, the existing scan entry is
`v-if="scanSupported"` where `scanSupported = !!navigator.mediaDevices?.getUserMedia`
(`LoginView.vue:77`, `:410`). A camera-less laptop is *precisely* this direction's case — ADR 0059 §1's "no
direction is the camera one" is only true if this entry is offered without one.

**Trusted device — a scan arm in `AddDevicePanel.vue`.** The panel today only displays (measured:
`grep -rn "KitScanner" web/src` hits only `LoginView.vue` and the component itself). Its `idle` step gains the
two halves §1 requires: *show a code* (today's flow, unchanged) and *scan a code*. It stays in the existing
`pairing` settings section (`SettingsHubView.vue:85`, `:176`), which is already behind
`/settings/:section?`'s `requiresUnlock` — **no new route on either device**.

`KitScanner` is reused as-is: it is decoder-agnostic and emits the raw string, and its `accepts?: (text) =>
boolean` prop is what lets each scanner refuse the other direction's marker while the camera keeps scanning
past it. The trusted arm passes `parseSignInPayload`; the sign-in card's `acceptsScan` (`LoginView.vue:269`)
stays `veyqkit:` + `veyqpair:` and must **not** learn the new marker — accepting it there is the destructive
mis-scan D1 exists to make loud.

### D7 — the tail is reused verbatim; the claim was checked, not inherited

The use-case's *"from here it is steps 5–7 of the other direction, unchanged"* holds. Measured signature:

```
$ grep -n "^export async function runOnboardTail" web/src/features/account/pairing/onboardTail.ts
89:export async function runOnboardTail(grant: PairingGrant, staySignedIn: boolean): Promise<void> {
```

Both inputs exist identically here: the grant comes from `openGrant(transportKey.priv, envelope)`
(`pairing.ts:111`) and the tier answer from the same question `JoinDeviceCard` asks
(`isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)`). Adoption-before-unlock, write moment 1's
label threading, the `DEVICE_KEY` tier and the `400`/`401` split are therefore **not re-decided and not
re-implemented**. Reuse `grantEnvelopePolling` for the envelope wait for the same reason.

### D8 — the sibling pollers stay separate; their stated reason does not

`grantEnvelopePolling.ts:6-8` justifies the split as *"two screens that are, in the sign in with a QR code
direction, the same screen."* Measured, they never are: in **both** directions the new device polls only the
envelope and the trusted device only the answer — one loop per device. The separation is still right, and phase
2 restates it on the ground that holds: the two stop conditions are inverse (`answer` costs nothing to read;
`envelope` **spends** the channel), and one shared module-level timer would be one place for those two rules to
be conflated.

### D9 — the slide is the **last** gesture, taken after the proof holds

The trusted device's order is: scan → state what is being granted and display the code → poll for the answer →
verify the proof → **take the name and the slide** → `delegate` → `seal`. The use-case previously put the slide
before the code; this refine reordered it, for three reasons.

**It is what the built direction already does.** Measured — `AddDevicePanel.vue`'s slide lives in the
`comparing` step, i.e. after the answer arrived and the number is on screen, and `@confirmed="onConfirmed"`
(`:185`) runs `runGrant()` (`:104-120`) = `delegate` → `seal` with nothing in between. Slide-before-proof would
make the QR direction the **only** place in this feature where the deliberate gesture is not the last thing
before the irreversible one — an asymmetry a reader would have to hold in their head, for nothing.

**A gesture taken before the evidence exists cannot be about the evidence.** ADR 0059 §7's claim is that *the
reflex is the attack; the copy and the gesture are what stand against it*. If the slide is spent before the
transcription comes back, the only thing between the user and the seal at the decisive moment is an automatic
check — the gesture has already happened, on a device that had proven nothing. Taken last, it is a commitment
to a device that has **already** demonstrated it is the one in front of them, which is the only thing the code
was ever there to establish.

**It makes the refusal cheap.** With the slide first, a wrong code spends the whole ceremony — name typed,
slide completed — and hands back nothing; the user's most likely reading of that is *"it glitched, try again"*,
which is precisely the reflex §7 is written against. Taken last, the refusal arrives before the user has
committed anything, and *"that code was not right — start again on the new device"* is the whole story.

**What does not move:** the *information* stays early. What is being granted, and that signing the device out
later will not take the key back, is on screen from the moment the code is displayed — only the gesture is
deferred. And the label is still collected before the seal, because it travels inside the envelope
([ADR 0059](../docs/decisions/0059-device-linking.md) §9); it rides on the same screen as the slide and gates
it, exactly as `canConfirm = label.trim().length > 0` does today (`AddDevicePanel.vue:55`, `:184`).

**The consequence for the tests, and it is not cosmetic.** There are now **two** gates in series — the proof
verified *and* the slide completed — so the criterion's negative (*no envelope is ever posted*) is satisfied by
either of them, and a suite that only drives a wrong code cannot tell which one held. It needs a bite per
direction the property can fail (`changes/README.md` § a survival claim with two failure modes needs two
bites): a wrong proof must not even **offer** the slide, and a **verified** proof with the slide left untouched
must still seal nothing.

### What this CR deliberately does not touch

Inbox **#123** (nothing in the web client serialises refresh) — a real, older defect this flow inherits like
every other web session, with its own ADR-0004 question. Inbox **#124** beyond adopt. Master-key / identity
rotation ([ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) (d)) — this feature's
live obligation, and its own refine. No attempt counter, no abandon endpoint, no `ABANDONED` status (D4).

## Inventory — locators, run at refine

Run each again at implement; the code drifts, the command does not.

| # | What it finds | Command |
|---|---|---|
| L1 | Every "way in" on the sign-in card the new entry must leave behaving identically | `grep -n "<Button\|<button" web/src/views/LoginView.vue` **and** `grep -n "@click=" web/src/views/LoginView.vue` |
| L2 | Every site that knows a payload marker (source, tests, docs, flow surfaces) | `grep -rn "veyqpair" web/src scripts/ contracts/ docs/` **and** `grep -rn "pairingPayload\|parsePairingPayload" web/src` |
| L3 | The real signature of every value a decision above demands | `grep -n "^export" web/src/features/account/pairing/{pairing,pairingChannel,onboardTail}.ts web/src/shared/crypto/v1.ts` |
| L4 | Every guard anchored on a symbol this CR reshapes — **before** reshaping it | `grep -rln "pairing\|Pairing" scripts/` |
| L5 | The i18n namespace the new copy joins | `awk 'NR>=175 && /^  \},/{exit} NR>=175' web/src/i18n/index.ts \| grep -cE "^    [a-zA-Z]+:"` |
| L6 | The gates that decide who arrives at each surface | `grep -n "name: 'login'" -A 2 web/src/router.ts` · `grep -n "scanSupported\|rememberSupported" web/src/views/LoginView.vue` |
| L7 | That no route was added (a comparing form, not a readable one) | `git diff --stat -- web/src/router.ts` — must be **empty** |

**L1 disagreed, and the disagreement is the finding.** Angle A (template controls) returns **7**; angle B
(`@click` handlers) returns **6**. The one only angle A sees is `LoginView.vue:446` — the **submit** button,
bound by the form rather than by `@click`. That is the single most important path the new entry must not
disturb, and an inventory keyed on handlers would have missed exactly it.

**L4 returns one file** — `scripts/validate_pairing_shapes.py`. It pins property *sets*, and this CR changes no
property set, so it must stay green **and unedited**. If a phase finds itself editing it, that phase has
changed a wire shape and the CR is wrong.

**L2 also hits `docs/inbox.md:36`** — row #12 still records *"what the code carries in the QR-sign-in direction
remains slice 3's decision"*. D1 answers it; the row is updated in phase 6, not left to rot.

## Phases

- [ ] **1 contracts + backend — prose only, no shape change.** Correct `PairingAnswerRequest.answer` and
      `PairingChannel.answer` in [`contracts/api/auth.openapi.yaml`](../contracts/api/auth.openapi.yaml): the
      value is an ephemeral transport public key, **or that key together with a commitment to the transcribed
      code** — not "a key *or* a commitment". Correct
      `backend/.../features/account/pairing/Entities.kt`'s companion docstring: the three-attempt ceiling is
      **deleted**, not reworded, and replaced with why one attempt is structural (D4) — the `ABANDONED`-status
      paragraph around it stays, since its conclusion is unchanged. ·
      **Done when:** `npm run generate:api` regenerates cleanly, `python3 scripts/validate_pairing_shapes.py`
      green and the file unedited, `./gradlew check` green.
- [ ] **2 web — the wire form and the commitment.** `signInPayload` / `parseSignInPayload`,
      `mintPairingCode`, `codeCommitment`, `signInAnswer` / `readSignInAnswer` in
      `web/src/features/account/pairing/pairing.ts`, with `transportAnswer` / `readTransportAnswer` /
      `sealGrant` / `openGrant` **untouched**. Restate `grantEnvelopePolling.ts:3-8`'s sibling rationale on the
      ground that holds (D8) — **narrowed**, one sentence replaced, the rest of the comment stands. ·
      **Done when:** `npm run check` green, with the new cases in `pairing.test.ts` mirroring the existing
      marker cases (`pairing.test.ts:38-79`) including the leading-`/` re-mint lever, plus: a commitment over a
      different key or a different code does not verify (negative) against the matching pair as its control;
      `readSignInAnswer` rejects a buffer with trailing bytes; a `veyqpair:` payload does not parse as a
      sign-in payload and vice versa.
- [ ] **3 web — the new device.** The routeless card (display the QR, take the six digits, send once, then
      `grantEnvelopePolling` → `openGrant` → `runOnboardTail`), and the entry on the sign-in card, ungated by
      camera support. ·
      **Done when:** `npm run check` green, and the suite asserts: the entry is offered with `scanSupported`
      false while the existing scan entry is hidden in that same state (control on the same predicate); the
      displayed payload parses back to the handle and never contains `://`; a second submit posts **nothing**
      further, against the first submit posting exactly once; `runOnboardTail` is called once with the opened
      grant and the tier answer; and the refused case ends in the expiry state via `onGone`, with the accepted
      case reaching `finishing` as its control.
- [ ] **4 web — the trusted device.** The scan arm in `AddDevicePanel.vue`, in D9's order: scan → state what is
      granted and show the code → poll for the answer → verify the proof → name + slide → `delegate` → `seal`.
      The show-a-code arm's behaviour is unchanged. ·
      **Done when:** `npm run check` green, and the suite asserts: **both** gates, separately, because either
      alone satisfies the criterion's negative — a wrong proof does not so much as offer the slide and calls
      neither `delegate` nor `seal`, **and** a verified proof whose slide is left untouched still calls neither;
      the completed ceremony calls each exactly once, as the live control on that same predicate; the sealed
      envelope opens with the private key of the bundle carried in the answer and **not** with any other; the
      scanner's `accepts` refuses a `veyqpair:` code while accepting a `veyqsignin:` one; the channel is never
      read again after the seal; and the existing add-a-device path's own suite still passes untouched.
- [ ] **5 wiki — the device-linking page, EN + DE.** A new page in `wiki/en/` + `wiki/de/`, linked from
      `wiki/Home.md`'s two per-language lists, derived from the use-case's *"What this means for you / Was das
      für dich bedeutet"* section — which now covers both directions and the honest start-again line. Plain
      language, no mechanism: what you can do, which direction fits the device in your hand, why the typed code
      is the step that matters, never type a code someone gave you, and the two honest details (a linked device
      gets everything permanently; it has no password saved on it). ·
      **Done when:** both pages exist and are reachable from `Home.md`, all `scripts/validate_*.py` green.
- [ ] **6 coverage + flow surfaces.** `docs/coverage/features/account/use-cases/uc.account.onboard-device.yaml`:
      name the new suites inside the `web:` gap in the shape the existing entries use, and **delete** the
      *"What is still owed here is slice 3's, and that is now the whole of it"* paragraph — deleted, not
      narrowed, because this CR is what it was waiting for. Add the second entry's behavioural half to
      `the-locked-entry-adds-no-shell-route`. `status: planned` **stays**: no CI run has ever executed here
      (ADR 0040), which is the same single condition every other criterion in the file names. Update
      `docs/inbox.md` row #12. ·
      **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

- `npm run generate:api` **then** `npm run check` green in `web/` (in that order — a green `check` over a stale
  generated client proves nothing about a contract change), and `./gradlew check` green in `backend/`.
- Every deterministic guard green over the **committed** tree: `for f in scripts/validate_*.py; do python3 "$f"; done`.
  `scripts/validate_pairing_shapes.py` green **and unedited** (L4).
- **No new route, in a form that compares rather than reads:** `git diff --stat -- web/src/router.ts` is empty,
  and `scripts/validate_shell_route_gate.py` prints the same line it prints today
  (`OK: all 3 layout: 'app' routes in web/src/router.ts declare requiresUnlock: true`).
- `docs/coverage/` updated for `uc.account.onboard-device` with `python3 scripts/validate_coverage.py` green.
- Every negative assertion in the phases above ships with its **positive control on the same predicate** —
  "no envelope was posted" is the assertion; "an error was shown" is not.
- **No new `scripts/` guard, and the reason:** the structural invariant this CR rests on — *the displayed
  payload is the marker and the handle, and can never read as a URL* — cannot be seen by a contract-shape
  validator without parsing TypeScript, which the coverage file already records for the sibling direction. It
  is carried by the same unit-test form that carries it for `veyqpair:` today (`pairing.test.ts:38-79`,
  including the lever that forces the one draw whose base64 begins with a slash), extended to the new marker.
  `scripts/validate_pairing_shapes.py` continues to carry ADR 0059 §4 at the wire.

## Retrospective

*(filled at implement-session close — planning, execution AND process/collaboration, both directions, ending
with one concrete "next time". Then: harvest what binds into where it binds, and delete this file.)*
