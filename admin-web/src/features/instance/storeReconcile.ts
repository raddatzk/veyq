import { instanceStoreReconcileApi } from '../../api/client'
import type { StoreReconcile } from '../../api/generated/instance'

export type {
  StoreReconcile,
  StoreReconcileCandidates,
  StoreReconcileFailure,
  StoreReconcilePass,
} from '../../api/generated/instance'
export {
  StoreReconcileVerdictEnum,
  StoreReconcileFailureReasonEnum,
  StoreReconcileClocksRestartReasonEnum,
} from '../../api/generated/instance'

/**
 * The bucket reconcile (ADR 0062 § Consequences, uc.admin.configure-object-store A7) — Server-Owner only. A background
 * sweep lists the store and counts the objects no row claims; a storage key that stayed one in every pass for the grace
 * goes onto the reclaim list, but only while the Server Owner has confirmed the bucket is this instance's alone and has
 * not paused it (`verdict: ARMED`). Counts and dates only — never a key.
 */
export function getStoreReconcile(): Promise<StoreReconcile> {
  return instanceStoreReconcileApi().getStoreReconcile()
}

/** Confirm that the bucket in use holds only this instance's objects — arms the deletion of candidates past their grace.
 *  For the store in use only: another store, or a restore, withdraws it. 409 `no_store` while no store is usable. */
export function confirmExclusiveBucket(): Promise<StoreReconcile> {
  return instanceStoreReconcileApi().confirmStoreExclusiveBucket()
}

/** Withdraw the confirmation: the reconcile goes back to only reporting. Idempotent. */
export function withdrawExclusiveBucket(): Promise<StoreReconcile> {
  return instanceStoreReconcileApi().withdrawStoreExclusiveBucket()
}

/** Stop putting due candidates on the reclaim list; the counting goes on. Idempotent. */
export function pauseReconcile(): Promise<StoreReconcile> {
  return instanceStoreReconcileApi().pauseStoreReconcile()
}

/** Let the next completed pass put due candidates on the reclaim list again. Idempotent. */
export function resumeReconcile(): Promise<StoreReconcile> {
  return instanceStoreReconcileApi().resumeStoreReconcile()
}

/** The grace in whole days, rounded down — what the console and the confirm dialog say. */
export function graceDays(r: Pick<StoreReconcile, 'graceSeconds'>): number {
  return Math.floor(r.graceSeconds / 86_400)
}
