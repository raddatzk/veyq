// Tenant terms — the MEMBER side (uc.account.accept-terms, cr-0114, ADR 0035): read the tenant's current terms
// and record an acceptance. Any member reaches this; **authoring** terms is a Server-Owner action and lives in
// `admin-web/` (ADR 0065). Terms are public operational content and acceptance is coarse metadata — no crypto.
import { tenantsApi } from '../../api/client'
import type { TenantTerms } from '../../api/generated/tenants'

export type { TenantTerms, LocalizedMarkdown } from '../../api/generated/tenants'

/** The tenant's current terms + the caller's accepted version (member-readable; a non-member is 404). */
export function getTenantTerms(tenantId: string): Promise<TenantTerms> {
  return tenantsApi().getTenantTerms({ tenantId })
}

/** Member: accept the tenant's current terms at [version] — must equal the current version, else 409. */
export function acceptTenantTerms(tenantId: string, version: number): Promise<TenantTerms> {
  return tenantsApi().acceptTenantTerms({ tenantId, acceptTermsRequest: { version } })
}
