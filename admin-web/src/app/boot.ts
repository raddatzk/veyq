// App boot for the admin surface: before anything renders, discover whether this instance still needs
// first-run setup, and revive a session if the browser carries one.
//
// This is the sequencing claim ADR 0065 rests on: an instance that has never been set up answers here over
// `http://localhost:<admin-port>` with no DNS, no TLS and no `VEYQ_DOMAIN` resolving anywhere — because the
// admin container reverse-proxies the backend from its own origin. Setup used to live in the app SPA served
// at `VEYQ_DOMAIN`, which made a public hostname a precondition for bootstrapping the instance at all.
import { setupApi } from '../api/client'
import { serverState } from '../shared/state/serverState'
import { refreshSession } from './sessionRefresh'

export async function boot(): Promise<void> {
  serverState.unreachable = false
  try {
    const status = await setupApi().getSetupStatus()
    serverState.setupCompleted = !status.setupRequired
  } catch {
    // Unreachable backend: say so, rather than rendering a console that cannot load anything.
    serverState.unreachable = true
    return
  }
  if (!serverState.setupCompleted) return
  // Revive the session from the httpOnly refresh cookie (cr-0055) so a reload does not force a re-sign-in.
  // Best-effort: no cookie, or an expired one, simply leaves the router landing on /login. There is no vault
  // to restore here and no device-key wrap to drop — this surface never holds key material past a derivation.
  await refreshSession().catch(() => {})
}
