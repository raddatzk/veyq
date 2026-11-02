// A verification (ADR 0111 decisions 12–14): after two members compared safety number and chain code, the verifier's
// identity signs that, in this tenant, the verifier at this head of their own key chain confirmed the subject at this
// head of theirs. It is written only into the verifier's own records in the tenant (one row per verifier and subject,
// replaced by a newer one) and read by every member there, sealed under a value key of its own whose wrap the tenant key
// holds (`value-key`, ADR 0115) — the operator reads none of it, and a rotation re-wraps that key and leaves the row.
//
//   - **It counts** while it names the subject's CURRENT head and verifies under the verifier's CURRENT identity — the
//     key at the head the verifier's chain ends at — with that head named as `verifierHead`. A rotation of the subject
//     moves their head, so every verification of them stops counting by itself ("a rotation drops verified").
//   - **A rotation of the verifier re-signs the outgoing key's verifications** in each tenant with the new identity,
//     naming the new head (decision 13). A statement signed by the retired key — a thief's, made after the rotation with
//     a copy of that key — does not count, because it is not the current key's; the thief cannot make the new one's.
//
// The address (tenant, verifier, subject) is both the row's and the statement's, so a row moved to another subject, to
// another verifier's records or into another tenant neither opens nor verifies. Pinned by
// `contracts/crypto/vectors/v1.json` (`#verification`, `field_seal['member-verification']`).
import { concat, lp } from './primitives'
import { openField, sealField } from './seal'
import { identitySign, identityVerify } from './v1'

/** Whose verification of whom, in which tenant: the row's address and the statement's subject. */
export type VerificationAddress = { tenantId: string; verifierUserId: string; subjectUserId: string }

/** The two heads a verification names (each the 32-byte `keyChainHead` of a chain's newest step). */
export type VerificationHeads = { subjectHead: Uint8Array; verifierHead: Uint8Array }

/** A verification as stored inside its seal: the two heads and the verifier's signature over the statement. */
export type Verification = VerificationHeads & { signature: Uint8Array }

export class VerificationInvalid extends Error {
  constructor(reason: string) {
    super(`verification_invalid: ${reason}`)
    this.name = 'VerificationInvalid'
  }
}

export const verificationStatement = (a: VerificationAddress, h: VerificationHeads) =>
  ({
    purpose: 'verification',
    tenantId: a.tenantId,
    verifierUserId: a.verifierUserId,
    subjectUserId: a.subjectUserId,
    subjectHead: h.subjectHead,
    verifierHead: h.verifierHead,
  }) as const

/** The verifier's identity signs the statement (`Identity['priv'].sign`). Deterministic, so the vectors pin it. */
export function signVerification(a: VerificationAddress, h: VerificationHeads, verifierSignPrivateKey: Uint8Array): Verification {
  if (a.verifierUserId === a.subjectUserId) throw new VerificationInvalid('self')
  return { ...h, signature: identitySign(verifierSignPrivateKey, verificationStatement(a, h)) }
}

/** Whether `v` is the signature of `verifierSignPublicKey` over this address and these heads. False — never a throw —
 *  for any malformed part: every byte here came from the server. */
export function verificationVerifies(a: VerificationAddress, v: Verification, verifierSignPublicKey: Uint8Array): boolean {
  try {
    return a.verifierUserId !== a.subjectUserId && identityVerify(verifierSignPublicKey, verificationStatement(a, v), v.signature)
  } catch {
    return false
  }
}

const VERSION = 1
const SIZES = [32, 32, 64] as const

/** `0x01 ‖ lp(subjectHead) ‖ lp(verifierHead) ‖ lp(signature)` — fixed sizes, so every row seals to one length. */
export function encodeVerification(v: Verification): Uint8Array {
  const parts = [v.subjectHead, v.verifierHead, v.signature]
  parts.forEach((p, i) => {
    if (!(p instanceof Uint8Array) || p.length !== SIZES[i]) throw new VerificationInvalid('size')
  })
  return concat(Uint8Array.of(VERSION), ...parts.map(lp))
}

export function decodeVerification(b: Uint8Array): Verification {
  if (b.length < 1 || b[0] !== VERSION) throw new VerificationInvalid('version')
  const out: Uint8Array[] = []
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength)
  let off = 1
  for (const size of SIZES) {
    if (off + 4 > b.length || dv.getUint32(off) !== size || off + 4 + size > b.length) throw new VerificationInvalid('size')
    out.push(b.slice(off + 4, off + 4 + size))
    off += 4 + size
  }
  if (off !== b.length) throw new VerificationInvalid('trailing')
  return { subjectHead: out[0], verifierHead: out[1], signature: out[2] }
}

/** Where a row is sealed: its address and the tenant epoch it was written at. */
export type VerificationSlot = VerificationAddress & { epoch: number }

/** The row's own seal context — what the wrap of its value key is addressed by (v1.ts `wrapValueKey`). */
export const verificationContext = (s: VerificationSlot) =>
  ({
    purpose: 'member-verification',
    scopeId: s.tenantId,
    userId: s.verifierUserId,
    subjectUserId: s.subjectUserId,
    epoch: s.epoch,
  }) as const

/** Seal a verification under its fresh value key `K_v`, bound to its address and the epoch it is written at. */
export const sealVerification = (valueKey: Uint8Array, slot: VerificationSlot, v: Verification): Uint8Array =>
  sealField(verificationContext(slot), valueKey, encodeVerification(v))

/** Open a row with the value key its wrap carries; `OpenRefused` for another slot's ciphertext or another `K_v`. */
export const openVerification = (valueKey: Uint8Array, slot: VerificationSlot, envelope: Uint8Array): Verification =>
  decodeVerification(openField(verificationContext(slot), valueKey, envelope))
