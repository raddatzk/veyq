// A member invite as the server serves it to the invitee (`InvitePreview.invite`) and the link's sealed part, built the
// way an owner's client builds one (`sealMemberInvite`): an invite key from a fresh seed, the owner's signature over it, a
// grant of the tenant key to it signed by the owner, and the seed sealed under a code. For the invitee-side tests
// (`tenantInvite`, `TenantJoinView`), which then break exactly one part of it per negative.
//
// The link's KEK is real Argon2id at `TEST_ARGON`: a suite using this fixture mocks `inviteLinkKekAsync` to the same
// cost, so the invitee's open stays input-sensitive (a wrong code derives another KEK) at a test's price.
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { TEST_ARGON } from '@veyq/crypto/testArgon'
import { b64std } from '@veyq/crypto/base64'
import { sealGrant } from '../features/spaces/grant'
import { genesisHeadOf } from './keyChain'
import type { ServedMemberInvite } from '../api/generated/tenants'

export type MemberInviteFixture = {
  tenantId: string
  /** What the preview serves — every byte of it the invitee's client checks before it trusts it. */
  served: ServedMemberInvite
  /** The fragment's sealed part (`#<token>.<sealed>`). */
  sealedPart: string
  /** The code the owner passed on (canonical). */
  code: string
  /** The tenant key the invite's grant carries, at `served.grantedEpoch`. */
  tenantKey: Uint8Array
  inviteKey: v1.Identity
  owner: v1.Identity
  ownerId: string
}

/** The invite key's public bundle, base64 — what `invitePublicKey` carries. */
export const bundleOf = (id: v1.Identity): string => b64std(v1.serializeIdentityPublic(id.pub))

/** The owner's signature over [inviteKey] for this invite, by [signer] — base64, as `inviteKeySignature` carries it. */
export function signedInviteKey(
  signer: v1.Identity, a: { tenantId: string; inviteId: string; expiresAt: Date }, inviteKey: v1.Identity,
): string {
  const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
  return b64std(invite.signInviteKey(signer.priv.sign, { ...a, invitePublicKey, expiresAt: a.expiresAt.getTime() / 1000 }))
}

/** A grant of [key] at [epoch] to [inviteKey] (the invite id its recipient), signed by [granter] — the served grant fields. */
export function inviteGrant(
  tenantId: string, epoch: number, key: Uint8Array, inviteId: string, inviteKey: v1.Identity, granter: v1.Identity,
): Pick<ServedMemberInvite, 'wrappedGrant' | 'grantSignature' | 'grantedEpoch'> {
  const g = sealGrant(key, { scopeId: tenantId, kind: 'TENANT', epoch }, { userId: inviteId, identityPublicKeyB64: bundleOf(inviteKey) }, granter.priv.sign)
  return { ...g, grantedEpoch: epoch }
}

/** A genuine member invite by a fresh owner into [tenantId] at [epoch], sealed under a fresh code. */
export function memberInviteFixture(o: { tenantId?: string; epoch?: number } = {}): MemberInviteFixture {
  const tenantId = o.tenantId ?? '7e000000-0000-4000-8000-0000000000f1'
  const epoch = o.epoch ?? 0
  const owner = v1.generateIdentity()
  const ownerId = '0a000000-0000-4000-8000-0000000000f0'
  const inviteId = crypto.randomUUID()
  const seed = invite.generateInviteSeed()
  const inviteKey = invite.inviteKeyFromSeed(seed)
  const tenantKey = v1.randomBytes(32)
  const expiresAt = new Date((Math.floor(Date.now() / 1000) + 3600) * 1000)
  const code = invite.generateInviteCode()
  const salt = v1.randomBytes(16)
  const kek = v1.kekInviteLink(new TextEncoder().encode(code), salt, TEST_ARGON)
  const sealedPart = invite.sealInviteLink({ tenantId, inviteId }, 'code', kek, salt, {
    seed, signerFingerprintHash: v1.fingerprintHash(owner.pub), signerUserId: ownerId,
  })
  const served: ServedMemberInvite = {
    inviteId,
    invitePublicKey: bundleOf(inviteKey),
    inviteKeySignature: signedInviteKey(owner, { tenantId, inviteId, expiresAt }, inviteKey),
    expiresAt,
    signerUserId: ownerId,
    signerIdentityPublicKey: bundleOf(owner),
    signerKeyChainHead: genesisHeadOf(ownerId, owner),
    ...inviteGrant(tenantId, epoch, tenantKey, inviteId, inviteKey, owner),
    granterUserId: ownerId,
    granterIdentityPublicKey: bundleOf(owner),
    granterKeyChainHead: genesisHeadOf(ownerId, owner),
  }
  return { tenantId, served, sealedPart, code, tenantKey, inviteKey, owner, ownerId }
}
