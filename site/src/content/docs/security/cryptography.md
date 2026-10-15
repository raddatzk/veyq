---
title: Cryptography
description: The primitives, the hybrid post-quantum KEM and the conformance-pinned suite — this page is written once the KEM change lands.
sources:
  - docs/crosscutting/security-model.md
---

:::note[Not written yet]
This page is written together with a change that is being built now: the hybrid post-quantum KEM becomes X-Wing
([ADR 0017](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0017-crypto-primitives-and-post-quantum-strategy.md)
decision 2). It will describe that KEM as built, the key wrap above it, the key-derivation profile, AEAD framing
and chunking, what a seal binds, and how the suite can change. Until then, the normative sources are the
[security model § Cryptographic stance](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#cryptographic-stance),
ADR 0017, [ADR 0072](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0072-a-seal-binds-its-object.md) and
the conformance-pinned suite
[`packages/crypto/src/v1.ts`](https://github.com/raddatzk/veyq/blob/main/packages/crypto/src/v1.ts) with its
vectors [`contracts/crypto/vectors/v1.json`](https://github.com/raddatzk/veyq/blob/main/contracts/crypto/vectors/v1.json).
:::
