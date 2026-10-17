// uc.admin.setup-server (web) — cold E2EE registration of the first Server Owner on the operator-blind
// (Model B) data layer. ALL key material is born here; the server only ever receives ciphertext + public
// keys + low-sensitivity metadata. Unlike the legacy Model-A flow, the raw password is NEVER sent: the
// client derives an Ed25519 auth keypair (ADR 0003) and registers only `authPublicKey`.
import { fromB64std } from '@veyq/crypto/base64'
import { setupApi } from '../../api/client'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { epochOneFor } from '../instance/moderation'
import { operatingModeRequest } from '../instance/operator'
import {
  type CreateServerOwnerRequest,
  CreateServerOwnerRequestSuiteIdEnum,
  type MasterKeyWrapMethodEnum,
  type OperatingMode,
} from '../../api/generated'

export type EmergencyKit = { secretKey: string }

/** The account as minted on this device: everything the setup request carries but the operating mode, which the
 *  Server Owner chooses only after the kit is confirmed (uc.admin.setup-server step 4). */
export type PreparedServerOwner = Omit<CreateServerOwnerRequest, 'operatingMode'>

/** Build the request (keys born here via the shared cold account-birth). Returns the request + the Emergency
 *  Kit to show the user. */
export async function buildCreateServerOwnerRequest(
  email: string,
  password: string,
  instanceSalt: Uint8Array,
): Promise<{ request: PreparedServerOwner; emergencyKit: EmergencyKit }> {
  const { material, emergencyKit } = await buildAccount(email, password, instanceSalt)
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
 * Create the Server Owner from a [prepareServerOwner] request — after its kit was confirmed — with the operating mode
 * the Server Owner chose (ADR 0093 § 14). *Private* needs the notice [accepted]: without it nothing is posted
 * (`NoticeNotAccepted`), so setup cannot finish as *private* without the tick. This closes setup (single-owner lock);
 * 409 `setup_already_complete` when someone else finished it first.
 */
export async function createServerOwner(
  request: PreparedServerOwner,
  mode: OperatingMode,
  accepted: boolean,
): Promise<{ userId: string }> {
  const operatingMode = operatingModeRequest(mode, accepted)
  const result = await setupApi().createServerOwner({ createServerOwnerRequest: { ...request, operatingMode } })
  return { userId: result.userId }
}
