// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner on the operator-blind
// (Model B) data layer. ALL key material is born here; the server only ever receives ciphertext + public
// keys + low-sensitivity metadata. Unlike the legacy Model-A flow, the raw password is NEVER sent: the
// client derives an Ed25519 auth keypair (ADR 0003) and registers only `authPublicKey`.
import { fromB64std } from '../../../shared/base64'
import { setupApi } from '../../../api/client'
import { buildAccount } from '../../account/register/accountBirth'
import {
  type CreateServerOwnerRequest,
  CreateServerOwnerRequestSuiteIdEnum,
  type MasterKeyWrapMethodEnum,
} from '../../../api/generated'

export type EmergencyKit = { secretKey: string }

/** Build the request (keys born here via the shared cold account-birth). Returns the request + the Emergency
 *  Kit to show the user. */
export function buildCreateServerOwnerRequest(
  email: string,
  password: string,
  instanceSalt: Uint8Array,
): { request: CreateServerOwnerRequest; emergencyKit: EmergencyKit } {
  const { material, emergencyKit } = buildAccount(email, password, instanceSalt)
  // The Server-Owner account carries no display name — identity is a per-tenant username (ADR 0023), and a
  // Server Owner who is not (yet) a tenant member is identified by email alone.
  const request: CreateServerOwnerRequest = {
    email,
    suiteId: CreateServerOwnerRequestSuiteIdEnum.V1,
    identityPublicKey: material.identityPublicKey,
    wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
    authPublicKey: material.authPublicKey,
    masterKeyWraps: material.masterKeyWraps.map((w) => ({
      method: w.method as MasterKeyWrapMethodEnum,
      wrappedKey: w.wrappedKey,
      kdfParams: w.kdfParams,
    })),
    recoveryMasterKeyTag: material.recoveryMasterKeyTag,
  }
  return { request, emergencyKit }
}

export async function registerServerOwner(
  email: string,
  password: string,
): Promise<{ userId: string; emergencyKit: EmergencyKit }> {
  // The auth keypair is derived with the instance-wide salt the server serves on GET /api/setup, so setup
  // and login derive identically (ADR 0003).
  const instanceSalt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
  const { request, emergencyKit } = buildCreateServerOwnerRequest(email, password, instanceSalt)
  const result = await setupApi().createServerOwner({ createServerOwnerRequest: request })
  return { userId: result.userId, emergencyKit }
}
