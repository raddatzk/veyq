---
id: cr-app-device-linking
title: Device linking on every app, both directions — "Add a new device" and "Sign in with a QR code" (ADR 0059)
status: planned
satisfies: []
adrs: ["0059", "0004", "0005", "0026", "0028", "0058", "0129", "0130"]
invariants:
  - "the QR never carries a server, and no app follows one out of a scan (ADR 0059 §4): each side talks only to the instance it is already signed in to or typed into its sign-in form; the payload parser is anchored at both ends and refuses the other direction's marker"
  - "each direction keeps its own binding step (ADR 0059 §5): Add a new device compares the five-digit number on both screens; Sign in with a QR code transcribes the six-digit code into the new device, one attempt (§6)"
  - "the relay sees only the handle (ADR 0059 §11): the trusted device seals only to an answer whose MAC verifies under the channel key, the new device opens only an envelope whose MAC verifies"
  - "a linked device enrols at most a DEVICE_KEY wrap, never a DEVICE one (ADR 0059 §10)"
  - "the confirmation is a slide that names the device and says signing it out will not take the key back (ADR 0059 §7)"
  - "no secret crosses the bridge: the QR's text, the channel key, the grant and the refresh token stay in the core; the desktops get the code as a picture and send frames in (cr-core-qr-decoder D2, D5)"
  - "the core's pairing crypto answers to contracts/crypto/vectors/v1.json, the same vectors @veyq/crypto answers to"
  - "the web's linking behaves as before; an app and the web link with each other in both directions and both roles"
---

## Goal

No app can link a device: a new app is signed in only with the email, password and Secret Key, and a signed-in app
cannot let another device in. This CR builds ADR 0059 on every app — Android, iOS, macOS, Linux, Windows — in **both
directions and both roles**, interoperable with the web:

| | Add a new device | Sign in with a QR code |
|---|---|---|
| **trusted** (signed in, unlocked) | Settings → Add a device → **shows** a `veyqpair:` code, compares the number, slides to confirm | Settings → Add a device → **scans** a `veyqsignin:` code, shows the six-digit code, slides to confirm |
| **new** (at the sign-in form) | **scans** the trusted device's code, shows the number | **shows** a `veyqsignin:` code, takes the six-digit code typed |

Whichever device has a camera scans (ADR 0059 §1); a device without one offers only its showing half and says the
other device scans (`cr-core-qr-decoder` D4).

**Depends on** `cr-core-qr-decoder` (all phases) and `cr-app-account-settings` (the Settings page whose "Add a device"
entry this CR fills). The sign-in form is shared with `cr-app-kit-sign-in` (written in parallel): that CR adds the kit
to the form, this one the two linking entries; both edit `SessionModel`'s `SignIn` gate, so whichever lands second
rebases onto the other's fields and both CRs' phases on that gate run one after the other, never side by side.

## Context & constraints

- **Read first:** ADR 0059 whole; ADR 0004 § Transport and § Session family; ADR 0005 §9, §17–22 (the wraps, why a
  linked device gets no `DEVICE` wrap); ADR 0028 Decision 7 (the trust-state marks the envelope carries); ADR 0026
  (why §6 refuses a server-side gate); `uc.account.onboard-device` (both directions, E1–E5); `uc.account.sessions`
  (where a linked device becomes visible); `src/mobile/CLAUDE.md` whole.
- **The web's reference** (`src/web/src/features/account/pairing/`): `pairing.ts` (the markers, `mintPairingSecret`'s
  re-mint of a secret starting with `/`, `parseMarked`, `checkNumber`, the grant's JSON and its reader, the
  six-digit draw), `pairingChannel.ts`, `pairingAnswerPolling.ts`, `grantEnvelopePolling.ts`, `onboardTail.ts`,
  `AddDevicePanel.vue`, `JoinDeviceCard.vue`, `SignInWithCodeCard.vue`, `GrantReceiverOutcome.vue`;
  `src/packages/crypto/src/v1.ts` (`pairingHandle`, `pairingChannelKey`, `pairingMac`), `seal.ts` (`pairing-grant`).
- **Where what runs:** as in `cr-core-qr-decoder` § Context. A live test links **the principal's own account to a
  fresh core session** (the trusted side is a core login of the principal; the new side a second core client with no
  session) and signs the linked family out at the end; it never touches the owner's browser sessions. The instance has
  `core.device-linking` on (the dev stack's default — the phase's first read checks it).

## Measured at refine (2026-11-12)

Commands from the repo root.

| # | What | Command |
|---|---|---|
| R1 | **No app has a pairing flow.** The core holds only the `pairing-grant` seal context and `FieldKind.PAIRING_CODE` (`^[0-9]{6}$`); no handle, channel key, MAC, payload parser or grant reader; the sign-in form takes server, email, password and Secret Key only. | `grep -rn -i 'pairing' src/mobile/core/src/commonMain`; `grep -n 'data class SignIn' -A8 src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/SessionModel.kt` |
| R2 | **The vectors pin the channel, the core does not read them:** `pairing_channel` (secret → handle and channel key; the `pairing-answer`, `pairing-sign-in-answer` and `pairing-envelope` MACs), `fingerprint` (the check number is its first five-digit group), `seal.asym["pairing-grant"]`. `Conformance.kt` reads `fingerprint` and not `pairing_channel`. **Nothing pins the QR payload's grammar or the grant's JSON** — each implementation has only its own tests. | `grep -n '"pairing_channel"\|"fingerprint"' contracts/crypto/vectors/v1.json`; `grep -n 'pairing_channel\|"fingerprint"' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Conformance.kt` |
| R3 | **The core's primitives cover it:** `hmacSha256`, `hkdfSha256` (`Primitives.kt`), `sealTo`/`openAs` (`V1.kt`), `fingerprintHash` (`Trust.kt`), the trust marks (`session/TrustMarks.kt`). | `grep -n 'fun hmacSha256\|fun hkdfSha256\|fun sealTo\|fun openAs\|fun fingerprintHash' -r src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto` |
| R4 | **`POST /api/token/adopt` answers only with a cookie** (`native-apps.md` § Account names it the blocker) — **but an app does not need it.** ADR 0004 § Transport: refresh "answers in kind — cookie in, cookie out; body in, body out", and adoption is "the same rotation refresh uses". The core already refreshes with a body token (`TokenApi.refreshSession(RefreshRequest(token))`), and `listSessionFamilies` marks the caller's family `current: true`, which is the family id adoption would have returned. | `sed -n 79,92p docs/decisions/0004-session-tokens.md`; `grep -n 'refreshSession(RefreshRequest' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/Account.kt`; `grep -n 'current: true' contracts/api/auth.openapi.yaml` |
| R5 | **The pairing endpoints are on the auth spec and gated by `core.device-linking`** (422 `capability_disabled` when off): `openPairingChannel`, `answerPairingChannel`, `delegatePairingSession`, `sealPairingChannel`, `collectPairingChannel`. `scripts/validate_pairing_shapes.py` pins their schemas' property sets. | `grep -n 'operationId: .*Pairing' contracts/api/auth.openapi.yaml`; `grep -n CORE_DEVICE_LINKING src/web/src/features/platform/capabilities.ts` |
| R6 | **The Use pages describe the web:** `account/adding-another-device.mdx` and `getting-started/sign-in.mdx`, each with its `de/` twin. | `git grep -li qr -- site/src/content/docs/account site/src/content/docs/getting-started site/src/content/docs/de` |

## Decisions

### D1 — An app spends the handed-over token at refresh, not at adoption; no contract or backend change

The new device spends the grant's refresh token **once at `POST /api/token/refresh` with the token in the body** —
the rotation adoption itself runs — keeps the successor where an app keeps a login's refresh token (memory, or the
platform's key store at the trust level chosen), and reads its family id from `listSessionFamilies`' `current` entry
(R4). Adoption stays the browser's verb: it exists to move a token *out* of script into a cookie, which an app has no
use for. A native variant of adoption (an optional `sessionScope`, a body answer) was weighed and rejected: it would
add a contract change, a backend change, and a way for a web caller that omits a field to get a body token — the
exfiltration ADR 0004 keeps the browser out of. **ADR 0059 §2 and ADR 0004 § Transport gain this sentence in phase 7.**
The "stay signed in" question is the app's own offer after the grant (as after its login), not a cookie lifetime;
ADR 0059 §2's ordering (asked before the session exists) binds the browser's cookie and not the app's key store.

**First measurement of phase 3, stop and ask if it fails:** a delegated family's token spent through refresh rotates
and lists as `current` for the spender, and the granting family still refreshes afterwards.

### D2 — The vectors grow the two shapes nothing pins (contracts, then the web)

`contracts/crypto/vectors/v1.json` § `pairing_channel` gains:
- **`payloads`** — accepted and refused scanned texts with the marker and the secret each yields: both markers;
  surrounding whitespace trimmed; 31 and 33 bytes refused; padding variants; a trailing character, a query, a path,
  `veyqpair://host/…`, a `https://` text, the other direction's marker for each parser, and lower/upper-case
  variations of the marker (refused: the marker is exact). ADR 0059 §4 is the rule a reviewer looks for by name; these
  cases are its executable form for both implementations.
- **`grant`** — the envelope's plaintext JSON (`v: 1`, `email`, `masterKey` in standard base64, `refreshToken`,
  `label`, optional `marks`) with refusals (`v: 2`, a missing field, a mistyped one, marks not in the writer's shape).

The web's `pairing.test.ts` reads both from the vectors file (as `@veyq/crypto`'s tests read theirs); the core's
`Conformance` reads them in phase 2. The web's behaviour does not change; a case the web's current parser decides
differently from the ADR is a finding, fixed in the web in phase 1, not written into the vector as it is.

### D3 — The crypto and the parsers are the core's, held to the vectors

`crypto/Pairing.kt` in commonMain: `handle(secret)`, `channelKey(secret)`, `answerMac`/`signInAnswerMac`/`envelopeMac`
and their verifies (constant-time compare), `checkNumber(transportPublic)` (the fingerprint's first group), the grant's
writer and reader, `mintSecret()` with the web's re-mint of a secret whose base64 starts with `/`, `drawCode()` (six
digits, uniform, from the suite's random bytes), and `PairingPayload.parse(text, marker)`. The transport identity is the
suite's existing X-Wing + Ed25519 identity, serialized as `serializeIdentityPublic` serializes it (R2's
`transport_key`). `Conformance` grows `pairing_channel` (all of it, D2's additions included); `checked` rises
accordingly, and each new check gets a bite in `ConformanceTest` (a swapped HKDF label, a MAC over the other
direction's statement, a parser that accepts a trailing path).

### D4 — The flows are one core model, `DeviceLink`, with four roles

`session/DeviceLink.kt` (ADR 0129 decision 2's shape): `showForNewDevice()` (trusted, Add a new device),
`scanSignInCode()` (trusted, Sign in with a QR code), `scanFromTrusted(server)` and `showForSignIn(server)` (new, from
the sign-in form). Each role's states mirror the web's cards: the code shown (the QR as a matrix for the phones, a PNG
for the desktops — `cr-core-qr-decoder` D2), waiting, the number to compare / the code to show / the code to type,
the slide, sealing or collecting, done, and the refusals (no answer before the channel's expiry; a MAC that does not
verify — said as "this code was not answered by your device", never retried in place, ADR 0059 §6 and
uc.account.onboard-device E2; the capability off; the scan refused because it is the other direction's code, said
with the other entry's name).
- **The new device's tail is the app's sign-in tail:** spend the token (D1), record the device in the register with
  the grant's suggested label as the device writes it (§9), apply the marks, open the tenants, then the app's offer —
  **restricted to its `DEVICE_KEY` levels** (§10); where the platform has none (Linux without the Secret Service),
  the account holds the Master Key for this run only, and the offer says it will need the password or the Emergency
  Kit next time (the copy is the ADR's consequence).
- **The server is the one the form names:** the new device's two entries sit below the server field and use that
  server; nothing in a scan can change it. A new device on another server than the trusted one simply sees no answer;
  the waiting state's words say "both devices must use the same server" after the first poll window.
- **Polling** follows the web's intervals and stops on close; a channel is closed when its page is.
- **Capability:** both Settings' entry and the two sign-in entries are offered only while `listInstanceCapabilities`
  lists `core.device-linking` for that server.

### D5 — The slide

ADR 0059 §7 asks for a slide, not a tap. Neither Material 3 nor the HIG ships a slide-to-confirm control; each app
builds one from its platform's slider (Compose `Slider` that confirms only at its end and springs back otherwise;
SwiftUI `Slider` likewise; WinUI `Slider`; GTK `GtkScale`), keyboard-operable (arrow keys to the end, then Enter) and
announced by the screen reader with the sentence it confirms. The sentence names the device and the consequence, in
the web's words (`words/words.json` takes the web's English and German).

### D6 — Bridge

`Link.ShowForNewDevice(connection)`, `Link.ScanSignInCode(connection)`, `Link.ScanFromTrusted(server)`,
`Link.ShowForSignIn(server)` → `Answer.LinkOpened(link)`; `Link.Code(link, px)` → `Answer.Bytes(image/png)` (the
code as a picture); `Link.Confirm(link, label)` (the slide's end), `Link.EnterCode(link, code)`, `Link.Close(link)`;
`Watched.Link(link)` → `Update.Link(shown)` carrying the state, the number or six-digit code to show, the suggested
label and the refusal — never the secret, the channel key, the grant or a token. Scanning goes through
`cr-core-qr-decoder`'s `Scan.*` with the link's scan opened by the link (`Scan.Open` of kind `link`), so the scanned
text goes straight into `DeviceLink` inside the core.

## Phases

- [ ] **1 contracts and the web** — D2's vectors; the web's `pairing.test.ts` reads them; a web parser finding fixed
  there. · Done when: `pnpm run check` in `src/web/` and `src/packages/crypto/` green; the trust-chain guards green;
  a bite — one refused payload moved to the accepted list — turns the web's test red.
- [ ] **2 the core's crypto** — D3. · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green, `Conformance`
  checks `pairing_channel` whole, each D3 bite red once.
- [ ] **3 the core's flows** — D4 with `commonTest` over a played relay (both directions and roles; a forged answer,
  a forged envelope, the other direction's code scanned, a wrong six-digit code sent once and the pairing abandoned,
  a timeout); D1's first measurement; `LiveDeviceLinkTest` (`VEYQ_LIVE`): both directions end to end between two core
  clients of the principal, the linked family listed with the label, rotating, revoked at the end, the granting family
  refreshing after (the control). · Done when: core checks and the live test green; a bite — the trusted side sealing
  without verifying the answer's MAC — makes the forged-answer case red.
- [ ] **4 Android and Apple** — the Settings entry (show and scan), the sign-in form's two entries, the slide, the
  words. · Done when: both builds green; passes on the throwaway AVD, the simulator and the macOS build, **each against
  the web**, driven in headless system Chrome (machine hint: `channel: 'chrome'`, `?devlogin`; the web scans through
  Chrome's fake camera, `--use-fake-device-for-media-stream --use-file-for-fake-video-capture=<the app's code as
  .y4m>`): the app as trusted and as new device in each direction, four runs per app, the app scanning with its test
  camera or the macOS camera; each run's linked family signed out after.
- [ ] **5 the bridge** — D6 with `WireTest` samples, `DispatchTest` (open each role, a code picture, confirm, a tenant
  switch closing it) and `SecretFreeSchemaTest` unchanged. · Done when: `./gradlew :bridge:jvmTest` green.
- [ ] **6 Linux and Windows** — the same screens as phase 4 on the desktops. · Done when: both `run.py build`/`test`
  green; each desktop's pass links with the web in both directions (the desktop showing; and scanning through its test
  camera); `NoCamera` offers only the showing halves on the Windows VM.
- [ ] **7 docs and harvest** — ADR 0059 §2 and ADR 0004 § Transport: D1's sentence; `uc.account.onboard-device`
  notes the apps (no coverage change); `native-apps.md` § Account: the adoption blocker removed, what is built added;
  `site/src/content/docs/account/adding-another-device.mdx` and `getting-started/sign-in.mdx` (with their `de/` twins)
  say the apps do both directions — the sign-in page is shared with `cr-app-kit-sign-in`, which owns its § kit, this
  CR its § QR code. · Done when: the trust-chain guards and the site's `pnpm run check` green; this CR deleted.

## Definition of done

Every app links a device with every other app and with the web, in both directions and both roles; the core's
pairing crypto, the payload grammar and the grant's JSON are held to the same vectors as the web's; no secret crosses
the bridge.

## Owner questions

1. **Two desktops, neither with a camera** cannot link to each other (neither can scan); they use the password and
   Secret Key, or the Emergency Kit. The web has the same gap. A typed fallback (the 32-byte secret as nine groups of
   Crockford base32, the Secret Key's form) would close it at the cost of a third way in; I would leave it out.
   Confirm.
2. **Copy** for the slide's sentence and the "no answer — same server?" hint: take the web's words where they exist
   (they do for the slide), and confirm the new hint's tone.
3. **Linux without the Secret Service** (D4): a linked device there keeps the Master Key only for the run. Acceptable,
   or should the Linux app refuse to be linked in that state?

**Decided** (the coordinator, 2026-11-12, under the owner's standing mandate to decide and report; the owner may overturn any):
- 1: no typed fallback; two cameraless desktops use the password and Secret Key or the Emergency Kit.
- 2: the web's words where they exist; the new hint as written.
- 3: acceptable — a linked Linux device without the Secret Service holds the Master Key for the run only, as a signed-in one does.
