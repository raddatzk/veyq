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
  acceptCheckedInvite, acceptInvite, buildMemberJoin, forgetMemberInvite, InviteCheckFailed, InviteCodeWrong, inviteLinkMode,
  openMemberInvite, registerAndAccept, tenantError, type OpenedMemberInvite,
} from './tenantInvite'
import { tenantsApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { ResponseError, type ServedMemberInvite } from '../../api/generated/tenants'
import type { AccountMaterial } from '@veyq/crypto/accountBirth'
import {
  bundleOf, carriedTo, epochLink, foreignGranter, inviteGrant, memberInviteFixture, ownerSecured, signedInviteKey,
  type MemberInviteFixture,
} from '../../test/memberInvite'
import { chainKeys, genesis, headOf } from '../../test/keyChain'
import { httpError } from '../../test/httpError'
import { grantContext, grantSignatureVerifies, recipientIdentityDigest } from '../spaces/grant'
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

  const BIRTH = { grant: 'AQ==', grantSignature: 'c2ln', recipientIdentityDigest: 'ZGlnZXN0' }

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

// ── A member invite, the invitee's half (ADR 0111 decisions 1 and 3, ADR 0111 decision 19) ──
// Each check has a crafted negative that fails EXACTLY that check — the rest of the invite stays genuine, so a check
// that was dropped would let it through (the bite) — and the genuine invite is the control on the same path. Every
// reason of `checkInvite` is the crypto package's own suite (`inviteCheck.test.ts`); here each is reached through what
// the preview serves on the wire, and the two probes the CR measured on main are regressions.

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

/** [signer]'s `invite-key` signature over [f]'s invite. */
const keySignedBy = (f: MemberInviteFixture, signer: v1.Identity) =>
  signedInviteKey(signer, { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, f.inviteKey)

/** An invite whose anchor (and carried grant, the same bytes) wraps [key] at the anchor's epoch, signed by [signer]. */
function anchoredOn(f: MemberInviteFixture, key: Uint8Array, signer: v1.Identity): ServedMemberInvite {
  const g = inviteGrant(f.tenantId, f.served.anchorEpoch, key, f.served.inviteId, f.inviteKey, signer)
  return { ...f.served, anchorWrappedGrant: g.wrappedGrant, anchorGrantSignature: g.grantSignature, ...g }
}

describe('openMemberInvite — the checks before anything is sent', () => {
  it('control: a genuine invite opens with its code — to the tenant key its grant carries, at the granted epoch', async () => {
    const f = memberInviteFixture({ epoch: 3 })
    const opened = await openWith(f)
    expect(Array.from(opened.epochKey)).toEqual(Array.from(f.tenantKey))
    expect(opened.grantedEpoch).toBe(3)
    expect(bundleOf(opened.inviteKey)).toBe(f.served.invitePublicKey)
    expect({ head: b64std(opened.signer.head), index: opened.signer.index, key: b64std(opened.signer.identityPublicKey) })
      .toEqual({ head: headOf(f.ownerChain), index: 0, key: bundleOf(f.owner) })
    expect(opened.foreignGranter).toBeNull()
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
    // Another invite key, signed by the very owner and anchored on the very tenant key: only the seed check can tell.
    const swapped = invite.inviteKeyFromSeed(invite.generateInviteSeed())
    const g = inviteGrant(f.tenantId, 0, f.tenantKey, f.served.inviteId, swapped, f.owner)
    const served = {
      ...f.served,
      invitePublicKey: bundleOf(swapped),
      inviteKeySignature: signedInviteKey(f.owner, { tenantId: f.tenantId, inviteId: f.served.inviteId, expiresAt: f.served.expiresAt }, swapped),
      anchorWrappedGrant: g.wrappedGrant, anchorGrantSignature: g.grantSignature, ...g,
    }
    expect(await refusal(openWith(f, served))).toBe('check:key_mismatch')
  })

  it('a signer account other than the sealed one is refused (signer_mismatch)', async () => {
    const f = memberInviteFixture()
    expect(await refusal(openWith(f, { ...f.served, signerUserId: crypto.randomUUID() }))).toBe('check:signer_mismatch')
  })

  it('a chain of the operator\'s own key under the owner\'s account does not pass the head inside the link (signer_chain_mismatch)', async () => {
    const f = memberInviteFixture()
    // The operator serves a chain it made for the owner's account, ending at its own key, which signs everything: every
    // later check passes, so only the head the owner sealed into the link refuses it.
    const operator = genesis(f.ownerId, chainKeys())
    const key = operator.keys[0].identity
    const served = {
      ...anchoredOn(f, f.tenantKey, key),
      signerIdentityPublicKey: bundleOf(key), signerKeyChain: operator.steps, inviteKeySignature: keySignedBy(f, key),
    }
    expect(await refusal(openWith(f, served))).toBe('check:signer_chain_mismatch')
  })

  it('#568 regression: after the owner secured the account, a grant signed by their RETIRED key is refused (signer_chain_mismatch)', async () => {
    const f = memberInviteFixture({ epoch: 1 })
    const secured = ownerSecured(f)
    // The probe on main: the owner's chain moved on; the operator (with a thief of the old device) serves the retired key
    // as the signer, signing an invite key and an anchor that carries a tenant key of its choosing.
    const chosen = v1.randomBytes(32)
    const served = {
      ...anchoredOn(secured, chosen, f.owner),
      signerIdentityPublicKey: bundleOf(f.owner), inviteKeySignature: keySignedBy(f, f.owner),
    }
    expect(await refusal(openWith(secured, served))).toBe('check:signer_chain_mismatch')
    // Control: the owner's own re-signature by the new key, reached from the link's head through the doubly signed step.
    const opened = await openWith(secured)
    expect(Array.from(opened.epochKey)).toEqual(Array.from(f.tenantKey))
    expect({ index: opened.signer.index, key: b64std(opened.signer.identityPublicKey) }).toEqual({ index: 1, key: bundleOf(secured.owner) })
  })

  it('an invite key signature by another key is refused (bad_signature), the chain being the owner\'s', async () => {
    const f = memberInviteFixture()
    expect(await refusal(openWith(f, { ...f.served, inviteKeySignature: keySignedBy(f, v1.generateIdentity()) }))).toBe('check:bad_signature')
  })

  it('a served expiry other than the signed one is refused (bad_signature) — the owner signed the lifetime', async () => {
    const f = memberInviteFixture()
    const served = { ...f.served, expiresAt: new Date(f.served.expiresAt.getTime() + 86_400_000) }
    expect(await refusal(openWith(f, served))).toBe('check:bad_signature')
  })

  it('an anchor grant the owner\'s key did not sign is refused (anchor_invalid)', async () => {
    const f = memberInviteFixture()
    const served = anchoredOn(f, v1.randomBytes(32), v1.generateIdentity())
    expect(await refusal(openWith(f, served))).toBe('check:anchor_invalid')
  })

  it('at the anchor\'s epoch, a carried grant other than the anchor is refused (epoch_unlinked)', async () => {
    const f = memberInviteFixture({ epoch: 2 })
    // Signed by the owner too, to the same key: only the byte comparison with the anchor refuses it.
    const other = inviteGrant(f.tenantId, 2, v1.randomBytes(32), f.served.inviteId, f.inviteKey, f.owner)
    expect(await refusal(openWith(f, { ...f.served, ...other }))).toBe('check:epoch_unlinked')
  })

  it('#565 regression: an operator\'s key at epoch 1 under a granter it fabricated is refused (epoch_unlinked)', async () => {
    const f = memberInviteFixture({ epoch: 0 })
    // The probe on main: a grant at epoch 1 wrapped to the invite key with a key of the operator's choosing, signed by a
    // freshly generated identity under an unknown user id — with a link it can make, under its own key.
    const fabricated = foreignGranter(crypto.randomUUID())
    const chosen = v1.randomBytes(32)
    const served: ServedMemberInvite = {
      ...f.served,
      ...inviteGrant(f.tenantId, 1, chosen, f.served.inviteId, f.inviteKey, fabricated.identity),
      granterUserId: fabricated.userId, granterIdentityPublicKey: bundleOf(fabricated.identity), granterKeyChainHead: fabricated.head,
      epochLinks: [epochLink(f.tenantId, 1, [v1.randomBytes(32), chosen])],
    }
    expect(await refusal(openWith(f, served))).toBe('check:epoch_unlinked')
    expect(await refusal(openWith(f, { ...served, epochLinks: [] }))).toBe('check:epoch_unlinked')
    // Control: a member's rotation genuinely carried the invite to epoch 1 — its link peels down to the owner's anchor.
    const carried = carriedTo(f, 1, fabricated)
    const opened = await openWith(carried)
    expect(Array.from(opened.epochKey)).toEqual(Array.from(carried.keys[1]))
    expect(opened.grantedEpoch).toBe(1)
    expect(opened.foreignGranter).toEqual({ userId: fabricated.userId, identityPublicKey: bundleOf(fabricated.identity), keyChainHead: fabricated.head })
  })

  it('a link whose sealed part is no invite is refused as uncheckable; its mode reads as none', async () => {
    const f = memberInviteFixture()
    expect(inviteLinkMode('AAAA')).toBeNull()
    expect(inviteLinkMode(f.sealedPart)).toBe('code')
    expect(await refusal(openMemberInvite(f.tenantId, f.served, 'AAAA', f.code))).toBe('check:key_mismatch')
  })

  it('a served byte string that is no base64 is refused before any check reads it (control: the genuine bytes)', async () => {
    const f = memberInviteFixture()
    expect(await refusal(openWith(f, { ...f.served, anchorWrappedGrant: '***' }))).toBe('check:key_mismatch')
    expect(await refusal(openWith(f))).toBe('opened')
  })

  it('forgetMemberInvite wipes the opened invite\'s private parts, the kept seed included', async () => {
    const opened = await openWith(memberInviteFixture())
    forgetMemberInvite(opened)
    for (const b of [opened.epochKey, opened.inviteKey.priv.sign, opened.inviteKey.priv.kemSeed, opened.link.seed]) {
      expect(b.every((x) => x === 0)).toBe(true)
    }
  })
})

describe('acceptCheckedInvite — one silent re-check on 409 invite_changed (ADR 0111 decision 20)', () => {
  /** The digest the join of [opened] states, as `send` saw it. */
  const digestOf = (opened: OpenedMemberInvite) => b64std(v1.sha256Digest(opened.signer.identityPublicKey))

  it('control: an accept the server takes is sent once, and the preview is not read again', async () => {
    const f = memberInviteFixture()
    const opened = await openWith(f)
    const send = vi.fn(async () => 'ok')
    const reread = vi.fn(async () => f.served)
    expect(await acceptCheckedInvite(opened, send, reread)).toBe('ok')
    expect(send).toHaveBeenCalledTimes(1)
    expect(reread).not.toHaveBeenCalled()
  })

  it('the owner secured their account meanwhile: re-read, re-checked without the code, and sent once more naming the new key', async () => {
    const f = memberInviteFixture({ epoch: 1 })
    const opened = await openWith(f)
    const secured = ownerSecured(f)
    const seen: { digest: string; key: number[] }[] = []
    const send = vi.fn(async (o: OpenedMemberInvite) => {
      seen.push({ digest: digestOf(o), key: Array.from(o.epochKey) })
      if (seen.length === 1) throw httpError(409, 'invite_changed')
      return 'joined'
    })
    const reread = vi.fn(async () => secured.served)
    expect(await acceptCheckedInvite(opened, send, reread)).toBe('joined')
    expect(reread).toHaveBeenCalledTimes(1)
    expect(seen.map((s) => s.digest)).toEqual([recipientIdentityDigest(bundleOf(f.owner)), recipientIdentityDigest(bundleOf(secured.owner))])
    expect(seen[1].key).toEqual(Array.from(f.tenantKey))
    // The caller's invite is still the caller's; the re-opened one was wiped.
    expect(opened.link.seed.some((b) => b !== 0)).toBe(true)
  })

  it('once: a second invite_changed is the caller\'s — no third send', async () => {
    const f = memberInviteFixture()
    const opened = await openWith(f)
    const send = vi.fn(async () => {
      throw httpError(409, 'invite_changed')
    })
    await expect(acceptCheckedInvite(opened, send, async () => ownerSecured(f).served)).rejects.toMatchObject({ message: 'http 409 invite_changed' })
    expect(send).toHaveBeenCalledTimes(2)
  })

  it('another refusal is not retried (control on the code: a 409 of another kind)', async () => {
    const f = memberInviteFixture()
    const opened = await openWith(f)
    const send = vi.fn(async () => {
      throw httpError(409, 'username_taken')
    })
    const reread = vi.fn(async () => f.served)
    await expect(acceptCheckedInvite(opened, send, reread)).rejects.toMatchObject({ message: 'http 409 username_taken' })
    expect(send).toHaveBeenCalledTimes(1)
    expect(reread).not.toHaveBeenCalled()
  })

  it('a re-read that no longer checks is refused, and nothing more is sent', async () => {
    const f = memberInviteFixture()
    const opened = await openWith(f)
    const secured = ownerSecured(f)
    const send = vi.fn(async () => {
      throw httpError(409, 'invite_changed')
    })
    // After the owner's securing, the retired key served again as the signer.
    const retired = { ...secured.served, signerIdentityPublicKey: bundleOf(f.owner), inviteKeySignature: keySignedBy(f, f.owner) }
    expect(await refusal(acceptCheckedInvite(opened, send, async () => retired))).toBe('check:signer_chain_mismatch')
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('a re-checked invite this account\'s pins refuse is not sent', async () => {
    const f = memberInviteFixture()
    const opened = await openWith(f)
    const send = vi.fn(async () => {
      throw httpError(409, 'invite_changed')
    })
    const anchored = vi.fn(async () => false)
    expect(await refusal(acceptCheckedInvite(opened, send, async () => ownerSecured(f).served, anchored))).toBe('check:signer_chain_mismatch')
    expect(anchored).toHaveBeenCalledTimes(1)
    expect(send).toHaveBeenCalledTimes(1)
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

  it('the record names the owner key the checks walked to, and signerIdentityDigest is SHA-256 of exactly that bundle', async () => {
    const secured = ownerSecured(f)
    opened = await openWith(secured)
    const j = join()
    const record = invite.openJoinRecord(f.tenantKey, { tenantId: f.tenantId, userId: NEW_ID }, fromB64std(j.sealedJoinRecord))
    expect(b64std(record.signerIdentityPublicKey)).toBe(bundleOf(secured.owner))
    expect(j.signerIdentityDigest).toBe(b64std(v1.sha256Digest(v1.serializeIdentityPublic(secured.owner.pub))))
    expect(j.signerIdentityDigest).not.toBe(b64std(v1.sha256Digest(v1.serializeIdentityPublic(f.owner.pub))))
  })

  it('accept and register-and-accept carry the join on the wire, the digest included', async () => {
    const api = stubApi(tenantsApi, {
      acceptTenantInvite: { tenantId: 't1', status: 'ACTIVE' },
      registerAndAcceptTenantInvite: { tenantId: 't1', status: 'ACTIVE', userId: NEW_ID },
    })
    const j = join()
    expect(j.signerIdentityDigest).toBeTruthy()
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
