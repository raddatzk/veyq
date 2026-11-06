// The Secret Key's TEXT form — what the Emergency Kit prints, what a person types, and what the `veyqkit:` token
// carries (en.account.key-model § The Secret Key's text form). It lives here because it is key material handling:
// every surface that shows, reads or compares a Secret Key goes through these three functions, and a second copy of
// the decode would be one more place a typed key could open a different account than the printed one.
//
// The form: the 32 key bytes as 52 Crockford base32 symbols (`crockford.ts`; big-endian, 5 bits each, the last symbol
// carrying the 256th bit and four zero bits), then 2 check symbols — the first 10 bits of SHA-256 over the 32 bytes —
// 54 symbols in all, written as 9 groups of 6 joined by `-`. A typed key is folded the Crockford way (case, spaces,
// hyphens, O for 0, I and L for 1), so the check is what catches a misread symbol: one wrong symbol slips past it with
// probability 2^-10.
import { sha256 } from '@noble/hashes/sha2.js'
import { CROCKFORD, foldCrockford, isCrockford } from './crockford'

export const SECRET_KEY_BYTES = 32
/** Symbols of a key: 52 for the bytes, 2 for the check. */
export const SECRET_KEY_SYMBOLS = 54
const BODY_SYMBOLS = 52

/** `count` symbols spelling the first `count * 5` of `bits`, most significant first; missing bits read as 0. */
function symbols(bits: number[], count: number): string {
  let out = ''
  for (let i = 0; i < count; i++) {
    let v = 0
    for (let j = 0; j < 5; j++) v = (v << 1) | (bits[i * 5 + j] ?? 0)
    out += CROCKFORD[v]
  }
  return out
}

/** A symbol's 5-bit value as bits, most significant first. */
const symbolBits = (v: number): number[] => [4, 3, 2, 1, 0].map((sh) => (v >> sh) & 1)

const bitsOf = (bytes: Uint8Array): number[] => Array.from(bytes, (b) => [7, 6, 5, 4, 3, 2, 1, 0].map((s) => (b >> s) & 1)).flat()

/** The 54 symbols (no hyphens) of a 32-byte key. */
function canonical(key: Uint8Array): string {
  // `symbols` reads the first 2 * 5 = 10 bits of the digest: the check.
  return symbols(bitsOf(key), BODY_SYMBOLS) + symbols(bitsOf(sha256(key)), 2)
}

/** A 32-byte Secret Key as it is printed and shown: 9 groups of 6 symbols joined by `-`. */
export function formatSecretKey(key: Uint8Array): string {
  if (key.length !== SECRET_KEY_BYTES) throw new Error('formatSecretKey: a Secret Key is 32 bytes')
  return canonical(key).match(/.{6}/g)!.join('-')
}

/**
 * Why a typed Secret Key is not one: `malformed` — not 54 Crockford symbols at all (too short, too long, a symbol
 * outside the alphabet); `typo` — 54 symbols, but not the ones any key prints as (the check symbols disagree, or the
 * last body symbol carries bits a key never sets): a symbol was misread or mistyped.
 */
export type SecretKeyProblem = 'malformed' | 'typo'

export type SecretKeyReading = { ok: true; key: Uint8Array; text: string } | { ok: false; problem: SecretKeyProblem }

/** Read a typed or pasted Secret Key. On success `key` is its 32 bytes and `text` its printed form. */
export function readSecretKey(typed: string): SecretKeyReading {
  const s = foldCrockford(typed)
  if (s.length !== SECRET_KEY_SYMBOLS || !isCrockford(s)) return { ok: false, problem: 'malformed' }
  // The key is the first 256 bits the 54 symbols spell; the rest (the 52nd symbol's four zero bits and the check) is
  // what the re-spelling below compares, so nothing here needs to stop at the 52nd symbol.
  const bits = [...s].flatMap((c) => symbolBits(CROCKFORD.indexOf(c)))
  const key = Uint8Array.from({ length: SECRET_KEY_BYTES }, (_, i) =>
    // Indexed, not sliced: a wrong slice start would only add leading bits, which the byte's truncation hides.
    [0, 1, 2, 3, 4, 5, 6, 7].reduce((v, j) => (v << 1) | bits[i * 8 + j], 0),
  )
  // Re-spelling the decoded bytes is the whole check: it compares the check symbols AND the four bits of the last body
  // symbol that no key sets, so every 54-symbol string but the one this key prints as is a typo.
  if (canonical(key) !== s) {
    key.fill(0)
    return { ok: false, problem: 'typo' }
  }
  return { ok: true, key, text: formatSecretKey(key) }
}

/** Thrown by {@link secretKeyBytes} for a string that is not a Secret Key; `problem` says which way. */
export class SecretKeyUnreadable extends Error {
  constructor(readonly problem: SecretKeyProblem) {
    super(`secret key unreadable: ${problem}`)
    this.name = 'SecretKeyUnreadable'
  }
}

/** The 32 bytes of a typed Secret Key, for the derivations that need them; throws {@link SecretKeyUnreadable}. The
 *  caller owns the returned bytes and zeroes them when done. */
export function secretKeyBytes(typed: string): Uint8Array {
  const r = readSecretKey(typed)
  if (!r.ok) throw new SecretKeyUnreadable(r.problem)
  return r.key
}
