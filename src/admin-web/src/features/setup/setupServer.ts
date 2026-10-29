// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner on the operator-blind
// (Model B) data layer. ALL key material is born here; the server only ever receives ciphertext + public
// keys + low-sensitivity metadata. Unlike the legacy Model-A flow, the raw password is NEVER sent: the
// client derives an Ed25519 auth keypair (ADR 0003) and registers only `authPublicKey`.
import { fromB64std } from '@veyq/crypto/base64'
import { setupApi } from '../../api/client'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { epochOneFor } from '../instance/moderation'
import {
  type CreateServerOwnerRequest,
  CreateServerOwnerRequestSuiteIdEnum,
  type MasterKeyWrapMethodEnum,
} from '../../api/generated'

export type EmergencyKit = { secretKey: string }

/** The account as minted on this device: the whole setup request, posted only after the kit is confirmed
 *  (uc.admin.setup-server E1). Setup asks for no operator value (ADR 0093 § 14). */
export type PreparedServerOwner = CreateServerOwnerRequest

/** Build the request (keys born here via the shared cold account-birth). Returns the request + the Emergency
 *  Kit to show the user. */
export async function buildCreateServerOwnerRequest(
  email: string,
  password: string,
  instanceSalt: Uint8Array,
): Promise<{ request: PreparedServerOwner; emergencyKit: EmergencyKit }> {
  const { material, emergencyKit, masterKey } = await buildAccount(email, password, instanceSalt)
  // The setup accepts no tenant, so it keys no trust mark (ADR 0111 decision 23): the Master Key is not needed here.
  masterKey.fill(0)
  // The Server-Owner account carries no display name — identity is a per-tenant username (ADR 0023), and a
  // Server Owner who is not (yet) a tenant member is identified by email alone.
  const request: PreparedServerOwner = {
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
    secretKeyProofPublicKey: material.secretKeyProofPublicKey,
    // Epoch 1 of the moderation key, sealed to the identity born just above (ADR 0099 § 3): reporting is on from day 1.
    moderationEpoch: epochOneFor(material.identityPublicKey),
  }
  return { request, emergencyKit }
}

/**
 * Mint the Server Owner's account on this device and send NOTHING yet (uc.admin.setup-server E1): the kit is shown
 * and confirmed first, and only then [createServerOwner] posts the request. Posting first locked setup with an
 * owner whose Secret Key nobody had saved if the tab closed on the kit screen — the member flow already ran in this
 * order (web's TenantJoinView: `registerAndAccept` waits for the confirmed kit).
 */
export async function prepareServerOwner(
  email: string,
  password: string,
): Promise<{ request: PreparedServerOwner; emergencyKit: EmergencyKit }> {
  // The auth keypair is derived with the instance-wide salt the server serves on GET /api/setup, so setup
  // and login derive identically (ADR 0003).
  const instanceSalt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
  return buildCreateServerOwnerRequest(email, password, instanceSalt)
}

/**
 * Create the Server Owner from a [prepareServerOwner] request — after its kit was confirmed. This closes setup
 * (single-owner lock); 409 `setup_already_complete` when someone else finished it first.
 */
export async function createServerOwner(request: PreparedServerOwner): Promise<{ userId: string }> {
  const result = await setupApi().createServerOwner({ createServerOwnerRequest: request })
  return { userId: result.userId }
}
