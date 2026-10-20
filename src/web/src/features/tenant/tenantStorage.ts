// A tenant's storage (uc.sharing.share-link-storage-limit, ADR 0073 §4) — a tenant-OWNER surface beside the mail
// settings, in `src/web/` for the same reason: the backend gates both calls with `403 not_tenant_owner`, and the owner
// is an ordinary member of their own tenant, not the Server Owner.
import { tenantsApi } from '../../api/client'
import type { TenantStorage } from '../../api/generated/tenants'

export type { TenantStorage } from '../../api/generated/tenants'

/** One GiB — the unit the per-link limit is entered in; the wire carries bytes. */
export const GIB = 1024 ** 3

const twoDecimals = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2, useGrouping: false })
const twoDigits = new Intl.NumberFormat('en-US', { maximumSignificantDigits: 2, useGrouping: false })

/**
 * Bytes as the GiB the limit field shows: two decimals, and below 0.01 GiB two significant digits, so a limit that is
 * not 0 never shows as "0" (`0` means link uploads off). A display only — what it rounds is not what an unchanged
 * save sends: the panel sends the stored bytes back for a field nobody edited.
 */
export function formatGib(bytes: number): string {
  const gib = bytes / GIB
  return (gib === 0 || gib >= 0.01 ? twoDecimals : twoDigits).format(gib)
}

/** Owner-only: the tenant's storage usage and its per-link limit. A non-owner member is 403, a non-member 404. */
export function getTenantStorage(tenantId: string): Promise<TenantStorage> {
  return tenantsApi().getTenantStorage({ tenantId })
}

/** Owner-only: set the per-link limit every upload link of the tenant stores at most. `0` switches link uploads off. */
export function updateTenantStorage(tenantId: string, shareLinkLimitBytes: number): Promise<TenantStorage> {
  return tenantsApi().updateTenantStorage({ tenantId, tenantStorageUpdate: { shareLinkLimitBytes } })
}

/**
 * Any member: would `bytes` more still fit the tenant's quota? A yes or a no, never the free space (ADR 0082; the server
 * rate-limits it per member and per tenant). Advisory - the upload that follows is the one decision. Resolves `null` when
 * the question could not be answered (a limit, an outage, an older server): the caller then goes on and lets the upload
 * decide, because a preflight that cannot be answered must not block anyone.
 */
export async function tenantStorageFits(tenantId: string, bytes: number): Promise<boolean | null> {
  try {
    return (await tenantsApi().checkTenantStorageFit({ tenantId, tenantStorageFitRequest: { bytes } })).fits
  } catch {
    return null
  }
}
