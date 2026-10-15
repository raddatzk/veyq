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
3. The operator **mirrors the bucket** — the object namespace as the store serves it over S3, not the store's
   on-disk directories (ADR 0056 §3).
4. The operator **copies the config-secret key file** to a destination of its own, **never into the capture
   beside the dump**: together the two open every tenant's SMTP password (ADR 0056 §1). This is the leg that is
   forgotten, and the only one whose absence lets a restore succeed and still be broken. Everything the capture
   writes is readable by the operator alone.
5. The operator **starts the application**. The downtime was the capture, and nothing else.

**Restore — onto an instance that holds no data.**

6. The operator brings up a **freshly installed** instance on the target and stops its application again: that
   one start migrated the schema and **recreated the store's configuration** — bucket, access key, layout —
   which is not restored because it was never state (ADR 0056 §4 of Context; the app provisions it,
   [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md)). It holds no data (A5).
7. The operator **loads the database dump**. It replaces the fresh schema wholesale, migration history included,
   so the instance is at the version it was captured at. The target may run the backup's release or a newer one
   (A9); a backup newer than the target is refused before anything is written (A10).
8. The operator **pushes the mirrored objects back** into that bucket.
9. The operator **puts the config-secret key file**, from wherever it was kept, where the application expects
   it, on storage that survives a container being recreated, **readable by the application's runtime user** — a
   key the application cannot read reports `ABSENT` and refuses invites and resets exactly as a missing one does.
10. The operator **starts the application**.

**Verify — the step that separates a restore from a hope.**

11. The operator reads the instance's **config-secret state**. A match is what says leg three arrived; a
    mismatch is the fingerprint doing its job (A2). Then a person signs in and **opens a Memory that contains
    media** — the only assertion that exercises all three legs at once, because the row, the object and the key
    that seals the SMTP settings are three different failures with the same symptom of "it looked fine".

## Alternate / exception flows

- **A1 — The application was still running during the capture.** The one precondition of the whole procedure is
  gone: a client can complete an upload and the app can commit its row between the database dump and the store
  mirror, producing a row whose object was never captured. That restores as a Memory with a picture that yields
  a valid URL and a `404` — no server-side error, nothing in a log, a broken thumbnail as the only sign
  (ADR 0056 § Context 2). Redo the capture; a partially captured backup cannot be repaired after the fact.
- **A2 — The config-secret key was not captured.** The instance starts, serves content, and reports
  `configSecretState: MISMATCH`. Member invites and password resets refuse with `503 mail_unavailable`
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
  check a volume copy cannot offer, and it is the reason the store leg is captured this way.
- **A5 — The target already holds data.** The restore is defined onto an empty instance and nothing reconciles
  two populated ones. A load over live rows, or a mirror into a bucket that already holds objects, produces a
  state no test covers and no decision describes. Start from a fresh instance instead.

  **Empty means *holding no data*, not *holding nothing*.** A **freshly installed** instance — started once, so
  its schema is migrated and its store provisioned — is exactly what a restore targets, and is accepted. That
  start is not optional and never was optional here: since
  [ADR 0067](../../../decisions/0067-store-provisioning-from-a-declared-type.md) the app is the only thing that
  brings the object store from *running* to *usable*, and the same start migrates the schema, so an operator
  has no way to produce a target that is initialised in one respect and untouched in the other. What is refused
  is an instance that has been **used**: any row of real data, including the rows a half-finished restore just
  wrote.
- **A6 — The target store is not the one the source used.** Supported by construction: the capture is the S3
  namespace, so any S3-compatible store accepts it, and the backend signs vendor-neutrally. Only the endpoint,
  credentials, bucket and region change, and all four are configuration.
- **A7 — The restore is a rollback after a failed upgrade.** This is the *reason* the backup exists as a
  precondition of upgrading (ADR 0056 §6): the schema migrates at boot with no gate and the migration set has no
  downgrade path, so the previous image cannot simply be started again. Restore the backup taken before the
  upgrade; there is no shorter way back. The target must run the **release the backup came from** (the
  manifest's `app.version`). Restored onto the new release, the backup would be migrated up again at the next
  start (A9), which repeats the failed upgrade instead of undoing it.
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

## What this means for you / Was das für dich bedeutet

**EN —** Whoever runs your instance can back up everything the server holds and bring it back after a disk
dies — but everything they hold is locked, including to them. That is why a backup has a second half only you
have: your password and your Emergency Kit. If the server is restored and you still have those, your memories
open exactly as before, and nothing was re-encrypted or handed over on the way. If the server is restored and
you lost both, your memories stay closed forever — there is no master key on the operator's side to open them
with. It is the same promise that keeps your memories private, seen from its uncomfortable end: keep your
Emergency Kit somewhere you will still find it in a year.

**DE —** Wer deine Instanz betreibt, kann alles sichern, was der Server hält, und es nach einem Plattendefekt
zurückholen — aber alles, was er hält, ist verschlossen, auch für ihn. Deshalb hat eine Sicherung eine zweite
Hälfte, die nur du hast: dein Passwort und dein Notfall-Kit. Wird der Server wiederhergestellt und du hast
beides noch, öffnen sich deine Erinnerungen genau wie vorher — unterwegs wurde nichts neu verschlüsselt und
nichts herausgegeben. Wird der Server wiederhergestellt und du hast beides verloren, bleiben deine Erinnerungen
für immer zu; auf der Betreiberseite liegt kein Hauptschlüssel, mit dem sich das öffnen ließe. Das ist dasselbe
Versprechen, das deine Erinnerungen privat hält, nur von seiner unbequemen Seite: bewahre dein Notfall-Kit dort
auf, wo du es in einem Jahr noch findest.

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
