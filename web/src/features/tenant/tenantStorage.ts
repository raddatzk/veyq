// A tenant's storage (uc.sharing.share-link-storage-limit, ADR 0073 §4) — a tenant-OWNER surface beside the mail
// settings, in `web/` for the same reason: the backend gates both calls with `403 not_tenant_owner`, and the owner
// is an ordinary member of their own tenant, not the Server Owner.
import { tenantsApi } from '../../api/client'
import type { TenantStorage } from '../../api/generated/tenants'

export type { TenantStorage } from '../../api/generated/tenants'

/** One GiB — the unit the per-link limit is entered in; the wire carries bytes. */
export const GIB = 1024 ** 3

/** Owner-only: the tenant's storage usage and its per-link limit. A non-owner member is 403, a non-member 404. */
export function getTenantStorage(tenantId: string): Promise<TenantStorage> {
  return tenantsApi().getTenantStorage({ tenantId })
}

/** Owner-only: set the per-link limit every upload link of the tenant stores at most. `0` switches link uploads off. */
export function updateTenantStorage(tenantId: string, shareLinkLimitBytes: number): Promise<TenantStorage> {
  return tenantsApi().updateTenantStorage({ tenantId, tenantStorageUpdate: { shareLinkLimitBytes } })
}
