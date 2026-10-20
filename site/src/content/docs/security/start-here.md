---
title: Start here
description: What "operator-blind" means in veyq, what it does not cover, which client it holds on, and the order to read this area in.
sources:
  - docs/crosscutting/security-model.md
  - docs/decisions/0026-client-trust-tiers.md
---

veyq is a self-hostable platform for sealed, shareable objects; its first application is Memories. Every scope
(personal, group, tenant) is end-to-end encrypted, and the server is **operator-blind**: it stores ciphertext,
opaque operational metadata and a blind job queue, and never holds a key that opens content. This page says
precisely what that claim covers, against whom, and on which client.

## The one guarantee

A user's **unshared** content is confidential **even from the operator**. The server enforces *authorization* on
opaque identifiers; the client enforces *confidentiality* with keys the server never sees. Confidentiality is
therefore cryptographic, not a server-side access-control promise
([security model § The one guarantee](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#the-one-guarantee)).

"The operator" names one adversary: the veyq server and whoever runs it. The security model and every page in this
area are written against it.

## Two enforcement layers

Every control in veyq sits on one of two layers
([ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)):

| Layer | What it is | Holds against |
|---|---|---|
| **1 — access control** | The server serves or withholds ciphertext by membership, share tokens, consent and revocation. Instant, needs no keys, reads no content. | Other users, **under an honest operator**. It is no defence against the operator, who runs the server. |
| **2 — confidentiality** | Content stays encrypted; the server never holds a content key. | An *actively* malicious operator, **but only on a client whose code that operator cannot alter**. |

A check the server runs is layer 1 by definition. Only something a client verifies with a key the operator lacks
can move a claim to layer 2 (security model § What holds today).

## What operator-blind does not cover

- **Metadata.** The server sees ids, structure, membership, exact timestamps, sizes, the account email and a few
  more facts, by design, so it can sort, paginate, route, authorize and bill without decrypting. The full list and
  the deliberate concessions are on [What the server sees](/veyq/security/what-the-server-sees/).
- **Availability and freshness.** On every tier the operator can deny or alter access. Binding a ciphertext to its
  object stops the server swapping one object's ciphertext for another's, but the server still chooses *which
  authentic version* a client receives; a device refuses an older one only where it keeps a mark of its own (the
  identity pin store, the device register, the per-scope epoch floor).
- **An active operator on a client it serves.** See the trust tiers below.
- **Key substitution at first contact.** The operator relays identity keys. A changed key is detected and surfaced
  to anyone who pinned the old one, and two members who compare safety numbers detect a substituted one; a key
  substituted before anyone pinned it, between members who never compare, is not detected. Key Transparency, which
  would close this, is not built ([Roadmap](/veyq/security/roadmap/)).
- **Recipients.** Sharing gives confidentiality up towards the people it is shared with, on purpose. A public link
  is a bearer capability: whoever holds the full URL decrypts what it shares. Content a recipient already
  downloaded cannot be retracted, by revocation or by key rotation.
- **Third-party services the client talks to directly.** The opt-in reverse-geocoding lookup and the automatic
  breached-password check go from the device straight to their provider, never through veyq, and carry no veyq
  identity. They are a separate trust boundary, stated on the [Threat model](/veyq/security/threat-model/).
- **The user's own device.** Content is decrypted there. A stolen device alone opens nothing by default, but one
  the user chose to remember without a password does; the [Threat model](/veyq/security/threat-model/) has the
  row.

## Client trust tiers

Confidentiality is enforced by the client, and a web client's code has to come from somewhere. Whether layer 2
holds against an *active* operator therefore depends on who serves the client
([security model § Client integrity & trust tiers](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#client-integrity--trust-tiers--what-a-malicious-operator-can-do),
[ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)):

| Tier | Against an active operator |
|---|---|
| **Self-hosted** — you run the instance and serve its client | **Moot.** The operator is you; nobody serves you tampered code. This is veyq's default posture and its high-assurance answer. |
| **Operator-served web** — someone else's instance, its own web client | **Passive operator only.** An active operator can serve tampered code that captures keys or plaintext. veyq labels this in the client and never claims it is operator-proof. |
| **A client the operator does not serve** — a web client you serve yourself against someone else's server; the native apps, once they ship | **Designed to hold.** How much of that design is built is the active-operator half of the [Threat model](/veyq/security/threat-model/). |
| **Trusted third party** — a reproducibly built web client from an origin the hosting operator does not control | **Would hold**, at the trust of that origin. **Not built** ([Roadmap](/veyq/security/roadmap/)). An operator-owned domain is always operator-served: it owns its DNS, so it controls code delivery. |


Constant on every tier: the server can **never** read already-stored content, because it holds no key; it can
**always** see operational metadata, deny or alter access, and *attempt* key substitution on new key exchanges.
In one line: **a passive operator is defeated everywhere; an active operator is defeated when you host veyq
yourself, and on someone else's instance veyq holds against a passive operator only.**

A stronger unlock method does not move this boundary. A passkey yields a secret the server never sees, but the
operator still ships the code that consumes it; unlock convenience and client integrity are independent axes
(ADR 0026, rejected alternatives).

## Reading path

1. **Start here** — this page: the claim, its limits, the trust tiers.
2. [Threat model](/veyq/security/threat-model/) — each adversary, and what it can and cannot do today.
3. [What the server sees](/veyq/security/what-the-server-sees/) — the server-visible metadata and the deliberate concessions.
4. [Cryptography](/veyq/security/cryptography/) — the primitives, the hybrid post-quantum KEM and the conformance-pinned suite.
5. [Key hierarchy](/veyq/security/key-hierarchy/) — from unlock methods through the Master Key and identity to scope, node and blob keys.
6. [Protocols](/veyq/security/protocols/) — admission, grant delivery, rotation, sharing, device linking and recovery, step by step.
7. [How veyq is tested](/veyq/security/how-veyq-is-tested/) — the checks that stand in for a human code review, what each proves, and which have run.
8. [Verify it yourself](/veyq/security/verify-it-yourself/) — the vectors, tests and guards that hold these claims, and how to run them.
9. [Known limitations](/veyq/security/known-limitations/) — every residual the design states, in one place.
10. [Roadmap](/veyq/security/roadmap/) — what is designed and not built.
11. [Reporting](/veyq/security/reporting/) — how to report a vulnerability.

To check that a release you run is the one that was published, see [Verify a release](/veyq/run/verify-a-release/).

:::note
The normative sources are the [security model](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md)
and the ADRs under [`docs/decisions/`](https://github.com/raddatzk/veyq/tree/main/docs/decisions). Byte formats
are specified only by [`packages/crypto/src/v1.ts`](https://github.com/raddatzk/veyq/blob/main/src/packages/crypto/src/v1.ts)
and its vectors, [`contracts/crypto/vectors/v1.json`](https://github.com/raddatzk/veyq/blob/main/contracts/crypto/vectors/v1.json).
Where this area and those files disagree, the files win.
:::
