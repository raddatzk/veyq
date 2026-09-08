// Per-tenant mail settings (uc.admin.tenant-mail-settings, cr-email-tenant-mail-settings-web, ADR 0048/0049/0050).
// A **tenant-OWNER** surface, so it stays in `web/` with the tenant's own app: the backend gates both calls with
// `403 not_tenant_owner`, and the panel reads `activeSpace.tenantId`. ADR 0065 listed it among what moves to
// `admin-web/`; that was measured wrong at refine time (see `tenantTerms.ts`) and moving it would have taken
// SMTP configuration away from every tenant owner who is not also the Server Owner.
import { tenantsApi } from '../../api/client'
import type { TenantMailSettings, TenantMailSettingsUpdate } from '../../api/generated/tenants'

export type { TenantMailSettings, TenantMailSettingsUpdate } from '../../api/generated/tenants'

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
