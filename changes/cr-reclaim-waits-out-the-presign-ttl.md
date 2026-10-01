---
id: cr-reclaim-waits-out-the-presign-ttl
title: The reclaim sweep deletes a key only once no signed PUT for it can still land
status: planned
satisfies: []
adrs: ["0062", "0013", "0073"]
invariants: [operator-blind E2EE everywhere]
---

## Open for the owner

**Second round answered by the owner, 2026-10-01: every recommendation added since the first answer is taken too** (questions added by the 2026-09-28 second refine wave). Rows those questions proposed exist as #222–#232.

**Answered by the owner, 2026-09-28: every recommendation below is taken, and implementation has the go.** Read each question's recommendation as its decision. Where a question proposed a separate inbox row, that row exists (#197–#221, `docs/inbox.md`).

1. **Is this a defect worth closing now, or an accepted residual?** A member with write access can do the
   following:
   1. upload a blob and complete it;
   2. trash it and purge it at once, or replace or clear a cover;
   3. PUT the same chunk bytes again, on URLs signed less than 600 s earlier, after the next reclaim tick.

   Those bytes sit under a key that no row names, that no reclaim row names, and that the storage quota
   ([ADR 0073](../docs/decisions/0073-storage-quota-belongs-to-whoever-pays-for-the-store.md)) never counts. The sweep never deletes them.
   One cycle leaks at most one presign window (64 chunks of 256 KiB, which is 16 MiB), and the cycle
   repeats. **Recommendation: close it.** The fix is small (one timestamp at record time), it needs no
   migration, and it restores the premise inbox #134 rests on, "the stock is zero by construction". The
   difference is that the premise then also holds against a client that misbehaves, not only a
   well-behaved one.
2. **Which number bounds the wait?**
   - (a) Reuse `veyq.media.upload.retention` (PT1H). Its comment in `application.properties` already derives
     it as "after this, no signed PUT can still land": presign TTL plus one chunk still streaming.
   - (b) A new `veyq.store-reclaim.not-before` with the same floor.

   **Recommendation: (a)**, read through `VeyqConfig`. It is one number with one meaning, and a second knob
   would have to be kept at least as large as the first by hand. The price is up to one more hour before
   bytes go, which is the kind of latency ADR 0062 Decision 3 already accepts.
3. **What happens to inbox #134, the bucket-versus-database reconcile?** **Recommendation: it stays parked.**
   After this CR the only producer left is a writer outside veyq (a shared bucket, a hand-copied object).
   One piece of the ADR's cost premise changes, though, and the ADR should say so. A reconcile built as an
   **operator tool** on `scripts/instance_backup.py`'s rclone plumbing (`rclone lsf` plus one DB snapshot)
   needs **no** new `BlobStore` port. The "new port operation" cost holds only for a backend reconcile.

## Goal

ADR 0062's reconcile bullet argues that "the write paths leave no moment at which an object exists unnamed".
It argues this at **presign** time: a URL is signed only after its session row has committed. It does not
argue it at **PUT** time. A PUT URL stays valid for `veyq.blob-store.presign-ttl-seconds` (600) after
signing. Several paths drop the row that names a key well inside that window, and the reclaim row they
write is due on the very next sweep tick. A tick that falls between the drop and a late PUT deletes the
objects and settles the row. The late PUT then re-creates an object under a key that nothing names.

The upload-session purge already solved exactly this problem for sessions: it waits
`veyq.media.upload.retention` after the last signed window. This CR applies the same bound to the reclaim
list. A recorded key is not due until no URL signed for it can still land.

## Inventory (measured at refine, `main` @ `faf9ef4`, read, not run)

| # | Where | Fact | How measured |
|---|---|---|---|
| 1 | `backend/…/shared/blobstore/StoreReclaim.kt`, `StoreReclaimStore.record` | `nextAttemptAt = now`, with the comment "due on the next tick" | `grep -n 'nextAttemptAt = now' backend/src/main/kotlin/com/veyq/app/shared/blobstore/StoreReclaim.kt` |
| 2 | `backend/src/main/resources/application.properties` | `veyq.store-reclaim.sweep.every=1h`, `veyq.blob-store.presign-ttl-seconds=600`, `veyq.blob.presign-window=64`, and `veyq.media.upload.retention=PT1H` with its derivation comment | `grep -n 'store-reclaim.sweep\|presign-ttl\|presign-window\|upload.retention' backend/src/main/resources/application.properties` |
| 3 | `backend/…/features/media/MediaHandler.kt`, `windowOne` | A window is signed only for a **live** session (`findSession`, otherwise `upload_not_found`). After `complete` the session row is gone, so no URL for a completed blob's keys is signed **after** its row drop. That is what makes the wait sufficient. | read `windowOne`, `presignWindow`, `presignOpened` |
| 4 | Every row drop that records a key: `grep -rn 'reclaim.record(' backend/src/main/kotlin` | Six sites. Three can fire **inside** the TTL of the key's last window: `MediaStore.deleteBlobRow` (media purge from trash, cover replace in `commitCover`, `clearCover`), the budget purge in `MediaHandler.purge` (it leaves the session row standing, so a late PUT stays named until the stale purge), and `NodeSubtreePurge`'s covers. `ScopePurge` and the stale-session purge fire only after a retention far longer than the TTL. | the grep, then read each caller's gate |
| 5 | `backend/…/shared/blobstore/StoreReclaimSweep.kt`, `run` | `Instant.now()` inline, and no clock seam | `grep -n 'Instant.now' backend/src/main/kotlin/com/veyq/app/shared/blobstore/StoreReclaimSweep.kt` |
| 6 | Tests that record and then sweep at once, which the wait will turn red unless given a seam | `grep -rln 'StoreReclaimSweep\|\.due(' backend/src/test`. At refine this found 8 files, among them `StoreReclaimSweepSliceTest`, `MediaTrashSliceTest`, `NodeTrashSliceTest`, `ScopesSliceTest`, `UploadSessionPurgeSliceTest`. | the grep |
| 7 | Prose that states the premise this CR corrects | ADR 0062 § Consequences, the reconcile bullet ("no moment at which an object exists unnamed"). The `VeyqConfig.StoreReclaim.sweep` KDoc. The `application.properties` reclaim comment ("bytes go up to one tick after the rows"). Inbox #134 ("no window, no grace period"). | `grep -rn 'exists unnamed\|no window, no grace\|one tick after the rows\|up to one interval' docs backend/src/main` |

*Remembered, not measured:* S3 and Garage accept a presigned PUT whose signature was valid when the request
began, and they accept it onto a key that was deleted in the meantime, because a key is only a name. The
comment on `veyq.media.upload.retention` already relies on the first half.

## Decisions

- **`record` stamps `nextAttemptAt = now + <bound>`, and the bound is Open question 2's.** `due()` and the
  backoff are unchanged. Nothing is added to the schema, because `next_attempt_at` already exists
  (`V48__store_reclaim.sql`). A row that is later refused backs off from its attempt time, as it does
  today.
- **The tests travel in time the way this repo's purge tests already do: they backdate rows.** They do not
  get a production clock seam. `UploadSessionPurgeSliceTest` backdates a session past
  `config.media().upload().retention()`, measured with
  `grep -n 'backdate' backend/src/test/kotlin/com/veyq/app/features/media/UploadSessionPurgeSliceTest.kt`.
  A reclaim test does the same to `next_attempt_at`. An `internal run(now)` seam is the fallback, for the
  case where backdating cannot reach a row a path records inside its own transaction. A `%test` override
  that sets the bound to zero is **rejected**. It would make the one property this CR adds invisible to the
  suite that has to prove it.
- **ADR 0062 is amended, not rewritten.** The reconcile bullet's "no moment unnamed" argument gains its
  PUT-time half and names this bound. Its "needs a new port operation" cost is scoped to a *backend*
  reconcile (Open question 3). Decision 3's accepted latency grows from "one tick" to "the bound plus one
  tick".

## Phases

- [ ] 1 backend: the wait, and the tests from Inventory 6 moved onto backdated rows. Add a new test
      in `StoreReclaimSweepSliceTest` named for the property, e.g.
      `aRecordedKeyIsNotDeletedWhileAPutSignedForItCanStillLand`. It does three things:
      - it records a key and sweeps at `now`, and asserts **no** delete reached the `RecordingBlobStore`;
      - it backdates the row's recording by `bound + 1s`, sweeps again, and asserts the delete happened and the row is settled;
      - it has a positive control on the same predicate: a row whose `nextAttemptAt` is already past is
        deleted at `now`.

      · Done when: `./gradlew check` is green in `backend/`.
- [ ] 2 docs: ADR 0062 amendment, the `VeyqConfig` KDoc, the `application.properties` comment, and the
      ADR 0073 quota note if the bound touches it (`grep -n 'reclaim' docs/decisions/0073-*.md`).
      · Done when: the trust-chain glob is green.

## Definition of done

- **Red first.** Write the new test before the change. It must fail on today's `record`, because the first
  sweep at `now` deletes. Record the failing assertion.
- **Bite, per lever.** Revert `record` to `nextAttemptAt = now`: the new test goes red, and the positive
  control stays green. Set the bound to `presign TTL − 1s`, the smallest wrong value: a second case
  whose recording is backdated by only `TTL − 1s` must still see no delete. That shows the test pins the floor and not just "some delay".
- **Nothing else moved.** The eight test files from Inventory 6 are green on backdated rows, and each still asserts
  that its path's objects are deleted in the end. If one of them loses that assertion in the move, the move
  disarmed it.
- The trust-chain glob is green. Inbox #134's row carries the outcome of Open question 3.

## Retrospective
