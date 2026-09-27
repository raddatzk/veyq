// Tenant-invite ACCEPT side (uc.admin.create-tenant Slice B / cr-0010) — the half of tenant administration an
// **anonymous invitee or an ordinary signed-in user** reaches, not the Server Owner. It stays in `web/` while
// the Server-Owner administration surface lives in `admin-web/` (ADR 0065): a person accepting an invite has no
// business loading the admin app. Thin facade over the generated tenants client: views depend on this typed
// surface, never on the generator runtime.
import { tenantsApi } from '../../api/client'
import type { InvitePreview, TenantActivated, TenantActivatedWithUser } from '../../api/generated/tenants'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { AccountMaterial } from '@veyq/crypto/accountBirth'
import { sealGrant, tagAdmission } from '../spaces/grant'

export type { InvitePreview } from '../../api/generated/tenants'
export { tenantError } from './tenantError'

/** Preview an invite from its bootstrap secret (public — no session). */
export function previewInvite(bootstrapSecret: string): Promise<InvitePreview> {
  return tenantsApi().previewTenantInvite({ acceptPreviewRequest: { bootstrapSecret } })
}

/** Accept as an existing (signed-in) user, choosing a `username` for this tenant (ADR 0023). An OWNER invite
 *  carries the cold birth; a MEMBER invite omits it (grant-less; a key-holder delivers the key, cr-0076). */
export function acceptInvite(
  bootstrapSecret: string,
  username: string,
  birth?: TenantBirth,
  sealedName?: string,
): Promise<TenantActivated> {
  return tenantsApi().acceptTenantInvite({ acceptInviteRequest: { bootstrapSecret, username, ...birth, sealedName } })
}

/** Register a new account cold and accept in one step (public — the email comes from the invite). `username` is
 *  the caller's per-tenant handle; `sealedName` is the OPTIONAL real name sealed under `K_tenant` at join
 *  (owner cold-birth only — a grant-less member seals it later via the profile op, ADR 0023). `grant` is set for
 *  an OWNER invite (cold-births the TENANT scope) and omitted for a MEMBER invite (grant-less join). */
export function registerAndAccept(
  bootstrapSecret: string,
  userId: string,
  username: string,
  material: AccountMaterial,
  birth?: TenantBirth,
  sealedName?: string,
): Promise<TenantActivatedWithUser> {
  return tenantsApi().registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret,
      userId,
      username,
      account: {
        suiteId: material.suiteId,
        identityPublicKey: material.identityPublicKey,
        wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
        authPublicKey: material.authPublicKey,
        masterKeyWraps: material.masterKeyWraps,
        secretKeyProofPublicKey: material.secretKeyProofPublicKey,
      },
      ...birth,
      sealedName,
    },
  })
}

/** What an OWNER accept carries for the tenant's cold birth: the first grant, the owner's self-signature over it
 *  (ADR 0028 Decision 6), and the owner's own admission self-tagged under `E_0` (ADR 0020 move 6) so the tenant's
 *  first rotation has a tag to carry forward. */
export type TenantBirth = {
  grant: string
  grantSignature: string
  admissionId: string
  admissionEpoch: number
  admissionTag: string
}

/** The owner an OWNER accept births the tenant for: their account id and their identity (the signing half signs the
 *  grant, the public bundle is what the grant is sealed to). */
export type TenantOwner = { userId: string; identity: { pub: v1.Identity['pub']; priv: { sign: Uint8Array } } }

/**
 * Cold-birth the TENANT scope: mint a fresh scope root key (`K_tenant`), and return **both** the birth (the key
 * sealed to the owner's own identity and signed by it, plus the owner's self-tagged admission — opaque to the
 * server) **and the key itself**, so the owner can immediately seal their real name under it at join (cr-0106).
 * The key never leaves the device; only the sealed grant, its signature, the tag + (optional) sealed name cross
 * the API.
 */
export function bornTenantScope(owner: TenantOwner, tenantId: string): { birth: TenantBirth; scopeRootKey: Uint8Array } {
  const scopeRootKey = v1.randomBytes(32)
  // The tenant scope's id IS the tenant id, so it is known before the birth; the grant is born at epoch 0 (ADR 0072).
  const scope = { scopeId: tenantId, kind: 'TENANT', epoch: 0 } as const
  const to = { userId: owner.userId, identityPublicKeyB64: b64std(v1.serializeIdentityPublic(owner.identity.pub)) }
  const signed = sealGrant(scopeRootKey, scope, to, owner.identity.priv.sign)
  const admission = tagAdmission(scopeRootKey, scope, 0, null)
  return { birth: { grant: signed.wrappedGrant, grantSignature: signed.grantSignature, ...admission }, scopeRootKey }
}

/** Just the birth from a fresh tenant-scope birth (for callers that don't seal a name). */
export function scopeGrant(owner: TenantOwner, tenantId: string): TenantBirth {
  return bornTenantScope(owner, tenantId).birth
}
