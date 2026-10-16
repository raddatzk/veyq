// The session a sign-in finds (uc.account.login A2, inbox #454). Boot revives a session from the refresh cookie and
// may not open the vault (a convenience wrap withheld or gone, a session vault lost with its tab, a sign-out that
// never reached the server), so the device meets the sign-in screen with a live session. Whether the submit
// unlocks INSIDE it or signs in anew is decided by the account it belongs to, which only the server knows: the
// client restored it from an httpOnly cookie it cannot read, and the address the member types is a claim.
//
// Operator-blind as before: the address is the server's own plaintext copy (ADR 0074), read back by the session
// that belongs to it, and nothing here touches a secret. The password and the Secret Key stay with the unlock.
import { sessionApi } from '../../../api/client'
import { clearTokens } from '../../../app/session'
import { withRefresh } from '../../../app/sessionRefresh'
import { getAccountEmail } from '../email/emailConfirmation'

/** Whether two addresses name one account: the one normalization (trim, lower-case) that `deriveAuthSalt` salts
 *  by and the server's `normalizeEmail` stores by, so "the same address" means what a login would mean by it. */
export function sameAccountAddress(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase()
}

/** The address of the account the session this tab holds belongs to, as the server holds it. A session the server
 *  refuses ends through the one ending (ADR 0070), as any refused refresh does; this module grows no second one. */
export async function liveSessionAddress(): Promise<string> {
  return (await getAccountEmail()).email
}

/**
 * End the session this tab holds at the server — revoke its family and drop its tokens — so the login that follows
 * replaces it instead of leaving it listed in Devices and sessions until it expires. Not a sign-out: the device
 * keeps its convenience records and no new document loads, because the screen goes on to sign in. Throws when the
 * server was not reached, so the caller mints no new family beside one that is still live.
 */
export async function leaveLiveSession(): Promise<void> {
  await withRefresh(() => sessionApi().logout())
  clearTokens()
}
