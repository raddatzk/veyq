import { instanceApi } from '../../api/client'
import type { InstanceStore, InstanceStoreCheck, InstanceStoreUpdate } from '../../api/generated/instance'
import { InstanceStoreType } from '../../api/generated/instance'
import { decodeHttpError } from '../../shared/httpError'

export type {
  InstanceStore,
  InstanceStoreCheck,
  InstanceStoreCheckStep,
  InstanceStoreSetting,
  InstanceStoreUpdate,
} from '../../api/generated/instance'
export {
  InstanceStoreType,
  InstanceStoreSecret,
  InstanceStoreCheckStepStepEnum,
  InstanceStoreCheckStepOutcomeEnum,
} from '../../api/generated/instance'

/**
 * The object-store setting (ADR 0089) — Server-Owner only, the one instance SETTING beside the reads of
 * `release.ts`, `storeProvisioning.ts` and `configSecret.ts`. The setting, whether the deployment offers a bundled
 * store, whether blobs already exist (after which type, endpoint and bucket are fixed), and the last check.
 * Secrets are never returned: only whether one is stored, and which stored ones cannot be opened.
 */
export function getInstanceStore(): Promise<InstanceStore> {
  return instanceApi().getInstanceStore()
}

/**
 * Store and apply a setting at once, without a restart (ADR 0089 decision 5). A changed setting clears
 * `lastCheck`, so tenant creation waits for a passed check again. Refusals: 400 `invalid_request`,
 * 409 `no_bundled_store` / `store_in_use`, 503 `store_secret_unavailable`.
 */
export function setInstanceStore(update: InstanceStoreUpdate): Promise<InstanceStore> {
  return instanceApi().setInstanceStore({ instanceStoreUpdate: update })
}

/** Run the step-by-step check of the current setting; the result is also what `lastCheck` reads from then on.
 *  409 `store_not_configured` when no store is set. */
export function checkInstanceStore(): Promise<InstanceStoreCheck> {
  return instanceApi().checkInstanceStore()
}

/** Choose the store the deployment brings: a reference to its hint, and every other field absent. */
export const BUNDLED_STORE_UPDATE: InstanceStoreUpdate = { bundled: true }

/** A store of the operator's own, as typed — an empty string is "not given". */
export type OwnStoreFields = {
  type: InstanceStoreType
  endpoint: string
  publicEndpoint: string
  bucket: string
  region: string
  accessKey: string
  secretKey: string
  garageAdminEndpoint: string
  garageAdminToken: string
}

/**
 * The `InstanceStoreUpdate` for a store of the operator's own — one builder for the console and `veyq-admin store
 * set`, so the two cannot send different shapes for the same input.
 *
 * - A write-only secret left empty is OMITTED, which the contract reads as "keep the stored one"; sending `''`
 *   would be a value, and the server would try to seal it.
 * - The Garage fields are omitted for an S3-compatible store: the contract has them absent there.
 * - An empty public endpoint is sent as `null` — "browsers reach the store at `endpoint`" — because the form shows
 *   the whole setting it sends, so clearing that field must clear it on the server, not keep the old origin.
 */
export function ownStoreUpdate(f: OwnStoreFields): InstanceStoreUpdate {
  const update: InstanceStoreUpdate = {
    bundled: false,
    type: f.type,
    endpoint: f.endpoint.trim(),
    publicEndpoint: f.publicEndpoint.trim() || null,
    bucket: f.bucket.trim(),
    region: f.region.trim(),
    accessKey: f.accessKey.trim(),
  }
  if (f.secretKey) update.secretKey = f.secretKey
  if (f.type === InstanceStoreType.Garage) {
    update.garageAdminEndpoint = f.garageAdminEndpoint.trim()
    if (f.garageAdminToken) update.garageAdminToken = f.garageAdminToken
  }
  return update
}

/** Whether a tenant can be created as far as the store goes: the current setting passed its last check
 *  (ADR 0089 decision 6). A courtesy over the server, which refuses with 409 `store_not_ready` on its own. */
export function storeReady(store: InstanceStore | null | undefined): boolean {
  return store?.lastCheck?.passed === true
}

/** Decode a failed store call into `{ status, code }` for UI mapping (the refusals named on each function above). */
export function storeError(e: unknown): Promise<{ status: number; code: string | null }> {
  return decodeHttpError(e)
}
