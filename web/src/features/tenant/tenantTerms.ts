// Tenant terms (uc.admin.tenant-terms + uc.account.accept-terms, cr-0114, ADR 0035) — all three of them, and
// all three stay in `web/`. Authoring is a **tenant-OWNER** action, not a Server-Owner one: the backend gates
// `updateTenantTerms` with `403 not_tenant_owner`, and a tenant owner is an ordinary user who accepted an
// invite (uc.admin.create-tenant is two-party). ADR 0065's "what moves" list named terms authoring for
// `admin-web`; measured against the code that was wrong — `TenantTermsPanel` hangs off `activeSpace.tenantId`
// in the tenant settings category, not behind any Server-Owner check — and moving it would have taken
// per-tenant terms away from every owner who is not also the Server Owner. Terms are public operational
// content and acceptance is coarse metadata — no crypto.
import { tenantsApi } from '../../api/client'
import type { LocalizedMarkdown, TenantTerms } from '../../api/generated/tenants'

export type { TenantTerms, LocalizedMarkdown } from '../../api/generated/tenants'

/** The tenant's current terms + the caller's accepted version (member-readable; a non-member is 404). */
export function getTenantTerms(tenantId: string): Promise<TenantTerms> {
  return tenantsApi().getTenantTerms({ tenantId })
}

/** Member: accept the tenant's current terms at [version] — must equal the current version, else 409. */
export function acceptTenantTerms(tenantId: string, version: number): Promise<TenantTerms> {
  return tenantsApi().acceptTenantTerms({ tenantId, acceptTermsRequest: { version } })
}

/** Owner-only: set/replace the tenant's per-locale terms — a normalized change bumps the version (else 403). */
export function updateTenantTerms(tenantId: string, terms: LocalizedMarkdown): Promise<TenantTerms> {
  return tenantsApi().updateTenantTerms({ tenantId, tenantTermsUpdate: { terms } })
}
