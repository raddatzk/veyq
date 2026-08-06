# ADR 0056 — An instance's durable state has three legs, and it is captured with the writer stopped

- Status: Accepted
- Date: 2026-08-06

## Context

[Architecture §10](../architecture/README.md) sets a durability bar in executable words: *a backup taken by the
documented procedure restores onto an empty instance, and afterwards every Memory opens with the keys their
owners already hold.* Half of it is built — the key half, [uc.account.recover-access](../features/account/use-cases/uc.account.recover-access.md)
and [ADR 0054](0054-password-recovery-two-factor-release.md). The server half is missing for a duller reason
than the first: nobody wrote the procedure down, so the bar's own sentence cannot be executed. This ADR decides
what that procedure captures and under what conditions.

The published operator page already tells users a veyq backup has **two halves and the server holds one of
them** — the server's ciphertext, and the users' passwords and Emergency Kits, which the operator cannot back
up at all. That split is right and this ADR does not disturb it. What it corrects is the server's half, which
is not one thing.

Four properties of the built system constrain the answer.

**1. There are three durable locations, and the third is a file on the app's filesystem.** The database and the
object store are the two anyone names. The third is the config-secret key: [ADR 0050](0050-operator-config-secret-encryption.md)
§ Amendment made the server generate and manage it as a `0600` file, defaulting to the process working
directory, and it is the KEK under which every per-tenant SMTP password is sealed. Its loss is detected — the
instance row carries an HKDF fingerprint — and detection is the whole of the remedy: a server that boots
without its key reports `configSecretState: MISMATCH` and **refuses member invites and password resets**. A
restore that captures the database and the store therefore starts cleanly and is still broken, in exactly the
two flows [ADR 0054](0054-password-recovery-two-factor-release.md) and [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)
exist to protect.

**2. The read path tolerates one skew and not its mirror.** Every store deletion runs best-effort *after* the
database transaction commits, so an object with no row is an ordinary steady state — orphans accumulate, and
the upload-session GC that `V11__media.sql` promised as "a later slice" was never built. The mirror case is
handled nowhere: a media read presigns a GET without asking the store whether the object exists, so a row whose
object is missing becomes a valid URL that 404s, with no server-side signal and a broken picture as the only
symptom.

**3. The app is the only writer to the database, but not the only writer to the store.** By
[ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §2 the backend never streams bytes; the client PUTs
directly under a presigned URL, and such a URL stays valid for its TTL after the backend is gone. A row,
however, is only ever written by the app.

**4. The store's configuration is not state.** Bucket, access key and single-node layout are recreated
idempotently by `infra/garage-init.sh`. Only the objects are durable.

## Decision

**1. An instance's durable state is exactly three legs.** (a) the **database**; (b) the object store's
**contents**, meaning the objects and not the store's on-disk representation; (c) the **config-secret key
file**. A procedure that captures fewer than three does not produce a restorable instance, and the third is
named first in the runbook precisely because it is the one that gets forgotten.

Three things inside the database are worth naming because losing them is not obviously recoverable: the
instance salt, which every client folds into its auth salt — an instance that loses it can authenticate nobody;
the config-secret fingerprint, which is what turns leg (c)'s absence into a detected fault rather than a silent
one; and the tenant's domain, which decides who can reach the instance at all (Decision 4).

**2. The capture is cold, and *cold* means the writer is stopped — not that the stores are.** Stop the
application; leave the database and the object store running, because the capture reads through their own
interfaces.

That single act buys the whole consistency argument. With the app stopped the database cannot gain a row
(Context 3), so the one skew the read path does not handle — a row whose object is missing (Context 2) —
becomes **structurally impossible**. An object that lands afterwards under a still-valid presigned URL never
gets a row, which is the skew the running system already produces by design. There is therefore **no capture
ordering to get right and no presign TTL to wait out**; the single precondition is that the app is stopped
before the database is dumped.

This is chosen over a hot capture *because* of that simplicity, not despite it. A correct hot procedure is a
three-level superset argument — the database must be a subset of the store index, which must be a subset of the
blocks — and its two plausible orderings differ in which direction of corruption they produce. A procedure
whose correctness rests on an argument that subtle is a procedure that gets executed wrong. The price is
downtime equal to the capture, which for a household instance is the cheaper side of that trade.

**3. The store leg is captured logically.** The backup is the object namespace read through the S3 API, not a
copy of the store's data directories. Three reasons, in order of weight:

- **It can be verified.** Object keys are comparable against the blob rows that reference them, so the one
  failure mode Context 2 leaves unhandled can be *detected in the backup* rather than discovered at restore. A
  volume copy is opaque: a complete one and a truncated one look identical until you restore them.
- **It is neutral.** It restores onto any S3-compatible store, and onto a different version of the same one.
  The backend signs through a vendor-neutral SDK precisely so the store is replaceable; a volume backup would
  reintroduce at the backup layer the binding the code goes out of its way to avoid, and would additionally tie
  the restorability of old backups to the store's on-disk format.
- **The restore target is cheap.** `infra/garage-init.sh` already recreates layout, bucket and key
  idempotently (Context 4), so a logical restore needs no surviving store identity.

**The tool is invoked, not vendored:** the procedure shells out to `rclone`. `scripts/` carries no third-party
Python dependency today and this decision does not add one, in the same shape as the e2e orchestrator shelling
out to `docker compose`. Rejected: a boto3-based script, which would make one procedure the reason for the
script tree's first dependency; and hand-rolled SigV4 signing, which would have us own signing code no one else
reviews.

A **volume copy remains documented as the fast path** for a same-host, same-version rollback — it is not the
procedure the durability bar is measured against. Note that the store's own tooling does not fully serve even
that path: a running node can snapshot its metadata database consistently, but there is no equivalent for its
data blocks. Where snapshots are used, their directory is configured **outside** the metadata directory, so
that they do not accumulate inside the very thing being copied.

**4. A restore targets the same domain. Changing it is out of scope for V1.** The active tenant resolves from
the request Host, the tenant's domain is unique, and that domain is also written into every invitation and
recovery link the instance mails. A restore onto a different hostname therefore produces an instance nobody can
reach, holding links that point at a name that no longer serves it.

V1 states this as a boundary rather than documenting a hand-written `UPDATE`. A runbook step that edits the
database directly is one an operator will eventually run against the wrong row, and a *supported* domain change
is a product surface with its own flow — mail templates, outstanding invitations, the unique index — not a
footnote in a restore procedure.

**5. Reading the config-secret state is the restore's final step, not its error path.** The fingerprint
comparison is the only check that distinguishes a complete restore from one that lost leg (c), and it is the
difference between an instance that looks healthy and one that cannot admit a member or reset a password. The
procedure ends by asserting it, in the same spirit as reading a CI log rather than trusting its colour.

**6. A backup is a precondition of an upgrade, not a neighbour of it.** The schema is migrated at boot with no
operator gate, and the migration set carries no downgrade path — so returning to the previous image means
restoring a backup. The upgrade procedure therefore *begins* with this one, and that ordering belongs to this
decision rather than to the runbook's discretion.

## Consequences

- **The operator page gains a leg.** Its two-halves framing — the server's ciphertext against the users' keys —
  stays exactly as it is; what changes is that the server's half is stated as three things, with the key file
  named as the one that fails quietly.
- **The deployment artifacts must persist leg (c), and today none of them do.** No image declares a volume for
  it and no compose file mounts it, so a container that is recreated regenerates the key and walks into the
  `MISMATCH` state on its own. This decision surfaces that defect; it is fixed where deployment lives, not here.
- **The durability bar becomes executable.** Its sentence turns into an acceptance criterion with an owner:
  [uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md).
  Until a test carries it, §10 keeps saying *half held* — the bar follows the proof, never the intention.
- **The backup inherits the instance's garbage.** Abandoned upload sessions and their partial chunks, and
  orphaned objects whose rows are gone, are captured as they are. They are what a running instance already
  accumulates (Context 2), not something the procedure introduces, and a restore is not the place to start
  collecting them.
- **Rejected — a hot capture with a documented ordering.** It is correct only under an argument nobody executes
  reliably, and it buys a household instance minutes it does not need (Decision 2).
- **Rejected — volume copy as the primary form.** Faster and credential-free, but unverifiable and bound to one
  store's format and version (Decision 3).
- **Rejected — a domain-rewrite step in the restore.** It is a product surface wearing a runbook's clothes
  (Decision 4).

## Related

- [architecture §10](../architecture/README.md) — the durability bar this makes executable
- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) — presigned direct-to-store, why the client is a writer
- [ADR 0050](0050-operator-config-secret-encryption.md) — the managed config-secret key and its fingerprint
- [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) — what a lost key file actually costs
- [infra/README](../../infra/README.md) — where the procedure and the dev stack live
