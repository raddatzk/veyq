---
id: cr-hybrid-kem-is-x-wing
title: The hybrid KEM becomes X-Wing, before V1, in suite v1
status: planned
satisfies: ["en.account.key-model"]
adrs: ["0017", "0072", "0031", "0028"]
invariants:
  - "Operator-blind E2EE everywhere: every wrap to a public key keeps going through the hybrid KEM; nothing falls back to a classical-only path"
  - "API-first: the vectors under contracts/crypto are the suite's byte definition and change before the implementation"
---

## Goal

Replace the suite's own X25519 + ML-KEM-768 combiner with **X-Wing**
([draft-connolly-cfrg-xwing-kem-11](https://datatracker.ietf.org/doc/html/draft-connolly-cfrg-xwing-kem-11)),
whole: one 32-byte seed as the decapsulation key, the 1216-byte encapsulation key, the 1120-byte ciphertext and
X-Wing's combiner, through `@noble/post-quantum`'s `XWing`. Every identity keypair and every Memory drop keypair
becomes an X-Wing keypair. The wrap layer above the KEM (HKDF with the seal context, then AES-256-GCM) and the
suite id `v1` stay. This happens **before V1**: there are no existing rows — every database is recreated from the
baseline — so `v1` changes in place, with no second suite and no migration. ADR 0017 decisions 1, 2 and 4 hold the
decision and its rejected alternatives.

## Context & constraints

- [ADR 0017](../docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md): decision 2 names X-Wing and
  its draft revision; decision 4: the vector comes first, then the implementation goes green.
- [ADR 0072](../docs/decisions/0072-a-seal-binds-its-object.md): every asymmetric seal purpose keeps exactly one
  vector; the seal context binding does not change.
- [ADR 0031](../docs/decisions/0031-uploader-public-share.md) point 7: the Memory's drop keypair is a hybrid KEM
  keypair and becomes an X-Wing keypair.
- [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md): the identity public bundle is what
  a pin stores and a safety number digests; its encoding changes, so every safety number changes. Pre-V1 there is
  no pin store to carry over.
- [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md): the identity bundle it
  describes (`kemX`, `kemM`, `sign` public; `kemX`, `mlkemSeed`, `sign` private).
- The backend performs no content crypto and stores the identity public bundle as opaque base64
  (`veyq_user.identity_public_key TEXT`; contract `identityPublicKey`, `maxLength: 8192`). The new bundle fits; the
  backend is expected to stay untouched, and phase 1's done-when proves it rather than assuming it.

### Measured at refine (2026-10-15, `main` @ `6b17bdbb`)

- **X-Wing's standing:** draft-connolly-cfrg-xwing-kem-11, 23 Sep 2026, an individual draft (Independent
  Submission, Informational), not an RFC and not CFRG-adopted. CFRG's `draft-irtf-cfrg-hybrid-kems-12` (July 2026,
  IRTF stream) defines the generic combiner shapes and cites X-Wing for the analysis of the one X-Wing uses.
- **noble's conformance:** `@noble/post-quantum` 0.6.1 (the version `packages/crypto` pins, `^0.6.1`) exports
  `XWing`, an alias of `ml_kem768_x25519`, with lengths seed 32 / public key 1216 / secret key 32 / ciphertext
  1120. Against the three Appendix C vectors of draft -11 it matches all of them: `keygen(seed)` gives `pk` and
  `sk`, `encapsulate(pk, eseed)` gives `ct` and `ss`, `decapsulate(ct, sk)` gives `ss`, and a ciphertext with one
  flipped byte decapsulates to a different secret. A copy of the vectors with one expected `ss` changed reported
  2/3, so the check can fail. Appendix C is headed *"TODO: replace with test vectors that re-use ML-KEM, X25519
  values"* — the draft itself calls them provisional.

### Inventory — the locators

| What | Locator |
|---|---|
| The hybrid-KEM API and its callers | `grep -rIlE "kemEncap\|kemDecap\|HybridPublic\|HybridSecret\|hybridSecret\|hybridPublic\|asymWrap\|asymUnwrap\|sealTo\|openAs\|DropKey\|dropKey" packages web/src admin-web/src --exclude-dir=generated --exclude-dir=node_modules` |
| The bundle's field names and the raw primitives | `grep -rIlE "kemX\|kemM\|kem_x\|kem_m\|mlkemSeed\|mlkem_seed\|ml_kem768\|x25519" packages web/src admin-web/src --exclude-dir=generated --exclude-dir=node_modules` |
| Vector groups that carry KEM keys, KEM output or the bundle | `grep -n '"kem_v1"\|"seal.asym"\|"fingerprint"\|"pairing\|"directory_entry"\|kem_x\|kem_m\|mlkem' contracts/crypto/vectors/v1.json` |
| Docs and contract prose naming the old shape | `grep -rIlE "kemX\|kemM\|mlkemSeed\|hybridPublic\|HPKE-style\|veyq/v1/kem" docs contracts/api` |
| The backend's view of the bundle (expected: opaque only) | `grep -rIn "identityPublicKey\|identity_public_key" backend --include=*.kt --include=*.sql \| grep -v /test/` |

## Phases

- [x] 1 contracts + `packages/crypto` — vectors first: add an `xwing` group to `contracts/crypto/vectors/v1.json`
      carrying draft -11's Appendix C vectors verbatim (seed, sk, pk, eseed, ct, ss) with a note naming the draft
      revision, and a conformance case that runs `XWing` against them; it is red until the suite uses `XWing`.
      Then replace `combiner` / `kemEncap` / `kemDecap` with `XWing`, the identity bundle with
      `pub {kem (1216), sign}` / `priv {kemSeed (32), sign}`, and the drop key with one X-Wing seed;
      `serializeIdentityPublic` becomes `lp(kem) ‖ lp(sign)`, and the fingerprint digests that. Regenerate every
      vector that carries KEM output, a KEM key or the bundle (locator above) — `kem_v1.decap_kat` gives way to the
      `xwing` group. The asymmetric envelope keeps `0x01 ‖ ct(1120) ‖ nonce ‖ aead`, now with X-Wing's ciphertext
      order. · Done when: `npm run check` in `packages/crypto` green; `./gradlew check` green with no backend file
      changed (the Kotlin vector readers use none of the regenerated groups — confirm, don't assume); bites:
      one flipped byte in an `xwing` vector's `ss` turns the conformance test red, and the removed combiner's
      output no longer opens a regenerated `seal.asym` vector.
- [ ] 2 web + admin-web — every caller in the locators moves to the new bundle and drop-key shapes: the vault,
      identity pins, pairing, grant delivery, invite names, scopes, uploader shares and the cold account birth in
      `admin-web`. · Done when: `npm run check` green in `web/` and `admin-web/`; the web e2e suites that create
      identities, admit members, deliver grants and open uploader shares green (`scripts/run_web_e2e.py`, with the
      phase's run named); a browser pass shows a member's safety number and a second member admitted and opening
      shared content.
- [ ] 3 docs — every doc the docs locator lists describes X-Wing and the new bundle (en.account.key-model and
      security-model § Cryptographic stance / § Key hierarchy first; the identity-pinning enabler, ADRs 0020,
      0028 and 0068, the use-cases and coverage files it names); contract prose that says `hybridPublic(target)`
      or "HPKE-style" follows; ADR 0017's status line loses "not built yet". · Done when: trust-chain green, and the docs locator above returns only
      deliberate historical mentions (ADR 0017 § Rejected).

## Definition of done

The suite's only hybrid KEM is `@noble/post-quantum`'s `XWing`, held to draft -11's published vectors; no code
outside `packages/crypto` touches `x25519` or `ml_kem768` for wrapping; `packages/crypto`, `web/`, `admin-web/`
and the backend checks are green, the e2e suites of phase 2 green, and the docs describe what is built. The
`xwing` conformance case is the permanent guard: it stays red while the suite drifts from X-Wing.

## Retrospective

(filled at close)
