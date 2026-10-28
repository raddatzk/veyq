// The member invite (ADR 0111 decisions 1-3) against the vectors an independent Python implementation generated
// (`#invite_key`, `#invite_link`, `#join_record`): the key a seed derives, the owner's signature, the link's code, KEK,
// seal and encoding, the invitee's checks — each with a crafted negative that fails exactly that check — the accept
// proof and the join record.
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

  it('the payload, its seal and the link\'s encoding are the vector\'s', () => {
    const payload = {
      seed: unhex(k.seed),
      signerFingerprintHash: unhex(k.owner.fingerprint_hash),
      signerUserId: k.owner.user_id,
    }
    expect(hex(invite.encodeInviteLinkPayload(payload))).toBe(l.payload)
    expect(hex(v1.encodeSealContext({ purpose: 'invite-link', ...address }))).toBe(l.context_bytes)
    const parsed = invite.parseSealedInviteLink(l.sealed)
    expect(parsed.mode).toBe('code')
    expect(parsed.params).toEqual(v1.ARGON)
    expect(hex(parsed.salt)).toBe(l.salt)
    expect(hex(parsed.sealed)).toBe(l.blob)
    expect(hex(v1.encodeKdfParams(v1.ARGON, unhex(l.salt)))).toBe(l.kdf_params)
    const opened = invite.openInviteLink(address, unhex(l.kek_code), parsed)
    expect(hex(opened.seed)).toBe(k.seed)
    expect(hex(opened.signerFingerprintHash)).toBe(k.owner.fingerprint_hash)
    expect(opened.signerUserId).toBe(k.owner.user_id)
    // Sealing draws its own nonce; what it writes parses to the same header and opens to the same payload.
    const fresh = invite.sealInviteLink(address, 'code', unhex(l.kek_code), unhex(l.salt), payload)
    expect(fresh.slice(0, 44)).toBe(l.sealed.slice(0, 44))
    expect(hex(invite.encodeInviteLinkPayload(invite.openInviteLink(address, unhex(l.kek_code), invite.parseSealedInviteLink(fresh))))).toBe(l.payload)
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

describe('the invitee\'s checks (ADR 0111 decision 1)', () => {
  const payload = { seed: unhex(k.seed), signerFingerprintHash: unhex(k.owner.fingerprint_hash), signerUserId: k.owner.user_id }
  const served: invite.ServedInvite = {
    ...k.context,
    invitePublicKey: unhex(k.bundle),
    inviteKeySignature: unhex(k.signature),
    signerUserId: k.owner.user_id,
    signerIdentityPublicKey: unhex(k.owner.bundle),
  }
  const failure = (p: invite.InviteLinkPayload, s: invite.ServedInvite): invite.InviteCheckFailure | null => {
    try {
      invite.checkInvite(p, s)
      return null
    } catch (e) {
      if (e instanceof invite.InviteCheckFailed) return e.reason
      throw e
    }
  }

  it('control: the genuine invite passes, and the key it returns is the seed\'s', () => {
    expect(failure(payload, served)).toBeNull()
    expect(hex(v1.serializeIdentityPublic(invite.checkInvite(payload, served).pub))).toBe(k.bundle)
  })

  // Each negative passes every check before its own, so it fails exactly that one.
  it('a served invite key the seed does not derive', () => {
    const other = v1.serializeIdentityPublic(invite.inviteKeyFromSeed(new Uint8Array(32).fill(9)).pub)
    expect(failure(payload, { ...served, invitePublicKey: other })).toBe('key_mismatch')
  })

  it('a served signer that is not the account the seal names', () => {
    expect(failure(payload, { ...served, signerUserId: 'b0b00000-0000-4000-8000-0000000000e0' })).toBe('signer_mismatch')
  })

  it('a served signer key whose fingerprint is not the seal\'s — even one that signed the invite', () => {
    // The operator's own key, which signs the genuine invite key: only the fingerprint inside the seal stops it.
    const operator = v1.generateIdentity()
    const forged = invite.signInviteKey(operator.priv.sign, { ...k.context, invitePublicKey: unhex(k.bundle) })
    expect(
      failure(payload, { ...served, signerIdentityPublicKey: v1.serializeIdentityPublic(operator.pub), inviteKeySignature: forged }),
    ).toBe('fingerprint_mismatch')
  })

  it('a signature by another key, or over another expiry', () => {
    expect(failure(payload, { ...served, inviteKeySignature: flip(unhex(k.signature)) })).toBe('bad_signature')
    expect(failure(payload, { ...served, expiresAt: k.context.expiresAt + 60 })).toBe('bad_signature')
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
