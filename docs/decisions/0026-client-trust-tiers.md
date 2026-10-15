# ADR 0026 — Client trust tiers & the operator-blindness boundary

- Status: Accepted
- Date: 2026-07-06

## Context

Operator-blind E2EE is the invariant, but confidentiality is enforced by the **client**, and the client's code
has to come from somewhere. A client whose code the operator can silently alter **cannot** hold the guarantee
against an *actively* malicious operator — it could be served tampered code that leaks keys or plaintext. This is
the well-known browser-crypto delivery problem (Matasano, 2011), mitigated in practice by native-only stances
(Signal has no web app), third-party code attestation (WhatsApp + Cloudflare "Code Verify"), store-signed
extensions (MEGA, password managers), and open-source + reproducible builds (Proton, Bitwarden, Tuta) — but never
fully *solved* for operator-served web.

veyq is **self-hostable and multi-operator**, which sharpens it: a hosting operator serves the web client to
*other* users who must be able to **distrust that operator**. We need to state precisely what is guaranteed, per
client, so features (consent-add, sharing, key exchange) place cleanly and we never over-claim.

## Decision

**Two enforcement layers** (the general form of the layers [security-model](../crosscutting/security-model.md)
§ Revocation already uses):

- **Layer 1 — access control (honest-operator, instant, no keys).** The server serves or withholds opaque
  ciphertext by ACL — membership, share tokens, consent, revocation. It reads no content. **It is not a defence
  against the operator** (the operator runs the server); L1 protects users *from each other* and gives instant
  access changes, valid only under an honest operator.
- **Layer 2 — confidentiality (cryptographic).** Content stays encrypted; the server never holds content keys.
  **The only guarantee that holds against an *actively* malicious operator — and only on a client whose code the
  operator cannot alter** (client integrity).

**Client trust tiers** — confidentiality against an *active* operator:

| Tier | Holds? |
|---|---|
| **Self-hosted** (the default posture) | **Moot** — operator = user. There is no adversary to serve you tampered code. |
| **Operator-served web** | **Passive only.** An active operator can serve tampered code that captures keys/plaintext. |
| **Trusted central / attested web** (code origin the operator does *not* control; reproducibly built — SaaS-era) | **Yes,** at the trust of the central origin. |
| **A client the operator does not serve** (a web client you serve yourself against someone else's server; the native apps, once they ship) | **Yes, by design.** |

**"Yes" in the last two rows is the design, and the build has not reached all of it.** On such a client every
remaining gap is protocol, not code delivery — a rotation that trusts the server's member list, trust state the
server can roll back. Which attacks hold today and what closes the rest
is kept in one place, [security-model § What holds today](../crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve),
and nowhere else.

Constant on every tier: the server can **never** read already-stored content (no keys); it can **always** see
operational metadata, deny/alter access, and *attempt* key-substitution on new key exchanges (detectable via the
fingerprint/safety-number seam). The honest boundary in one line: **passive operator → protected everywhere;
active operator → protected when you host it yourself, passive-only on someone else's instance.**

**Positioning: self-hosting *is* the high-assurance answer** — not a fallback for it. veyq is
**self-hostable by default** (a hard invariant), and in that posture the client-integrity question dissolves
rather than being mitigated: you serve your own code. Whoever must distrust their operator has one durable
answer, and it is the same answer the product is built around.

**On someone else's instance, veyq is passive-safe and says so** — honestly labelled, surfaced in-client, never
claimed operator-proof. That is not a gap awaiting a client that closes it: no client a hosting operator ships
can. Closing it needs code from an origin the operator does not control (the trusted/attested tier), which is a
SaaS-era build.

## Consequences

- **veyq never claims operator-served web is operator-proof.** The client **surfaces its tier** to the user and
  the wiki/security-model state the boundary plainly — an honesty that is a **trust asset**, not a weakness (the
  Signal/Proton posture).
- **The invariant carries a load it did not obviously carry before.** *Self-hostable by default* was already a
  hard invariant on sovereignty grounds; it is now also the **only** live answer to client integrity. Anything
  that erodes self-hostability (a build that needs a central service, a feature only the SaaS can run) is
  therefore a **security** regression, not just a product one.
- **Web-delivery hardening (SaaS-era) is the one open path to the guarantee on someone else's instance:** a
  central, reproducibly-built **trusted web client** served from an origin the hosting operator does not control.
  **Branded + trusted** domains require veyq to control the DNS → SaaS. An **operator-owned** domain is *always*
  operator-served (it owns its DNS → controls code delivery; no CNAME/attestation trick durably removes that).
  Tracked in the inbox.
- **The key-substitution residual persists** on every tier until fingerprint/TOFU/KT (the existing seam,
  [ADR 0020](0020-grant-distribution-invite-wrap.md) / security-model § key-directory trust).
- **Rejected — claiming browser-delivered E2EE is operator-proof:** dishonest.
- **Rejected — treating a stronger *unlock* method as a client-integrity answer.** A passkey (WebAuthn, incl. the
  PRF extension) yields a secret the server never sees, so it reads like a fix. It is not: the operator still
  ships the code that *consumes* that secret, and tampered code can forward it the moment it is unwrapped.
  Passkeys defend against phishing and against a server harvesting passwords — not against a server rewriting the
  client that uses them. **Unlock convenience and client integrity are orthogonal axes; a method on one never
  moves the other.** This is the tempting wrong turn here, so it is recorded rather than left to be re-proposed.

Explained for security researchers on the docs site: [Start here](../../site/src/content/docs/security/start-here.md), [Threat model](../../site/src/content/docs/security/threat-model.md), [Roadmap](../../site/src/content/docs/security/roadmap.md).

For Server Owners, on the docs site: [Running your own instance](../../site/src/content/docs/run/index.md).
