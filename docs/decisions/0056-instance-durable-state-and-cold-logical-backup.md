# ADR 0056 — An instance's durable state has three legs, and it is captured with the writer stopped

- Status: Accepted
- Built: yes
- Date: 2026-08-06
- Model: [cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md)

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
makes the server generate and manage it as a `0600` file, defaulting to the process working directory, and it is the KEK
under which the server's config secrets are sealed, every tenant's SMTP password among them. Its loss is detected — each
sealed value carries an HKDF fingerprint of the key that sealed it — and detection is the whole of the remedy: a server
that boots without its key reports `configSecretState: MISMATCH` and **refuses member invites and password resets**. A
restore that captures the database and the store therefore starts cleanly and is still broken, in exactly the two flows
[ADR 0054](0054-password-recovery-two-factor-release.md) and [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)
exist to protect.

**2. The read path tolerates one skew and not its mirror.** An object with no row is an ordinary steady state: by
[ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) it is a reclaim **pending**, or an object the bucket
reconcile counts and reclaims after its grace where the Server Owner allows it — never an orphan that accumulates
unrecorded ([cc.storage.object-lifecycle](../crosscutting/cc.storage.object-lifecycle.md) § Reclaim). The mirror case
is handled nowhere: a media read presigns a GET without asking the store whether the object exists, so a row whose
object is missing becomes a valid URL that 404s, with no server-side signal and a broken picture as the only
symptom.

**3. The app is the only writer to the database, but not the only writer to the store.** By
[ADR 0013](0013-blob-storage-presigned-direct-to-store.md) §2 the backend never streams bytes; the client PUTs
directly under a presigned URL, and such a URL stays valid for its TTL after the backend is gone. A row,
however, is only ever written by the app.

**4. The store's configuration is not state.** Bucket, access key and single-node layout are recreated
idempotently by the **backend itself**, on every start, from the declared store type
([ADR 0067](0067-store-provisioning-from-a-declared-type.md)). Only the objects are durable.

## Decision

**1. An instance's durable state is exactly three legs.** (a) the **database**; (b) the object store's
**contents**, meaning the objects and not the store's on-disk representation; (c) the **config-secret key
file**. A procedure that keeps fewer than three does not produce a restorable instance, and the third is
named first in the runbook precisely because it is the one that gets forgotten. *Keeps*, not *captures*: the
capture holds leg (b) as its index, and the objects themselves are kept by the store or by an object pool (§3).

**Leg (c) is captured, but never beside legs (a) and (b).** ADR 0050's key file defends a leaked database
backup only while the key is somewhere else. A capture directory that holds the dump *and* the key is
precisely a leaked backup that opens every config secret the key seals. So the capture writes the key to a
destination of its own and refuses one inside the capture directory. Putting it beside the dump takes an
explicit flag, and the manifest records which of the two was done, or that leg (c) is absent. A restore reads
the key from wherever the manifest says it went. If it was captured separately and is not supplied, the
restore **refuses**, unless the operator explicitly accepts a restore without it. Everything a capture writes
is owner-only (`0700` directories, `0600` files), and the runbook says to encrypt a capture before it leaves
the host and to keep the key on a different medium.

Three things inside the database are worth naming because losing them is not obviously recoverable: the
instance salt, which every client folds into its auth salt — an instance that loses it can authenticate nobody;
the config-secret fingerprints, which are what turn leg (c)'s absence into a detected fault rather than a silent
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

**3. The store leg is captured logically, as its index; the objects go into an object pool only when one is named.** The
backup reads the object namespace through the S3 API, never the store's data directories, and on every target it records
that namespace as an **index**: every object's key, size and MD5, in one listing, in the manifest. The objects stay in
the store: keeping the objects is the store operator's job (its versioning, replication or snapshots), and a backup that
copied the whole bucket every time would make each capture, and each upgrade's downtime, as large as the store. Two
properties of the built system make the index enough for what veyq's own backup is for: an upgrade migrates the schema
and **never rewrites an object** — every object is an immutable ciphertext blob, written once by one presigned PUT — so
the database plus the index is a complete way back from an upgrade; and the index is exactly what lets a restore *check*
the objects without holding them.

Logical, for three reasons, in order of weight:

- **It can be verified.** Object keys are comparable against the blob rows that reference them, so the one
  failure mode Context 2 leaves unhandled can be *detected in the backup* rather than discovered at restore — and
  the index's sizes and MD5s are what a restore holds the live store, or a pool, against before it writes anything
  (§7). A volume copy is opaque: a complete one and a truncated one look identical until you restore them.
- **It is neutral.** A pool restores onto any S3-compatible store, and onto a different version of the same one.
  The backend signs through a vendor-neutral SDK precisely so the store is replaceable; a volume backup would
  reintroduce at the backup layer the binding the code goes out of its way to avoid, and would additionally tie
  the restorability of old backups to the store's on-disk format.
- **The restore target is cheap** — cheaper, in fact, than when this was written. Layout, bucket and key are
  recreated idempotently (Context 4), so a logical restore needs no surviving store identity; and since
  [ADR 0067](0067-store-provisioning-from-a-declared-type.md) the thing that recreates them is the app's own
  first start, so the target is just a normally installed instance rather than a stack with an extra step run
  against it. That same start also migrates the schema, which is why *empty* in
  [A5](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md) means **holding no data** rather
  than **holding no tables**.

**The object pool is optional and named per run** (a directory, or a bucket of another S3-compatible store whose
credentials come from the environment, never an argument). It is **copied into, never synced**: rclone
`copy --immutable`, never `sync` or `move` — a key whose bytes differ from the pool's copy fails the capture instead of
replacing it (objects are immutable, so a differing copy is a fault, never an update; measured with the pinned rclone
1.75.0: `copy --immutable` exits 6, *"immutable file modified"*, and leaves the pool's copy). It is **incremental by
construction**: a key the pool holds is not copied again. The capture records the pool's target (never a credential)
beside the index, and **the index is what ties the two**: a restore copies exactly the index's keys out of the pool,
whatever else the pool holds. The pool holds ciphertext only, as the store does, so it changes nothing about what the
operator can read.

**The pool keeps exactly what its retained captures need.** An object is deleted from the pool as soon as no retained
capture's index names it, and captures rotate by `keep` — so a deletion, a member's erasure included, reaches the pool
within the backup rotation window [security-model](../crosscutting/security-model.md) promises, and an object an
earlier, still-retained capture needs is never deleted. The retained captures are the pool's own: every capture that
named the pool writes its index's keys into the pool's ledger once every one of them is in the pool, and the ledger
keeps a bounded number of the newest and none older than B (§8) — not the capture directories, which an operator moves,
encrypts or keeps off the host, and which §8 prunes only where veyq's own tooling wrote them. The prune is the pool's
one delete, of a computed list, runs only after the new capture's entry is written, and refuses when the ledger it reads
lacks that entry, since a ledger read that came back empty would otherwise name nothing and delete everything. A capture
outside the newest `keep` still restores without a source, its objects being in the store; from the pool it refuses by
name whatever the pool no longer holds. A pool therefore serves one instance.

**The bundled store keeps no history of its own.** Garage, the pinned v2.0.0, has no object versioning (measured
against a throwaway node through the pinned rclone: `GetBucketVersioning` answers `Unversioned`,
`PutBucketVersioning` and `ListObjectVersions` answer `501 NotImplemented`), and it can snapshot its metadata but
not its data blocks (the fast path below). So on the bundled store, under Compose, nothing but a pool or a volume
copy of the store keeps an object the store loses, and `veyqctl status` says so while no pool is configured
([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)). On Kubernetes the gate takes no pool
([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) decision 4).

**Every capture is an index capture.** A capture that holds its objects itself (the format before this decision) is
written by nothing and stays readable: it verifies against its own `objects/` and restores by copying them, which, like
any restore that writes objects, goes only into an empty store (§7).

**The tool is invoked, not vendored:** the procedure shells out to `rclone`. The capture tool ships alone, on the
standard library only ([ADR 0106](0106-deployment-under-infra-tests-under-tests.md) decision 8), and this decision adds
no dependency to it, in the same shape as the e2e orchestrator shelling out to `docker compose`. Rejected: a boto3-based
tool, which would make one procedure the reason for its first dependency; and hand-rolled SigV4 signing, which would
have us own signing code no one else reviews.

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

**7. A restore accepts a backup of the target's schema or an older one, and refuses a newer one; it checks the
objects, and writes them only into an empty store.** The restore
compares the backup's schema version with the Flyway version the freshly installed target is at
(Flyway's own version order, never a string comparison), before anything is written:

- **Newer than the target:** refused. The message names the release the backup came from. The older image would refuse
  to start on a history holding migrations it does not know (measured: ADR 0098 M9 — Quarkus hands Flyway no ignore
  pattern), so loading first and failing at boot would leave the operator with neither instance.
- **The same or older:** the restore first drops **every** table of the target and then loads the dump. Dropping
  is safe only because the target is proven to hold no data (A5 of the use-case). It is needed because the dump
  drops and recreates only the tables *it* names: a table a later migration added would otherwise survive under
  the backup's older history, and the next start would fail re-creating it. After the load the instance is at
  the version it was captured at, and the target's application **migrates it up at its next start**. That is the
  same forward migration every upgrade runs, not a second path.

This turns "install the backup's release, restore, upgrade" into "restore, start". It also means **the target's release
decides the outcome**. A rollback after a failed upgrade (§6) therefore still starts by installing the release the
backup came from. Restoring the pre-upgrade backup onto the new release would only run the failed upgrade again. The
restore says this when the backup is older than its target. On Compose the rollback is **in place**: the capture holds
no object and an upgrade rewrites none, so the objects never leave the store and the restore runs the check only —
[ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) decision 1 says how, and
[ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) decision 9 is the cluster's rollback. The
uploads of the failed release stay in the store with no row; the bucket reconcile counts them, and every restore — a
rollback's included — empties its state, so their grace starts at the rollback and nothing is deleted before the Server
Owner confirms the bucket again ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) § Consequences).

**Rejected — refusing every backup that is not exactly the target's version.** It is safe, but it makes the
operator carry the downgrade-restore-upgrade sequence by hand. Each step in it is one more place to install the
wrong image, and it buys nothing that the forward migration does not already have to guarantee.

**A restore also checks the objects before it writes, and it writes objects only into an empty store**. Both forms run before the dump is loaded, and a refusal writes nothing:

- **Without a source — the check only.** Every object the dump's rows require must be in the **live store** with
  the size and MD5 the index recorded (A4). A missing or different one refuses the restore, naming each; an object the
  store holds and the index does not is tolerated (an upload after the capture). Only an explicit flag restores
  knowingly without the named ones, each a Memory whose picture yields a valid URL and a `404`. Nothing is written to
  the store. This is the way back from an upgrade, whose objects never left the store.
- **With a source — a pool — data, onto a fresh instance only.** The target's database holds no data (A5) **and its
  store holds no object**; anything else is refused, because copying objects into a store that is in use is reconciling
  two populated instances, which nothing describes. Every object the rows require must be in the source with the indexed
  size and MD5, refused by name otherwise as above. Then exactly the index's keys are copied (`copy`, never `sync`), and
  the store is checked once more against the index before the dump is loaded. A capture that holds its own objects is
  this form with those objects as the source.
- **Never onto a running instance.** The application is stopped for every restore (§2's writer), and `veyqctl`
  stops it itself.

*Required* means what the dump's blob rows name, not every key the index lists: the index also holds orphans
pending reclaim and the chunks of open upload sessions (Context 2), which the instance that resumes would reclaim
anyway, so their absence is reported and never refused.

**8. A capture is kept at most B days, and an erasure made after a capture is re-applied when it is restored.**
Backups are not exempt from erasure (GDPR Art. 17; Art. 5(1)(e) bounds how long anything is kept), and the EDPB's
erasure report asks a controller to track erasures and apply them again on a restored system
([legal register](../crosscutting/legal-register.md) L4, L10). Two halves, on both targets in step:

- **B is an instance setting** (days, 30 by default — the most the moderation Run page recommends for a backup holding
  guest addresses), set in admin-web and `veyq-admin` like the guest-write record's N
  ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)), and read by the capture
  tooling from the database it captures, so it has one home. Every capture veyq's tooling wrote is deleted once older
  than B, **the newest included**: a capture kept past B would bring back what an erasure removed. A copy the operator
  moved off the host is theirs to delete by B.
- **An erasure ledger outside the three legs**, beside leg (c)'s key, so neither a restore nor a rollback touches it,
  lists every hard erasure no restored row would trigger again by itself, and every moderation block, lift, case
  change and hold end. **At every start, before the instance serves,** the application re-applies it through each
  kind's own eraser, so no restore needs to be detected, and fails the start closed where a line cannot be applied. A
  line older than B goes, since no capture is older — at every start and hourly while the instance runs, so an instance
  that is never restarted keeps no id longer than a capture could.
  **Moderation survives a restore**, by the rule that a legal duty wins: a TCO Art. 3 block and a DSA Art. 17 measure
  must not be undone by one, and a hold that had ended must not come back past its bound.

What a capture holds, the prune on each target, the ledger's lines and their re-application, and the stated limits
are [cc.privacy.retention](../crosscutting/cc.privacy.retention.md) § Backups and the erasure ledger.

## Consequences

- **The operator page gains a leg.** Its two-halves framing — the server's ciphertext against the users' keys —
  stays exactly as it is; what changes is that the server's half is stated as three things, with the key file
  named as the one that fails quietly.
- **Every deployment artifact must persist leg (c), and the obligation outlives the one that first met it.** A container
  that is recreated without a persisted key path regenerates the key and walks into the `MISMATCH` state on its own — an
  instance that boots, serves every page, and can admit nobody. The Compose recipe persists it on a named volume and the
  chart on a claim of its own, each held by a guard; for a bare `docker run` or a systemd unit the obligation is the
  operator's, and the sealed values' fingerprints are the only detection. Note what the fix is NOT: a `VOLUME` in the
  image, which would create an *anonymous* volume when nothing is mounted — persistence-shaped, discarded by `--rm` and
  `down -v`, under a name no `docker volume ls` identifies. The defect must stay visible where nothing mounts the path.
- **The durability bar becomes executable.** Its sentence turns into an acceptance criterion with an owner:
  [uc.admin.back-up-and-restore-instance](../features/admin/use-cases/uc.admin.back-up-and-restore-instance.md);
  the bar follows the proof, never the intention.
- **The backup inherits the instance's garbage.** Abandoned upload sessions and their partial chunks, and
  orphaned objects whose rows are gone, are captured as they are. They are what a running instance already
  accumulates (Context 2), not something the procedure introduces, and a restore is not the place to start
  collecting them.
- **Rejected — a hot capture with a documented ordering.** It is correct only under an argument nobody executes
  reliably, and it buys a household instance minutes it does not need (Decision 2).
- **Rejected — volume copy as the primary form.** Faster and credential-free, but unverifiable and bound to one
  store's format and version (Decision 3).
- **A lost store is lost unless something outside veyq's backup kept its objects.** The index proves what is
  missing; it brings nothing back. That is the store's versioning, replication or snapshots, or the object pool
  (Decision 3) — and on the bundled Garage, which keeps no versions, the pool or a volume copy of the store. The
  Run pages say it where the commands are, and `veyqctl status` says it on the host.
- **Rejected — a full copy of the bucket in every capture** (what Compose once did). Self-contained,
  but every capture and every upgrade's downtime grows with the store, the copies duplicate what the store's own
  durability already keeps, and an upgrade, which never rewrites an object, gains nothing from them. The pool keeps
  the self-contained option for whoever wants it, at the cost of the new objects only.
- **Rejected — a pool that mirrors the bucket (`sync`).** It would delete from the pool what the store deleted, so
  an earlier capture's objects would vanish from under its index, and a store that lost objects would take the
  pool's copies with it on the next run.
- **Rejected — a domain-rewrite step in the restore.** It is a product surface wearing a runbook's clothes
  (Decision 4).

## Related

- [architecture §10](../architecture/README.md) — the durability bar this makes executable
- [ADR 0013](0013-blob-storage-presigned-direct-to-store.md) — presigned direct-to-store, why the client is a writer
- [ADR 0050](0050-operator-config-secret-encryption.md) — the managed config-secret key and its fingerprint
- [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md) — what a lost key file actually costs
- Run area of the docs site: [Backup and restore](../../site/src/content/docs/run/backup-and-restore.md) — where the
  procedure lives, and [Upgrade](../../site/src/content/docs/run/upgrade.mdx) — the upgrade that begins with it
- [infra/README](../../infra/README.md) — the dev stack
