import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import type { Capability, SetupStatus } from '../../api/generated'

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
   * True when a set was read — the open tenant's, else the instance's — and it lacks [id]: an entry switched off, as an
   * application's is (uc.platform.switch-application-off). Not yet discovered is not off: a banner saying an
   * application is switched off would then be false, and the server refuses what is off either way.
   */
  function isSwitchedOff(id: string): boolean {
    const ids = capabilityState.tenantIds ?? capabilityState.ids
    return ids !== null && !ids.includes(id)
  }

  /**
   * The requirement values in effect on capability [id] (ADR 0088 decision 8) — the open tenant's once loaded, else the
   * instance's — or undefined when the entry is not served. A requirement constrains a form (a password a link must
   * carry); it never hides an affordance, so it is read by the form it constrains, and the server enforces it anyway.
   */
  function capabilityValues(id: string): Capability | undefined {
    return (capabilityState.tenantEntries ?? capabilityState.entries)[id]
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

  return { capabilityState, hasCapability, isSwitchedOff, capabilityValues, incompatibility }
}

/** Record the open tenant's effective set after sign-in, or forget it (`null`: sign-out, or no tenant's set could be
 *  read, when the instance's manifest stands — the server refuses what the tenant switched off either way). */
export function setTenantCapabilities(entries: Capability[] | null): void {
  capabilityState.tenantIds = entries?.map((c) => c.id) ?? null
  capabilityState.tenantEntries = entries ? Object.fromEntries(entries.map((c) => [c.id, c])) : null
}

/** Record the instance's version + active capability manifest from the boot setup status. */
export function setCapabilities(status: SetupStatus): void {
  capabilityState.ids = status.capabilities.map((c) => c.id)
  capabilityState.entries = Object.fromEntries(status.capabilities.map((c) => [c.id, c]))
  capabilityState.major = status.version.major
  capabilityState.build = status.version.build
}
