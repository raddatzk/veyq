# ADR 0050 — Operator config secrets are encrypted at rest with a server-managed KEK

- Status: Accepted
- Date: 2026-07-24

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
Key-Encryption-Key the server manages as a file.** Concretely:

- **The KEK is a 32-byte key in a server-managed file.** On first start the server generates one, writes it
  `0600`, and logs loudly that it must be backed up; an operator who supplies their own mounts it and points
  `veyq.security.config-secret-file` at it — the K8s/Docker-secret shape. It is the same trust tier as the
  datasource and blob-store credentials, never committed, never in the DB. The name is deliberately generic
  (not mail-specific): it seals *any* server-held config secret.
- The secret is sealed with **AES-256-GCM via standard JCA** (no new dependency, no home-grown crypto); the DB
  stores only `ciphertext + a per-record random 12-byte nonce`. The server unseals on demand (at send time).
- **A fingerprint of the active KEK lives in the DB, as a tripwire.**
  `HKDF-SHA256(ikm=KEK, info="veyq/v1/config-secret-key-fingerprint")`, one server-global row. A first start
  with no row **adopts** the current key by writing the fingerprint; a later **mismatch** means the loaded key
  is not the one that sealed the stored secrets. Storing it is safe, and for a specific reason rather than by
  assertion: a 256-bit full-entropy key is **not** brute-forceable from its hash — unlike a password, which
  would need a salt and a slow KDF — so "a DB dump yields no usable secret" still holds. The DB gains a
  fingerprint, never the key.
- This is **operator-side** crypto, distinct from the client-side content E2EE ([ADR 0012](0012-content-key-hierarchy.md)):
  it protects an operator's *own* infrastructure secret, not user content, so it does **not** touch
  operator-blindness — the server was always allowed to hold its own infra creds; this only stops them sitting
  in the DB in the clear.
- **Scope: the mail password now**, and the same envelope is the pattern for any future server-held config
  secret. **Not** the full #43 (no multi-ServerOwner, no per-tenant server-scope key).
- **Mismatch posture: degrade, don't brick.** The operator-blind rest of veyq needs no KEK, so only the
  config-secret subsystem degrades, and a loud startup log names the recovery.
  [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) fixes what that degradation means in practice: a
  tenant whose SMTP carries a `username` is simply **non-sendable** without an openable KEK, so member invites
  and password recovery are **refused** with `503` (§2/§4) rather than degraded, and only the Server-Owner's
  bootstrap invite still falls back to a copy-link (§3). An unopenable KEK therefore costs the tenant those two
  actions outright. The owner recovers by re-entering the SMTP password: the reseal happens under the current
  key and advances the stored fingerprint back to `OK`. The mismatch row is **never** auto-overwritten — that
  would defeat the detection it exists for; it clears only on a deliberate reseal.
- **No KEK rotation in v1**, a documented limitation: rotation means re-sealing every record under a new KEK.
  Loss is no longer silent — the fingerprint names it — but rotation itself stays a later concern, cleanly
  signalled by that same fingerprint. With one config secret today, re-entering it fully recovers; that scales
  with #43, where a key change means re-entering **all** stored secrets.

## Consequences

- **The property is preserved.** A DB dump *alone* still yields no usable secret — decryption needs the key
  file, which does not live in the database, and the stored fingerprint reveals nothing about it. The guarantee
  is extended to operator config secrets, not surrendered.
- **The threat model this defends, stated honestly.** A key file plus a DB fingerprint defend the **leaked DB
  backup** — the common real leak. They do **not** defend a full-host compromise, where the file and the
  database sit on one box. That is a deliberate trade for zero-config self-hostability, bought with key loss
  becoming **detected** instead of silent.
- **veyq's first server-held key.** Small, standard, self-contained — but a real new primitive. It marks the
  exact seam **#43** will generalize (encrypting more operational PII/secrets, possibly under a per-ServerOwner
  server-scope key with rotation). This ADR is the narrow down-payment, cited from there.
- **Shared-instance trust (stated).** The Server Operator holds the KEK and can unseal a tenant's SMTP password —
  inherent to running the send path on the tenant's behalf ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md));
  self-hosted, operator = tenant, moot.
- **The sealed secret never leaves sealed.** Write-only over the API (never returned or logged); a DB dump,
  backup, or subpoena of data-at-rest sees ciphertext.
- **The key file is durable state, and the third leg of a backup.**
  [ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) captures it alongside the database and the
  store objects precisely because an instance restored without it starts cleanly and is still broken, in exactly
  the two flows above. `scripts/validate_config_secret_persistence.py` holds the compose side of that.
- **Rejected — the KEK as an env value.** The original shape was `VEYQ_SECURITY_CONFIG_SECRET_KEY`, base64,
  supplied by the operator. It was dropped while still greenfield — wired only in test properties and docs,
  never deployed — for two reasons. A self-hoster who simply omitted it hit a silent fallback rather than a
  signal; and an env value is **not** strictly safer than a file, having its own leak surface in
  `/proc/<pid>/environ`, `docker inspect`, crash dumps and child-process inheritance, while a mounted file is
  the standard secret-injection path on both Docker and Kubernetes.
- **Rejected — plaintext in the DB.** Breaks the "no usable secret in a DB dump" property for a first-of-its-kind
  operator secret.
- **Rejected — building the full #43 vault / server-scope key now.** Premature: the mailer needs *one* narrow
  secret protected, not a multi-operator key hierarchy. This ADR is deliberately smaller and cited from #43.
- **Rejected — keeping the password in env, keyed per tenant.** Cannot self-service (a tenant can't edit env at
  runtime) and does not scale to many tenants; the whole point of per-tenant settings is runtime configuration.

For Server Owners, on the docs site: [Hardening](../../site/src/content/docs/run/hardening.md).
