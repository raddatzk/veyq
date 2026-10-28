// The member invite (ADR 0111 decisions 1-3) against the vectors an independent Python implementation generated
// (`#invite_key`, `#invite_link`, `#join_record`, `#join_endorsement`): the key a seed derives, the owner's signature,
// the link's code, KEK, seal and encoding (version 2, carrying the owner's key-chain head), the accept proof, the join
// record and an owner's endorsement of it. The invitee's checks are `inviteCheck.test.ts`'s.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
import * as invite from './invite'
import { b64url } from './base64'
import { PRODUCTION_ARGON_TIMEOUT_MS } from './testArgon'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const k = vectors.invite_key
const l = vectors.invite_link
const j = vectors.join_record
const e = vectors.join_endorsement
const flip = (b: Uint8Array, i = 0) => {
  const out = b.slice()
  out[i] ^= 1
  return out
}

describe('invite key (#invite_key)', () => {
  const key = invite.inviteKeyFromSeed(unhex(k.seed))

  it('derives an identity-shaped bundle from the seed under labels of its own', () => {
    expect(hex(key.priv.kemSeed)).toBe(k.kem_seed)
    expect(hex(key.priv.sign)).toBe(k.sign_seed)
    expect(hex(key.pub.sign)).toBe(k.sign_public)
    expect(hex(key.pub.kem)).toBe(k.kem_public)
    expect(hex(v1.serializeIdentityPublic(key.pub))).toBe(k.bundle)
    // Identity-shaped: the bundle the private half re-derives is the same, so a grant seals to it like to a member.
    expect(hex(v1.serializeIdentityPublic(v1.identityPublicFromPrivate(key.priv)))).toBe(k.bundle)
  })

  it('a seed that is not 32 bytes derives nothing', () => {
    expect(() => invite.inviteKeyFromSeed(new Uint8Array(31))).toThrow(new TypeError('invite seed: not 32 bytes'))
  })

  it('the owner signs it: statement bytes and signature', () => {
    const input = { ...k.context, invitePublicKey: unhex(k.bundle) }
    expect(hex(v1.encodeStatement(invite.inviteKeyStatement(input)))).toBe(k.statement)
    expect(hex(invite.signInviteKey(unhex(vectors.grant_signature.sign_seed), input))).toBe(k.signature)
    expect(invite.inviteKeySignatureVerifies(unhex(k.owner.sign_public), input, unhex(k.signature))).toBe(true)
  })

  it('the signature answers for nothing else', () => {
    const base = { ...k.context, invitePublicKey: unhex(k.bundle) }
    const sig = unhex(k.signature)
    const owner = unhex(k.owner.sign_public)
    for (const [name, moved] of [
      ['tenantId', { ...base, tenantId: 'c0ffee00-0000-4000-8000-0000000000e0' }],
      ['inviteId', { ...base, inviteId: 'c0ffee00-0000-4000-8000-0000000000e0' }],
      ['invitePublicKey', { ...base, invitePublicKey: flip(base.invitePublicKey, 40) }],
      ['expiresAt', { ...base, expiresAt: base.expiresAt + 1 }],
    ] as const) {
      expect(invite.inviteKeySignatureVerifies(owner, moved, sig), name).toBe(false)
    }
    expect(invite.inviteKeySignatureVerifies(unhex(k.sign_public), base, sig)).toBe(false)
  })

  it('the owner bundle hashes to the fingerprint the link carries', () => {
    expect(hex(v1.fingerprintHash(v1.deserializeIdentityPublic(unhex(k.owner.bundle))))).toBe(k.owner.fingerprint_hash)
  })
})

describe('invite link (#invite_link)', () => {
  const address = l.context as invite.InviteAddress

  it('a typed code is read in its canonical form; anything else is no code', () => {
    expect(invite.normalizeInviteCode(l.typed)).toBe(l.code)
    expect(invite.normalizeInviteCode(l.code)).toBe(l.code)
    expect(invite.normalizeInviteCode(invite.formatInviteCode(l.code))).toBe(l.code)
    expect(invite.normalizeInviteCode('R0SE1N7AK')).toBeNull() // nine characters
    expect(invite.normalizeInviteCode('R0SE1N7AKEE')).toBeNull() // eleven
    expect(invite.normalizeInviteCode('R0SE1N7AKU')).toBeNull() // U is not in the alphabet
    expect(invite.inviteLinkSecret('code', 'R0SE1N7AK')).toBeNull()
    expect(invite.inviteLinkSecret('password', '')).toBeNull()
  })

  it('a fresh code is 10 characters of the alphabet, and two are not the same', () => {
    const a = invite.generateInviteCode()
    expect(invite.normalizeInviteCode(a)).toBe(a)
    expect(a).not.toBe(invite.generateInviteCode())
  })

  it('the KEK is Argon2id of the code, or of the password as typed, at the production profile', () => {
    expect(l.params).toEqual(v1.ARGON)
    const secret = invite.inviteLinkSecret('code', l.typed)
    expect(secret).not.toBeNull()
    expect(hex(v1.kekInviteLink(secret as Uint8Array, unhex(l.salt)))).toBe(l.kek_code)
    expect(hex(v1.kekInviteLink(invite.inviteLinkSecret('password', l.password) as Uint8Array, unhex(l.salt)))).toBe(l.kek_password)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  it('the signer head is the head of the owner\'s chain at step 0 (#key_chain\'s encoding)', () => {
    const genesis = v1.keyChainGenesisStatement(k.owner.user_id, {
      identityPublicKey: unhex(k.owner.bundle),
      secretKeyProofPublicKey: unhex(vectors.secret_key_proof.key.public_key),
    })
    expect(hex(v1.encodeStatement(genesis))).toBe(l.signer_genesis)
    expect(hex(v1.keyChainHead(genesis))).toBe(l.signer_head)
  })

  it('the payload, its seal and the link\'s encoding are the vector\'s', () => {
    const payload = {
      seed: unhex(k.seed),
      signerHead: unhex(l.signer_head),
      signerUserId: k.owner.user_id,
    }
    expect(hex(invite.encodeInviteLinkPayload(payload))).toBe(l.payload)
    expect(invite.decodeInviteLinkPayload(unhex(l.payload))).toEqual(payload)
    expect(hex(v1.encodeSealContext({ purpose: 'invite-link', ...address }))).toBe(l.context_bytes)
    const parsed = invite.parseSealedInviteLink(l.sealed)
    expect(parsed.mode).toBe('code')
    expect(parsed.params).toEqual(v1.ARGON)
    expect(hex(parsed.salt)).toBe(l.salt)
    expect(hex(parsed.sealed)).toBe(l.blob)
    expect(hex(v1.encodeKdfParams(v1.ARGON, unhex(l.salt)))).toBe(l.kdf_params)
    const opened = invite.openInviteLink(address, unhex(l.kek_code), parsed)
    expect(hex(opened.seed)).toBe(k.seed)
    expect(hex(opened.signerHead)).toBe(l.signer_head)
    expect(opened.signerUserId).toBe(k.owner.user_id)
    // Sealing draws its own nonce; what it writes parses to the same header and opens to the same payload.
    const fresh = invite.sealInviteLink(address, 'code', unhex(l.kek_code), unhex(l.salt), payload)
    expect(fresh.slice(0, 44)).toBe(l.sealed.slice(0, 44))
    expect(hex(invite.encodeInviteLinkPayload(invite.openInviteLink(address, unhex(l.kek_code), invite.parseSealedInviteLink(fresh))))).toBe(l.payload)
  })

  it('a version-1 payload (the owner\'s fingerprint, no head) is refused — encoded alone or opened from a link', () => {
    expect(() => invite.decodeInviteLinkPayload(unhex(l.payload_v1))).toThrow(new invite.InviteLinkInvalid('version'))
    const parsed = invite.parseSealedInviteLink(l.sealed)
    const v1Sealed = v1.seal({ purpose: 'invite-link', ...address }, unhex(l.kek_code), unhex(l.payload_v1))
    expect(() => invite.openInviteLink(address, unhex(l.kek_code), { ...parsed, sealed: v1Sealed })).toThrow(
      new invite.InviteLinkInvalid('version'),
    )
    // Control: the same seal over the version-2 payload opens.
    const v2Sealed = v1.seal({ purpose: 'invite-link', ...address }, unhex(l.kek_code), unhex(l.payload))
    expect(hex(invite.openInviteLink(address, unhex(l.kek_code), { ...parsed, sealed: v2Sealed }).signerHead)).toBe(l.signer_head)
  })

  it('a payload is exactly its three fields at their sizes', () => {
    const p = unhex(l.payload)
    expect(() => invite.decodeInviteLinkPayload(p.slice(0, -1))).toThrow(new invite.InviteLinkInvalid('truncated'))
    expect(() => invite.decodeInviteLinkPayload(v1.concat(p, Uint8Array.of(0)))).toThrow(new invite.InviteLinkInvalid('trailing'))
    expect(() => invite.decodeInviteLinkPayload(new Uint8Array(0))).toThrow(new invite.InviteLinkInvalid('version'))
    const base = { seed: unhex(k.seed), signerHead: unhex(l.signer_head), signerUserId: k.owner.user_id }
    expect(() => invite.encodeInviteLinkPayload({ ...base, signerHead: new Uint8Array(31) })).toThrow(new invite.InviteLinkInvalid('head'))
    expect(() => invite.encodeInviteLinkPayload({ ...base, seed: new Uint8Array(31) })).toThrow(new invite.InviteLinkInvalid('seed'))
    expect(() => invite.encodeInviteLinkPayload({ ...base, signerUserId: 'not-a-uuid' })).toThrow(new invite.InviteLinkInvalid('signer'))
  })

  it('opens with its code\'s KEK only, and only as this invite', () => {
    const parsed = invite.parseSealedInviteLink(l.sealed)
    expect(() => invite.openInviteLink(address, unhex(l.kek_password), parsed)).toThrow(new v1.OpenRefused('invite-link'))
    expect(() => invite.openInviteLink({ ...address, inviteId: 'c0ffee00-0000-4000-8000-0000000000e0' }, unhex(l.kek_code), parsed)).toThrow(
      new v1.OpenRefused('invite-link'),
    )
    expect(() => invite.openInviteLink({ ...address, tenantId: 'c0ffee00-0000-4000-8000-0000000000e0' }, unhex(l.kek_code), parsed)).toThrow(
      new v1.OpenRefused('invite-link'),
    )
  })

  it('a link with another profile, an unknown mode or no seal is refused before any derivation', () => {
    const raw = Buffer.from(l.sealed.replace(/-/g, '+').replace(/_/g, '/'), 'base64')
    const heavier = Uint8Array.from(raw)
    heavier[1 + 7] = 0x01 // m: 32768 → 33024 KiB
    expect(() => invite.parseSealedInviteLink(b64url(heavier))).toThrow(v1.KdfProfileRefused)
    const mode = Uint8Array.from(raw)
    mode[0] = 3
    expect(() => invite.parseSealedInviteLink(b64url(mode))).toThrow(new invite.InviteLinkInvalid('mode'))
    expect(() => invite.parseSealedInviteLink(b64url(Uint8Array.from(raw).slice(0, 33)))).toThrow(new invite.InviteLinkInvalid('length'))
    expect(() => invite.parseSealedInviteLink('!!')).toThrow(new invite.InviteLinkInvalid('encoding'))
  })

  it('the fragment carries the token and, for a member invite, the sealed part', () => {
    expect(invite.parseInviteFragment(`#${l.fragment}`)).toEqual({ token: l.token, sealed: l.sealed })
    expect(invite.parseInviteFragment(l.token)).toEqual({ token: l.token, sealed: null })
  })
})

describe('accept proof and join record (#join_record)', () => {
  const key = invite.inviteKeyFromSeed(unhex(k.seed))
  const address = j.context as invite.JoinRecordAddress
  const body = {
    identityPublicKey: unhex(j.joiner_bundle),
    secretKeyProofPublicKey: unhex(j.secret_key_proof_public_key),
    inviteId: k.context.inviteId,
    invitePublicKey: unhex(k.bundle),
    inviteKeySignature: unhex(k.signature),
    inviteExpiresAt: k.context.expiresAt,
    signerUserId: k.owner.user_id,
    signerIdentityPublicKey: unhex(k.owner.bundle),
  }

  it('the accept proof: statement bytes and the invite key\'s signature', () => {
    const a = { inviteId: k.context.inviteId, userId: address.userId, identityPublicKey: unhex(j.joiner_bundle) }
    expect(hex(v1.encodeStatement(invite.inviteAcceptStatement(a)))).toBe(j.accept_statement)
    expect(hex(invite.signInviteAccept(key.priv.sign, a))).toBe(j.accept_signature)
  })

  it('statement bytes, both signatures and the record\'s encoding', () => {
    expect(hex(v1.encodeStatement(invite.joinRecordStatement(address, body)))).toBe(j.statement)
    const record = invite.signJoinRecord(address, body, key.priv.sign, unhex(j.joiner_sign_seed))
    expect(hex(record.inviteSignature)).toBe(j.invite_signature)
    expect(hex(record.identitySignature)).toBe(j.identity_signature)
    expect(hex(invite.encodeJoinRecord(record))).toBe(j.record)
    expect(invite.decodeJoinRecord(unhex(j.record))).toEqual(record)
    expect(invite.joinRecordVerifies(address, record)).toBe(true)
  })

  it('a record verifies only with all three signatures, at its own address', () => {
    const record = invite.decodeJoinRecord(unhex(j.record))
    expect(invite.joinRecordVerifies(address, { ...record, inviteSignature: flip(record.inviteSignature) })).toBe(false)
    expect(invite.joinRecordVerifies(address, { ...record, identitySignature: flip(record.identitySignature) })).toBe(false)
    expect(invite.joinRecordVerifies(address, { ...record, inviteKeySignature: flip(record.inviteKeySignature) })).toBe(false)
    expect(invite.joinRecordVerifies({ ...address, userId: 'b0b00000-0000-4000-8000-0000000000e0' }, record)).toBe(false)
    expect(invite.joinRecordVerifies(address, { ...record, secretKeyProofPublicKey: flip(record.secretKeyProofPublicKey) })).toBe(false)
  })

  it('sealed under the tenant key bound to (tenant, member), it opens there and nowhere else', () => {
    const record = invite.decodeJoinRecord(unhex(j.record))
    const tenantKey = new Uint8Array(32).fill(5)
    const sealed = invite.sealJoinRecord(tenantKey, address, record)
    expect(invite.openJoinRecord(tenantKey, address, sealed)).toEqual(record)
    expect(() => invite.openJoinRecord(tenantKey, { ...address, userId: 'b0b00000-0000-4000-8000-0000000000e0' }, sealed)).toThrow(
      new v1.OpenRefused('member-join-record'),
    )
    expect(() => invite.openJoinRecord(new Uint8Array(32).fill(6), address, sealed)).toThrow(new v1.OpenRefused('member-join-record'))
  })

  it('a malformed record is refused, never half-read', () => {
    const b = unhex(j.record)
    expect(() => invite.decodeJoinRecord(b.slice(0, b.length - 1))).toThrow(new invite.JoinRecordInvalid('truncated'))
    expect(() => invite.decodeJoinRecord(v1.concat(b, Uint8Array.of(0)))).toThrow(new invite.JoinRecordInvalid('trailing'))
    expect(() => invite.decodeJoinRecord(v1.concat(Uint8Array.of(2), b.slice(1)))).toThrow(new invite.JoinRecordInvalid('version'))
  })
})

describe('an owner\'s endorsement of a join record (#join_endorsement)', () => {
  const input: invite.JoinEndorsementInput = { ...e.context, sealedJoinRecord: unhex(e.sealed_join_record) }
  const owner = unhex(k.owner.sign_public)
  const signature = unhex(e.signature)

  it('statement bytes and the owner\'s signature, over SHA-256 of the sealed record as stored', () => {
    expect(e.sealed_join_record).toBe(vectors.field_seal['member-join-record'].envelope)
    expect(hex(invite.joinEndorsementStatement(input).recordDigest)).toBe(e.record_digest)
    expect(hex(v1.encodeStatement(invite.joinEndorsementStatement(input)))).toBe(e.statement)
    expect(hex(invite.signJoinEndorsement(unhex(vectors.grant_signature.sign_seed), input))).toBe(e.signature)
  })

  it('verifies under the owner key — the control for every negative below', () => {
    expect(invite.joinEndorsementVerifies(owner, input, signature)).toBe(true)
  })

  it.each([
    ['another tenant', { ...input, tenantId: 'c0ffee00-0000-4000-8000-0000000000e0' }],
    ['another member', { ...input, userId: 'c0ffee00-0000-4000-8000-0000000000e0' }],
    ['another signer', { ...input, signerUserId: 'c0ffee00-0000-4000-8000-0000000000e0' }],
    ['another sealed record — one the thief wrote', { ...input, sealedJoinRecord: flip(input.sealedJoinRecord, 40) }],
  ] as const)('answers for nothing else: %s', (_name, moved) => {
    expect(invite.joinEndorsementVerifies(owner, moved, signature)).toBe(false)
  })

  it('a signature by another key — the retired key a thief holds — is not the owner\'s', () => {
    const thief = invite.signJoinEndorsement(unhex('8d'.repeat(32)), input)
    expect(invite.joinEndorsementVerifies(owner, input, thief)).toBe(false)
    expect(invite.joinEndorsementVerifies(owner, input, flip(signature, 5))).toBe(false)
  })

  it('a malformed key, signature or id is false, never a throw', () => {
    expect(invite.joinEndorsementVerifies(new Uint8Array(3), input, signature)).toBe(false)
    expect(invite.joinEndorsementVerifies(owner, input, new Uint8Array(2))).toBe(false)
    expect(invite.joinEndorsementVerifies(owner, { ...input, userId: 'not-a-uuid' }, signature)).toBe(false)
  })

  it('its label is a statement of its own, in no seal table', () => {
    expect(new TextDecoder().decode(unhex(e.statement).slice(4, 4 + 'join-endorsement'.length))).toBe('join-endorsement')
    expect(Object.keys(vectors.seal.sym)).not.toContain('join-endorsement')
    expect(Object.keys(vectors.field_seal)).not.toContain('join-endorsement')
  })
})
