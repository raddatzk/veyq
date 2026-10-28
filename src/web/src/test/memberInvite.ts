// A member invite as the server serves it to the invitee (`InvitePreview.invite`) and the link's sealed part, built the
// way an owner's client builds one (`sealMemberInvite`): an invite key from a fresh seed, the owner's signature over it, a
// grant of the tenant key to it signed by the owner — the invite's anchor — and the seed sealed under a code with the
// head of the owner's real key chain (`src/test/keyChain.ts`). Beside it, what later happens to an invite as the server
// serves it: a rotation carries it to a later epoch ({@link carriedTo}), or its owner secures their account and re-signs
// it ({@link ownerSecured}). For the invitee-side tests (`tenantInvite`, `TenantJoinView`), which then break exactly one
// part of it per negative.
//
// The link's KEK is real Argon2id at `TEST_ARGON`: a suite using this fixture mocks `inviteLinkKekAsync` to the same
// cost, so the invitee's open stays input-sensitive (a wrong code derives another KEK) at a test's price.
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { TEST_ARGON } from '@veyq/crypto/testArgon'
import { b64std } from '@veyq/crypto/base64'
import { sealGrant, signGrant } from '../features/spaces/grant'
import { chainKeys, genesis, headOf, step, type BuiltChain } from './keyChain'
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
  /** The tenant key of every epoch so far (index = epoch) — the test's oracle. */
  keys: Uint8Array[]
  inviteKey: v1.Identity
  /** The inviting owner's CURRENT identity — the newest key of `ownerChain`. */
  owner: v1.Identity
  ownerId: string
  ownerChain: BuiltChain
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

/** [signer]'s `grant` signature over the invite's grant [wrappedGrant] at [epoch] — a re-signature of the same bytes. */
export const signedInviteGrant = (f: MemberInviteFixture, epoch: number, wrappedGrant: string, signer: v1.Identity): string =>
  signGrant(signer.priv.sign, { scopeId: f.tenantId, kind: 'TENANT', epoch }, { userId: f.served.inviteId, identityPublicKeyB64: f.served.invitePublicKey }, wrappedGrant)

/** The link epoch [epoch] of the tenant scope stores: `E_{epoch-1}` sealed under `E_epoch` (ADR 0024), base64. */
export const epochLink = (tenantId: string, epoch: number, keys: Uint8Array[]): { epoch: number; wrappedPrev: string } => ({
  epoch, wrappedPrev: b64std(v1.seal({ purpose: 'scope-epoch', scopeId: tenantId, epoch }, keys[epoch], keys[epoch - 1])),
})

/** A genuine member invite by a fresh owner into [tenantId] at [epoch], sealed under a fresh code: its grant is its
 *  anchor, no epoch links. */
export function memberInviteFixture(o: { tenantId?: string; epoch?: number } = {}): MemberInviteFixture {
  const tenantId = o.tenantId ?? '7e000000-0000-4000-8000-0000000000f1'
  const epoch = o.epoch ?? 0
  const ownerId = '0a000000-0000-4000-8000-0000000000f0'
  const ownerChain = genesis(ownerId, chainKeys())
  const owner = ownerChain.keys[0].identity
  const inviteId = crypto.randomUUID()
  const seed = invite.generateInviteSeed()
  const inviteKey = invite.inviteKeyFromSeed(seed)
  const keys = Array.from({ length: epoch + 1 }, () => v1.randomBytes(32))
  const expiresAt = new Date((Math.floor(Date.now() / 1000) + 3600) * 1000)
  const code = invite.generateInviteCode()
  const salt = v1.randomBytes(16)
  const kek = v1.kekInviteLink(new TextEncoder().encode(code), salt, TEST_ARGON)
  const sealedPart = invite.sealInviteLink({ tenantId, inviteId }, 'code', kek, salt, {
    seed, signerHead: ownerChain.head, signerUserId: ownerId,
  })
  const anchor = inviteGrant(tenantId, epoch, keys[epoch], inviteId, inviteKey, owner)
  const served: ServedMemberInvite = {
    inviteId,
    invitePublicKey: bundleOf(inviteKey),
    inviteKeySignature: signedInviteKey(owner, { tenantId, inviteId, expiresAt }, inviteKey),
    expiresAt,
    signerUserId: ownerId,
    signerIdentityPublicKey: bundleOf(owner),
    signerKeyChain: ownerChain.steps,
    anchorWrappedGrant: anchor.wrappedGrant,
    anchorEpoch: epoch,
    anchorGrantSignature: anchor.grantSignature,
    ...anchor,
    granterUserId: ownerId,
    granterIdentityPublicKey: bundleOf(owner),
    granterKeyChainHead: headOf(ownerChain),
    epochLinks: [],
  }
  return { tenantId, served, sealedPart, code, tenantKey: keys[epoch], keys, inviteKey, owner, ownerId, ownerChain }
}

/**
 * [f]'s invite as a rotation by [granter] (another member by default) carried it to epoch [to]: fresh tenant keys up to
 * there, the scope's links from the anchor up, and the carried grant at [to] signed by the granter — the anchor stays.
 */
export function carriedTo(
  f: MemberInviteFixture, to: number, granter: { userId: string; identity: v1.Identity; head: string } = foreignGranter(),
): MemberInviteFixture {
  const keys = [...f.keys]
  for (let e = keys.length; e <= to; e++) keys.push(v1.randomBytes(32))
  const epochLinks = []
  for (let e = f.served.anchorEpoch + 1; e <= to; e++) epochLinks.push(epochLink(f.tenantId, e, keys))
  const served: ServedMemberInvite = {
    ...f.served,
    ...inviteGrant(f.tenantId, to, keys[to], f.served.inviteId, f.inviteKey, granter.identity),
    granterUserId: granter.userId,
    granterIdentityPublicKey: bundleOf(granter.identity),
    granterKeyChainHead: granter.head,
    epochLinks,
  }
  return { ...f, served, keys, tenantKey: keys[to] }
}

/** Another member of the tenant, born fresh — a rotator whose key nothing anchors for the newcomer. */
export function foreignGranter(userId = '0a000000-0000-4000-8000-0000000000f9'): { userId: string; identity: v1.Identity; head: string } {
  const chain = genesis(userId, chainKeys())
  return { userId, identity: chain.keys[0].identity, head: headOf(chain) }
}

/**
 * [f]'s invite after its owner secured their account (cr-trust-anchors-survive-rotation decision 6): their chain has one
 * more step to a new key, which re-signed the invite key and the anchor grant — and the carried grant too where it is the
 * anchor. The link still carries the head from before: the invite was minted then.
 */
export function ownerSecured(f: MemberInviteFixture): MemberInviteFixture {
  const ownerChain = step(f.ownerChain, chainKeys())
  const owner = ownerChain.keys.at(-1)!.identity
  const s = f.served
  const carriedIsAnchor = s.grantedEpoch === s.anchorEpoch && s.wrappedGrant === s.anchorWrappedGrant
  const anchorGrantSignature = signedInviteGrant(f, s.anchorEpoch, s.anchorWrappedGrant, owner)
  const served: ServedMemberInvite = {
    ...s,
    inviteKeySignature: signedInviteKey(owner, { tenantId: f.tenantId, inviteId: s.inviteId, expiresAt: s.expiresAt }, f.inviteKey),
    signerIdentityPublicKey: bundleOf(owner),
    signerKeyChain: ownerChain.steps,
    anchorGrantSignature,
    ...(carriedIsAnchor
      ? { grantSignature: anchorGrantSignature, granterIdentityPublicKey: bundleOf(owner), granterKeyChainHead: headOf(ownerChain) }
      : {}),
  }
  return { ...f, served, owner, ownerChain }
}
