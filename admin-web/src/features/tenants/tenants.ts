// uc.admin.create-tenant (admin-web, Slice A / cr-0009) — Server-Owner tenant administration on the
// operator-blind data layer. Everything here is operational metadata + a hashed invite; there is NO content
// key. The cold TENANT-scope birth that ACTIVATES a tenant is Slice B and stays in `web/`
// (`features/tenant/tenantInvite.ts`): an invitee accepting an invite is an anonymous or ordinary user and
// has no business loading the admin app (ADR 0065). Thin facade over the generated tenants client: views
// depend on this typed surface, never on the generator runtime.
import { tenantsApi } from '../../api/client'
import { decodeHttpError } from '../../shared/httpError'
import type { TenantCreated, TenantInviteState, TenantSummary } from '../../api/generated/tenants'

export type { TenantSummary, TenantInviteState, TenantCreated } from '../../api/generated/tenants'

/** Create a PENDING tenant and mint an owner invite. */
export function createTenant(tenantName: string, domain: string, ownerEmail: string): Promise<TenantCreated> {
  return tenantsApi().createTenant({ createTenantRequest: { tenantName, domain, ownerEmail } })
}

/** List all tenants (Server-Owner only). */
export function listTenants(): Promise<TenantSummary[]> {
  return tenantsApi().listTenants()
}

/** Re-mint a pending tenant's invite for the same owner email. No mail goes out (a PENDING tenant has no mail
 *  settings, ADR 0055 §3): the returned `bootstrapSecret` is the copy-link the Server Owner hands over. */
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
 *  content row or session is touched. While suspended its members are refused on every operation acting on it,
 *  from any domain, and its own domain refuses every API call (uc.spaces.scope-access).
 *  409 `tenant_not_active` if it isn't ACTIVE. */
export function suspendTenant(tenantId: string): Promise<void> {
  return tenantsApi().suspendTenant({ tenantId })
}

/** Bring a SUSPENDED tenant back to ACTIVE. 409 `tenant_not_suspended` if it isn't suspended. */
export function reactivateTenant(tenantId: string): Promise<void> {
  return tenantsApi().reactivateTenant({ tenantId })
}

/** One GiB — the unit a quota is entered in, in the UI and the CLI alike; the wire carries bytes. */
export const GIB = 1024 ** 3

/**
 * A quota as the Server Owner types it, in GiB ("2", "2.5", the German "2,5"), or `none` for no quota — as bytes,
 * `null` for none, or `undefined` for input that is neither. One parser for the detail panel and `tenant quota`, so
 * the two cannot disagree about what "2,5" means.
 */
export function parseQuotaGib(input: string): number | null | undefined {
  const s = input.trim().toLowerCase()
  if (s === 'none') return null
  const n = Number(s.replace(',', '.'))
  return s !== '' && Number.isFinite(n) && n >= 0 ? Math.round(n * GIB) : undefined
}

/**
 * Set, change or clear (`null`) a tenant's storage quota (uc.admin.tenant-storage-quota, ADR 0073 §1): the most the
 * tenant may hold in this instance's store. Every upload start into the tenant is reserved against it from then on;
 * a quota below the current usage deletes nothing, it refuses the next upload. No quota is the default.
 */
export function setTenantStorageQuota(tenantId: string, quotaBytes: number | null): Promise<void> {
  return tenantsApi().setTenantStorageQuota({ tenantId, tenantStorageQuotaUpdate: { quotaBytes } })
}


/**
 * Decode a failed tenant call into `{ status, code }` for UI mapping (401 → re-login, 403 → not owner,
 * 409 → domain_taken / tenant_not_pending). Thin domain-named alias over the shared {@link decodeHttpError}.
 * `web/` carries its own equivalent: the `ResponseError` a client throws is that client's own type, so this
 * is a structural duplicate, not a shareable one.
 */
export function tenantError(e: unknown): Promise<{ status: number; code: string | null }> {
  return decodeHttpError(e)
}
