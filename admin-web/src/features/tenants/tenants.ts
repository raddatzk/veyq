// uc.admin.create-tenant (admin-web, Slice A / cr-0009) — Server-Owner tenant administration on the
// operator-blind data layer. Everything here is operational metadata + a hashed invite; there is NO content
// key. The cold TENANT-scope birth that ACTIVATES a tenant is Slice B and stays in `web/`
// (`features/tenant/tenantInvite.ts`): an invitee accepting an invite is an anonymous or ordinary user and
// has no business loading the admin app (ADR 0065). Thin facade over the generated tenants client: views
// depend on this typed surface, never on the generator runtime.
import { tenantsApi } from '../../api/client'
import { decodeHttpError } from '../../shared/httpError'
import type { TenantCreated, TenantDeletion, TenantInviteState, TenantSummary } from '../../api/generated/tenants'

export type { TenantSummary, TenantInviteState, TenantCreated, TenantDeletion } from '../../api/generated/tenants'

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

/**
 * Delete an ACTIVE or SUSPENDED tenant (uc.admin.delete-tenant, ADR 0101 decision 10): it goes offline at once as a
 * suspension takes it, its status becomes PENDING_DELETION, and one purge erases everything in it at `dueAt`. Members
 * keep their accounts and are mailed. `domain` is the domain as the confirmation typed it — the server compares it with
 * the tenant's, so a wrong id deletes nothing (409 `tenant_domain_mismatch`); 409 `tenant_not_deletable` for a PENDING
 * tenant (revoke it) or one pending deletion already.
 */
export function deleteTenant(tenantId: string, domain: string): Promise<TenantDeletion> {
  return tenantsApi().deleteTenant({ tenantId, deleteTenantRequest: { domain: domain.trim().toLowerCase() } })
}

/** Stop a pending deletion before the purge: the tenant returns SUSPENDED and is reactivated as usual.
 *  409 `tenant_not_pending_deletion`. */
export function stopTenantDeletion(tenantId: string): Promise<void> {
  return tenantsApi().stopTenantDeletion({ tenantId })
}

/**
 * The way out of an ownerless tenant (ADR 0101 decision 9): an OWNER invite to one of its **members** — accepting it
 * makes that member an owner. Mailed through the instance mail where it is set up; the returned `bootstrapSecret` is
 * the copy-link otherwise (`delivery` says which). 409 `tenant_not_active` unless ACTIVE; 409 `not_tenant_member`.
 */
export function inviteTenantOwner(tenantId: string, email: string): Promise<TenantInviteState> {
  return tenantsApi().inviteTenantOwner({ tenantId, inviteTenantOwnerRequest: { email: email.trim() } })
}

/** The delete confirmation's rule: the typed domain is the tenant's own (case-insensitive, as a domain is). */
export function domainConfirmed(typed: string, domain: string): boolean {
  const s = typed.trim().toLowerCase()
  return s.length > 0 && s === domain.toLowerCase()
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

const twoDecimals = new Intl.NumberFormat('en-US', { maximumFractionDigits: 2, useGrouping: false })
const twoDigits = new Intl.NumberFormat('en-US', { maximumSignificantDigits: 2, useGrouping: false })

/**
 * Bytes as the GiB the detail panel's quota field shows: two decimals, and below 0.01 GiB two significant digits, so
 * a quota that is not 0 never shows as "0" (0 is a real quota: nothing more may be uploaded). A display only — the
 * panel sends the stored bytes back for a field nobody edited, never what this rounds. `parseQuotaGib` reads it back.
 */
export function formatQuotaGib(bytes: number): string {
  const gib = bytes / GIB
  return (gib === 0 || gib >= 0.01 ? twoDecimals : twoDigits).format(gib)
}

/**
 * Set, change or clear (`null`) a tenant's storage quota (uc.admin.tenant-storage-quota, ADR 0073 §1): the most the
 * tenant may hold in this instance's store. Every upload start into the tenant is reserved against it from then on;
 * a quota below the current usage deletes nothing, it refuses the next upload. No quota is the default — and it is
 * its own call: the server refuses a null quota, so an update that lost its number can never clear one (#304).
 */
export function setTenantStorageQuota(tenantId: string, quotaBytes: number | null): Promise<void> {
  return quotaBytes === null
    ? tenantsApi().clearTenantStorageQuota({ tenantId })
    : tenantsApi().setTenantStorageQuota({ tenantId, tenantStorageQuotaUpdate: { quotaBytes } })
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
