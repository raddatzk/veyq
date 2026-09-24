// The complete sign-out (web) — one owner of "end the local session and land on the sign-in screen".
//
// `logout()` alone is not it: it revokes the family and drops the tokens + vault, but leaves the active space
// behind and leaves the user standing on whatever route they were on. Both call sites
// need the whole sequence — the profile menu, and the session manager when you revoke the family you are sitting
// on (uc.account.sessions E2, "sign me out here too"). Two half-copies of a sign-out is how a stale
// `activeSpace` survives into the next account's session, so the sequence lives here once.
//
// `handle401` is deliberately NOT this: it is redirect-only, for a request that came back unauthorized.
import type { Router } from 'vue-router'
import { logout } from './login'
import { clearActiveSpace } from '../../../app/activeSpace'

/** Revoke the current session, drop every local trace of it, and return to the sign-in screen. Idempotent. */
export async function signOut(router: Router): Promise<void> {
  try {
    await logout()
  } finally {
    clearActiveSpace()
    await router.push({ name: 'login' })
  }
}
