// Tenant-invite ACCEPT side (uc.admin.create-tenant Slice B / cr-0010) — the half of tenant administration an
// **anonymous invitee or an ordinary signed-in user** reaches, not the Server Owner. It stays in `src/web/` while
// the Server-Owner administration surface lives in `src/admin-web/` (ADR 0065): a person accepting an invite has no
// business loading the admin app. Thin facade over the generated tenants client: views depend on this typed
// surface, never on the generator runtime.
import { tenantsApi } from '../../api/client'
import type { InvitePreview, InviteSelfGrant, ServedMemberInvite, TenantActivated, TenantActivatedWithUser } from '../../api/generated/tenants'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { inviteLinkKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { AccountMaterial } from '@veyq/crypto/accountBirth'
import { recipientIdentityDigest, sealGrant } from '../spaces/grant'
import { anchorChainKey, anchorServedChain, getPin, linkOf } from '../account/identityPins'
import { holdEpochKey } from '../account/trustMarks'
import { HttpStatus, isCodedError } from '../../shared/httpError'

export type { InvitePreview } from '../../api/generated/tenants'
export { tenantError } from './tenantError'

/** Preview an invite from its bootstrap secret (public — no session). */
export function previewInvite(bootstrapSecret: string): Promise<InvitePreview> {
  return tenantsApi().previewTenantInvite({ acceptPreviewRequest: { bootstrapSecret } })
}

/** Accept as an existing (signed-in) user, choosing a `username` for this tenant (ADR 0023). An OWNER invite
 *  carries the cold birth; a MEMBER invite carries its join ({@link buildMemberJoin}): the self-grant, the invite key's
 *  proof and the sealed join record (ADR 0111 decision 3). */
export function acceptInvite(
  bootstrapSecret: string,
  username: string,
  birth?: TenantBirth,
  sealedName?: string,
  sealedAvatar?: string,
  join?: MemberJoin,
): Promise<TenantActivated> {
  return tenantsApi().acceptTenantInvite({
    acceptInviteRequest: { bootstrapSecret, username, ...birth, ...join, sealedName, sealedAvatar, epoch: profileEpoch(birth, join) },
  })
}

/** The tenant epoch an accept's sealed profile fields (the join record, the name, the picture) are sealed under — the
 *  one the server checks against the current one (ADR 0115 decision 3): a MEMBER join's key's, an OWNER birth's 0. */
function profileEpoch(birth?: TenantBirth, join?: MemberJoin): number | undefined {
  return join ? join.epoch : birth ? 0 : undefined
}

/** Accept an owner invite into an ACTIVE tenant whose member the signed-in caller already is (`InvitePreview.handover`,
 *  ADR 0101 decision 9): they become an owner. Nothing is born and no username is chosen — the member holds the tenant's
 *  key and its handle already. */
export function acceptOwnership(bootstrapSecret: string): Promise<void> {
  return tenantsApi().acceptTenantOwnership({ acceptOwnershipRequest: { bootstrapSecret } })
}

/** Register a new account cold and accept in one step (public — the email comes from the invite). `username` is
 *  the caller's per-tenant handle; `sealedName` is the OPTIONAL real name sealed under `K_tenant` at join (ADR 0023)
 *  and `sealedAvatar` the OPTIONAL profile picture sealed alongside it (ADR 0084 decision 7) — every joiner holds
 *  `K_tenant` here, from the cold birth or its invite's grant. `birth` is set for an OWNER invite (cold-births the
 *  TENANT scope), `join` for a MEMBER invite (ADR 0111 decision 3). */
export function registerAndAccept(
  bootstrapSecret: string,
  userId: string,
  username: string,
  material: AccountMaterial,
  birth?: TenantBirth,
  sealedName?: string,
  sealedAvatar?: string,
  join?: MemberJoin,
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
      ...join,
      sealedName,
      sealedAvatar,
      epoch: profileEpoch(birth, join),
    },
  })
}

/** What an OWNER accept carries for the tenant's cold birth: the first grant, the owner's self-signature over it
 *  (ADR 0028 Decision 6) and the digest of the key it is wrapped to (ADR 0111 decision 10: the server refuses a grant to a
 *  key that is not the recipient's current one). The first owner is the tenant's trust root (ADR 0111 decision 3): no
 *  join record. */
export type TenantBirth = {
  grant: string
  grantSignature: string
  recipientIdentityDigest: string
}

/** The owner an OWNER accept births the tenant for: their account id and their identity (the signing half signs the
 *  grant, the public bundle is what the grant is sealed to). */
export type TenantOwner = { userId: string; identity: { pub: v1.Identity['pub']; priv: { sign: Uint8Array } } }

/**
 * Cold-birth the TENANT scope: mint a fresh scope root key (`K_tenant`), and return **both** the birth (the key
 * sealed to the owner's own identity and signed by it — opaque to the server) **and the key itself**, so the owner can
 * immediately seal their real name under it at join (cr-0106). The key never leaves the device; only the sealed grant,
 * its signature + (optional) sealed name cross the API.
 */
export function bornTenantScope(owner: TenantOwner, tenantId: string): { birth: TenantBirth; scopeRootKey: Uint8Array } {
  const scopeRootKey = v1.randomBytes(32)
  // The tenant scope's id IS the tenant id, so it is known before the birth; the grant is born at epoch 0 (ADR 0072).
  const scope = { scopeId: tenantId, kind: 'TENANT', epoch: 0 } as const
  const to = { userId: owner.userId, identityPublicKeyB64: b64std(v1.serializeIdentityPublic(owner.identity.pub)) }
  const signed = sealGrant(scopeRootKey, scope, to, owner.identity.priv.sign)
  const birth = {
    grant: signed.wrappedGrant, grantSignature: signed.grantSignature, recipientIdentityDigest: recipientIdentityDigest(to.identityPublicKeyB64),
  }
  return { birth, scopeRootKey }
}

/** Just the birth from a fresh tenant-scope birth (for callers that don't seal a name). */
export function scopeGrant(owner: TenantOwner, tenantId: string): TenantBirth {
  return bornTenantScope(owner, tenantId).birth
}

// ── A member invite: the invitee's half (ADR 0111 decisions 1 and 3) ──────────────────────────────────────────

/** A MEMBER accept's join, as the wire carries it: the self-grant, the invite key's proof, the sealed join record, the
 *  digest of the inviting owner's key the record names — the key this client checked, which the server compares with
 *  the invite's stored signer key (`409 invite_changed`, ADR 0111 decision 20) — and the tenant epoch the record (and
 *  the name and picture beside it) is sealed under (`409 stale_epoch` when a rotation moved past it, ADR 0115
 *  decision 3). */
export type MemberJoin = { selfGrant: InviteSelfGrant; inviteProof: string; sealedJoinRecord: string; signerIdentityDigest: string; epoch: number }

/** The code (or password) does not open the link's sealed part — mistyped, or another invite's link. */
export class InviteCodeWrong extends Error {
  constructor() {
    super('invite_code_wrong')
    this.name = 'InviteCodeWrong'
  }
}

export { InviteCheckFailed } from '@veyq/crypto/invite'

/** A member invite the invitee's client opened and checked: the invite key, the tenant key its grant carries (at
 *  [grantedEpoch]), the inviting owner as the check walked their chain, and the served facts the checks vouched for.
 *  Transient: zero it with {@link forgetMemberInvite}. */
export type OpenedMemberInvite = {
  tenantId: string
  served: ServedMemberInvite
  inviteKey: v1.Identity
  epochKey: Uint8Array
  grantedEpoch: number
  /** Where the inviting owner's served chain ended, walked from the head inside the link: its head, index and key —
   *  the key the join record names and the accept's `signerIdentityDigest` states. */
  signer: { head: Uint8Array; index: number; identityPublicKey: Uint8Array }
  /** What the link's sealed part held, kept so a `409 invite_changed` can re-run the checks without the code
   *  ({@link acceptCheckedInvite}): the seed (as secret as the invite key it derives, zeroed with it), the owner's head
   *  and account (not secret). */
  link: invite.InviteLinkPayload
  /** The member whose rotation re-wrapped the invite's grant above its anchor, when it is not the inviting owner: their
   *  key as the grant's snapshot carries it, and the head the invite serves for them. Nothing vouches for it — the epoch
   *  link down to the owner's anchor vouches for the grant — so it is only judged where this account pinned them. */
  foreignGranter: { userId: string; identityPublicKey: string; keyChainHead: string } | null
}

/** How the link's sealed part asks to be opened — a generated code or a password — or null for a part that is no
 *  sealed invite (the link is then not one this client can check). */
export function inviteLinkMode(sealedPart: string): invite.InviteLinkMode | null {
  try {
    return invite.parseSealedInviteLink(sealedPart).mode
  } catch {
    return null
  }
}

/**
 * Open and check a member invite before anything is sent (ADR 0111 decisions 1 and
 * 19): open the link's sealed part with the typed code or password ({@link InviteCodeWrong} if it does not open), then
 * {@link invite.checkInvite} — the key the seed derives is the served one, the owner's served chain passes through the
 * head inside the seal and ends at the served owner key, which signed the invite key and the anchor grant, and the tenant
 * key is the anchor's or one the tenant's succession statements lead to from it ({@link invite.InviteCheckFailed} naming the
 * check otherwise). Nothing the result relies on comes from the server unchecked.
 */
export async function openMemberInvite(tenantId: string, served: ServedMemberInvite, sealedPart: string, typed: string): Promise<OpenedMemberInvite> {
  let link: invite.SealedInviteLink
  try {
    link = invite.parseSealedInviteLink(sealedPart)
  } catch {
    throw new invite.InviteCheckFailed('key_mismatch')
  }
  const secret = invite.inviteLinkSecret(link.mode, typed)
  if (!secret) throw new InviteCodeWrong()
  const kek = await inviteLinkKekAsync(secret, link.salt, link.params)
  let payload: invite.InviteLinkPayload
  try {
    payload = invite.openInviteLink({ tenantId, inviteId: served.inviteId }, kek, link)
  } catch {
    throw new InviteCodeWrong()
  } finally {
    kek.fill(0)
    secret.fill(0)
  }
  try {
    return checkMemberInvite(tenantId, served, payload)
  } finally {
    payload.seed.fill(0)
  }
}

/** {@link invite.checkInvite} over what the preview served, decoded; the opened invite keeps a copy of the link's facts.
 *  A served byte string that is no base64 is refused as no invite the link names (`key_mismatch`). */
function checkMemberInvite(tenantId: string, served: ServedMemberInvite, payload: invite.InviteLinkPayload): OpenedMemberInvite {
  let servedInvite: invite.ServedInvite
  try {
    servedInvite = {
      tenantId,
      inviteId: served.inviteId,
      invitePublicKey: fromB64std(served.invitePublicKey),
      inviteKeySignature: fromB64std(served.inviteKeySignature),
      expiresAt: Math.floor(served.expiresAt.getTime() / 1000),
      signerUserId: served.signerUserId,
      signerIdentityPublicKey: fromB64std(served.signerIdentityPublicKey),
      signerKeyChain: served.signerKeyChain.map(linkOf),
      anchorWrappedGrant: fromB64std(served.anchorWrappedGrant),
      anchorEpoch: served.anchorEpoch,
      anchorGrantSignature: fromB64std(served.anchorGrantSignature),
      wrappedGrant: fromB64std(served.wrappedGrant),
      grantedEpoch: served.grantedEpoch,
      grantSignature: fromB64std(served.grantSignature),
      granterIdentityPublicKey: fromB64std(served.granterIdentityPublicKey),
      successions: served.successions.map((s) => ({
        epoch: s.epoch, successionPublicKey: fromB64std(s.successionPublicKey), successionSignature: fromB64std(s.successionSignature),
      })),
    }
  } catch {
    throw new invite.InviteCheckFailed('key_mismatch')
  }
  const checked = invite.checkInvite(payload, servedInvite)
  // A grant a rotation re-wrapped above the anchor names its rotator as granter. The succession statements vouch for it;
  // the rotator is handed back only so a client that pinned them judges them ({@link memberInviteAnchored}).
  const foreign = served.grantedEpoch > served.anchorEpoch && served.granterUserId !== served.signerUserId
  return {
    tenantId,
    served,
    inviteKey: checked.inviteKey,
    epochKey: checked.epochKey,
    grantedEpoch: checked.grantedEpoch,
    signer: checked.signer,
    link: { seed: payload.seed.slice(), signerHead: payload.signerHead.slice(), signerUserId: payload.signerUserId },
    foreignGranter: foreign
      ? { userId: served.granterUserId, identityPublicKey: served.granterIdentityPublicKey, keyChainHead: served.granterKeyChainHead }
      : null,
  }
}

/**
 * Whether a signed-in account may accept an opened member invite against its pin store (ADR 0028 Decision 2,
 * ADR 0111 decision 19). The account is not yet a co-member, so the server serves it nobody's
 * key chain through `getKeyChain`:
 * - **the inviting owner** is judged by the chain the invite served, which {@link openMemberInvite} walked from the head
 *   inside the link to the served key ({@link anchorServedChain}): a pinned head must lie on it and carry the pinned key,
 *   and a first use pins the walked head at its real index;
 * - **a foreign granter** (a rotation re-wrapped the grant) the account already pinned is judged against its chain like
 *   any granter: a key the chain does not reach, or a retired key of it, is refused. One it never met is NOT pinned
 *   here: the epoch link down to the owner's anchor, not its key, vouches for the grant, and the grant's snapshot may be
 *   a key its granter has since retired, which beside their current head would pin a pair that never was; the account
 *   pins them through their chain at its first contact as a co-member.
 */
export async function memberInviteAnchored(opened: OpenedMemberInvite): Promise<boolean> {
  const granter = opened.foreignGranter
  if (granter && (await getPin(granter.userId))) {
    const judged = await anchorChainKey(granter.userId, granter.identityPublicKey)
    if (judged.anchor === 'anomaly' || judged.retired) return false
  }
  const head = b64std(opened.signer.head)
  const chain = { userId: opened.served.signerUserId, head, steps: opened.served.signerKeyChain }
  const signer = { identityPublicKey: b64std(opened.signer.identityPublicKey), keyChainHead: head }
  return (await anchorServedChain(opened.served.signerUserId, chain, signer)) !== 'anomaly'
}

/**
 * Send a member accept built from [opened] (ADR 0111 decision 20). When the server answers `409
 * invite_changed` — the inviting owner secured their account between this client's check and the accept, so the join
 * record would name a key their securing retired — the preview is read again through [reread], the checks run again with
 * the link's facts [opened] kept (no code is asked for again), [anchored] judges the re-checked invite where the account
 * has a pin store, and [send] goes once more with it. Once: a second refusal is the caller's. [send] builds everything it
 * seals under the tenant key from the invite it is handed. An invite re-opened here is zeroed before this returns;
 * [opened] stays the caller's to forget.
 *
 * **The accepted key is held** (ADR 0111 decision 23, inbox #740): once the server took the accept, the tenant key the
 * checks vouched for - of the invite actually sent, the re-checked one after a `409` - becomes the key this device holds
 * for the tenant, so its first open takes no epoch that does not link down to it. Without it the device would hold
 * nothing until that open and take the first grant served as it comes. Under the vault's Master Key, or under
 * [accountKey] for a new account the accept registers (the vault holds none yet). Nothing is held for an accept that
 * failed.
 */
export async function acceptCheckedInvite<T>(
  opened: OpenedMemberInvite,
  send: (opened: OpenedMemberInvite) => Promise<T>,
  reread: () => Promise<ServedMemberInvite | null | undefined>,
  anchored?: (opened: OpenedMemberInvite) => Promise<boolean>,
  accountKey?: Uint8Array,
): Promise<T> {
  const sendAndHold = async (checked: OpenedMemberInvite): Promise<T> => {
    const accepted = await send(checked)
    await holdEpochKey(checked.tenantId, checked.grantedEpoch, checked.epochKey, accountKey)
    return accepted
  }
  try {
    return await sendAndHold(opened)
  } catch (e) {
    // the owner secured their account since the check (`invite_changed`), or a rotation carried the invite to a new epoch
    // and re-sealed the tenant under its new key (`stale_epoch`, ADR 0115 decision 8): re-read, check again, seal again
    if (!(await isCodedError(e, HttpStatus.conflict, 'invite_changed')) && !(await isCodedError(e, HttpStatus.conflict, 'stale_epoch'))) throw e
  }
  const served = await reread()
  if (!served || served.inviteId !== opened.served.inviteId) throw new invite.InviteCheckFailed('key_mismatch')
  const again = checkMemberInvite(opened.tenantId, served, opened.link)
  try {
    if (anchored && !(await anchored(again))) throw new invite.InviteCheckFailed('signer_chain_mismatch')
    return await sendAndHold(again)
  } finally {
    forgetMemberInvite(again)
  }
}

/** Zero an opened invite's private parts. */
export function forgetMemberInvite(opened: OpenedMemberInvite): void {
  opened.epochKey.fill(0)
  opened.inviteKey.priv.kemSeed.fill(0)
  opened.inviteKey.priv.sign.fill(0)
  opened.link.seed.fill(0)
}

/** The newcomer as its join needs it: the account id, its identity, and its Secret-Key proof public key (raw bytes). */
export type Newcomer = {
  userId: string
  identity: { pub: v1.Identity['pub']; priv: { sign: Uint8Array } }
  secretKeyProofPublicKey: Uint8Array
}

/**
 * The newcomer grants itself and writes its join record (ADR 0111 decision 3): the invite grant's epoch key sealed to
 * its own identity and signed by it; the invite key's proof over (invite, account, identity) that the server checks;
 * and the join record — identity and Secret-Key proof key, the owner-signed invite it came through — signed by the
 * invite key and by the new identity, sealed under the tenant key as the field `member-join-record`; and the digest of the
 * owner key the record names — the one the checks walked to (ADR 0111 decision 20).
 */
export function buildMemberJoin(opened: OpenedMemberInvite, newcomer: Newcomer): MemberJoin {
  const { tenantId, served, inviteKey, epochKey, grantedEpoch, signer } = opened
  const identityPublicKey = v1.serializeIdentityPublic(newcomer.identity.pub)
  const identityB64 = b64std(identityPublicKey)
  const signed = sealGrant(
    epochKey, { scopeId: tenantId, kind: 'TENANT', epoch: grantedEpoch },
    { userId: newcomer.userId, identityPublicKeyB64: identityB64 }, newcomer.identity.priv.sign,
  )
  const inviteProof = invite.signInviteAccept(inviteKey.priv.sign, { inviteId: served.inviteId, userId: newcomer.userId, identityPublicKey })
  const address = { tenantId, userId: newcomer.userId }
  const record = invite.signJoinRecord(address, {
    identityPublicKey,
    secretKeyProofPublicKey: newcomer.secretKeyProofPublicKey,
    inviteId: served.inviteId,
    invitePublicKey: fromB64std(served.invitePublicKey),
    inviteKeySignature: fromB64std(served.inviteKeySignature),
    inviteExpiresAt: Math.floor(served.expiresAt.getTime() / 1000),
    signerUserId: served.signerUserId,
    signerIdentityPublicKey: signer.identityPublicKey,
  }, inviteKey.priv.sign, newcomer.identity.priv.sign)
  return {
    selfGrant: { ...signed, epoch: grantedEpoch, recipientIdentityDigest: recipientIdentityDigest(identityB64) },
    inviteProof: b64std(inviteProof),
    sealedJoinRecord: b64std(invite.sealJoinRecord(epochKey, address, record)),
    signerIdentityDigest: b64std(v1.sha256Digest(signer.identityPublicKey)),
    epoch: grantedEpoch,
  }
}
