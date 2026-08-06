// Client-side master-password policy. The server is operator-blind — it never sees the password (only an
// Argon2-derived auth public key), so length/strength can ONLY be checked on the client. Length is the
// dominant strength factor (NIST 800-63B); we deliberately impose NO character-class rules. The HIBP breach
// check (shared/pwned.ts) is the other half. A short password is blocked at submit; a soft, non-blocking
// length hint nudges toward longer.

export const MIN_PASSWORD_LENGTH = 10
const STRONG_PASSWORD_LENGTH = 16

/** True when a (non-empty) password is below the hard minimum — submit is gated on this. */
export const passwordTooShort = (pw: string): boolean => pw.length > 0 && pw.length < MIN_PASSWORD_LENGTH

/** i18n key for a soft length hint, or null in the acceptable middle (10–15 chars). */
export function passwordHintKey(pw: string): string | null {
  if (pw.length === 0) return null
  if (pw.length < MIN_PASSWORD_LENGTH) return 'password.minLength'
  if (pw.length >= STRONG_PASSWORD_LENGTH) return 'password.strong'
  return null
}
