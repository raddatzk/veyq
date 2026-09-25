// A suspended tenant, seen from the app (uc.spaces.scope-access A7, ADR 0066). The server answers a member of a
// suspended tenant `403 tenant_suspended` wherever they ask — but what the app should do depends on WHICH tenant:
//  - the tenant this domain belongs to: every API call here is refused (its `Host` filter), there is nowhere to go,
//    so the app shows the full-page offline notice;
//  - another tenant (reached by id from this domain): leave that space for this domain's own home and refresh the
//    tenant list, which then shows the suspended one offline.
// The two are told apart by asking this domain's public setup route: it answers unless the domain itself is
// suspended. The `awaiting access` marker is a different reason entirely (en.spaces.tenant-scope E1 vs E1a) and is
// not touched here.
import { reactive } from 'vue'
import { getSetupStatus } from '../features/account/instance/instance'
import { HttpStatus, isCodedError } from '../shared/httpError'
import { useServerState } from '../shared/state/useServerState'

export const TENANT_SUSPENDED = 'tenant_suspended'

/** Signals the shell reacts to: `leaveHome` asks the home view to leave the space for the domain's own home,
 *  `tenantList` asks every tenant list to re-read (the suspended tenant now comes back with its status). */
export const suspensionSignals = reactive({ leaveHome: 0, tenantList: 0 })

/** Whether a failure is a `403 tenant_suspended` — by code, since a 403 alone is also a missing role. */
export function isTenantSuspended(err: unknown): Promise<boolean> {
  return isCodedError(err, HttpStatus.forbidden, TENANT_SUSPENDED)
}

/** Whether this domain's own tenant is suspended: its public setup route is then refused like every API call. */
export async function domainIsSuspended(): Promise<boolean> {
  try {
    await getSetupStatus()
    return false
  } catch (probe) {
    return isTenantSuspended(probe)
  }
}

/**
 * React to a failed call: returns `'own-domain'` or `'other-tenant'` when it was a `403 tenant_suspended` (after
 * acting on it), `null` for anything else — which the caller handles as before.
 */
export async function handleTenantSuspended(err: unknown): Promise<'own-domain' | 'other-tenant' | null> {
  if (!(await isTenantSuspended(err))) return null
  if (await domainIsSuspended()) {
    useServerState().setSuspended(true)
    return 'own-domain'
  }
  suspensionSignals.leaveHome++
  suspensionSignals.tenantList++
  return 'other-tenant'
}
