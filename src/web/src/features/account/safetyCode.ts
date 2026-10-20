// The safety-number QR (uc.spaces.enforce-verified-identities, ADR 0028 Decision 3). A member's own profile shows
// a QR of THEIR identity fingerprint; another member's device scans it and compares it with the key it would trust.
//
// **The payload is a marker, a version and the fingerprint digest - nothing else.** `veyqid:1:<base64 of the 32-byte
// digest>`, 53 characters. It never carries the identity bundle: `src/web/CLAUDE.md`'s `jsqr` exception holds only while
// our own payloads stay small, and a bundle is >1 KiB (a version-28 code). The digest is `v1.fingerprintHash`, the one
// the 6x5 digits are rendered from, so a scan and a read-aloud cannot disagree about a key.
//
// The version sits BEFORE the digest on purpose: a standard-base64 digest may start with `/`, and `veyqid:/…` would
// read URL-shaped (the trap `pairing.ts`'s `mintPairingSecret` re-mints around); `veyqid:1:/…` never does.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

const MARKER = 'veyqid:'
const VERSION = 1
const DIGEST_BYTES = 32

/** The digest of a serialized identity bundle (the directory's base64), as the QR carries it and a pin stores it. */
export function fingerprintDigestB64(identityPublicKeyB64: string): string {
  return b64std(v1.fingerprintHash(v1.deserializeIdentityPublic(fromB64std(identityPublicKeyB64))))
}

/** The QR payload for an identity bundle - my own, on my profile. */
export function safetyCodePayload(identityPublicKeyB64: string): string {
  return `${MARKER}${VERSION}:${fingerprintDigestB64(identityPublicKeyB64)}`
}

/** The digest a scanned string carries, or `null` when it is no safety code of ours: another marker, another
 *  version, anything around it, or a digest that is not exactly 32 bytes. Anchored at both ends. */
export function parseSafetyCode(text: string): string | null {
  const m = new RegExp(`^${MARKER}${VERSION}:([A-Za-z0-9+/]+={0,2})$`).exec(text.trim())
  if (!m) return null
  try {
    const digest = fromB64std(m[1])
    return digest.length === DIGEST_BYTES ? b64std(digest) : null // canonical, so the compare is over the bytes
  } catch {
    return null
  }
}

/** What a scan says about the key the caller would trust, given as its fingerprint digest (`fingerprintDigestB64` of the
 *  directory key, or the pin's stored one — ADR 0104 decision 3):
 *  - `foreign`: not a veyq safety code at all (another QR in the room, an Emergency Kit, a pairing code);
 *  - `mismatch`: a safety code of a DIFFERENT key - never accepted;
 *  - `match`: the scanned digest is `expectedFingerprintB64`. */
export type ScanVerdict = 'foreign' | 'mismatch' | 'match'

export function compareScan(text: string, expectedFingerprintB64: string): ScanVerdict {
  const scanned = parseSafetyCode(text)
  if (scanned === null) return 'foreign'
  return scanned === expectedFingerprintB64 ? 'match' : 'mismatch'
}
