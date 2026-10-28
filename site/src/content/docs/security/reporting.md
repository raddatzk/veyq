---
title: Reporting a vulnerability
description: How to report a security issue in veyq privately, what is in scope, and which findings are already documented as accepted limits.
sources:
  - docs/crosscutting/security-model.md
---

veyq's central claim is cryptographic: the server stores and serves only ciphertext, and a user's unshared content
is confidential even from the operator. A break in that claim is the most serious kind of bug the project can
have, and it is worth reporting even if you are not sure. The binding policy is
[`SECURITY.md`](https://github.com/raddatzk/veyq/blob/main/SECURITY.md); this page restates it.

## How to report

**Do not open a public issue.** Use GitHub's private vulnerability reporting: **Security → Report a
vulnerability** on [github.com/raddatzk/veyq](https://github.com/raddatzk/veyq). This opens a private advisory that
only the maintainers can see.

If the form is unavailable, open a public issue that says only *"security report, please provide a private
channel"*, with no details, and wait for a reply.

A useful report says what you did, what you observed, and why you believe it breaks a guarantee. A concrete
sequence is better than a description. If a proof of concept is easier to give than prose, attach it.

veyq is a small project without a paid response rotation, so there is **no guaranteed response time and no bug
bounty**. Reports are read and taken seriously.

## Read the limits first

The guarantees and their honest limits are stated in the
[security model](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md). Several
tempting findings are **already documented as accepted**, so read it before you report. The ceiling of what veyq
claims against an *actively* malicious operator is its section
[What holds today on a client the operator does not serve](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-holds-today-on-a-client-the-operator-does-not-serve):
a table of attacks with the state of each today, followed by a list of what stays open even once every row reads
*Cannot*. A finding that shows a row's *Cannot* to be false is in scope. A finding that restates an item on the
"stays open" list is not new. See also [Known limitations](/veyq/security/known-limitations/) and
[Roadmap](/veyq/security/roadmap/).

## In scope

- Anything that lets the server, the operator or a database dump recover plaintext, a content key, a password or
  a master key.
- Anything that leaks content or metadata beyond what
  [security-model § What is encrypted vs. server-visible](https://github.com/raddatzk/veyq/blob/main/docs/crosscutting/security-model.md#what-is-encrypted-vs-server-visible)
  declares. That includes leaks through a URL, a path parameter, an error message, a presigned link or a log line.
- Authorization holes: reading or writing content of a scope you are not a member of, or access that survives a
  removal beyond what
  [ADR 0024](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0024-scope-key-rotation.md) allows.
- Anything that breaks the share model: a link role gaining more than it should, or a revoked share still serving.

## Out of scope

- **An actively malicious operator on an instance you do not host.** This is the documented boundary, not a bug.
  The operator serves the client code on every load, so a client it serves can be modified. On operator-served
  web, veyq protects against a *passive* operator only, and self-hosting is the high-assurance answer
  ([ADR 0026](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0026-client-trust-tiers.md)).
- **Content that was already shared.** Revoking a share stops future access. It cannot un-download what a
  recipient already has.
- **The first-use trust gap.** Identity keys are trust-on-first-use. A new key is accepted only through a
  key-chain step signed by the old identity and the old Secret-Key proof key, every grant is signed by its granter, and out-of-band safety-number verification is built, but nothing *enforces* it. The
  opt-in strict mode that would require a verified key before a first share is not built, and its shape is still
  open ([Roadmap](/veyq/security/roadmap/)).
- **Findings against the development stack** in
  [`infra/`](https://github.com/raddatzk/veyq/blob/main/infra/README.md), which is not a production deployment and
  is not presented as one.
- **Missing hardening headers or rate limits on an instance you configured yourself.** Limits are deliberately
  tunable per instance
  ([ADR 0051](https://github.com/raddatzk/veyq/blob/main/docs/decisions/0051-declarative-rate-limit-posture.md)).

## Supported versions

veyq is pre-V1. There are no released versions and no backported fixes: fixes land on `main`.

To check a claim yourself before reporting, see [Verify it yourself](/veyq/security/verify-it-yourself/).
