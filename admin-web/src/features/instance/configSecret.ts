import { instanceApi } from '../../api/client'
import type { InstanceConfigSecret } from '../../api/generated/instance'

export type { InstanceConfigSecret } from '../../api/generated/instance'
export { InstanceConfigSecretStateEnum } from '../../api/generated/instance'

/**
 * Whether the instance's config-secret key can open the secrets sealed under it (ADR 0050) — Server-Owner only,
 * the sibling of `getInstanceRelease` and `getInstanceStoreProvisioning`.
 *
 * It is the verification step of a restore and an upgrade (ADR 0056 §5): a restore that lost the key file starts
 * and looks healthy, and this is the read that says so. The read changes nothing on the server.
 */
export function getInstanceConfigSecret(): Promise<InstanceConfigSecret> {
  return instanceApi().getInstanceConfigSecret()
}
