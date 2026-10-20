import { instanceApi } from '../../api/client'
import type { InstanceStoreProvisioning } from '../../api/generated/instance'

export type { InstanceStoreProvisioning } from '../../api/generated/instance'
export { InstanceStoreProvisioningStateEnum } from '../../api/generated/instance'

/**
 * How far this instance got with bringing its object store up (ADR 0067) — Server-Owner only, and the sibling
 * of `getInstanceRelease`.
 *
 * It exists because provisioning deliberately never gates the boot: an instance whose store is unusable starts
 * anyway and looks healthy, so without this read the operator's first notice would be a photo that will not
 * upload. Every one of the four states is a real answer — `NOT_ATTEMPTED` most of all, which is what a
 * correctly configured `type: s3` instance reports forever.
 */
export function getInstanceStoreProvisioning(): Promise<InstanceStoreProvisioning> {
  return instanceApi().getInstanceStoreProvisioning()
}
