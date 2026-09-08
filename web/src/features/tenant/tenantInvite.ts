// Tenant-invite ACCEPT side (uc.admin.create-tenant Slice B / cr-0010) — the half of tenant administration an
// **anonymous invitee or an ordinary signed-in user** reaches, not the Server Owner. It stays in `web/` while
// the Server-Owner administration surface lives in `admin-web/` (ADR 0065): a person accepting an invite has no
// business loading the admin app. Thin facade over the generated tenants client: views depend on this typed
// surface, never on the generator runtime.
import { tenantsApi } from '../../api/client'
import type { InvitePreview, TenantActivated, TenantActivatedWithUser } from '../../api/generated/tenants'
import * as v1 from '@veyq/crypto/v1'
import type { HybridPublic } from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { AccountMaterial } from '@veyq/crypto/accountBirth'

export type { InvitePreview } from '../../api/generated/tenants'
export { tenantError } from './tenantError'

/** Preview an invite from its bootstrap secret (public — no session). */
export function previewInvite(bootstrapSecret: string): Promise<InvitePreview> {
  return tenantsApi().previewTenantInvite({ acceptPreviewRequest: { bootstrapSecret } })
}

/** Accept as an existing (signed-in) user, choosing a `username` for this tenant (ADR 0023). An OWNER invite
 *  carries the cold-born grant; a MEMBER invite omits it (grant-less; a key-holder delivers the key, cr-0076). */
export function acceptInvite(
  bootstrapSecret: string,
  username: string,
  grant?: string,
  sealedName?: string,
): Promise<TenantActivated> {
  return tenantsApi().acceptTenantInvite({ acceptInviteRequest: { bootstrapSecret, username, grant, sealedName } })
}

/** Register a new account cold and accept in one step (public — the email comes from the invite). `username` is
 *  the caller's per-tenant handle; `sealedName` is the OPTIONAL real name sealed under `K_tenant` at join
 *  (owner cold-birth only — a grant-less member seals it later via the profile op, ADR 0023). `grant` is set for
 *  an OWNER invite (cold-births the TENANT scope) and omitted for a MEMBER invite (grant-less join). */
export function registerAndAccept(
  bootstrapSecret: string,
  username: string,
  material: AccountMaterial,
  grant?: string,
  sealedName?: string,
): Promise<TenantActivatedWithUser> {
  return tenantsApi().registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret,
      username,
      account: {
        suiteId: material.suiteId,
        identityPublicKey: material.identityPublicKey,
        wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
        authPublicKey: material.authPublicKey,
        masterKeyWraps: material.masterKeyWraps,
        recoveryMasterKeyTag: material.recoveryMasterKeyTag,
      },
      grant,
      sealedName,
    },
  })
}

/**
 * Cold-birth the TENANT scope: mint a fresh scope root key (`K_tenant`), and return **both** the first grant
 * (the key `asymWrap`'d to the owner's own identity public key — opaque ciphertext to the server) **and the key
 * itself**, so the owner can immediately seal their real name under it at join (cr-0106). The key never leaves
 * the device; only the sealed grant + (optional) sealed name cross the API.
 */
export function bornTenantScope(identityPublic: HybridPublic): { grant: string; scopeRootKey: Uint8Array } {
  const scopeRootKey = v1.randomBytes(32)
  const grant = b64std(v1.asymWrap(identityPublic, scopeRootKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
  return { grant, scopeRootKey }
}

/** Just the grant from a fresh tenant-scope birth (for callers that don't seal a name). */
export function scopeGrant(identityPublic: HybridPublic): string {
  return bornTenantScope(identityPublic).grant
}
