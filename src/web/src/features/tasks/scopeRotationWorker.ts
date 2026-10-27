// The web worker loop for the **second** consumer of the operator-blind task queue (ADR 0021) — scope-rotation
// (cr-0092, ADR 0025; uc.spaces.complete-queued-rotation). When a tenant owner removes a member co-sharing a GROUP
// the owner doesn't hold, or a member rotates their account keys after a compromise, the backend queues a
// SCOPE_ROTATION job (it can't rotate — it holds no key there). The next online **owner** of the scope completes it
// on-device: mint E_{n+1}, chain-link E_n, re-grant the surviving members (reusing `commitRotation`, cr-0089), then
// complete. The backend offers the job to owners only (the only members who may commit a rotation), so this worker
// is never handed one it could not finish — except by a race it declines (below). Claimed **globally across every
// held scope** — an owner sitting anywhere (even their own personal scope) drains queued rotations WITHOUT opening
// the group. Requires an unlocked vault (the rotation opens the caller's own grant on-device). First-online-wins: the
// backend leases each job to one worker, and folds events into a job only while nobody has claimed it, so a second
// owner simply finds nothing to claim. The backend completes the job only after this worker's own commit landed
// during its lease, so `completeJob` without a commit is refused.
import { tasksApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { commitRotation, isStaleEpoch, listMyScopes } from '../spaces/scopes'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { listScopeMembers } from '../spaces/members'
import { activeSpace, refreshActiveScope } from '../../app/activeSpace'
import { SCOPE_ROTATION } from './capabilityTags'
import type { ClaimedJob } from '../../api/generated/tasks'

/**
 * Drain the scope-rotation jobs claimable for this key-holder across all held scopes: claim → re-grant the
 * surviving directory forward → complete, until nothing is left (a 204 → null claim). Returns how many groups
 * this worker rotated. A claimed job whose scope this worker can't rotate (a race — e.g. the grant was cut) is
 * failed back for the right worker rather than left leased; one whose rotation stays `stale_epoch` is declined.
 */
export async function runScopeRotationWorker(): Promise<number> {
  // ADR 0109 point 7: a rotation is done only once every node of the scope is re-keyed. Any member holding the current
  // epoch finishes a re-key another device left unfinished (a closed tab, a crash) — the server derives `rekeying`
  // from the node rows, so there is nothing to claim: resume it first, then take the queued jobs.
  await resumeRekeys()
  let rotated = 0
  for (;;) {
    const job = await claimScopeRotation()
    if (!job) break
    if (job.capabilityTag !== SCOPE_ROTATION) {
      // Not a rotation job we can fulfil — release it for the right worker (defensive; the claim tag-filters).
      await failJob(job.jobId, 'not-scope-rotation')
      continue
    }
    const stopRenewing = renewLeaseWhileWorking(job.jobId)
    try {
      // The directory AFTER the cut (the ex-member's membership is already deleted server-side), so the rotation
      // re-grants exactly the surviving set — coalesced removals collapse into one forward-secure rotation.
      // LOAD-BEARING ORDER: this read comes after the claim. The backend joins a later event (a removal, an account
      // rotation's new identity) only into a job nobody has claimed yet, so every event this job carries committed
      // before the claim — and therefore before this read. Reading the directory earlier (cached, prefetched, before
      // the claim) would let a commit seal the new epoch to an identity the event already replaced.
      // This is the server's word on who remains: unlike a group owner's removal (`rotateGroupForRemoval`, which
      // excludes the member it removes by its own choice), this worker did not remove anyone and has nothing of its
      // own to exclude by, so a server that re-lists a cut member gets them re-granted and re-tagged. That holds
      // against an honest operator only — ADR 0024 § Guarantee names it — and is not closed here.
      // A job whose rotation an earlier lease already committed (that worker stopped before its re-key finished)
      // completes once the scope's nodes are re-keyed: re-key, then complete; only `rotation_not_committed` makes this
      // lease commit its own rotation. A fresh job rotates, re-keys, completes. A re-key that does not finish leaves the
      // job leased: the server refuses its completion (`rekey_incomplete`) until the re-key is done, and the next tick's
      // resume pass continues it.
      if (await scopeRekeying(job.scopeId)) {
        if (!(await rekeyScope(job.scopeId)).done) continue
        if (await completeOrUncommitted(job.jobId)) { rotated++; continue }
      }
      const remaining = await listScopeMembers(job.scopeId)
      await commitRotation(job.scopeId, remaining)
      if (!(await rekeyScope(job.scopeId)).done) continue
      await completeJob(job.jobId)
      // If the rotated group is the scope we're actively viewing, swap in the fresh epoch so our next write seals
      // under E_{n+1}, not the stale E_n (mirrors the synchronous remove flow, cr-0089).
      if (activeSpace.scopeId === job.scopeId) await refreshActiveScope()
      rotated++
    } catch (e) {
      // A refusal about THIS worker, never about the job: decline — released to the scope's other owners, no
      // attempt spent — rather than fail, which would burn the job's attempts on a worker that can never do it.
      //  - still stale after the rotation's own retry: this worker's grant lags the scope (ADR 0024 §4);
      //  - not_scope_owner: demoted between claim and commit (the claim offers rotations to owners only);
      //  - rotation_excluded: this account rotated its keys while holding the lease (ADR 0060 Decision 4).
      // Not a refused OWN grant (`cannot open scope to rotate`): that fails, on purpose. A decline is permanent for
      // this job, so an owner whose grant opens again later could never take it; a job that fails out dead-letters,
      // reads `failed`, and is re-queued as a fresh job with no declines (uc.spaces.complete-queued-rotation E8).
      const refusal = await workerRefusal(e)
      if (refusal) await declineJob(job.jobId, refusal)
      else await failJob(job.jobId, 'rotate-failed')
    } finally {
      stopRenewing()
    }
  }
  return rotated
}

/**
 * How often the lease of a job this worker holds is renewed while it works on it (ADR 0021 §6: long jobs heartbeat).
 * Bound to the server's lease, `veyq.tasks.lease.ttl` (PT2M): four renewals fit in one lease, so a hidden tab whose
 * timers the browser throttles to one a minute still keeps it. What the short lease buys is the takeover: a tab that
 * closes, reloads or navigates mid-job renews nothing, and the scope's other owners — or this owner's next page —
 * claim the job once its lease runs out, within two minutes instead of five (proof run 2026-10-24, N1b).
 */
export const LEASE_RENEW_MS = 30_000

/** Renew `jobId`'s lease every {@link LEASE_RENEW_MS} until the returned stop is called. A renewal the server refuses
 *  (`not_lease_holder`: the lease ran out and another worker took the job) changes nothing here — the guarded commit
 *  and completion decide, as they do for any lost lease. */
function renewLeaseWhileWorking(jobId: string): () => void {
  const timer = setInterval(() => {
    void withRefresh(() => tasksApi().heartbeatTask({ jobId })).catch(() => {})
  }, LEASE_RENEW_MS)
  return () => clearInterval(timer)
}

/** Re-key every scope this member reads as `rekeying` (its row's derived flag, served to every member — ADR 0111
 *  decision 11). A member's run leaves the nodes only an owner re-keys; one that fails stays so. */
async function resumeRekeys(): Promise<void> {
  const scopes = await listMyScopes().catch(() => [])
  for (const s of scopes) {
    if (s.rekeying !== true) continue
    await rekeyScope(s.id).catch(() => {})
  }
}

async function scopeRekeying(scopeId: string): Promise<boolean> {
  const scopes = await listMyScopes()
  return scopes.find((s) => s.id === scopeId)?.rekeying === true
}

/** Complete the job; false when this lease committed no rotation yet (`rotation_not_committed`). */
async function completeOrUncommitted(jobId: string): Promise<boolean> {
  try {
    await completeJob(jobId)
    return true
  } catch (e) {
    if (await isCodedError(e, HttpStatus.conflict, 'rotation_not_committed')) return false
    throw e
  }
}

/** The decline reason when a rotation was refused for a reason about this worker rather than the job, else null. */
async function workerRefusal(e: unknown): Promise<string | null> {
  if (await isStaleEpoch(e)) return 'stale_epoch'
  if (await isCodedError(e, HttpStatus.forbidden, 'not_scope_owner')) return 'not_scope_owner'
  if (await isCodedError(e, HttpStatus.forbidden, 'rotation_excluded')) return 'rotation_excluded'
  return null
}

async function claimScopeRotation(): Promise<ClaimedJob | null> {
  // No `scopeId` narrowing — claim a rotation for ANY scope this worker holds a grant in, so it drains queued
  // rotations for groups it isn't currently viewing (the whole point of the async backstop).
  const job = await withRefresh(() => tasksApi().claimTask({ claimRequest: { capabilityTags: [SCOPE_ROTATION] } }))
  return job ?? null
}

async function completeJob(jobId: string): Promise<void> {
  // No sealedResult: the rotation-commit (`POST /api/scopes/{scopeId}/epochs`) already advanced the epoch and marked
  // this job as rotated by its holder; this just closes the job. The backend marks it DONE idempotently (a re-complete
  // of a DONE job is a no-op), and refuses `rotation_not_committed` if no commit of ours landed during the lease.
  await withRefresh(() => tasksApi().completeTask({ jobId, completeRequest: {} }))
}

async function failJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().failTask({ jobId, failRequest: { reason } }))
}

/** Decline a job back to the queue without a failed attempt: released to other workers, never re-offered to this
 *  one (the grant-delivery worker's `declineTask`, cr-0098). */
async function declineJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().declineTask({ jobId, declineRequest: { reason } }))
}
