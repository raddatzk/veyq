// Where a person is in typing a Secret Key — the one answer `SecretKeyField` draws its block counter and its reasons
// from, and every screen that asks for a key greys its submit with (`secretKeyReady`). One answer, so the counter, the
// reason and the grey button cannot disagree: a button greyed for a reason the field does not show is a dead end.
//
// The kit prints the key in three lines of three blocks (`emergencyKit.ts`), and the first person to sign in with one
// typed the first line only: it looks like a whole key. A key shorter than 54 symbols used to say nothing until the
// submit, which then answered "password or Secret Key didn't match" — so it now never reaches the submit at all.
import { foldCrockford, isCrockford } from '@veyq/crypto/crockford'
import { readSecretKey, SECRET_KEY_SYMBOLS } from '@veyq/crypto/secretKey'

/** Blocks the key is printed in, and symbols per block. */
export const SECRET_KEY_BLOCKS = 9
const BLOCK_SYMBOLS = SECRET_KEY_SYMBOLS / SECRET_KEY_BLOCKS

/**
 * `empty` — nothing typed; `partial` — fewer than 54 symbols, `block` the one being typed (1–9); `foreign` — a
 * character no key has (U, or anything outside the alphabet after folding); `tooLong` — more than 54 symbols; `typo` —
 * 54 symbols that are no key's (the check disagrees); `ready` — a key this client reads. Only `ready` may be submitted.
 */
export type SecretKeyEntry =
  | { state: 'empty' }
  | { state: 'partial'; block: number }
  | { state: 'foreign' }
  | { state: 'tooLong' }
  | { state: 'typo' }
  | { state: 'ready' }

export function secretKeyEntry(typed: string): SecretKeyEntry {
  const folded = foldCrockford(typed)
  if (folded.length === 0) return { state: 'empty' }
  if (!isCrockford(folded)) return { state: 'foreign' }
  if (folded.length > SECRET_KEY_SYMBOLS) return { state: 'tooLong' }
  if (folded.length < SECRET_KEY_SYMBOLS) return { state: 'partial', block: Math.ceil(folded.length / BLOCK_SYMBOLS) }
  const read = readSecretKey(folded)
  if (!read.ok) return { state: 'typo' }
  read.key.fill(0)
  return { state: 'ready' }
}

/** Whether a screen may submit this key: the one condition every Secret-Key screen adds to its submit's `disabled`. */
export const secretKeyReady = (typed: string): boolean => secretKeyEntry(typed).state === 'ready'
