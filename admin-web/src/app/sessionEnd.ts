// The one way the console ends a session the server no longer honours (inbox #449): a `401` that survived the
// rotation in `app/sessionRefresh.ts`. Drop the tokens FIRST — the router sends `login` back to `console` while
// an access token is set, which is right for a live session and is exactly what swallowed the old redirect — then
// forget the Server-Owner verdict, leave a word for the sign-in page, and go there.
//
// What the owner was doing is not carried across the sign-in: a `401` is answered before any handler runs, so it
// was not applied, and the sign-in says exactly that. Every form on this surface is a line or two long, and a
// fresh console after the sign-in is the honest state to retry from.
import { reactive } from 'vue'
import type { Router } from 'vue-router'
import { clearTokens } from './session'
import { resetServerRole } from './serverRole'

const notice = reactive({ expired: false })

export async function endExpiredSession(router: Router): Promise<void> {
  clearTokens()
  resetServerRole()
  notice.expired = true
  await router.replace({ name: 'login' })
}

/** Whether the sign-in page should say the session ended — once; reading it clears it. */
export function takeSessionExpired(): boolean {
  const expired = notice.expired
  notice.expired = false
  return expired
}
