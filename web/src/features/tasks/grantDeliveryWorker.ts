// The web worker loop for the operator-blind background-task mechanism (ADR 0021, cr-0076) — its first
// consumer, grant-delivery (ADR 0020). Any online key-holder is an equal worker: it pulls grant-delivery jobs
// for a scope it has opened, seals that scope's key `K_SC` to a pending member's *foreign* identity public key —
// bound to (scope, the epoch it is), ADR 0072 — and completes **stating that epoch**; the backend writes the
// opaque ciphertext onto the target membership at exactly that epoch, idempotently, and refuses a stale one. Delivery
// is automatic and first-online-wins — the wrap runs on-device, the server only ever relays ciphertext.
import { tasksApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { identityPrivate } from '../../app/vault'
import { checkAndPin, type CheckResult } from '../account/identityPins'
import { TrustStateRefused } from '../account/trustMarks'
import { admissionVerifies, sealGrant } from '../spaces/grant'
import { GRANT_DELIVERY } from './capabilityTags'
import type { ClaimedJob } from '../../api/generated/tasks'
import type { ScopeKind } from '../../api/generated/scopes'

// The foreign-identity wrap (ADR 0020) now lives in `../spaces/grant` (shared with rotation re-grants, cr-0089);
// re-exported here so this worker + its test keep their existing import path.
export { wrapScopeKeyToMember } from '../spaces/grant'

/** Just what the worker needs from a cr-0075 opened scope: its id and kind, the in-memory write key `K_SC` and the
 *  epoch it is — a grant is sealed bound to that epoch, and the completion states it (ADR 0072); the kind is signed
 *  with the grant (ADR 0028 Decision 6) and decides whether an admission binds its target's account. */
export type ScopeKeyHolder = { scopeId: string; kind: ScopeKind; key: Uint8Array; epoch: number }

/** The decline reason for a target whose admission tag does not verify under this worker's current epoch key. */
export const ADMISSION_UNVERIFIED = 'admission_unverified'

/**
 * Drain the grant-delivery jobs claimable for a single opened scope: claim → wrap `K_SC` to the target →
 * complete, until nothing is left (a 204 → null claim). Idempotent + first-online-wins by construction — the
 * backend leases each job to one worker and no-ops a completion whose target is already granted, so a second
 * key-holder running this simply finds nothing to claim. Returns how many grants this worker delivered.
 */
export async function runGrantDeliveryWorker(scope: ScopeKeyHolder): Promise<number> {
  // The identity whose signing key signs every grant this run posts (ADR 0028 Decision 6). Locked vault: no run.
  const priv = identityPrivate()
  if (!priv) return 0
  // The run's own copy of K_SC. The poll hands over the active space's write key, which leaving or re-entering the
  // space zeroes in place while a run may still be in flight: read through that reference, a genuine admission no
  // longer verified (declined for good, found live 2026-10-16) and the seal would wrap an all-zero key to the member.
  const key = scope.key.slice()
  try {
    return await drain({ ...scope, key }, priv.sign)
  } finally {
    key.fill(0)
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}

async function drain(scope: ScopeKeyHolder, signPrivateKey: Uint8Array): Promise<number> {
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
    // Admission gate (ADR 0020 move 6): wrap only to a member whose admission tag verifies under THIS worker's
    // current epoch key — stated at exactly that epoch, so a tag made before a rotation does not admit. An owner's
    // session alone (no vault), or the operator, can create a membership but not a tag; such a target is declined,
    // never wrapped to, and the member stays awaiting access. Checked before the TOFU pin, so an unadmitted target's
    // key is never pinned either.
    //
    // An admission stated AHEAD of this worker's epoch is the one exception: a rotation landed and re-tagged it while
    // this tab still holds the older key. That is a fact about the worker, not the target — a decline would exclude
    // this key-holder for good, even once it holds the new key. PAUSE, like `stale_epoch` below: nothing is wrapped,
    // the lease lapses, and every further job would meet the same behind key. A tag made BEFORE a rotation (behind
    // the worker) still declines.
    if (target.admissionEpoch != null && target.admissionEpoch > scope.epoch) break
    if (!admissionVerifies(scope.key, scope.epoch, scope, target.targetUserId, target)) {
      await declineJob(job.jobId, ADMISSION_UNVERIFIED)
      continue
    }
    // TOFU gate (cr-0098, ADR 0028): pin the target's identity key on first sight, proceed on a match, but on a
    // CHANGED key never seal K_SC to a possibly-substituted key. This worker is human-less, so it BLOCKS +
    // DECLINES the job (release to other key-holders + never re-offer it to this worker) instead of wrapping — a
    // human resolves the change in the UI. Per key-holder, not a global veto: the exclusion is keyed on this
    // account (its other devices too), while a *different* key-holder without a conflicting pin still
    // TOFU-delivers. `acceptKeyChange` is what lifts it once a human resolves the change (ADR 0060 Decision 7).
    let pin: CheckResult
    try {
      pin = await checkAndPin(target.targetUserId, target.identityPublicKey)
    } catch (e) {
      // This device refused the pin store itself (ADR 0028 Decision 7): no pin can be read or written, so no key
      // can be judged. PAUSE, never decline — a decline is about this target and excludes this worker for good,
      // while the refusal is about the store. Nothing is wrapped; the lease lapses and the job is offered again.
      if (e instanceof TrustStateRefused) break
      throw e
    }
    if (pin === 'changed') {
      await declineJob(job.jobId, 'identity_changed')
      continue
    }
    const signed = sealGrant(
      scope.key, { scopeId: scope.scopeId, kind: scope.kind, epoch: scope.epoch },
      { userId: target.targetUserId, identityPublicKeyB64: target.identityPublicKey }, signPrivateKey,
    )
    try {
      await completeJob(job.jobId, signed.wrappedGrant, scope.epoch, signed.grantSignature)
    } catch (e) {
      // Our key is not the scope's current epoch (a rotation landed, or we still hold an older grant): the server
      // wrote nothing and released the job, so a key-holder at the current epoch delivers it. Stop draining —
      // every further job would meet the same answer, and re-claiming would only spin on this one.
      if (await isCodedError(e, HttpStatus.conflict, 'stale_epoch')) break
      throw e
    }
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

async function completeJob(jobId: string, sealedResult: string, epoch: number, grantSignature: string): Promise<void> {
  await withRefresh(() => tasksApi().completeTask({ jobId, completeRequest: { sealedResult, epoch, grantSignature } }))
}

async function failJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().failTask({ jobId, failRequest: { reason } }))
}

/** Decline a job back to the queue without a failed attempt (cr-0098): release it to other workers + never
 *  re-offer it to this one. Used when the target's identity key changed (this worker refuses to wrap to it), or
 *  when its admission does not verify under this worker's current epoch key. */
async function declineJob(jobId: string, reason: string): Promise<void> {
  await withRefresh(() => tasksApi().declineTask({ jobId, declineRequest: { reason } }))
}
