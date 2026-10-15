# Security policy

veyq's central claim is cryptographic: the server stores and serves only ciphertext, and a user's unshared
content is confidential even from the operator. A break in that claim is the most serious kind of bug this
project can have, and it is worth reporting even if you are not sure.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private vulnerability reporting:

> **Security** → **Report a vulnerability** on <https://github.com/raddatzk/veyq>

That opens a private advisory visible only to the maintainers. If the form is unavailable, open a public issue
that says only *"security report, please provide a private channel"* — no details — and wait for a reply.

Helpful in a report: what you did, what you observed, and why you believe it breaks a guarantee. A concrete
sequence beats a description. If a proof-of-concept is easier than prose, attach it.

This is a small project without a paid response rotation, so there is no guaranteed response time and no bug
bounty. Reports are read and taken seriously.

## What is in scope

The guarantees are stated in the [security model](docs/crosscutting/security-model.md), and the honest limits
are stated there too — read them first, because several tempting findings are **already documented as
accepted**:

- Anything that lets the server, the operator, or a database dump recover plaintext, a content key, a
  password, or a master key.
- Anything that leaks content or metadata beyond what
  [security-model § What is encrypted vs. server-visible](docs/crosscutting/security-model.md#what-is-encrypted-vs-server-visible) declares — including through
  a URL, a path parameter, an error message, a presigned link, or a log line.
- Authorization holes: reading or writing content of a scope you are not a member of; access surviving a
  removal beyond what [ADR 0024](docs/decisions/0024-scope-key-rotation.md) allows.
- Anything that breaks the share model — a link role gaining more than it should, or a revoked share still
  serving.

## What is out of scope

- **An actively malicious operator on an instance you do not host.** This is not a bug, it is the documented
  boundary: a client the operator serves can be modified, which is why self-hosting is the high-assurance
  answer ([ADR 0026](docs/decisions/0026-client-trust-tiers.md)).
- **Already-shared content.** Revoking a share stops future access; it cannot un-download what a recipient
  already has.
- **The first-use trust gap.** Identity keys are trust-on-first-use. A changed key is surfaced, every grant is
  signed by its granter, and out-of-band safety-number verification is built, but nothing *enforces* it: the
  opt-in strict mode that would require a verified key before a first share is decided, not built — see
  [ADR 0028 § What is still open](docs/decisions/0028-identity-authenticity-tofu-verification.md#what-is-still-open).
- Findings against the development stack in [`infra/`](infra/README.md), which is not a production
  deployment and is not presented as one.
- Missing hardening headers or rate limits on an instance the reporter configured themselves — limits are
  deliberately per-instance tunable ([ADR 0051](docs/decisions/0051-declarative-rate-limit-posture.md)).

## Supported versions

veyq is pre-V1. There are no released versions and no backported fixes: fixes land on `main`.
