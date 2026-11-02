// The owner's half of a member invite (ADR 0111 decisions 1-2, uc.spaces.add-member): an invite is a placeholder member.
// The owner's client mints an invite key from a fresh seed, signs its public half with the owner's identity, wraps the
// tenant scope's current epoch key to it, and seals the seed — with the head of the owner's key chain and the owner's
// account — into the link's secret part under a generated code (or a password the owner chose). The grant it wraps is
// the invite's anchor (ADR 0111 decision 19): whatever a later rotation re-wraps, the invitee
// checks it against this one. The server receives the public key, the
// signature, the grant and the sealed part, mails the link and forgets the sealed part; the seed and the code never
// reach it. The owner passes the code on outside veyq: the operator mails the link, so a link alone must not open.
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { inviteLinkKekAsync } from '@veyq/crypto/argonWorker'
import { b64std, fromB64std, fromB64url } from '@veyq/crypto/base64'
import { identityPrivate } from '../../app/vault'
import { me } from './grantTrust'
import { listMyTenants, openScopeById } from './scopes'
import { zeroScopeKey } from './epochs'
import { recipientIdentityDigest, sealGrant } from './grant'
import { myChainHead } from '../account/tenantTrust'
import type { MemberInviteRequest } from '../../api/generated/tenants'

/** What opens the link: a code minted here (shown once, passed on by the owner), or a password the owner chose. A code may
 *  carry its link key's derivation already under way ({@link prepareInviteCode}). */
export type InviteSecret = { mode: 'code'; code: string; ahead?: LinkKekAhead } | { mode: 'password'; password: string }

/**
 * A link KEK whose Argon2id derivation is already running (inbox #566). At the production profile it is the one slow step of
 * building an invite — about a second, in the Argon2 worker — so the invite dialog mints its code and starts this as it
 * opens, while the owner types the address. Single-use: {@link sealMemberInvite} `take`s it and zeroes the KEK after
 * sealing; a second build (the stale-epoch retry) finds it taken and derives anew. One never taken is zeroed by `discard`.
 */
export interface LinkKekAhead {
  take(): { salt: Uint8Array; kek: Promise<Uint8Array> } | null
  discard(): void
}

/** A fresh code with its link KEK's derivation started now ({@link LinkKekAhead}) — the code it derives from is this one. */
export function prepareInviteCode(): { mode: 'code'; code: string; ahead: LinkKekAhead } {
  const code = invite.generateInviteCode()
  const secretBytes = invite.inviteLinkSecret('code', code)!
  const salt = v1.randomBytes(16)
  const kek = inviteLinkKekAsync(secretBytes, salt).finally(() => secretBytes.fill(0))
  kek.catch(() => undefined) // a derivation nobody takes must not surface as an unhandled rejection
  let settled = false
  return {
    mode: 'code',
    code,
    ahead: {
      take() {
        if (settled) return null
        settled = true
        return { salt, kek }
      },
      discard() {
        if (settled) return
        settled = true
        void kek.then((k) => k.fill(0), () => undefined)
      },
    },
  }
}

/** The invite lifetime the server takes when the switcher's row does not carry it (`veyq.tenant.invite.ttl`'s default). */
export const DEFAULT_INVITE_TTL_SECONDS = 7 * 24 * 60 * 60

/** The instance's member-invite lifetime, as the caller's tenant row serves it. */
async function inviteTtlSeconds(tenantId: string): Promise<number> {
  const row = (await listMyTenants()).find((t) => t.tenantId === tenantId)
  return row?.memberInviteTtlSeconds ?? DEFAULT_INVITE_TTL_SECONDS
}

/**
 * Build the member invite request for [email] into the tenant [tenantId]: the invite key, the owner's signature over
 * it (with the expiry the server will hold), a grant of the tenant's **current** epoch to it, and the link's sealed
 * part under [secret]. Needs the unlocked vault and the tenant's key; throws otherwise. The seed, the KEK and every
 * transient key are wiped before it returns.
 */
export async function buildMemberInvite(tenantId: string, email: string, secret: InviteSecret): Promise<MemberInviteRequest> {
  if (!invite.inviteLinkSecret(secret.mode, secret.mode === 'code' ? secret.code : secret.password)) {
    throw new Error('invite secret is not a code or a password')
  }
  const ttl = await inviteTtlSeconds(tenantId)
  // The head the link carries (ADR 0111 decision 19): this account's chain as served, walked to the identity this vault holds —
  // a chain that withholds this account's newest step is refused rather than sealed into a link.
  const { head } = await myChainHead()
  const opened = await openScopeById(tenantId) // the tenant scope's id IS the tenant id
  if (!opened) throw new Error('cannot open the tenant scope to invite — the vault is locked or no grant is held')
  const priv = identityPrivate()
  try {
    if (!priv) throw new Error('vault is locked — cannot invite')
    const self = await me(priv)
    return await sealMemberInvite(
      tenantId, email, secret, { userId: self.userId, pub: self.pub, head: fromB64std(head), signPrivateKey: priv.sign },
      { epoch: opened.epochs.epoch, key: opened.epochs.key }, ttl,
    )
  } finally {
    priv?.kemSeed.fill(0)
    priv?.sign.fill(0)
    zeroScopeKey(opened.epochs)
  }
}

/** Who signs an invite and grants it the tenant key: the owner's account, identity bundle, the head of their key chain at
 *  that identity (32 bytes) and signing key. */
export type InviteSigner = { userId: string; pub: v1.Identity['pub']; head: Uint8Array; signPrivateKey: Uint8Array }

/** The tenant key an invite's grant carries: the tenant scope's current epoch and its key. */
export type InviteEpoch = { epoch: number; key: Uint8Array }

/**
 * The invite itself, from what {@link buildMemberInvite} gathered — the owner as [signer], the tenant's current key as
 * [tenant], the instance's invite lifetime [ttlSeconds] — so a caller without a vault (the dev seeder) builds the very
 * same request. Wipes the seed, the KEK (a derivation started ahead included), the secret's bytes and the invite's private
 * key; the caller's keys stay its own.
 */
export async function sealMemberInvite(
  tenantId: string, email: string, secret: InviteSecret, signer: InviteSigner, tenant: InviteEpoch, ttlSeconds: number,
): Promise<MemberInviteRequest> {
  const secretBytes = invite.inviteLinkSecret(secret.mode, secret.mode === 'code' ? secret.code : secret.password)
  if (!secretBytes) throw new Error('invite secret is not a code or a password')
  const ahead = secret.mode === 'code' ? (secret.ahead?.take() ?? null) : null
  const seed = invite.generateInviteSeed()
  const inviteKey = invite.inviteKeyFromSeed(seed)
  const salt = ahead?.salt ?? v1.randomBytes(16)
  let kek: Uint8Array | null = null
  try {
    const inviteId = crypto.randomUUID()
    const invitePublicKey = v1.serializeIdentityPublic(inviteKey.pub)
    const invitePublicKeyB64 = b64std(invitePublicKey)
    const expiresAt = Math.floor(Date.now() / 1000) + ttlSeconds
    const inviteKeySignature = invite.signInviteKey(signer.signPrivateKey, { tenantId, inviteId, invitePublicKey, expiresAt })
    const epoch = tenant.epoch
    const grant = sealGrant(
      tenant.key, { scopeId: tenantId, kind: 'TENANT', epoch },
      { userId: inviteId, identityPublicKeyB64: invitePublicKeyB64 }, signer.signPrivateKey,
    )
    kek = await (ahead?.kek ?? inviteLinkKekAsync(secretBytes, salt))
    const sealed = invite.sealInviteLink({ tenantId, inviteId }, secret.mode, kek, salt, {
      seed, signerHead: signer.head, signerUserId: signer.userId,
    })
    return {
      email,
      inviteId,
      invitePublicKey: invitePublicKeyB64,
      inviteKeySignature: b64std(inviteKeySignature),
      expiresAt: new Date(expiresAt * 1000),
      // The wire carries opaque bytes as standard base64; the server re-encodes them for the link's fragment.
      sealedLink: b64std(fromB64url(sealed)),
      epoch,
      wrappedGrant: grant.wrappedGrant,
      grantSignature: grant.grantSignature,
      recipientIdentityDigest: recipientIdentityDigest(invitePublicKeyB64),
    }
  } finally {
    seed.fill(0)
    inviteKey.priv.kemSeed.fill(0)
    inviteKey.priv.sign.fill(0)
    kek?.fill(0)
    // A derivation taken but never awaited (a step before it threw) is zeroed once it lands.
    if (!kek && ahead) void ahead.kek.then((k) => k.fill(0), () => undefined)
    secretBytes.fill(0)
  }
}
