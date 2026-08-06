// The web worker loop for the operator-blind background-task mechanism (ADR 0021, cr-0076) — its first
// consumer, grant-delivery (ADR 0020). Any online key-holder is an equal worker: it pulls grant-delivery jobs
// for a scope it has opened, wraps that scope's key `K_SC` to a pending member's *foreign* identity public key
// (`asymWrap(hybridPublic(target), K_SC, "scope-grant")` — the first wrap to a foreign identity in the client),
// and completes; the backend writes the opaque ciphertext onto the target membership, idempotently. Delivery
// is automatic and first-online-wins — the wrap runs on-device, the server only ever relays ciphertext.
import { tasksApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { checkAndPin } from '../account/identityPins'
import { wrapScopeKeyToMember } from '../spaces/grant'
import { GRANT_DELIVERY } from './capabilityTags'
import type { ClaimedJob } from '../../api/generated/tasks'

// The foreign-identity wrap (ADR 0020) now lives in `../spaces/grant` (shared with rotation re-grants, cr-0089);
// re-exported here so this worker + its test keep their existing import path.
export { wrapScopeKeyToMember } from '../spaces/grant'

/** Just what the worker needs from a cr-0075 opened scope: its id + the in-memory scope key `K_SC`. */
export type ScopeKeyHolder = { scopeId: string; key: Uint8Array }

/**
 * Drain the grant-delivery jobs claimable for a single opened scope: claim → wrap `K_SC` to the target →
 * complete, until nothing is left (a 204 → null claim). Idempotent + first-online-wins by construction — the
 * backend leases each job to one worker and no-ops a completion whose target is already granted, so a second
 * key-holder running this simply finds nothing to claim. Returns how many grants this worker delivered.
 */
export async function runGrantDeliveryWorker(scope: ScopeKeyHolder): Promise<number> {
  let delivered = 0
  for (;;) {
    const job = await claimGrantDelivery(scope.scopeId)
    if (!job) break
    const target = job.grantDelivery
    if (job.capabilityTag !== GRANT_DELIVERY || !target) {
      // Not a grant-delivery job we can fulfil with this scope key — release it for the right worker.
      await failJob(job.jobId, 'not-grant-delivery')
      continue
    }
    // TOFU gate (cr-0098, ADR 0028): pin the target's identity key on first sight, proceed on a match, but on a
    // CHANGED key never seal K_SC to a possibly-substituted key. This worker is human-less, so it BLOCKS +
    // DECLINES the job (release to other key-holders + never re-offer it to this worker) instead of wrapping — a
    // human resolves the change in the UI. Per-device, not a global veto: a key-holder without a conflicting pin
    // still TOFU-delivers.
    if ((await checkAndPin(target.targetUserId, target.identityPublicKey)) === 'changed') {
      await declineJob(job.jobId, 'identity_changed')
      continue
    }
    const wrap = wrapScopeKeyToMember(scope.key, target.identityPublicKey)
    await completeJob(job.jobId, wrap)
    delivered++
  }
  return delivered
}

async function claimGrantDelivery(scopeId: string): Promise<ClaimedJob | null> {
  const job = await withRefresh(() =>
    tasksApi().claimTask({ claimRequest: { capabilityTags: [GRANT_DELIVERY], scopeId } }),
  )
  return job ?? null
}

async function completeJob(jobId: string, sealedResult: string): Promise<void> {
  await withRefresh(() => tasksApi().completeTask({ jobId, completeRequest: { sealedResult } }))
}

async function failJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().failTask({ jobId, failRequest: { reason } }))
}

/** Decline a job back to the queue without a failed attempt (cr-0098): release it to other workers + never
 *  re-offer it to this one. Used when the target's identity key changed (this worker refuses to wrap to it). */
async function declineJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().declineTask({ jobId, declineRequest: { reason } }))
}
