// The safety-number QR (uc.spaces.enforce-verified-identities, ADR 0028 Decision 3, ADR 0111 decision 14). A member's own
// profile shows a QR of THEIR identity fingerprint and key-chain head; another member's device scans it and compares both
// with the key and head it would trust.
//
// **The payload is a marker, a version, the fingerprint digest and the head - nothing else.** `veyqid:2:<base64 of the
// 32-byte digest>:<base64 of the 32-byte head>`, 98 characters. It never carries the identity bundle: `src/web/CLAUDE.md`'s
// `jsqr` exception holds only while our own payloads stay small, and a bundle is >1 KiB (a version-28 code). The digest is
// `v1.fingerprintHash`, the one the 6x5 digits are rendered from, and the head is the one the chain code's two groups are
// rendered from (`v1.chainCodeDigits`), so a scan and a read-aloud cannot disagree about either.
//
// **The head is what shows a withheld step** (ADR 0111 decision 14): comparing does not change it, so two people see the
// same head unless the server withheld a step from one of them - then the fingerprints may agree while the heads differ,
// and that is a mismatch. A `veyqid:1` code (the fingerprint alone, before the head was in it) is no match either: it is
// read as "the other app is outdated", never compared on the half it carries.
//
// The version sits BEFORE the digest on purpose: a standard-base64 digest may start with `/`, and `veyqid:/…` would
// read URL-shaped (the trap `pairing.ts`'s `mintPairingSecret` re-mints around); `veyqid:2:/…` never does. Standard base64
// holds no `:`, so the two fields split unambiguously.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const MARKER = 'veyqid:'
const VERSION = 2
/** The version before the head was in the payload: still recognised, only to say the other app is outdated. */
const OUTDATED_VERSION = 1
const DIGEST_BYTES = 32
const B64 = '[A-Za-z0-9+/]+={0,2}'

/** The digest of a serialized identity bundle (the directory's base64), as the QR carries it and a pin stores it. */
export function fingerprintDigestB64(identityPublicKeyB64: string): string {
  return b64std(v1.fingerprintHash(v1.deserializeIdentityPublic(fromB64std(identityPublicKeyB64))))
}

/** The chain code (two groups of five digits) of a key-chain head (base64), as a person reads it aloud beside the safety
 *  number (ADR 0111 decision 14, `v1.chainCodeDigits`). Throws on a head that is not 32 bytes. */
export function chainCode(headB64: string): string {
  return v1.chainCodeDigits(fromB64std(headB64))
}

/** The QR payload for an identity bundle and the head of its key chain - my own, on my profile. Throws on a head that is
 *  not 32 bytes. */
export function safetyCodePayload(identityPublicKeyB64: string, headB64: string): string {
  const head = canonical(headB64)
  if (head === null) throw new Error('safety code: not a 32-byte head')
  return `${MARKER}${VERSION}:${fingerprintDigestB64(identityPublicKeyB64)}:${head}`
}

/** A base64 field that decodes to exactly 32 bytes, re-encoded canonically (so a compare is over the bytes), else null. */
function canonical(b64: string): string | null {
  try {
    const bytes = fromB64std(b64)
    return bytes.length === DIGEST_BYTES ? b64std(bytes) : null
  } catch {
    return null
  }
}

/** What a scanned string carries: a current code (fingerprint and head), an outdated one (`veyqid:1`, the fingerprint
 *  alone), or `null` when it is no safety code of ours - another marker, another version, anything around it, or a field
 *  that is not exactly 32 bytes. Anchored at both ends. */
export type ParsedSafetyCode = { version: 2; fingerprint: string; head: string } | { version: 1; fingerprint: string }

export function parseSafetyCode(text: string): ParsedSafetyCode | null {
  const trimmed = text.trim()
  const current = new RegExp(`^${MARKER}${VERSION}:(${B64}):(${B64})$`).exec(trimmed)
  if (current) {
    const fingerprint = canonical(current[1])
    const head = canonical(current[2])
    return fingerprint && head ? { version: 2, fingerprint, head } : null
  }
  const outdated = new RegExp(`^${MARKER}${OUTDATED_VERSION}:(${B64})$`).exec(trimmed)
  if (outdated) {
    const fingerprint = canonical(outdated[1])
    return fingerprint ? { version: 1, fingerprint } : null
  }
  return null
}

/** What a scan says about the key and head the caller would trust (`fingerprintDigestB64` of the directory key and the
 *  head served beside it, both anchored):
 *  - `foreign`: not a veyq safety code at all (another QR in the room, an Emergency Kit, a pairing code);
 *  - `outdated`: a well-formed `veyqid:1` code - the other app is too old to show its head; nothing is compared;
 *  - `mismatch`: a code of a DIFFERENT key or a DIFFERENT head (a step withheld from one side) - never accepted;
 *  - `match`: the scanned digest AND head are the expected ones. */
export type ScanVerdict = 'foreign' | 'outdated' | 'mismatch' | 'match'

export function compareScan(text: string, expected: { fingerprint: string; head: string }): ScanVerdict {
  const scanned = parseSafetyCode(text)
  if (scanned === null) return 'foreign'
  if (scanned.version === 1) return 'outdated'
  return scanned.fingerprint === expected.fingerprint && scanned.head === canonical(expected.head) ? 'match' : 'mismatch'
}
