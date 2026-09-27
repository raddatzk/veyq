// The web worker loop for the **second** consumer of the operator-blind task queue (ADR 0021) — scope-rotation
// (cr-0092, ADR 0025). When a tenant owner removes a member co-sharing a GROUP the owner doesn't hold, the
// backend queues a SCOPE_ROTATION job (it can't rotate — it holds no key there). The next remaining key-holder
// online completes it on-device: mint E_{n+1}, chain-link E_n, re-grant the surviving members (reusing
// `commitRotation`, cr-0089), then complete. Claimed **globally across every held scope** — a key-holder
// sitting anywhere (even their own personal scope) drains queued rotations WITHOUT opening the group. Requires
// an unlocked vault (the rotation opens the caller's own grant on-device). Idempotent + first-online-wins: the
// backend leases each job to one worker and coalesces multiple removals into one rotation, so a second
// key-holder simply finds nothing to claim.
import { tasksApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { commitRotation, isStaleEpoch } from '../spaces/scopes'
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
  let rotated = 0
  for (;;) {
    const job = await claimScopeRotation()
    if (!job) break
    if (job.capabilityTag !== SCOPE_ROTATION) {
      // Not a rotation job we can fulfil — release it for the right worker (defensive; the claim tag-filters).
      await failJob(job.jobId, 'not-scope-rotation')
      continue
    }
    try {
      // The directory AFTER the cut (the ex-member's membership is already deleted server-side), so the rotation
      // re-grants exactly the surviving set — coalesced removals collapse into one forward-secure rotation.
      // This is the server's word on who remains: unlike a group owner's removal (`rotateGroupForRemoval`, which
      // excludes the member it removes by its own choice), this worker did not remove anyone and has nothing of its
      // own to exclude by, so a server that re-lists a cut member gets them re-granted and re-tagged. That holds
      // against an honest operator only — ADR 0024 § Guarantee names it — and is not closed here.
      const remaining = await listScopeMembers(job.scopeId)
      await commitRotation(job.scopeId, remaining)
      await completeJob(job.jobId)
      // If the rotated group is the scope we're actively viewing, swap in the fresh epoch so our next write seals
      // under E_{n+1}, not the stale E_n (mirrors the synchronous remove flow, cr-0089).
      if (activeSpace.scopeId === job.scopeId) await refreshActiveScope()
      rotated++
    } catch (e) {
      // Still stale after the rotation's own retry: this worker's grant lags the scope (ADR 0024 §4), so it
      // cannot perform the rotation at all. Decline — released for a current key-holder, no attempt spent —
      // rather than fail, which would burn the job's attempts on workers that were never able to do it.
      if (await isStaleEpoch(e)) await declineJob(job.jobId, 'stale_epoch')
      else await failJob(job.jobId, 'rotate-failed')
    }
  }
  return rotated
}

async function claimScopeRotation(): Promise<ClaimedJob | null> {
  // No `scopeId` narrowing — claim a rotation for ANY scope this worker holds a grant in, so it drains queued
  // rotations for groups it isn't currently viewing (the whole point of the async backstop).
  const job = await withRefresh(() => tasksApi().claimTask({ claimRequest: { capabilityTags: [SCOPE_ROTATION] } }))
  return job ?? null
}

async function completeJob(jobId: string): Promise<void> {
  // No sealedResult: the rotation-commit (`POST /api/scopes/{scopeId}/epochs`) already advanced the epoch; this
  // just closes the job. The backend marks it DONE idempotently (a re-complete of a DONE job is a no-op).
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
