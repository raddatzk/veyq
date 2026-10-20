// Tenant terms (uc.admin.tenant-terms + uc.account.accept-terms, cr-0114, ADR 0035) — all three of them, and
// all three stay in `src/web/`. Authoring is a **tenant-OWNER** action, not a Server-Owner one: the backend gates
// `updateTenantTerms` with `403 not_tenant_owner`, and a tenant owner is an ordinary user who accepted an
// invite (uc.admin.create-tenant is two-party). ADR 0065's "what moves" list named terms authoring for
// `admin-web`; measured against the code that was wrong — `TenantTermsPanel` hangs off `activeSpace.tenantId`
// in the tenant settings category, not behind any Server-Owner check — and moving it would have taken
// per-tenant terms away from every owner who is not also the Server Owner. Terms are public operational
// content and acceptance is coarse metadata — no crypto.
import { tenantsApi } from '../../api/client'
import type { LocalizedMarkdown, TenantTerms } from '../../api/generated/tenants'
import type { TermsTriple } from '../../core/terms/operatorSection'

export type { TenantTerms, LocalizedMarkdown } from '../../api/generated/tenants'

/** The tenant's current terms, the operator section after them, and whether the caller accepted the current triple
 *  (member-readable; a non-member is 404). */
export function getTenantTerms(tenantId: string): Promise<TenantTerms> {
  return tenantsApi().getTenantTerms({ tenantId })
}

/** Member: accept the triple the member was shown (ADR 0035 decision 6) — every part must equal the current one, else
 *  409 `terms_revision_changed`. */
export function acceptTenantTerms(tenantId: string, triple: TermsTriple): Promise<TenantTerms> {
  return tenantsApi().acceptTenantTerms({
    tenantId,
    acceptTermsRequest: { revision: triple.revision, operatorRevision: triple.operatorRevision, variant: triple.variant },
  })
}

/** Owner-only: set/replace the tenant's per-locale terms — a normalized change bumps the revision (else 403). */
export function updateTenantTerms(tenantId: string, terms: LocalizedMarkdown): Promise<TenantTerms> {
  return tenantsApi().updateTenantTerms({ tenantId, tenantTermsUpdate: { terms } })
}
