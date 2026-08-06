import { capabilityState, EXPECTED_MAJOR } from './capabilities'
import type { SetupStatus } from '../../api/generated'

/** Direction of a `major` mismatch: the app is too old (update it) vs the server is too old (update it). */
export type Incompatibility = 'update-app' | 'update-server'

export function useCapabilities() {
  /**
   * True when the instance advertises capability [id]. Absent (or not yet discovered) ⇒ false, so a consumer
   * hides the feature gracefully rather than invoking something the instance does not serve.
   */
  function hasCapability(id: string): boolean {
    return capabilityState.ids?.includes(id) ?? false
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

/** Record the instance's version + active capability manifest from the boot setup status. */
export function setCapabilities(status: SetupStatus): void {
  capabilityState.ids = status.capabilities.map((c) => c.id)
  capabilityState.major = status.version.major
  capabilityState.build = status.version.build
}
