import { instanceApi } from '../../../api/client'
import type { InstanceRelease } from '../../../api/generated/instance'

export type { InstanceRelease } from '../../../api/generated/instance'

/**
 * The release the running instance was published under (ADR 0057) — Server-Owner only, and deliberately not on
 * the pre-auth `GET /api/setup` that `useCapabilities` reads. `release` is `null` for a build the release
 * pipeline never stamped; that absence is the answer, not a missing read.
 */
export function getInstanceRelease(): Promise<InstanceRelease> {
  return instanceApi().getInstanceRelease()
}
