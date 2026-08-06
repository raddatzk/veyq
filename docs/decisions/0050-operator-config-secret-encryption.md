# ADR 0050 — Operator config secrets are encrypted at rest with a server-managed KEK

- Status: Accepted
- Date: 2026-07-24 (amended 2026-07-24 — see [Amendment](#amendment-2026-07-24--the-kek-is-a-managed-key-file-guarded-by-a-db-fingerprint))

## Context

veyq stores **zero usable secrets in its database** today, and this is a stated property: an invite is a
`token_hash`, a session is a token **hash**, a share password is a **verifier + salt**, every `wrapped_*` column
is client-sealed ciphertext the operator cannot open. "A DB dump yields no usable secret" holds
([security-model](../crosscutting/security-model.md)).

Per-tenant SMTP ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) needs the **SMTP password stored
server-side**: the tenant owner enters it at runtime and the send path must use it — there is no way around it
for tenant-self-service transport. Storing it in the clear would be the **first usable secret ever persisted in
the veyq DB**, breaking the property above. There is **no operator-side encryption-at-rest today** — the only
crypto is the client-side content E2EE ([ADR 0012](0012-content-key-hierarchy.md)), which the server cannot use
(it holds no content key). At-rest handling of operator/PII secrets ultimately belongs to the multi-ServerOwner
/ server-scope-key work (inbox #43), which is unbuilt; this ADR pulls forward the **minimal, self-contained**
slice the mailer needs, without pre-empting #43's key hierarchy.

## Decision

**A server-held config secret that must be persisted is encrypted at rest with AES-256-GCM under a
Key-Encryption-Key supplied via the operator's environment.** Concretely:

- The KEK is a 32-byte key from env — `VEYQ_SECURITY_CONFIG_SECRET_KEY` (property `veyq.security.config-secret-key`),
  base64 — the same trust tier as the datasource and blob-store creds already there, never committed, never in
  the DB. The name is deliberately generic (not mail-specific): it seals *any* server-held config secret.
- The secret is sealed with **AES-256-GCM via standard JCA** (no new dependency, no home-grown crypto); the DB
  stores only `ciphertext + a per-record random 12-byte nonce`. The server unseals on demand (at send time).
- This is **operator-side** crypto, distinct from the client-side content E2EE ([ADR 0012](0012-content-key-hierarchy.md)):
  it protects an operator's *own* infrastructure secret, not user content, so it does **not** touch
  operator-blindness — the server was always allowed to hold its own infra creds; this only stops them sitting
  in the DB in the clear.
- **Scope: the mail password now**, and the same envelope is the pattern for any future server-held config
  secret. **Not** the full #43 (no multi-ServerOwner, no per-tenant server-scope key).
- **No KEK rotation in v1** (documented limitation; rotation = re-seal every record under a new KEK, a later
  concern). **KEK loss** ⇒ the sealed configs are unreadable and the owner re-enters the password — an accepted
  operational cost.

## Consequences

- **The property is preserved.** A DB dump *alone* still yields no usable secret — decryption needs the env KEK,
  and an attacker with the operator's env already holds the DB creds anyway. The guarantee is extended to
  operator config secrets, not surrendered.
- **veyq's first server-held key.** Small, standard, self-contained — but a real new primitive. It marks the
  exact seam **#43** will generalize (encrypting more operational PII/secrets, possibly under a per-ServerOwner
  server-scope key with rotation). This ADR is the narrow down-payment, cited from there.
- **Shared-instance trust (stated).** The Server Operator holds the KEK and can unseal a tenant's SMTP password —
  inherent to running the send path on the tenant's behalf ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md));
  self-hosted, operator = tenant, moot.
- **The sealed secret never leaves sealed.** Write-only over the API (never returned or logged); a DB dump,
  backup, or subpoena of data-at-rest sees ciphertext.
- **Rejected — plaintext in the DB.** Breaks the "no usable secret in a DB dump" property for a first-of-its-kind
  operator secret.
- **Rejected — building the full #43 vault / server-scope key now.** Premature: the mailer needs *one* narrow
  secret protected, not a multi-operator key hierarchy. This ADR is deliberately smaller and cited from #43.
- **Rejected — keeping the password in env, keyed per tenant.** Cannot self-service (a tenant can't edit env at
  runtime) and does not scale to many tenants; the whole point of per-tenant settings is runtime configuration.

## Amendment (2026-07-24) — the KEK is a managed key file, guarded by a DB fingerprint

The original decision required the operator to supply the KEK as an env **value**
(`VEYQ_SECURITY_CONFIG_SECRET_KEY`). Two problems: a self-hoster who omits it hits a silent copy-link fallback,
and a lost/changed key fails **silently** (unseal errors, no signal). This amends the **source** of the KEK and
adds a **tripwire** (cr-config-secret-key-file-and-fingerprint). The envelope (AES-256-GCM via JCA, ciphertext +
nonce in the DB, operator-side, not #43) is unchanged.

- **Source: a server-managed key file, not an env value.** On first start the server generates a 32-byte KEK,
  writes it `0600`, and logs loudly to back it up. An operator supplying their own key mounts it and points
  `veyq.security.config-secret-file` at it — the K8s/Docker-secret shape (a mounted *file*). The env **value**
  channel is dropped (it was greenfield — wired only in test properties + docs, no deployment). Rationale: an env
  value is **not** strictly safer — it has its own leak surface (`/proc/<pid>/environ`, `docker inspect`, crash
  dumps, child-process inheritance) — and a file is the standard secret-injection path.
- **Tripwire: an HKDF fingerprint of the active KEK in the DB.**
  `HKDF-SHA256(ikm=KEK, info="veyq/v1/config-secret-key-fingerprint")`, one server-global row. First start with no
  row **adopts** the current key (writes the fingerprint); a later **mismatch** means the loaded key is not the one
  that sealed the stored secrets. Safe to store: a 256-bit full-entropy key is **not** brute-forceable from its
  hash (unlike a password, which needs salt + a slow KDF), so the "no usable secret in a DB dump" property holds —
  the DB gains a fingerprint, never the key.
- **Mismatch posture: degrade, don't brick.** The operator-blind rest of veyq needs no KEK, so only the
  config-secret subsystem degrades — the send path fell back to copy-link and a loud startup log names the
  recovery. [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) narrowed what that degradation means:
  a tenant whose SMTP carries a `username` is simply **non-sendable** without an openable KEK, so member invites
  and password recovery are **refused** with `503` (§2/§4) rather than degraded, and only the Server-Owner's
  bootstrap invite still falls back to a copy-link (§3). The startup log and the recovery below are unchanged —
  what changed is that an unopenable KEK now costs the tenant those two actions outright.
  The owner recovers by re-entering the SMTP password (reseal under the current key → the stored
  fingerprint is updated → back to OK). The mismatch row is never auto-overwritten (that would defeat detection);
  it clears only on a deliberate reseal. With one config secret today this fully recovers; it scales with #43 (a
  key change then needs re-entering **all** stored secrets).

**Threat-model shift (honest):** a key file + DB fingerprint defend the **leaked DB backup** — the common real
leak — but **not** a full-host compromise (file + DB on one box). This is a deliberate move from "env-only" toward
zero-config self-hostability, with key loss now **detected** rather than silent. KEK **rotation** stays the open
limitation (loss is no longer silent, but rotation — re-sealing every record under a new KEK — is still a later
concern, now cleanly signalled by the same fingerprint).

This supersedes the original **Decision** bullet "The KEK is a 32-byte key from env …" and the **Consequences**
line on env trust: the trust tier is now a `0600` file on the app host (or a mounted secret), and the header title
reflects "server-managed" rather than "env-supplied".
