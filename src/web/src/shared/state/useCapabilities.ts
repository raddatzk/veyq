import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import type { Capability, SetupStatus } from '../../api/generated'
import type { ApplicationErasure } from '../../api/generated/tenants'

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
   * The day application [id]'s content in the open tenant is erased (uc.platform.erase-switched-off-application), or null.
   * A day only while the set says [id] is switched off ({@link isSwitchedOff}): the server keeps its row until a due
   * erasure has finished, even after the application was switched on again, and a banner naming a day for an
   * application that is on would be false. Null too while no tenant's set is loaded — the instance's manifest names no day.
   */
  function erasureDay(id: string): Date | null {
    if (!isSwitchedOff(id)) return null
    return capabilityState.tenantErasures?.[id] ?? null
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

  return { capabilityState, hasCapability, isSwitchedOff, erasureDay, capabilityValues, incompatibility }
}

/** Record the open tenant's effective set after sign-in, with the days its switched-off applications are erased, or
 *  forget both (`null`: sign-out, or no tenant's set could be read, when the instance's manifest stands — the server
 *  refuses what the tenant switched off either way). */
export function setTenantCapabilities(entries: Capability[] | null, erasures: readonly ApplicationErasure[] = []): void {
  capabilityState.tenantIds = entries?.map((c) => c.id) ?? null
  capabilityState.tenantEntries = entries ? Object.fromEntries(entries.map((c) => [c.id, c])) : null
  capabilityState.tenantErasures = entries ? Object.fromEntries(erasures.map((e) => [e.id, e.dueAt])) : null
}

/** Record the instance's version + active capability manifest from the boot setup status. */
export function setCapabilities(status: SetupStatus): void {
  capabilityState.ids = status.capabilities.map((c) => c.id)
  capabilityState.entries = Object.fromEntries(status.capabilities.map((c) => [c.id, c]))
  capabilityState.major = status.version.major
  capabilityState.build = status.version.build
}
