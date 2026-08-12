// uc.account.sessions (web) — the session manager's data layer: every active session of the account, and the
// two ways to end one remotely. A thin wrapper over the generated auth client, so the panel mocks this module
// rather than the HTTP runtime.
//
// **Nothing here sends a device name, user-agent or client label — and there is no field to put one in.** A
// session family is nameless on the server by design (ADR 0004): a per-family name blob would let the operator
// match a device across logins and re-link families that are otherwise unlinkable. Names will come from a
// Master-Key-sealed device register joined in on-device; they are a later slice, and this layer must stay
// name-free when that lands too.
//
// Every call needs a **session, not an unlock**: that is what lets a locked device cut off a lost one.
import { sessionApi } from '../../../api/client'
import type { SessionFamily } from '../../../api/generated/auth'

export type { SessionFamily } from '../../../api/generated/auth'

/** Every ACTIVE session family of the caller's account, with the server marking the one you are on. */
export function listSessionFamilies(): Promise<SessionFamily[]> {
  return sessionApi().listSessionFamilies()
}

/**
 * End one session by its family id. Idempotent, and the server answers the same for an unknown or foreign id as
 * for a real one — so a failure here is a transport failure, never "that session did not exist".
 */
export function revokeSessionFamily(id: string): Promise<void> {
  return sessionApi().revokeSessionFamily({ id })
}

/** End every session of the account **except** this one. The caller stays signed in where it is. */
export function revokeOtherSessionFamilies(): Promise<void> {
  return sessionApi().revokeOtherSessionFamilies()
}
