---
title: Roadmap
description: What is designed or decided in principle and not built — the gap each item would close, what exists today instead, and where it is decided.
sources:
  - docs/crosscutting/security-model.md
  - docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md
  - docs/decisions/0026-client-trust-tiers.md
  - docs/decisions/0037-password-protected-shares.md
  - docs/features/spaces/use-cases/uc.spaces.require-verified-identities.md
  - docs/features/account/enablers/en.account.key-model.md
  - docs/decisions/0088-the-capability-register-is-a-tree-switched-by-operator-and-tenant.md
  - docs/decisions/0032-capability-discovery-version-gating.md
---

This is the one page of the Security area that describes things veyq does **not** do yet. Every item is named in a
decision document as a planned or future step, and none of it is built. For each one: what exists today instead,
the gap it would close, and how far its design has got. The residuals these items answer are listed with their
reasons on [Known limitations](/veyq/security/known-limitations/).

:::note
Nothing here is a promise of a date or of a final shape. Where a doc names only the direction and records no design,
this page says so.
:::

## Overview

| Item | Gap it would close | Design state | Decided in |
|---|---|---|---|
| [Key Transparency](#key-transparency) | an identity installed by the operator; TOFU's first-use gap | named as the end-state; no design recorded | ADR 0060 (g), ADR 0028 |
| [Strict verification mode](#strict-verification-mode) | verification is advisory; nothing enforces it before a first share | decided in principle; shape open | ADR 0028 Decision 4 |
| [Abuse-report intake and per-share operator action](#abuse-report-intake-and-per-share-operator-action) | no notice-and-takedown path for a single public share | described in the target model | security-model § Sharing |
| [Passkey unlock](#passkey-unlock) | no passwordless login and unlock | method named in the contract; no client | en.account.key-model |
| [Hybrid ML-DSA signatures](#hybrid-ml-dsa-signatures) | signatures are classical (Ed25519) | strategy only | ADR 0017 |
| [A client served by a trusted third party](#a-client-served-by-a-trusted-third-party) | operator-served web is passive-operator only | trust tier defined; needs a central origin | ADR 0026 |
| [Cryptographic share password](#cryptographic-share-password) | a share password does not hold against a malicious operator | shape sketched as a rejected-for-now alternative | ADR 0037 |
| [Feature switches](#feature-switches) | an operator or tenant owner cannot turn a feature off; the register is seven entries and no switch | designed as a tree; the entries below are marked built, planned or idea | ADR 0088, ADR 0032 |

## Key Transparency

**Today.** Identity keys are trust-on-first-use: a client pins a member's identity public key the first time it wraps
to it, surfaces a changed key (a notice in the UI, a refusal in the grant-delivery worker), and lets two members
compare safety numbers on their profiles to mark each other *verified*. The operator writes the key directory itself,
so it **can** install an identity of its own on an account; only a member who pinned the old key sees `changed`. And
a key substituted before anyone pinned it goes unnoticed until the members compare safety numbers.

**Gap it would close.** The security-model's *What holds today* table names Key Transparency as what closes the
"installs an identity of its own on an account" row, and the key-directory section names it as the eventual end-state
for TOFU's first-use gap.

**Design state.** The docs name it as the correct end-state and do not design it.
[ADR 0028](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md)
(alternative (f)) calls it far heavier than TOFU plus verification, which is the near-term step it later subsumes;
[ADR 0060](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0060-key-rotation-after-compromise.md)
(alternative (g)) adds that it would subsume the human re-admission review after a key rotation rather than compete
with it.

**Decided in:** [security-model § What holds today](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve),
ADR 0060 (g), ADR 0028.

## Strict verification mode

**Today.** Verification is built and advisory. A member compares another member's safety number (QR scan or digits)
and marks them *verified*; a changed key is a notice a human reviews, and the automated worker refuses to wrap to a
changed key. Nothing refuses to share with a member nobody has verified.

**Gap it would close.** The goal stated in its use-case: *an unverified member is never silently trusted with a
share*. With it on, the grant-delivery worker would skip unverified members (they stay *awaiting access*) and a
changed key would be a hard block on every path, the UI's included, until a human verifies the member again.

**Design state.** Decided in principle, not built, and **not buildable as specified: its shape is open.** A
per-account toggle cannot protect a shared scope, because any co-member can key an unverified identity regardless of
the caller's own setting. The mechanism has to be scope-wide, and that redesign is undecided. Two constraints are
fixed: the setting must not hand the server a who-must-be-verified graph, and, being enforced on the client, it would
hold against an active operator only on a client that operator does not serve.

**Decided in:** [ADR 0028](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md)
Decision 4 and [§ What is still open](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0028-identity-authenticity-tofu-verification.md#what-is-still-open),
[uc.spaces.require-verified-identities](https://github.com/raddatzk/veyq/blob/main/docs/features/spaces/use-cases/uc.spaces.require-verified-identities.md).

## Abuse-report intake and per-share operator action

**Today.** Neither half is built. There is no report intake, and the operator has no action on a single share:
revoking one is its creator's alone. The operator's only lever is suspending the whole tenant, which takes every link
in it down
([uc.admin.suspend-tenant](https://github.com/raddatzk/veyq/blob/main/docs/features/admin/use-cases/uc.admin.suspend-tenant.md)).

**Gap it would close.** Notice-and-takedown at the share boundary. In the target model, trust and safety operates
where content is distributed: an abuse report includes the full share URL and thus the key in its fragment, so the
operator **can decrypt a reported public share** to verify it, quarantine it, preserve evidence and act; the same
intake serves takedown requests. Private, unshared content stays unscannable by design; public shares become
decryptable on report, so notice-and-takedown works exactly where content is distributed.

**Design state.** Described in the security model's target; no use-case or ADR for the intake is referenced there.

**Decided in:** [security-model § Sharing & share-boundary Trust & Safety](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#sharing--share-boundary-trust--safety).

## Passkey unlock

**Today.** Four unlock methods are built, each a separate wrap of the account's Master Key: password plus Secret Key
(the mandatory floor), recovery by the Secret Key alone, a trusted device (device-bound key plus password), and an
opt-in passwordless device key. Login is a challenge-response signature with an Ed25519 key derived from the password.
The contract and the backend name a `PASSKEY` wrap method, but no client writes or opens one.

**Gap it would close.** A passwordless method: one credential covering authentication (its signature) and unlock (its
PRF output derives the key-encryption key that opens a `PASSKEY` wrap). ADR 0026 records what passkeys defend against:
phishing, and a server harvesting passwords.

:::caution[It does not move the operator boundary]
A stronger unlock method is not a client-integrity answer. A passkey yields a secret the server never sees, but the
operator still ships the code that consumes it, and tampered code can forward it the moment it is unwrapped. On an
operator-served web client, veyq remains passive-operator only with or without passkeys
([ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md) § Consequences;
[security-model § What holds today](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve)).
:::

**Design state.** The target is stated (above); the method is listed out of V1 by decision
([inbox](https://github.com/raddatzk/veyq/blob/main/docs/inbox.md), part of #12).

**Decided in:** [en.account.key-model § Unlock methods & KEK derivation](https://github.com/raddatzk/veyq/blob/main/docs/features/account/enablers/en.account.key-model.md#unlock-methods--kek-derivation).

## Hybrid ML-DSA signatures

**Today.** Encryption is post-quantum from the start (the hybrid post-quantum KEM; see
[Cryptography](/veyq/security/cryptography/)). Signatures are classical Ed25519: the login auth key, the identity
signing key that signs every grant, and the Secret-Key proof key.

**Gap it would close.** Signatures that are post-quantum as well. ADR 0017 makes the asymmetry deliberate: a
signature is verified once, so there is no harvest-now-decrypt-later for it, and the post-quantum urgency is low for
signatures where it is high for encryption. The stated later step is *Ed25519 now, hybrid ML-DSA later*, avoiding the
size and performance cost of post-quantum signatures before they buy anything.

**Design state.** Strategy only; no construction, suite or migration is recorded.

**Decided in:** [ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)
decision 2 and § Consequences; [en.account.key-model](https://github.com/raddatzk/veyq/blob/main/docs/features/account/enablers/en.account.key-model.md)
(the auth keypair moves to a post-quantum or hybrid signature later).

## A client served by a trusted third party

**Today.** A web client loaded from the operator's own origin protects against a **passive** operator only: an active
operator can serve tampered code. Against an active operator, confidentiality is designed to hold on a client the
operator does not serve — today, a web client you serve yourself against someone else's server — and how much of that
is built is the *What holds today* table. Self-hosted, the question is moot.

**Gap it would close.** The full guarantee **in a browser on someone else's instance**, for the no-install flows (the
public share viewer and the upload page): code from an origin the hosting operator does not control, reproducibly
built.

**Design state.** The trust tier is defined in ADR 0026; it is a SaaS-era build. A branded, trusted domain requires
veyq to control the DNS; an operator-owned domain is always operator-served, because the operator controls its DNS and
so the code delivery — no CNAME or attestation trick durably removes that. Trusted web delivery is listed out of V1 by
decision ([inbox](https://github.com/raddatzk/veyq/blob/main/docs/inbox.md), #53).

**Decided in:** [ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)
(the trusted central / attested web tier and § Consequences).

## Cryptographic share password

**Today.** A share password is an operator-blind **access-control** gate: the server refuses to serve a locked share
without a valid token derived from the password, while the content stays encrypted under the unchanged share key in
the link's fragment. It defends a leaked link against an honest operator. A malicious operator can ignore the check
and serve the ciphertext to a colluding link holder, who decrypts with the fragment.

**Gap it would close.** A password that is cryptographically required to read anything: Argon2id of the password
folded into the decryption key, which protects the content even against a malicious operator colluding with a link
holder.

**Design state.** Weighed and deferred, not designed. ADR 0037 calls it a possible future upgrade for the
malicious-operator case and names what it would cost: the link fragment would have to carry a separate link key (so the
Memory's name and note are not readable with the bare fragment), plus a recoverable copy of that key for in-place
edits, nested wraps and a migration.

**Decided in:** [ADR 0037](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0037-password-protected-shares.md)
(§ Context, § Operator-blindness & threat model, § Alternatives considered (A)).

## Feature switches

**Today.** An instance advertises what it serves as a list of ids, and the apps hide a feature whose id is missing. The
server refuses the matching action with `422 capability_disabled`. Seven entries exist, all under `core`, and each
gate is tested. **The Server Owner switches them** in the admin console and its CLI, for the whole instance, with no
restart, and each entry describes itself in English and German. A device switch acts at once: the server withholds
what those devices unlock with, without deleting it, so their members need the password and the Secret Key until it
is switched back on. The [Moderation](/veyq/run/moderation/#switch-a-feature-off) page says how. There is no
tenant-owner switch.

**The gap.** A tenant owner who does not want password-less devices in their tenant has no way to say so, and a
Server Owner who switches public links off still has every existing link working.

**Design state.** Decided in ADR 0088; the instance level is built, the rest is not. The ids are a tree, a child is
off when its own id or any parent's is, and a tenant owner can only narrow what the operator allows. A switch exists
only where the server can see what it refuses and a rule says what happens to what already exists: share switches act
at once, on existing links too, and a feature switch refuses only what is new. A tenant owner would switch in the
tenant settings. Declaring switches in the deployment files is a later topic. Share switches reaching existing links
and requirements such as a password on every link are each part of this and not built either.

| Entry | State | What the server sees |
|---|---|---|
| `core.public-shares`, with `.contribute` and `.drop-box` | built | a share's role: READ, READ_WRITE, WRITE_ONLY |
| `core.group-space` | built | creating a group space |
| `core.remember-device`, with `.passwordless` | built | a device wrap, or a device-key wrap |
| `core.device-linking` | built | the pairing endpoints |
| `core.personal-space`, `core.member-invites`, `core.breached-password` | planned | a scope kind; the invite endpoint; a call to an external service |
| `memories.chapters`, `.places`, `.map`, `.download-originals` and the application's own share entries | planned | a node kind; external services; a blob role |
| `depot.*` share entries | planned | the application's node kind and blob role |
| `memories.journal`, `memories.gpx` | planned | a node kind or blob role the server would then see |
| `core.self-registration`, `memories.faces` | idea | a new endpoint; a role for a face index |

Export and import, favorites, trash, cover, participants and the viewer are deliberately not in the register: no
switch could be enforced for them, or switching them off would break the app.
