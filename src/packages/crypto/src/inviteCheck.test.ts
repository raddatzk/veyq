// The invitee's checks (ADR 0111 decisions 1 and 19): `checkInvite` against a
// served invite the test builds from the vectors' owner (`#invite_key.owner`, whose chain's step 0 is `#invite_link`'s
// signer head). Each refusal reason has its negatives — each passes every check before its own, so it fails exactly that
// one — beside a positive control on the same predicate. The two probes the CR measured on main are here as regressions:
// an operator's tenant key under a granter it fabricated (#565, `epoch_unlinked`) and a grant signed by the owner's
// retired key after the owner secured the account (#568, `signer_chain_mismatch`).
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'
import * as invite from './invite'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const k = vectors.invite_key
const flip = (b: Uint8Array, i = 0) => {
  const out = b.slice()
  out[i] ^= 1
  return out
}

const TENANT: string = k.context.tenantId
const INVITE: string = k.context.inviteId
const EXPIRES: number = k.context.expiresAt
const OWNER: string = k.owner.user_id
const OTHER = 'c0ffee00-0000-4000-8000-0000000000e0'
const seed = unhex(k.seed)
const inviteKey = invite.inviteKeyFromSeed(seed)
const inviteBundle = v1.serializeIdentityPublic(inviteKey.pub)

/** An account key as a chain step installs it, with the private halves the test signs with. */
type Keys = { bundle: Uint8Array; sign: Uint8Array; proofPub: Uint8Array; proofPriv: Uint8Array }
const freshKeys = (): Keys => {
  const id = v1.generateIdentity()
  const proof = v1.deriveSecretKeyProofKeypair(v1.randomBytes(32))
  return { bundle: v1.serializeIdentityPublic(id.pub), sign: id.priv.sign, proofPub: proof.publicKey, proofPriv: proof.privateKey }
}

// The owner at step 0: the vectors' owner — the key that signed #invite_key, the proof key of #secret_key_proof.
const born: Keys = {
  bundle: unhex(k.owner.bundle),
  sign: unhex(vectors.grant_signature.sign_seed),
  proofPub: unhex(vectors.secret_key_proof.key.public_key),
  proofPriv: unhex(vectors.secret_key_proof.key.private_key),
}
const genesis = (keys: Keys): v1.KeyChainLink => ({
  index: 0, previousHead: null, identitySignature: null, proofSignature: null,
  identityPublicKey: keys.bundle, secretKeyProofPublicKey: keys.proofPub,
})
const headOf0 = (userId: string, keys: Keys) =>
  v1.keyChainHead(v1.keyChainGenesisStatement(userId, { identityPublicKey: keys.bundle, secretKeyProofPublicKey: keys.proofPub }))
/** The step securing the account appends: signed by the retired identity AND the retired proof key. */
const stepTo = (userId: string, index: number, previousHead: Uint8Array, retired: Keys, installed: Keys, proofBy = retired.proofPriv) => {
  const statement = v1.keyChainStepStatement({
    userId, index, previousHead,
    retired: { identityPublicKey: retired.bundle, secretKeyProofPublicKey: retired.proofPub },
    installed: { identityPublicKey: installed.bundle, secretKeyProofPublicKey: installed.proofPub },
  })
  const sigs = v1.signKeyChainStep(statement, retired.sign, proofBy)
  const link: v1.KeyChainLink = {
    index, previousHead, ...sigs, identityPublicKey: installed.bundle, secretKeyProofPublicKey: installed.proofPub,
  }
  return { link, head: v1.keyChainHead(statement) }
}

const head0 = headOf0(OWNER, born)
const secured = freshKeys()
const step1 = stepTo(OWNER, 1, head0, born, secured)

/** A grant of `key` at `epoch` to the invite key, signed by `signKey` — the `grant` statement, kind TENANT. */
const grantTo = (signKey: Uint8Array, epoch: number, key: Uint8Array, wrapped?: Uint8Array) => {
  const wrappedGrant = wrapped ?? v1.sealTo({ purpose: 'scope-grant', scopeId: TENANT, epoch }, inviteKey.pub.kem, key)
  const grantSignature = v1.identitySign(signKey, v1.grantStatement({
    scopeId: TENANT, scopeKind: 'TENANT', epoch, recipientUserId: INVITE, recipientIdentityPublicKey: inviteBundle, wrappedGrant,
  }))
  return { wrappedGrant, grantSignature }
}
/** The succession statement to `epoch` (ADR 0115 decision 5): `newer`'s succession public key signed by `older`'s. */
const succession = (epoch: number, newer: Uint8Array, older: Uint8Array, scopeId = TENANT): v1.SuccessionLink => {
  const successionPublicKey = v1.successionKeyPair(newer).publicKey
  return { epoch, successionPublicKey, successionSignature: v1.signSuccession(older, { scopeId, epoch, successionPublicKey }) }
}
const signInvite = (signKey: Uint8Array) =>
  invite.signInviteKey(signKey, { tenantId: TENANT, inviteId: INVITE, invitePublicKey: inviteBundle, expiresAt: EXPIRES })

const E0 = v1.randomBytes(32)
const E1 = v1.randomBytes(32)
const E2 = v1.randomBytes(32)
const payload: invite.InviteLinkPayload = { seed, signerHead: head0, signerUserId: OWNER }

/** A: the invite as minted — owner at step 0, its grant at epoch 0 is the anchor. */
const anchor0 = grantTo(born.sign, 0, E0)
const fresh: invite.ServedInvite = {
  tenantId: TENANT, inviteId: INVITE, invitePublicKey: inviteBundle, inviteKeySignature: unhex(k.signature), expiresAt: EXPIRES,
  signerUserId: OWNER, signerIdentityPublicKey: born.bundle, signerKeyChain: [genesis(born)],
  anchorWrappedGrant: anchor0.wrappedGrant, anchorEpoch: 0, anchorGrantSignature: anchor0.grantSignature,
  wrappedGrant: anchor0.wrappedGrant, grantedEpoch: 0, grantSignature: anchor0.grantSignature, granterIdentityPublicKey: born.bundle,
  successions: [],
}

/** B: the owner secured the account since (their securing re-signed the invite and the anchor with the new key), and a
 *  co-member G rotated the tenant twice, carrying the invite to epoch 2 under G's own signature. */
const g = freshKeys()
const anchorBySecured = grantTo(secured.sign, 0, E0)
const carried2 = grantTo(g.sign, 2, E2)
const rotated: invite.ServedInvite = {
  ...fresh,
  inviteKeySignature: signInvite(secured.sign),
  signerIdentityPublicKey: secured.bundle,
  signerKeyChain: [genesis(born), step1.link],
  anchorWrappedGrant: anchorBySecured.wrappedGrant, anchorEpoch: 0, anchorGrantSignature: anchorBySecured.grantSignature,
  wrappedGrant: carried2.wrappedGrant, grantedEpoch: 2, grantSignature: carried2.grantSignature, granterIdentityPublicKey: g.bundle,
  successions: [succession(1, E1, E0), succession(2, E2, E1)],
}

const failure = (s: invite.ServedInvite, p: invite.InviteLinkPayload = payload): invite.InviteCheckFailure | null => {
  try {
    invite.checkInvite(p, s)
    return null
  } catch (e) {
    if (e instanceof invite.InviteCheckFailed) return e.reason
    throw e
  }
}
const same = (a: Uint8Array, b: Uint8Array) => Buffer.from(a).equals(Buffer.from(b))

describe('the invitee\'s checks — controls', () => {
  it('A: the invite as minted passes; the tenant key is the anchor\'s E_0, the signer at step 0', () => {
    const checked = invite.checkInvite(payload, fresh)
    expect(same(v1.serializeIdentityPublic(checked.inviteKey.pub), inviteBundle)).toBe(true)
    expect(same(checked.epochKey, E0)).toBe(true)
    expect(checked.grantedEpoch).toBe(0)
    expect(same(checked.signer.head, head0)).toBe(true)
    expect(checked.signer.index).toBe(0)
    expect(same(checked.signer.identityPublicKey, born.bundle)).toBe(true)
  })

  it('B: an owner who secured the account since is accepted at the new key, reached by the doubly signed step; a carried grant whose succession statements lead from the anchor gives E_2', () => {
    const checked = invite.checkInvite(payload, rotated)
    expect(same(checked.epochKey, E2)).toBe(true)
    expect(checked.grantedEpoch).toBe(2)
    expect(same(checked.signer.head, step1.head)).toBe(true)
    expect(checked.signer.index).toBe(1)
    expect(same(checked.signer.identityPublicKey, secured.bundle)).toBe(true)
  })
})

describe('key_mismatch', () => {
  it('a served invite key the seed does not derive', () => {
    const other = v1.serializeIdentityPublic(invite.inviteKeyFromSeed(new Uint8Array(32).fill(9)).pub)
    expect(failure({ ...fresh, invitePublicKey: other })).toBe('key_mismatch')
    expect(failure(fresh)).toBeNull()
  })
})

describe('signer_mismatch', () => {
  it('a served signer that is not the account the seal names', () => {
    expect(failure({ ...fresh, signerUserId: OTHER })).toBe('signer_mismatch')
    expect(failure(fresh)).toBeNull()
  })
})

describe('signer_chain_mismatch', () => {
  const operator = freshKeys()
  it.each([
    // #568, the probe on main: the owner secured the account, and a thief of the old device signs with the retired key.
    ['the retired key, after the owner secured the account', {
      ...rotated,
      signerIdentityPublicKey: born.bundle,
      inviteKeySignature: unhex(k.signature),
      ...(() => { const a = grantTo(born.sign, 0, E0); return { anchorWrappedGrant: a.wrappedGrant, anchorGrantSignature: a.grantSignature } })(),
    }],
    ['a chain the operator invented for the owner, which never passes the link\'s head', {
      ...fresh, signerIdentityPublicKey: operator.bundle, signerKeyChain: [genesis(operator)],
      inviteKeySignature: signInvite(operator.sign),
    }],
    ['a step the retired identity signed alone (the proof signature by another key)', {
      ...rotated, signerKeyChain: [genesis(born), stepTo(OWNER, 1, head0, born, secured, operator.proofPriv).link],
    }],
    ['the head\'s chain, extended by a step to a key the operator holds signed by neither retired key', {
      ...rotated,
      signerIdentityPublicKey: operator.bundle,
      signerKeyChain: [genesis(born), step1.link, stepTo(OWNER, 2, step1.head, operator, operator).link],
      inviteKeySignature: signInvite(operator.sign),
    }],
    ['no chain at all', { ...fresh, signerKeyChain: [] }],
  ] as const)('%s', (_name, served) => {
    expect(failure(served)).toBe('signer_chain_mismatch')
  })

  it('control: the same owner, served at the newest key the genuine chain reaches', () => {
    expect(failure(rotated)).toBeNull()
  })

  it('another account\'s link head refuses the owner\'s genuine chain', () => {
    expect(failure(fresh, { ...payload, signerHead: headOf0(OTHER, born) })).toBe('signer_chain_mismatch')
    expect(failure(fresh, payload)).toBeNull()
  })
})

describe('bad_signature', () => {
  it.each([
    ['a tampered invite-key signature', { ...fresh, inviteKeySignature: flip(unhex(k.signature)) }],
    ['a signature over another expiry', { ...fresh, expiresAt: EXPIRES + 60 }],
    ['the retired key\'s invite-key signature beside the new key', { ...rotated, inviteKeySignature: unhex(k.signature) }],
  ] as const)('%s', (_name, served) => {
    expect(failure(served)).toBe('bad_signature')
  })

  it('control: the invite-key signature by the newest key', () => {
    expect(failure(rotated)).toBeNull()
  })
})

describe('anchor_invalid', () => {
  const operator = freshKeys()
  it.each([
    ['a tampered anchor signature', { ...fresh, anchorGrantSignature: flip(anchor0.grantSignature, 3) }],
    ['an anchor the retired key signed, beside the new key', {
      ...rotated, anchorWrappedGrant: anchor0.wrappedGrant, anchorGrantSignature: anchor0.grantSignature,
    }],
    ['an anchor at another epoch than it was signed at', { ...rotated, anchorEpoch: 1 }],
    ['an anchor the operator signed', (() => {
      const a = grantTo(operator.sign, 0, E0)
      return { ...fresh, anchorWrappedGrant: a.wrappedGrant, anchorGrantSignature: a.grantSignature }
    })()],
  ] as const)('%s', (_name, served) => {
    expect(failure(served)).toBe('anchor_invalid')
  })

  it('control: the anchor the newest key signed', () => {
    expect(failure(rotated)).toBeNull()
  })
})

describe('epoch_unlinked', () => {
  const operator = freshKeys()
  const X = v1.randomBytes(32)
  const Y = v1.randomBytes(32)
  it.each([
    // #565, the probe on main: a grant at epoch 1 of a key the operator chose, under a granter it fabricated, with a
    // statement it signed with another key of its own — it holds no E_0, so no statement it signs starts there.
    ['the operator\'s key under a granter it fabricated', (() => {
      const c = grantTo(operator.sign, 1, X)
      return { ...fresh, wrappedGrant: c.wrappedGrant, grantedEpoch: 1, grantSignature: c.grantSignature, granterIdentityPublicKey: operator.bundle, successions: [succession(1, X, Y)] }
    })()],
    ['a genuine carried grant with a statement missing (a gap)', { ...rotated, successions: [succession(2, E2, E1)] }],
    ['a genuine carried grant with a statement served for another epoch', { ...rotated, successions: [succession(2, E2, E1), { ...succession(1, E1, E0), epoch: 3 }] }],
    ['a genuine carried grant with a statement served twice', { ...rotated, successions: [succession(1, E1, E0), succession(1, E1, E0), succession(2, E2, E1)] }],
    ['a genuine carried grant with a statement signed for another scope', { ...rotated, successions: [succession(1, E1, E0, OTHER), succession(2, E2, E1)] }],
    ['a genuine carried grant whose path skips the anchor (the first statement not signed by E_0)', { ...rotated, successions: [succession(1, E1, Y), succession(2, E2, E1)] }],
    ['a genuine carried grant whose statements end at another key', { ...rotated, successions: [succession(1, E1, E0), succession(2, X, E1)] }],
    ['a carried grant whose signature is not the served granter\'s', { ...rotated, granterIdentityPublicKey: operator.bundle }],
    ['a carried grant below the anchor', (() => {
      const a = grantTo(secured.sign, 2, E2)
      const c = grantTo(g.sign, 1, E1)
      return { ...rotated, anchorWrappedGrant: a.wrappedGrant, anchorEpoch: 2, anchorGrantSignature: a.grantSignature, wrappedGrant: c.wrappedGrant, grantedEpoch: 1, grantSignature: c.grantSignature }
    })()],
    ['a carried grant at the anchor\'s epoch that is not the anchor', (() => {
      const c = grantTo(operator.sign, 0, X)
      return { ...fresh, wrappedGrant: c.wrappedGrant, grantSignature: c.grantSignature, granterIdentityPublicKey: operator.bundle }
    })()],
    ['an anchor the owner signed that does not open', (() => {
      const a = grantTo(born.sign, 0, E0, v1.randomBytes(1200))
      return { ...fresh, anchorWrappedGrant: a.wrappedGrant, anchorGrantSignature: a.grantSignature, wrappedGrant: a.wrappedGrant, grantSignature: a.grantSignature }
    })()],
  ] as const)('%s', (_name, served) => {
    expect(failure(served)).toBe('epoch_unlinked')
  })

  it('control: the genuine carried grant with every statement from the anchor up to it, in any order', () => {
    expect(failure(rotated)).toBeNull()
    expect(failure({ ...rotated, successions: [...rotated.successions].reverse() })).toBeNull()
  })

  it('no statement opens anything: neither the anchor\'s key nor the carried one is in what is served', () => {
    for (const s of rotated.successions) {
      for (const key of [E0, E1, E2]) {
        expect(same(s.successionPublicKey, key)).toBe(false)
        expect(Buffer.from(s.successionSignature).includes(Buffer.from(key))).toBe(false)
      }
    }
  })
})
