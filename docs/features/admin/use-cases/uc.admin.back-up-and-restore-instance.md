# UC: Back up an instance and restore it onto an empty one

- **ID:** uc.admin.back-up-and-restore-instance
- **Primary actor:** The **operator** of a self-hosted instance — in the common single-node deployment the same
  person as the Server Owner ([ADR 0006](../../../decisions/0006-cold-tenant-scope-birth.md)), working at a
  shell on the host, not in the app
- **Goal:** Capture everything an instance durably holds, and bring it back on empty hardware such that every
  Memory opens afterwards **with the keys their owners already hold** — no re-encryption, no key handed over,
  nothing the operator could have read on the way ([ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md)).
- **Preconditions:** The operator can stop and start the application, and can reach the database and the object
  store from the host. The restore target serves the **same domain** as the source (ADR 0056 §4). The people
  whose Memories these are still hold their passwords and Emergency Kits — the operator holds no key and cannot
  supply that half ([uc.account.recover-access](../../account/use-cases/uc.account.recover-access.md)).
- **What is built** (2026-10-17): every step below, on Compose and `veyqctl` and on Kubernetes (A11) — the store leg as
  an index, the optional pruned object pool, the restore's check against the index and its copy only into an empty
  store (ADR 0056 §3, §7), the in-place rollback (A7), and every capture kept at most B days with the erasure ledger
  re-applied at every start (A12, A13; ADR 0056 decision 8).
- **Postconditions (success):** A restored instance holds the same three legs as the source: the same database,
  the same objects, and the same config-secret key. Its users sign in with the credentials they already had,
  every Memory decrypts, and the instance can admit members and send recovery links again. Nothing about the
  restore made the operator able to read anything.

## Main flow

**Capture — the app is stopped, the stores are not.**

1. The operator **stops the application**. The database and the object store keep running: the capture reads
   through their own interfaces, not through their files.
2. The operator **dumps the database**. From this moment the dump cannot go stale in the direction that
   matters — with the app down the database gains no rows, so no row can appear whose object the later store
   capture missed (ADR 0056 §2).
3. The operator **lists the bucket into the capture as its index** — every object's key, size and MD5, as the store
   serves them over S3, never the store's on-disk directories and never the objects (ADR 0056 §3). The objects stay
   in the store, whose versioning, replication or snapshots keep them; when the operator names an **object pool**
   (`--objects-to`), the objects the pool lacks are copied into it — never replacing a copy it holds — and the
   capture records the pool beside the index; the pool then deletes every object no retained capture's index names,
   its captures rotating by `keep` (ADR 0056 §3), so an erased object leaves it within that window. The capture is cold, so no app is running to sign for the tool: the
   operator **supplies the store's credentials for this run** ([ADR 0089](../../../decisions/0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)
   decision 7). For the bundled store the recipe's environment already holds them (the tool derives the S3 key pair
   from the bundled admin token); for a store of their own they are the ones entered in the admin console, which
   never shows them again.
4. The operator **copies the config-secret key file** to a destination of its own, **never into the capture
   beside the dump**: together the two open every tenant's SMTP password (ADR 0056 §1). This is the leg that is
   forgotten, and the only one whose absence lets a restore succeed and still be broken. Everything the capture
   writes is readable by the operator alone.
5. The operator **starts the application**. The downtime was the capture, and nothing else.

**Restore — onto an instance that holds no data.**

6. The operator brings up a **freshly installed** instance on the target and stops its application again: that
   one start migrated the schema and, for the bundled store, **recreated its configuration** — bucket, access
   key, layout — which is not restored because it was never state (ADR 0056 §4 of Context; the app provisions it
   from the bundled hint, [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md)). It holds
   no data (A5). The **store setting** is data, not configuration: it is a database row, so step 7 brings it back.
7. The operator **loads the database dump**. It replaces the fresh schema wholesale, migration history included,
   so the instance is at the version it was captured at. The target may run the backup's release or a newer one
   (A9); a backup newer than the target is refused before anything is written (A10).
8. The operator **lets the restore check the objects**: before anything is written, every object the dump's rows
   require must be in the store with the size and MD5 the index recorded (A4). On a fresh store — another host, a
   lost disk — the operator names the pool (`--objects-from`) instead: then the store must hold **no object**, every
   required object must be in the pool as indexed, and exactly the index's keys are copied in (ADR 0056 §7). The
   credentials are supplied as for the capture.
9. The operator **puts the config-secret key file**, from wherever it was kept, where the application expects
   it, on storage that survives a container being recreated, **readable by the application's runtime user** — a
   key the application cannot read reports `ABSENT` and refuses invites and resets exactly as a missing one does.
10. The operator **starts the application**.

**Verify — the step that separates a restore from a hope.**

11. The operator reads the instance's **config-secret state** as the Server Owner, on the admin channel (the
    admin console's Server section or `veyq-admin`) — not through a tenant's mail settings, which only that
    tenant's owner may read. A match is what says leg three arrived; a
    mismatch is the fingerprint doing its job (A2). The operator also **checks the store** (Server → Object store, or
    `veyq-admin store check`): the restored setting must reach its store, its credentials sealed under the restored
    key ([uc.admin.configure-object-store](uc.admin.configure-object-store.md)). Then a person signs in and **opens a Memory that contains
    media** — the only assertion that exercises all three legs at once, because the row, the object and the key
    that seals the SMTP settings are three different failures with the same symptom of "it looked fine".

## Alternate / exception flows

- **A1 — The application was still running during the capture.** The one precondition of the whole procedure is
  gone: a client can complete an upload and the app can commit its row between the database dump and the store's
  index, producing a row whose object was never indexed (nor pooled). That restores as a Memory with a picture that yields
  a valid URL and a `404` — no server-side error, nothing in a log, a broken thumbnail as the only sign
  (ADR 0056 § Context 2). Redo the capture; a partially captured backup cannot be repaired after the fact.
- **A2 — The config-secret key was not captured.** The instance starts, serves content, and reports
  `configSecretState: MISMATCH`. The store's own credentials are sealed under the same key, so media are
  unreachable until the Server Owner enters them again in the console (configure-object-store A4); a bundled store
  is unaffected, because its setting refers to the hint. Member invites and password resets refuse with `503 mail_unavailable`
  ([ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md)). Content is unaffected — the
  key seals operator config, never content. Two ways out: restore the key file, or have each tenant owner
  re-enter their SMTP password, which re-seals it under the new key. A restore reaches this state only
  knowingly: when the capture records its key as kept separately and none is supplied, the restore refuses
  unless the operator explicitly accepts going on without it.
- **A3 — The target serves a different domain.** Not supported in V1 (ADR 0056 §4). The instance comes up
  unreachable under the new name, and any outstanding invitation or recovery link still points at the old one.
  Restore onto the same domain, or treat it as a new instance.
- **A4 — Objects are missing relative to the rows that reference them.** Detectable before the restore, because
  the capture is logical: the object keys can be compared against the blob rows that name them. This is the one
  check a volume copy cannot offer, and it is the reason the store leg is captured this way. It is asked twice: at
  capture, of the index; and before a restore writes anything, of **where the objects come from** — the live store,
  or the pool named with `--objects-from` — every object the dump's rows require must be there with its indexed size
  and MD5 (size alone where one side has no MD5, said so). A missing or changed object refuses the restore, naming
  it; an object the index does not hold is tolerated (an upload after the capture), and so is an indexed key no row
  requires (an orphan pending reclaim, an open upload's chunk). The operator brings missing objects back from the
  store's own versioning or from a pool, or restores knowingly without them (`--accept-missing-objects`), each a
  Memory whose picture yields a `404`. **On the bundled Garage there is no versioning to bring them back from**
  (ADR 0056 §3, measured): only a pool or a volume copy of the store can.
- **A5 — The target already holds data.** The restore is defined onto an empty instance and nothing reconciles
  two populated ones. A load over live rows, or objects copied into a bucket that already holds objects, produces a
  state no test covers and no decision describes. So a restore that copies objects (`--objects-from`, or a capture
  that holds its own) also refuses a store holding **any** object, before it writes; a restore that only checks
  the store needs the store as it is, and refuses only a populated database. Start from a fresh instance instead.

  **Empty means *holding no data*, not *holding nothing*.** A **freshly installed** instance — started once, so
  its schema is migrated and its store provisioned — is exactly what a restore targets, and is accepted. That
  start is not optional and never was optional here: since
  [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md) the app is the only thing that
  brings the object store from *running* to *usable*, and the same start migrates the schema, so an operator
  has no way to produce a target that is initialised in one respect and untouched in the other. What is refused
  is an instance that has been **used**: any row of real data, including the rows a half-finished restore just
  wrote.
- **A6 — The target store is not the one the source used.** The objects then come from a pool (`--objects-from`);
  the pool and the index are the S3 namespace, so any S3-compatible store accepts the objects, and the backend signs
  vendor-neutrally. Without a pool a different store cannot be restored onto: the index names objects it cannot
  supply. What differs is where the
  *setting* comes from. A **bundled** setting is a reference to the hint of the install it runs on, so a new bundled
  store, even under another bucket name the new recipe's hint carries, works as it stands. A store of the
  operator's own comes back as the setting the source held — its endpoint and bucket are in the restored database,
  and they are fixed once blobs exist ([configure-object-store](uc.admin.configure-object-store.md) A3) — so the
  objects go back into that same endpoint and bucket; only its credentials can be re-entered. Moving a restored
  instance onto a *different* own store is not supported here: it is the blob-moving case that ADR 0089 decision 6
  leaves out of scope.
- **A7 — The restore is a rollback after a failed upgrade.** This is the *reason* the backup exists as a
  precondition of upgrading (ADR 0056 §6): the schema migrates at boot with no gate and the migration set has no
  downgrade path, so the previous image cannot simply be started again. Restore the backup taken before the
  upgrade; there is no shorter way back. The target must run the **release the backup came from** (the
  manifest's `app.version`). Restored onto the new release, the backup would be migrated up again at the next
  start (A9), which repeats the failed upgrade instead of undoing it. The upgrade rewrote no object, so the
  objects the backup indexes are still in the instance's own store: the rollback restores onto **that** store, its
  database emptied, with the check only — or onto a fresh instance's empty store from a pool (`--objects-from`). Both
  targets take the first: on Kubernetes the restore Job (A11), on Compose `veyqctl rollback`, which refuses unless the
  capture verifies against the live store, then empties the database, starts the capture's release once and
  restores with the check only.
- **A9 — The backup is older than the target.** Accepted (ADR 0056 §7). The restore drops every table of the
  data-less target before loading, so the instance holds exactly the captured schema. The application then
  migrates it up at its next start, the same forward migration an ordinary upgrade runs. The restore says so as
  it finishes, because this is an upgrade, and that matters whenever the operator meant a rollback (A7).
  **Proven over real data by the upgrade proof** ([ADR 0057](../../../decisions/0057-release-identity-and-deployment-recipe.md)
  Decision 7, inbox #419): a predecessor's backup, written by its own client, restored
  onto the candidate, migrated up, and read back by the candidate's suites.
- **A10 — The backup is newer than the target.** Refused before anything is written, naming the release the
  backup came from. The older image would refuse to start on a migration history it does not know, so loading
  and failing at boot would leave the operator with neither instance.
- **A11 — On Kubernetes, the capture is the chart's backup gate**
  ([ADR 0098](../../../decisions/0098-on-kubernetes-the-start-that-would-migrate-backs-up-first.md)). The gate runs in
  the app pod before the container that migrates, with the old pod already terminated, so steps 1 and 5 are
  Kubernetes' (`Recreate`). Step 3 is the index, as everywhere. Step 4
  is not the gate's: it never mounts the key, and records leg (c) as kept separately; the operator exports the key
  once, by hand. The restore is a one-off Job, run with the tools image of the release that wrote the capture, onto
  an emptied database the backup's release has started once: step 8 is the check only (the objects are where they
  were, A4), the key from the operator's medium, and as its last step the gate's record set to the
  backup's release, so that release's next start passes. The gate names no pool (ADR 0098 decision 4); the Job may
  pass `--objects-from` like any restore, then only into an empty store.
- **A12 — A capture is kept at most B days** (ADR 0056 decision 8). B is an instance setting the Server Owner sets in
  admin-web or with `veyq-admin backup-retention set` (7 to 365, 30 until set). Every capture veyq's tooling wrote is
  deleted once it is older than B, the newest included: `veyqctl` at every `backup`, `upgrade` and `start` and with
  `veyqctl backup prune` on a daily timer, the cluster's gate at every start and the chart's daily prune; `backup.keep`
  stays a second bound. An object pool's ledger drops its entries older than B, and the pool's prune follows. A copy
  the operator moved off the host is theirs to delete by B. `veyqctl status` warns while no capture younger than B
  exists.
- **A13 — An erasure made after the capture is made again** (ADR 0056 decision 8). The instance keeps an erasure
  ledger, `erasures.log`, beside leg (c)'s key, which no restore touches: one line per hard erasure no restored row
  would trigger again (an account, a tenant, a scope, a node, a medium, a link, a reclaimed storage key) and per
  moderation block, lift and case. At every start, before it answers a request, the application reads it and erases
  every listed object whose row exists, through its own eraser; it blocks again an item whose last line is a block and
  that the restored database does not block, never one lifted since; and it recreates a case the database lacks as a
  stub. A restore given the ledger (`--erasures`) counts a key it lists as erased, never as missing. Without the ledger
  the object check runs as before, and says so; a restore onto a new host re-applies only the ledger the operator
  carried there with leg (c).
- **A8 — Only the users' half is lost.** Nothing here helps. A perfectly restored server whose people lost their
  passwords and Emergency Kits is unreadable ciphertext, and that is the design, not a defect
  ([uc.account.recover-access](../../account/use-cases/uc.account.recover-access.md) A7).

## Quality notes

- **The whole consistency argument is one act.** Stopping the app makes the dangerous skew impossible rather
  than unlikely, which is why there is no ordering rule to remember and no window to wait out. A procedure whose
  correctness depends on the operator recalling which artifact to capture first is a procedure that will
  eventually be run in the wrong order.
- **Content is ciphertext, end to end.** Rows and objects are material the operator already could not read on
  the running instance, so backing up grants no new power to read content, and neither does restoring. This is
  the operator-blindness invariant surviving contact with the most privileged routine an operator has. The one
  secret a backup *can* open is operator config: the dump plus the key yields the tenants' SMTP passwords.
  That is why the two are never captured into one place, and why a capture is encrypted before it leaves the
  host.
- **A restore is proven by opening something, not by starting cleanly.** Two of the three legs produce an
  instance that boots, serves pages and answers requests while being incomplete. Only decrypting a Memory that
  has media touches all three.
- **The backup carries the instance's existing garbage.** Abandoned upload sessions and orphaned objects come
  along. They are the running system's steady state, not an artifact of the capture, and the restore is not
  where they get collected. Since [ADR 0062](../../../decisions/0062-store-object-reclaim-is-a-durable-work-list.md)
  an orphaned object is specifically a reclaim *pending* — a capture taken between a row delete and the next
  sweep tick — and it comes back with the reclaim row that explains it, so the restored instance finishes the
  job the captured one had started.

## User impact

A server that loses its disk can be brought back whole, and your memories open again with your own password and
Emergency Kit — never with a master key the operator holds.

## Related

- [ADR 0056](../../../decisions/0056-instance-durable-state-and-cold-logical-backup.md) — the three legs, the cold capture, and why the store leg is logical
- [ADR 0013](../../../decisions/0013-blob-storage-presigned-direct-to-store.md) — presigned direct-to-store, which is why the client is a writer the capture must account for
- [ADR 0050](../../../decisions/0050-operator-config-secret-encryption.md) — the config-secret key and the fingerprint that detects its loss
- [ADR 0055](../../../decisions/0055-mail-is-a-precondition-not-a-capability.md) — what a missing key file actually costs an instance
- [uc.account.recover-access](../../account/use-cases/uc.account.recover-access.md) — the half of durability the operator cannot hold
- [uc.admin.setup-server](uc.admin.setup-server.md) — how the empty instance a restore targets comes into being
- [architecture §10](../../../architecture/README.md) — the durability bar this exists to make executable
- Run area of the docs site: [Backup and restore](../../../../site/src/content/docs/run/backup-and-restore.md) — the
  procedure's commands
- Operator view (docs site): [Running your own instance](../../../../site/src/content/docs/run/index.md)
- End-user view (docs site): [Your Emergency Kit](../../../../site/src/content/docs/getting-started/emergency-kit.mdx)
