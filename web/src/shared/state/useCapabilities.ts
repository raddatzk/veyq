import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import type { SetupStatus } from '../../api/generated'

/** Direction of a `major` mismatch: the app is too old (update it) vs the server is too old (update it). */
export type Incompatibility = 'update-app' | 'update-server'

export function useCapabilities() {
  /**
   * True when capability [id] is served here: by the open tenant, once its set is loaded, else by the instance. Absent
   * (or not yet discovered) ⇒ false, so a consumer hides the feature gracefully rather than invoking something the
   * server does not serve. The server computed both sets (ADR 0088 decision 3); nothing is ANDed here.
   */
  function hasCapability(id: string): boolean {
    return (capabilityState.tenantIds ?? capabilityState.ids)?.includes(id) ?? false
  }

  /**
   * The pre-login hard gate (ADR 0032): compares the instance's advertised `major` to the one this build was
   * compiled against. Returns the direction of a mismatch, or null when compatible (or not yet discovered).
   * `build` never gates.
   */
  function incompatibility(): Incompatibility | null {
    const major = capabilityState.major
    if (major === null) return null
    if (major > EXPECTED_MAJOR) return 'update-app'
    if (major < EXPECTED_MAJOR) return 'update-server'
    return null
  }

  return { capabilityState, hasCapability, incompatibility }
}

/** Record the open tenant's effective set after sign-in, or forget it (`null`: sign-out, or no tenant's set could be
 *  read, when the instance's manifest stands — the server refuses what the tenant switched off either way). */
export function setTenantCapabilities(ids: string[] | null): void {
  capabilityState.tenantIds = ids
}

/** Record the instance's version + active capability manifest from the boot setup status. */
export function setCapabilities(status: SetupStatus): void {
  capabilityState.ids = status.capabilities.map((c) => c.id)
  capabilityState.major = status.version.major
  capabilityState.build = status.version.build
}
