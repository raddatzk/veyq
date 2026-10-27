# ADR 0056 — An instance's durable state has three legs, and it is captured with the writer stopped

- Status: Accepted
- Built: yes
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
makes the server generate and manage it as a `0600` file, defaulting to the process working
directory, and it is the KEK under which every per-tenant SMTP password is sealed. Its loss is detected — the
instance row carries an HKDF fingerprint — and detection is the whole of the remedy: a server that boots
without its key reports `configSecretState: MISMATCH` and **refuses member invites and password resets**. A
restore that captures the database and the store therefore starts cleanly and is still broken, in exactly the
two flows [ADR 0054](0054-password-recovery-two-factor-release.md) and [ADR 0055](0055-mail-is-a-precondition-not-a-capability.md)
exist to protect.

**2. The read path tolerates one skew and not its mirror.** An object with no row is an ordinary steady state,
but the reason changed with [ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md): the keys go onto
`veyq_store_reclaim` in the same transaction as the row delete and one sweep drains it, so what a reader meets
between those two moments is a reclaim **pending**, not a delete that failed unrecorded. Orphans therefore no
longer accumulate silently — a refused delete keeps its row and its `attempts`, and a permanently refused one
is `abandoned_at`, which is countable in SQL — and an object no row names at all, reclaim row included, is counted
by the bucket reconcile, and reclaimed after its grace where the Server Owner allows it
([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md) § Consequences). (Abandoned upload *sessions*
themselves are reclaimed, by `UploadSessionPurge`; `UploadSessionEntity`'s KDoc is the live statement of the rule.) The mirror case is
handled nowhere: a media read presigns a GET without asking the store whether the object exists, so a row whose
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
precisely a leaked backup that opens every tenant's SMTP password. So the capture writes the key to a
destination of its own and refuses one inside the capture directory. Putting it beside the dump takes an
explicit flag, and the manifest records which of the two was done, or that leg (c) is absent. A restore reads
the key from wherever the manifest says it went. If it was captured separately and is not supplied, the
restore **refuses**, unless the operator explicitly accepts a restore without it. Everything a capture writes
is owner-only (`0700` directories, `0600` files), and the runbook says to encrypt a capture before it leaves
the host and to keep the key on a different medium.

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

**3. The store leg is captured logically, as its index; the objects go into an object pool only when one is
named.** The backup reads the object namespace through the S3 API, never the store's data directories, and on
every target it records that namespace as an **index**: every object's key, size and MD5, in one listing, in the
manifest (`backupFormat` 3). The objects stay in the store. Owner, 2026-10-17: *"Richtiges Vollbackup liegt doch
im Betrieb des S3"* — keeping the objects is the store operator's job (its versioning, replication or snapshots),
and a backup that copied the whole bucket every time would make each capture, and each upgrade's downtime, as
large as the store. Two properties of the built system make the index enough for what veyq's own backup is for:
an upgrade migrates the schema and **never rewrites an object** — every object is an immutable ciphertext blob,
written once by one presigned PUT — so the database plus the index is a complete way back from an upgrade; and
the index is exactly what lets a restore *check* the objects without holding them.

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

**The object pool is optional and named per run** (`--objects-to <target>`: a directory, or a bucket of another
S3-compatible store whose credentials come from the environment, never an argument). It is **copied into, never
synced**: rclone `copy --immutable`, never `sync` or `move` — a key whose bytes differ from the pool's copy fails the
capture instead of replacing it (objects are immutable, so a differing copy is a fault, never an update; measured
with the pinned rclone 1.75.0: `copy --immutable` exits 6, *"immutable file modified"*, and leaves the pool's copy).
It is **incremental by construction**: a key the pool holds is not copied again. The capture records the pool's
target (never a credential) beside the index, and **the index is what ties the two**: a restore copies exactly the
index's keys out of the pool, whatever else the pool holds. The pool holds ciphertext only, as the store does, so it
changes nothing about what the operator can read.

**The pool keeps exactly what its retained captures need** (owner 2026-10-17). An object is deleted from the pool as
soon as no retained capture's index names it, and captures rotate by `keep` — so a deletion, a member's erasure
included, reaches the pool within the backup rotation window [security-model](../crosscutting/security-model.md)
promises, and an object an earlier, still-retained capture needs is never deleted. The retained captures are the
pool's own: every capture that named the pool writes its index's keys into the pool's ledger (`veyq-pool/captures/`)
once every one of them is in the pool, and the ledger keeps the newest `keep` (`--pool-keep`, default 3, as the
gate's `backup.keep`) and none older than B (§8) — not the capture directories, which an operator moves, encrypts or
keeps off the host, and which §8 prunes only where veyq's own tooling wrote them. The prune is the pool's one delete (`delete --files-from` the computed list), runs only after
the new capture's entry is written, and refuses when the ledger it reads lacks that entry, since a ledger read that
came back empty would otherwise name nothing and delete everything. A capture outside the newest `keep` still
restores without a source, its objects being in the store; from the pool it refuses by name whatever the pool no
longer holds. A pool therefore serves one instance.

**The bundled store keeps no history of its own.** Garage, the pinned v2.0.0, has no object versioning (measured
2026-10-17 against a throwaway node through the pinned rclone: `GetBucketVersioning` answers `Unversioned`,
`PutBucketVersioning` and `ListObjectVersions` answer `501 NotImplemented`), and it can snapshot its metadata but
not its data blocks (the fast path below). So on the bundled store, under Compose, nothing but a pool or a volume
copy of the store keeps an object the store loses, and `veyqctl status` says so while no pool is configured
([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)). On Kubernetes the gate takes no pool
([ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) decision 4).

**Every capture is `backupFormat` 3.** A capture that holds its objects itself (`objects/`, `backupFormat` 2, what
Compose wrote before this decision) is written by nothing and stays readable: it verifies against its own
`objects/` and restores by copying them, which, like any restore that writes objects, goes only into an empty store
(§7).

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

**7. A restore accepts a backup of the target's schema or an older one, and refuses a newer one; it checks the
objects, and writes them only into an empty store.** The restore
compares the manifest's `database.schemaVersion` with the Flyway version the freshly installed target is at
(Flyway's own version order, never a string comparison), before anything is written:

- **Newer than the target:** refused. The message names the release the backup came from
  (`app.version`). The older image would refuse to start on a history holding migrations it does not know
  (measured: ADR 0098 M9 — Quarkus hands Flyway no ignore pattern), so loading first and failing at boot would leave
  the operator with neither instance.
- **The same or older:** the restore first drops **every** table of the target and then loads the dump. Dropping
  is safe only because the target is proven to hold no data (A5 of the use-case). It is needed because the dump
  drops and recreates only the tables *it* names: a table a later migration added would otherwise survive under
  the backup's older history, and the next start would fail re-creating it. After the load the instance is at
  the version it was captured at, and the target's application **migrates it up at its next start**. That is the
  same forward migration every upgrade runs, not a second path.

This turns "install the backup's release, restore, upgrade" into "restore, start". It also means **the
target's release decides the outcome**. A rollback after a failed upgrade (§6) therefore still starts by
installing the release the backup came from. Restoring the pre-upgrade backup onto the new release would only
run the failed upgrade again. The restore says this when the backup is older than its target. On Compose the
rollback is **in place** (owner 2026-10-17): the capture holds no object and an upgrade rewrites none, so `veyqctl
rollback` empties the database, starts the capture's release once, and restores with the check only — the objects
never leave the store ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md), the cluster's
rollback in [ADR 0098](0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md) decision 9 in Compose's
form). The uploads of the failed release stay in the store with no row; the bucket reconcile counts them, and every
restore — a rollback's included — empties its state, so their grace starts at the rollback and nothing is deleted
before the Server Owner confirms the bucket again ([ADR 0062](0062-store-object-reclaim-is-a-durable-work-list.md)
§ Consequences).

**Rejected — refusing every backup that is not exactly the target's version.** It is safe, but it makes the
operator carry the downgrade-restore-upgrade sequence by hand. Each step in it is one more place to install the
wrong image, and it buys nothing that the forward migration does not already have to guarantee.

**A restore also checks the objects before it writes, and it writes objects only into an empty store** (owner
2026-10-17). Both forms run before the dump is loaded, and a refusal writes nothing:

- **Without a source — the check only.** Every object the dump's rows require must be in the **live store** with
  the size and MD5 the index recorded (A4). A missing or different one refuses the restore, naming each; an object
  the store holds and the index does not is tolerated (an upload after the capture). `--accept-missing-objects`
  restores knowingly without the named ones, each a Memory whose picture yields a valid URL and a `404`. Nothing is
  written to the store. This is the way back from an upgrade, whose objects never left the store.
- **With a source — `--objects-from <pool>` — data, onto a fresh instance only.** The target's database holds no
  data (A5) **and its store holds no object**; anything else is refused, because copying objects into a store that
  is in use is reconciling two populated instances, which nothing describes. Every object the rows require must be
  in the source with the indexed size and MD5, refused by name otherwise as above. Then exactly the index's keys are
  copied (`copy`, never `sync`), and the store is checked once more against the index before the dump is loaded.
  A capture that holds its own objects (`backupFormat` 2) is this form with its `objects/` as the source.
- **Never onto a running instance.** The application is stopped for every restore (§2's writer), and `veyqctl`
  stops it itself.

*Required* means what the dump's blob rows name, not every key the index lists: the index also holds orphans
pending reclaim and the chunks of open upload sessions (Context 2), which the instance that resumes would reclaim
anyway, so their absence is reported and never refused.

**8. A capture is kept at most B days, and an erasure made after a capture is re-applied when it is restored.**
Backups are not exempt from erasure (GDPR Art. 17; Art. 5(1)(e) bounds how long anything is kept), and the EDPB's
erasure report asks a controller to track erasures and apply them again on a restored system (CEF 2025 § 4.2.6). Two
halves, on both targets in step:

- **B is an instance setting**, set in admin-web and `veyq-admin` like the guest-write record's N
  ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)): days, 30 by default (the
  most the moderation Run page already recommends for a backup holding guest addresses). The capture tooling reads it
  from the database it captures, so it has one home; an unreadable value means 30. Every capture veyq's tooling
  wrote — Compose's `veyqctl` captures under its home, the cluster gate's under its claim, an object pool's ledger
  entries — is deleted once it is older than B, **the newest included** (owner question in
  `cr-backup-erasure-and-retention`): `veyqctl` prunes at every `backup`, `upgrade` and `start` and offers
  `veyqctl backup prune` for a daily timer, the gate prunes at every start and the chart runs the same prune daily;
  `veyqctl status` warns while no capture younger than B exists (admin-web cannot see the captures). `backup.keep`
  stays as the bound on space. The chart's daily prune is a CronJob of its own on the gate's image and claim: it reads
  B from the database and writes only that claim, never the key's (`scripts/chart_meta_check.py` (xxviii)). A copy the operator moved off the host is theirs to delete by B; the Run docs say so, with access only to
  restore it.
- **The erasure ledger** lives outside the three legs, in the directory leg (c)'s key file lives in — persisted by
  Compose's named volume and by the cluster's kept claim (Consequences, below) — so neither a restore nor a rollback
  touches it. It holds one line per hard erasure that no restored row would trigger again by itself — an erased
  account or tenant (a revoked pending one too), a purged scope, node or medium, a deleted link, a reclaimed storage key,
  an ended moderation hold: kind, opaque id, time. The kinds are a sealed set the re-applier's `when` covers, so a kind without its re-applier does
  not compile; an application contributes its own (Memories: a medium) through a core interface, as it does an eraser.
  A purge that re-fires on its own restored row (a trash past its retention, a guest-write record) needs none; a hold's
  end is a line however it ended, the cleanup's included, since one ended early comes back with its end still ahead. An erasure writes its line in its own transaction to a table, and a sweep appends it to the file after
  the commit — at least once, which is harmless because re-applying is idempotent. **At every start, before the
  instance serves,** the application reads the file and erases, through that kind's own eraser, every listed object
  whose row exists; no restore needs to be detected. A line older than B goes, since no retained capture predates it.
  The restore's object check (§7) counts a key the ledger lists as erased, never as missing — `veyqctl restore` and
  `rollback` read the file out of the app's volume and hand it over (`--erasures`), the cluster's restore Job reads it
  from the config-secret claim it already mounts; the gate itself still never mounts that claim (ADR 0098 decision
  4c). A restored row that still names an erased key with no line of its own (an old version a Depot replace dropped)
  is counted in the start's log line and served as a missing object is (Context 2).
- **Moderation survives a restore** (owner 2026-10-18, by the legal-duty rule: a TCO Art. 3 block and a DSA Art. 17
  measure must not be undone by one). The same outbox carries every block and lift of a measure (its item kind and
  target, its link, space and case, who took it and its basis — a measure or an authority's order), every change of
  a case (its item, space, category, state, when it opened, and its retention date: the latest `expires_at` of its
  events once every event has one) and every end of a hold — a dismissal, the cleanup's end, a child-sexual-abuse case's
  handover, the lift of a case's last measure (`HOLD_ENDED`, its hold and case; owner 2026-10-19). At start, per item
  the **last** line wins, in both directions (owner 2026-10-19): an item whose last line is a block and that the
  restored database does not block is blocked again, and one whose last line is a lift is lifted, never blocked again.
  Re-blocking re-applies what the block did besides its row: a member's sessions end, and a tenant's status is set again
  — offline for `TENANT_OFFLINE`, pending deletion for `TENANT_DELETED` — since the status, not the record, is what
  refuses it (owner 2026-10-19). A case the database lacks comes back as a stub, `origin = RESTORED`, with no report and
  nothing sealed: closed on its retention date if it had closed (decided too, its hold having been part of what the
  capture lacked), open and owed a decision otherwise. A case the database holds in an earlier state than its last line
  moves on to that state — forward only, open → decided → closed, so closed where it was closed (owner 2026-10-19); never
  back, since an objection that reopened it after the capture is lost with the capture. A hold whose line says it ended
  and that the restore brought back ends again — its snapshot and pins go — so a dismissed case's copy is not kept to
  the hold's own bound, which for child sexual abuse would pass what § 184b(5) StGB allows (ADR 0093 § 4). The case's
  sealed parts, and what decided it (a dismissal's note), are lost with a capture older than them. Whether CSAM ciphertext kept in a
  capture is possession under § 184b StGB stays ADR 0093's open question; captures expire at B either way.
- **Limit, stated:** a restore onto a new host re-applies only the ledger it is given; the Run docs put the file
  beside leg (c)'s key on the list of what to carry. Erasures made in the last sweep interval (a minute) before a crash
  that also lost the file are not re-applied. The re-application is part of the start: a line that cannot be applied
  fails it closed, so the instance does not serve with an erasure undone (owner 2026-10-19).

## Consequences

- **The operator page gains a leg.** Its two-halves framing — the server's ciphertext against the users' keys —
  stays exactly as it is; what changes is that the server's half is stated as three things, with the key file
  named as the one that fails quietly.
- **Every deployment artifact must persist leg (c), and the obligation outlives the one that first met it.**
  A container that is recreated without a persisted key path regenerates the key and walks into the `MISMATCH`
  state on its own — an instance that boots, serves every page, and can admit nobody. `infra/docker/docker-compose.prod.yml`
  mounts a named volume for it and `scripts/validate_config_secret_persistence.py` holds the three files that
  have to agree about that path — the `Dockerfile`'s `WORKDIR`, `VeyqConfig`'s default, and the compose mount —
  to each other. That guard sees compose files in this tree only: for Kubernetes, a bare `docker run` or a
  systemd unit the obligation is the operator's, and the `veyq_instance` fingerprint tripwire is the only
  detection. Note what the fix is NOT: a `VOLUME` in the image, which would create an *anonymous* volume when
  nothing is mounted — persistence-shaped, discarded by `--rm` and `down -v`, under a name no `docker volume ls`
  identifies. The defect must stay visible where nothing mounts the path.
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
- **A lost store is lost unless something outside veyq's backup kept its objects.** The index proves what is
  missing; it brings nothing back. That is the store's versioning, replication or snapshots, or the object pool
  (Decision 3) — and on the bundled Garage, which keeps no versions, the pool or a volume copy of the store. The
  Run pages say it where the commands are, and `veyqctl status` says it on the host.
- **Rejected — a full copy of the bucket in every capture** (what Compose did until 2026-10-17). Self-contained,
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
