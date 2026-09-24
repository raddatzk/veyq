---
id: cr-pairing-authenticated-by-the-qr
title: Device pairing authenticates the relay's two messages with a key only the QR carries — and receives the grant in one place
status: planned
satisfies: [uc.account.onboard-device]
adrs: ["0059", "0017", "0072", "0026"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

The pairing relay sees both halves of each binding step, so a malicious server takes the Master Key and a live
session in **either** direction:
- **Sign in with a QR code.** The new device's proof `sha256(domain ‖ key ‖ code)` crosses the relay in the
  clear. The relay tries all 10⁶ codes offline and proves the code for a key of its own.
- **Add a new device.** The compared number is five digits of one SHA-256 over a transport bundle whose Ed25519
  half the wrap never uses. The relay varies that half until its own bundle shows the same number, about 10⁵
  hashes.

[ADR 0059](../docs/decisions/0059-device-linking.md) §11 closes both. The QR carries a 32-byte secret, the handle
and a channel key are derived from it, and both relayed messages carry a MAC under that key. The rework touches
both receiving cards, so it also merges their duplicated grant-receiver logic into one `useGrantReceiver()`
(review F9, inbox #161 (g)).

## Context & constraints

- **The rule:** ADR 0059 §11, with §3, §5 and §6 as it edited them. The rejected alternatives (a PAKE,
  commit-then-reveal) and why are recorded there.
- **No wire-shape change.** The MAC rides inside the opaque `answer` and `envelope` strings, which the server
  stores verbatim and derives nothing from. `scripts/validate_pairing_shapes.py` pins the seven pairing schemas
  as exact property sets, and it must stay green **unchanged**: a new property would be a design change, so it
  goes back to refinement. The relay must not learn that a MAC exists.
  - Both strings are bounded at `maxLength: 8192`. Check the new sizes against that bound.
  - The operation descriptions in `contracts/api/auth.openapi.yaml` that say "commitment" are edited:
    `grep -n 'commitment' contracts/api/auth.openapi.yaml`.
- **The byte formats move to `@veyq/crypto`.** The two derivations and the three MAC statements are a
  cross-device byte format. So each gets a vector first
  ([ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md) rule 4). The vector is
  asserted in `packages/crypto`, which is where `fingerprint` already lives. Today's `codeCommitment` says it
  needs no vector, and that comment goes with the function. `hkdfSha256` is exported; HMAC-SHA256 is used
  inside `v1.ts` (`slugToken`, `dedupToken`).
- **Order:**
  - **After** `cr-pairing-collect-single-use` and `cr-visibility-poller`. Both reshape the pairing pollers and
    the two cards this CR merges. After the second, `grantEnvelopePolling` is a factory rather than a module
    singleton.
  - **After** `cr-seal-binds-its-object`. `pairing-grant` becomes a typed-seal purpose there, and the envelope's
    MAC sits beside that seal.
  - **Before** `cr-trust-state-never-goes-backwards`, whose trust-state marks ride this CR's authenticated
    envelope.
- **Measured against** `main` @ `a494436` on 2026-09-24. The grind claims were measured by reading `pairing.ts`
  and `v1.fingerprint` (the check number is `v % 100000` of the digest's first 40 bits). They have not been run.

## Decisions

- **The payload.** `veyqpair:` / `veyqsignin:` + base64 of 32 random bytes `s`. `s` is re-minted while its
  encoding starts with `/`, for ADR 0059 §4's reason (a payload that reads `veyqpair://…` is URL-shaped). Each
  marker still refuses the other's payload.
- **The derivations.** `handle = HKDF-SHA256(s, info = "veyq/v1/pairing-handle")` and
  `k = HKDF-SHA256(s, info = "veyq/v1/pairing-channel")`, 32 bytes each. The handle is encoded on the wire as
  today. The server never receives `s` or `k`.
- **The statements.** Each is length-prefixed with the `lp` encoding the vectors already use, and has its own
  label:
  - *add a device* answer: MAC over (label, transport key);
  - *sign in* answer: MAC over (another label, transport key, transcribed code). Two labels keep the two
    directions' answers non-interchangeable, which is the reason `readSignInAnswer`'s boxed comment gives for
    two readers;
  - envelope: MAC over (a third label, sealed grant).

  The vector defines the exact bytes. Every reader consumes its buffer exactly, as `readSignInAnswer` already
  does.
- **Comparison and refusal.** MACs are compared in constant time.
  - A failed answer MAC is a failed proof: the trusted device abandons the pairing, since §6 allows one attempt.
  - A failed envelope MAC means "this envelope is not ours": the new device takes the path an unreadable
    envelope takes today.
  - Every refusal is distinct and named, so a test can anchor on it.
- **The check number is unchanged.** It still defends against a photographed QR. Rewrite its comment, which
  says the attacker "never sees the intended device's number"; the relay did.
- **`useGrantReceiver()`** owns the transport key, the stay-signed-in gate, step/error/settling, `onGone`,
  `onEnvelope` (verify the MAC, `openGrant`, `runOnboardTail`) and disposal. Each card keeps its
  direction-specific half:
  - join: the check number and the add-a-device answer;
  - sign-in: opening the channel, the transcription, the send-once guard, the sign-in answer, and its i18n keys.

## Phases

- [ ] 1 contracts — crypto: the derivations, the three statements and their vectors, as `@veyq/crypto`
      functions. API: the operation descriptions only.
      · Done when: `npm run check` in `packages/crypto` green, `npm run generate:api` + `npm run check` (web)
      green, and all `scripts/validate_*.py` green, `validate_pairing_shapes.py` included and unedited.
- [ ] 2 web — the payload, both directions' answers and verification, the envelope MAC, and `useGrantReceiver()`.
      Locator: `grep -rnE 'codeCommitment|codeProofHolds|signInAnswer|transportAnswer|readTransportAnswer|sealGrant|openGrant|mintHandle|pairingPayload|signInPayload' web/src`
      (tests included).

      **Bites — live defects on the untouched tree. Write them first and see them red.** Each relay is a test
      double that sees only what crosses the channel:
      - **Sign in.** The relay reads the answer and grinds the code over a candidate list that contains the
        right one. A list keeps the test fast; the lever is still "only what crossed the relay". It posts its
        own key with a valid proof. Today the trusted device seals to the relay's key. After the fix it refuses
        on the answer MAC and seals nothing. Control: the honest answer → sealed.
      - **Add a device.** The relay varies the Ed25519 half of its own bundle until `checkNumber` matches the
        honest device's. Today the trusted device shows the matching number and seals to the relay. After the
        fix the answer is refused before any number is shown. Control: honest → shown and sealed.
        **Measure the grind's runtime before committing the test.** If it is too slow for the unit suite, bound
        it with a known-good seed and say so in the test.
      - **Envelope.** The relay seals a grant of its own to the new device's public key (a valid seal with no
        MAC). Today it opens. After the fix it is refused. Control: the trusted device's envelope → opens.
      - **The merge.** The sign-in card's unclassified failure → `pairing.joinFailed`. Only the join card pins
        this today (`grep -n 'joinFailed' web/src/features/account/pairing/*.test.ts`). After the merge, one
        test on the composable pins it for both.

      Every negative anchors on its specific refusal, not on "it threw".
      · Done when: `npm run check` (web) green.
- [ ] 3 docs + coverage — `uc.account.onboard-device` gains *"A relay that substitutes its own key in either
      direction is refused: the trusted device seals only to an answer, and the new device opens only an
      envelope, whose MAC under the QR-derived channel key verifies"*, covered by the phase-2 bites. Delete
      ADR 0059 § What is still open. Flip the row in
      [security-model § What holds today](../docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve).
      Check that the wiki's pairing pages still read true (`grep -rln -i 'pair\|QR' wiki/`).
      · Done when: `python3 scripts/validate_coverage.py` green, all `scripts/validate_*.py` green.

## Definition of done

- Every phase done. Baseline before the first edit:
  `for f in scripts/validate_*.py; do python3 "$f" >/dev/null 2>&1 || echo "RED $f"; done`.
- All three relay bites were seen red on the untouched tree.
- `grep -rn 'codeCommitment\|codeProofHolds' web/src packages/crypto/src` returns nothing: the unkeyed proof is
  gone, not kept beside the MAC.
- The two cards import `useGrantReceiver()`, and the six blocks the refine found identical by `diff` no longer
  exist twice.

**Not proven:** an attacker who both reads the QR and runs the relay (ADR 0059 §11 names it). No test pretends
otherwise.

## Retrospective
