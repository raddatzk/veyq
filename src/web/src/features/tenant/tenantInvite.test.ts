import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock the contracts client so the facade's network calls are controllable; assert it maps args onto the
// generated tenants client correctly. The error decoder uses the real generated ResponseError.
vi.mock('../../api/client', () => ({ tenantsApi: vi.fn() }))
// The link's KEK at the fixture's light cost (src/test/memberInvite.ts): real Argon2id, input-sensitive.
vi.mock('@veyq/crypto/argonWorker', async () => {
  const real = await vi.importActual<typeof import('@veyq/crypto/v1')>('@veyq/crypto/v1')
  const { TEST_ARGON } = await vi.importActual<typeof import('@veyq/crypto/testArgon')>('@veyq/crypto/testArgon')
  return { inviteLinkKekAsync: async (secret: Uint8Array, salt: Uint8Array) => real.kekInviteLink(secret, salt, TEST_ARGON) }
})

import {
  acceptInvite, buildMemberJoin, forgetMemberInvite, InviteCheckFailed, InviteCodeWrong, inviteLinkMode, openMemberInvite,
  registerAndAccept, tenantError, type OpenedMemberInvite,
} from './tenantInvite'
import { tenantsApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { ResponseError, type ServedMemberInvite } from '../../api/generated/tenants'
import type { AccountMaterial } from '@veyq/crypto/accountBirth'
import { bundleOf, inviteGrant, memberInviteFixture, signedInviteKey, type MemberInviteFixture } from '../../test/memberInvite'
import { grantContext, grantSignatureVerifies } from '../spaces/grant'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std, fromB64std } from '@veyq/crypto/base64'

describe('tenant-invite accept facade (uc.admin.create-tenant, Slice B)', () => {
  const stubInvite = () =>
    stubApi(tenantsApi, {
      acceptTenantInvite: { tenantId: 't1', status: 'ACTIVE' },
      registerAndAcceptTenantInvite: { tenantId: 't1', status: 'ACTIVE', userId: 'u1' },
    })
  let api: ReturnType<typeof stubInvite>

  beforeEach(() => {
    api = stubInvite()
  })

  const BIRTH = { grant: 'AQ==', grantSignature: 'c2ln' }

  it('acceptInvite threads the birth and the optional sealedName onto the accept request (cr-0106)', async () => {
    await acceptInvite('secret', 'kevin', BIRTH, 'c2VhbGVkLW5hbWU=')
    expect(api.acceptTenantInvite).toHaveBeenCalledWith({
      acceptInviteRequest: { bootstrapSecret: 'secret', username: 'kevin', ...BIRTH, sealedName: 'c2VhbGVkLW5hbWU=' },
    })
  })

  it('registerAndAccept threads the sealedName; undefined when the name is skipped (cr-0106)', async () => {
    const material: AccountMaterial = {
      suiteId: 'v1', identityPublicKey: 'aQ==', wrappedIdentityPrivateKey: 'aQ==',
      authPublicKey: 'aQ==', masterKeyWraps: [], secretKeyProofPublicKey: 'aQ==',
    }
    const userId = '5c09e000-0000-4000-8000-0000000000a7' // client-minted: a sealed name is bound to it (ADR 0072)
    await registerAndAccept('secret', userId, 'kevin', material, BIRTH) // birth set, no name
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledWith(
      expect.objectContaining({
        registerAndAcceptRequest: expect.objectContaining({ userId, username: 'kevin', ...BIRTH, sealedName: undefined }),
      }),
    )
  })

  it('tenantError decodes the HTTP status and the body code', async () => {
    const res = {
      status: 409,
      clone: () => ({ json: async () => ({ code: 'domain_taken' }) }),
    } as unknown as Response
    expect(await tenantError(new ResponseError(res, 'conflict'))).toEqual({ status: 409, code: 'domain_taken' })
  })

  it('tenantError returns status 0 for a non-HTTP failure', async () => {
    expect(await tenantError(new Error('network'))).toEqual({ status: 0, code: null })
  })
})

// ── A member invite, the invitee's half (ADR 0111 decisions 1 and 3; cr-invite-is-a-placeholder-member decision 8) ──
// Each check has a crafted negative that fails EXACTLY that check — the rest of the invite stays genuine, so a check
// that was dropped would let it through (the bite) — and the genuine invite is the control on the same path.

/** Open [f]'s invite with [served] in place of what the server served, and [typed] for the code. */
const openWith = (f: MemberInviteFixture, served: ServedMemberInvite = f.served, typed = f.code) =>
  openMemberInvite(f.tenantId, served, f.sealedPart, typed)

/** Why an open was refused — the class and, for a failed check, which check. */
async function refusal(p: Promise<unknown>): Promise<string> {
  try {
    await p
    return 'opened'
  } catch (e) {
    return e instanceof InviteCheckFailed ? `check:${e.reason}` : e instanceof InviteCodeWrong ? 'code' : `other:${String(e)}`
  }
}

describe('openMemberInvite — the checks before anything is sent', () => {
  it('control: a genuine invite opens with its code — to the tenant key its grant carries, at the granted epoch', async () => {
    const f = memberInviteFixture({ epoch: 3 })
    const opened = await openWith(f)
    expect(Array.from(opened.epochKey)).toEqual(Array.from(f.tenantKey))
    expect(opened.grantedEpoch).toBe(3)
    expect(bundleOf(opened.inviteKey)).toBe(f.served.invitePublicKey)
  })

  it('a wrong code is InviteCodeWrong — the invitee may retype it', async () => {
    const f = memberInviteFixture()
    let other = invite.generateInviteCode()
    while (other === f.code) other = invite.generateInviteCode()
    expect(await refusal(openWith(f, f.served, other))).toBe('code')
    expect(await refusal(openWith(f, f.served, 'too-short'))).toBe('code') // no code at all
    expect(await refusal(openWith(f))).toBe('opened')
  })

  it('a served key the seed does not derive is refused (key_mismatch) — the operator cannot choose the seed', async () => {
    const f = memberInviteFixture()
    // Another invite key, signed by the very owner and granted the very tenant key: only the seed check can tell.
    const swapped = invite.inviteKeyFromSeed(invite.generateInviteSeed())
    const served = {
      ...f.served,
      invitePublicKey: bundleOf(swapped),
      inviteKeySignature: signedInviteKey(f.owner, { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, swapped),
      ...inviteGrant(f.tenantId, 0, f.tenantKey, f.served.inviteId, swapped, f.owner),
    }
    expect(await refusal(openWith(f, served))).toBe('check:key_mismatch')
  })

  it('a signer key whose fingerprint is not the one inside the seal is refused (fingerprint_mismatch)', async () => {
    const f = memberInviteFixture()
    // The operator serves its own key as the owner's and signs the invite key and the grant with it — every other
    // check passes, so only the fingerprint the owner sealed into the link refuses it.
    const operator = v1.generateIdentity()
    const served = {
      ...f.served,
      signerIdentityPublicKey: bundleOf(operator),
      inviteKeySignature: signedInviteKey(operator, { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, f.inviteKey),
      ...inviteGrant(f.tenantId, 0, f.tenantKey, f.served.inviteId, f.inviteKey, operator),
      granterIdentityPublicKey: bundleOf(operator),
    }
    expect(await refusal(openWith(f, served))).toBe('check:fingerprint_mismatch')
  })

  it('an invite key signature by another key is refused (bad_signature), the signer being the sealed one', async () => {
    const f = memberInviteFixture()
    const served = {
      ...f.served,
      inviteKeySignature: signedInviteKey(v1.generateIdentity(), { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, f.inviteKey),
    }
    expect(await refusal(openWith(f, served))).toBe('check:bad_signature')
  })

  it('a served expiry other than the signed one is refused (bad_signature) — the owner signed the lifetime', async () => {
    const f = memberInviteFixture()
    const served = { ...f.served, expiresAt: new Date(f.served.expiresAt.getTime() + 86_400_000) }
    expect(await refusal(openWith(f, served))).toBe('check:bad_signature')
  })

  it('a signer account other than the sealed one is refused (signer_mismatch)', async () => {
    const f = memberInviteFixture()
    expect(await refusal(openWith(f, { ...f.served, signerUserId: crypto.randomUUID() }))).toBe('check:signer_mismatch')
  })

  it('a grant whose signature does not verify under its granter is refused before it is opened', async () => {
    const f = memberInviteFixture()
    const forged = inviteGrant(f.tenantId, 0, v1.randomBytes(32), f.served.inviteId, f.inviteKey, v1.generateIdentity())
    expect(await refusal(openWith(f, { ...f.served, wrappedGrant: forged.wrappedGrant }))).toBe('check:bad_signature')
  })

  it('a grant naming the inviting owner as granter verifies only under the key the link anchored, not a served copy', async () => {
    // The operator wraps a key of its own to the (public) invite key, signs it with a key of its own, and serves that
    // key as the owner's: only the fingerprint inside the seal stops it — the owner's grant verifies under the checked
    // signer key, never under a second served copy.
    const f = memberInviteFixture()
    const operator = v1.generateIdentity()
    const forged = inviteGrant(f.tenantId, 0, v1.randomBytes(32), f.served.inviteId, f.inviteKey, operator)
    const served = { ...f.served, ...forged, granterUserId: f.served.signerUserId, granterIdentityPublicKey: bundleOf(operator) }
    expect(await refusal(openWith(f, served))).toBe('check:bad_signature')
    // Control: a grant a rotation re-wrapped names ANOTHER member as granter — trusted on first use, and handed back
    // for the caller to pin.
    const rotator = v1.generateIdentity()
    const rewrapped = inviteGrant(f.tenantId, 0, f.tenantKey, f.served.inviteId, f.inviteKey, rotator)
    const rotatorId = crypto.randomUUID()
    const opened = await openWith(f, { ...f.served, ...rewrapped, granterUserId: rotatorId, granterIdentityPublicKey: bundleOf(rotator) })
    expect(opened.foreignGranter).toEqual({ userId: rotatorId, identityPublicKey: bundleOf(rotator) })
    expect((await openWith(f)).foreignGranter).toBeNull()
  })

  it('a link whose sealed part is no invite is refused as uncheckable; its mode reads as none', async () => {
    const f = memberInviteFixture()
    expect(inviteLinkMode('AAAA')).toBeNull()
    expect(inviteLinkMode(f.sealedPart)).toBe('code')
    expect(await refusal(openMemberInvite(f.tenantId, f.served, 'AAAA', f.code))).toBe('check:key_mismatch')
  })

  it('forgetMemberInvite wipes the opened invite\'s private parts', async () => {
    const opened = await openWith(memberInviteFixture())
    forgetMemberInvite(opened)
    for (const b of [opened.epochKey, opened.inviteKey.priv.sign, opened.inviteKey.priv.kemSeed]) expect(b.every((x) => x === 0)).toBe(true)
  })
})

describe('buildMemberJoin — the newcomer grants itself and writes its join record', () => {
  const NEWCOMER = v1.generateIdentity()
  const NEW_ID = '5c09e000-0000-4000-8000-0000000000b9'
  const PROOF_KEY = v1.randomBytes(32)
  let f: MemberInviteFixture
  let opened: OpenedMemberInvite

  beforeEach(async () => {
    f = memberInviteFixture({ epoch: 2 })
    opened = await openWith(f)
  })
  const join = () => buildMemberJoin(opened, { userId: NEW_ID, identity: NEWCOMER, secretKeyProofPublicKey: PROOF_KEY })

  it('the self-grant opens under the new identity to the tenant key, signed by it, naming its key', () => {
    const { selfGrant } = join()
    expect(selfGrant.epoch).toBe(2)
    const key = v1.openAs(grantContext(f.tenantId, 2), v1.hybridSecret(NEWCOMER.priv), fromB64std(selfGrant.wrappedGrant))
    expect(Array.from(key)).toEqual(Array.from(f.tenantKey))
    const to = { userId: NEW_ID, identityPublicKeyB64: bundleOf(NEWCOMER) }
    const scope = { scopeId: f.tenantId, kind: 'TENANT', epoch: 2 } as const
    expect(grantSignatureVerifies(NEWCOMER.pub.sign, scope, to, selfGrant.wrappedGrant, selfGrant.grantSignature)).toBe(true)
    expect(grantSignatureVerifies(f.owner.pub.sign, scope, to, selfGrant.wrappedGrant, selfGrant.grantSignature)).toBe(false)
    expect(selfGrant.recipientIdentityDigest).toBe(b64std(v1.sha256Digest(v1.serializeIdentityPublic(NEWCOMER.pub))))
  })

  it('the invite proof verifies under the invite key over (invite, account, identity) — and under no other', () => {
    const proof = fromB64std(join().inviteProof)
    const stmt = invite.inviteAcceptStatement({ inviteId: f.served.inviteId, userId: NEW_ID, identityPublicKey: v1.serializeIdentityPublic(NEWCOMER.pub) })
    expect(v1.identityVerify(f.inviteKey.pub.sign, stmt, proof)).toBe(true)
    expect(v1.identityVerify(NEWCOMER.pub.sign, stmt, proof)).toBe(false)
    expect(v1.identityVerify(f.inviteKey.pub.sign, { ...stmt, userId: crypto.randomUUID() }, proof)).toBe(false)
  })

  it('the join record opens under the tenant key, bound to the newcomer, and both signatures verify', () => {
    const envelope = fromB64std(join().sealedJoinRecord)
    const address = { tenantId: f.tenantId, userId: NEW_ID }
    const record = invite.openJoinRecord(f.tenantKey, address, envelope)
    expect(invite.joinRecordVerifies(address, record)).toBe(true)
    expect(bundleOf({ pub: v1.deserializeIdentityPublic(record.identityPublicKey) } as v1.Identity)).toBe(bundleOf(NEWCOMER))
    expect(Array.from(record.secretKeyProofPublicKey)).toEqual(Array.from(PROOF_KEY))
    expect({ inviteId: record.inviteId, signerUserId: record.signerUserId }).toEqual({ inviteId: f.served.inviteId, signerUserId: f.ownerId })
    // Controls: another member's address does not open it, another key does not, and a record moved to another
    // member does not verify.
    expect(() => invite.openJoinRecord(f.tenantKey, { ...address, userId: crypto.randomUUID() }, envelope)).toThrow()
    expect(() => invite.openJoinRecord(v1.randomBytes(32), address, envelope)).toThrow()
    expect(invite.joinRecordVerifies({ ...address, userId: crypto.randomUUID() }, record)).toBe(false)
  })

  it('accept and register-and-accept carry the join on the wire', async () => {
    const api = stubApi(tenantsApi, {
      acceptTenantInvite: { tenantId: 't1', status: 'ACTIVE' },
      registerAndAcceptTenantInvite: { tenantId: 't1', status: 'ACTIVE', userId: NEW_ID },
    })
    const j = join()
    await acceptInvite('tok', 'bea', undefined, undefined, undefined, j)
    expect(api.acceptTenantInvite).toHaveBeenCalledWith({ acceptInviteRequest: expect.objectContaining({ bootstrapSecret: 'tok', ...j }) })
    const material: AccountMaterial = {
      suiteId: 'v1', identityPublicKey: bundleOf(NEWCOMER), wrappedIdentityPrivateKey: 'aQ==',
      authPublicKey: 'aQ==', masterKeyWraps: [], secretKeyProofPublicKey: b64std(PROOF_KEY),
    }
    await registerAndAccept('tok', NEW_ID, 'bea', material, undefined, undefined, undefined, j)
    expect(api.registerAndAcceptTenantInvite).toHaveBeenCalledWith({ registerAndAcceptRequest: expect.objectContaining({ userId: NEW_ID, ...j }) })
  })
})
