// uc.admin.create-tenant (web, Slice A / cr-0009) — Server-Owner tenant administration on the
// operator-blind data layer. Everything here is operational metadata + a hashed invite; there is NO content
// key (the cold TENANT-scope birth that activates a tenant is Slice B, and lives with the **accept** side in
// `features/tenant/tenantInvite.ts` — see ADR 0065). Thin facade over the generated tenants client: views
// depend on this typed surface, never on the generator runtime.
import { tenantsApi } from '../../../api/client'
import type {
  LocalizedMarkdown,
  TenantCreated,
  TenantInviteState,
  TenantMailSettings,
  TenantMailSettingsUpdate,
  TenantSummary,
  TenantTerms,
} from '../../../api/generated/tenants'

export type { TenantSummary, TenantInviteState, TenantCreated } from '../../../api/generated/tenants'
export type { TenantTerms, LocalizedMarkdown } from '../../../api/generated/tenants'
export type { TenantMailSettings, TenantMailSettingsUpdate } from '../../../api/generated/tenants'
export { tenantError } from '../../tenant/tenantError'

/** Create a PENDING tenant and mint an owner invite. */
export function createTenant(tenantName: string, domain: string, ownerEmail: string): Promise<TenantCreated> {
  return tenantsApi().createTenant({ createTenantRequest: { tenantName, domain, ownerEmail } })
}

/** List all tenants (Server-Owner only). */
export function listTenants(): Promise<TenantSummary[]> {
  return tenantsApi().listTenants()
}

/** Re-mint and resend a pending tenant's invite to the same owner email. */
export function resendInvite(tenantId: string): Promise<TenantInviteState> {
  return tenantsApi().resendTenantInvite({ tenantId })
}

/** Re-bind a pending tenant's invite to a different owner email. */
export function reassignInvite(tenantId: string, ownerEmail: string): Promise<TenantInviteState> {
  return tenantsApi().reassignTenantInvite({ tenantId, reassignOwnerRequest: { ownerEmail } })
}

/** Move a tenant to a different domain (uc.admin.create-tenant). The one tenant-admin write that is **not**
 *  PENDING-only — a domain is how a tenant is reached, not part of its invite lifecycle. What makes the new
 *  address actually resolve is the server-side ingress projector (ADR 0063), not anything the client does.
 *  409 `domain_taken` if another tenant holds it, 409 `tenant_not_pending_or_active` if this one is suspended. */
export function changeTenantDomain(tenantId: string, domain: string): Promise<void> {
  return tenantsApi().changeTenantDomain({ tenantId, changeDomainRequest: { domain } })
}

/** Revoke a pending tenant. */
export function revokeTenant(tenantId: string): Promise<void> {
  return tenantsApi().revokeTenant({ tenantId })
}

/** Take an ACTIVE tenant offline (ADR 0066) — reversible, and nothing but its `status` changes: no key, grant,
 *  content row or session is touched. While suspended every request to its domain is refused at the boundary.
 *  409 `tenant_not_active` if it isn't ACTIVE. */
export function suspendTenant(tenantId: string): Promise<void> {
  return tenantsApi().suspendTenant({ tenantId })
}

/** Bring a SUSPENDED tenant back to ACTIVE. 409 `tenant_not_suspended` if it isn't suspended. */
export function reactivateTenant(tenantId: string): Promise<void> {
  return tenantsApi().reactivateTenant({ tenantId })
}

// --- Terms of service, AUTHORING side (cr-0114, ADR 0035). The member read/accept half is
//     `features/tenant/tenantTerms.ts` — `getTenantTerms` appears on both because the author reads what
//     they are about to replace. ---

/** The tenant's current terms + the caller's accepted version (member-readable; a non-member is 404). */
export function getTenantTerms(tenantId: string): Promise<TenantTerms> {
  return tenantsApi().getTenantTerms({ tenantId })
}

/** Owner-only: set/replace the tenant's per-locale terms — a normalized change bumps the version (else 403). */
export function updateTenantTerms(tenantId: string, terms: LocalizedMarkdown): Promise<TenantTerms> {
  return tenantsApi().updateTenantTerms({ tenantId, tenantTermsUpdate: { terms } })
}

// --- Mail settings (cr-email-tenant-mail-settings-web, ADR 0048/0049/0050) ---

/** Owner-only: read the tenant's SMTP + invite-template settings. The password is never returned — only
 *  `passwordSet` (ADR 0050). A non-owner member is 403 `not_tenant_owner`, a non-member 404 (indistinguishable). */
export function getTenantMailSettings(tenantId: string): Promise<TenantMailSettings> {
  return tenantsApi().getTenantMailSettings({ tenantId })
}

/** Owner-only: set/replace the tenant's mail settings. The password is write-only — the caller **omits** it (leaves
 *  it out of `update`) to keep the stored one; a supplied one is sealed at rest (ADR 0050). A template referencing a
 *  `{{tag}}` outside `{{link}}`/`{{domain}}`/`{{expiry}}` is rejected 400 `invalid_mail_template`; a supplied password
 *  with no server KEK is 503 `mail_secret_unavailable`. */
export function updateTenantMailSettings(
  tenantId: string,
  update: TenantMailSettingsUpdate,
): Promise<TenantMailSettings> {
  return tenantsApi().updateTenantMailSettings({ tenantId, tenantMailSettingsUpdate: update })
}
