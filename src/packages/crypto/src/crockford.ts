// Crockford's base32 (https://www.crockford.com/base32.html) — the alphabet veyq uses wherever a person types or
// reads a secret: the invite code (`invite.ts`) and the Secret Key's text form (`secretKey.ts`). One copy, because
// the two must fold a typed string the same way: a person who learned that an O may be typed for a 0 on one screen
// meets the same rule on the other.

/** The 32 symbols: digits and capitals without I, L, O and U. A symbol's index is its 5-bit value. */
export const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/**
 * A typed string in the alphabet's canonical spelling: case folded, spaces and hyphens dropped, and the look-alikes
 * read as the digit they stand for (I and L as 1, O as 0). U stays U, which is no symbol — the caller's alphabet check
 * refuses it. Nothing else is checked here.
 */
export function foldCrockford(typed: string): string {
  return typed
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/[IL]/g, '1')
    .replace(/O/g, '0')
}

/** Whether every character of an already folded string is one of the 32 symbols. */
export const isCrockford = (folded: string): boolean => [...folded].every((c) => CROCKFORD.includes(c))
