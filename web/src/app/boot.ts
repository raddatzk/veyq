// App boot sequence (uc.admin.setup-server step 1): before any content loads, discover the instance's state and
// route accordingly. A fresh instance lands on first-run setup; an already-set-up instance on the (stubbed)
// login; an unreachable instance surfaces a clear error state rather than a blank/broken screen.
//
// Trimmed for this CR: only the setup-status branch exists. The remembered-session / unlock machinery from
// the legacy boot arrives with the login CR.
import { getSetupStatus } from '../features/account/instance/instance'
import { useServerState } from '../shared/state/useServerState'
import { setCapabilities, useCapabilities } from '../shared/state/useCapabilities'
import { refreshSession } from './sessionRefresh'
import { restoreVaultFromCache } from '../features/account/unlock/unlock'
import { clearDeviceKey } from '../features/account/rememberMe/deviceKeyWrap'
import { clearSessionVault } from '../features/account/rememberMe/sessionVault'
import { maybeDevAutoUnlock } from './devAutoUnlock'
import { listenForSessionEnd } from './sessionEnd'
import { isTenantSuspended } from './tenantSuspension'

/**
 * Discover the instance state and prepare the app shell. The result is reflected in the shared server
 * state, which the router + App shell read to land on the right view.
 */
export async function boot(): Promise<void> {
  // Another tab of this instance may end the session at any moment from here on (uc.account.sign-out E3). The
  // listener ignores the message while this tab holds no session, so installing it first costs nothing.
  listenForSessionEnd()
  const { serverState, setSetupCompleted, setUnreachable, setSuspended } = useServerState()
  const { incompatibility } = useCapabilities()
  setUnreachable(false)
  setSuspended(false)
  try {
    const status = await getSetupStatus()
    // Record the instance's version + capability manifest first — the shell reads it to soft-gate optional
    // features, and to hard-gate on a `major` mismatch (ADR 0032).
    setCapabilities(status)
    setSetupCompleted(!status.setupRequired)
  } catch (err) {
    // This domain's tenant is suspended: the shell arrived, but every API call here is refused — the offline
    // notice, not an "unreachable" error (uc.spaces.scope-access A7).
    if (await isTenantSuspended(err)) setSuspended(true)
    // Otherwise the instance is unreachable — show a clear error state instead of forcing setup or a blank screen.
    else setUnreachable(true)
    return
  }
  // Hard compatibility gate: on a `major` mismatch the shell shows a blocking incompatibility screen before
  // login — so stop here rather than restoring a session or landing on any content view.
  if (incompatibility() !== null) return
  if (!serverState.setupCompleted) return
  // Restore a "remember me" session (cr-0054): the httpOnly refresh cookie revives the session, then the
  // device's password-less DEVICE_KEY wrap re-opens the Master Key — so a browser restart lands straight in the
  // shell, no prompt. All best-effort: no cookie/wrap (or expired) → stays logged out and the router lands on
  // login; with no live session, drop the local device key so it can never outlive its session.
  const restored = await refreshSession().then(() => true).catch(() => false)
  if (restored) await restoreVaultFromCache().catch(() => {})
  else {
    // No live session (no cookie, or expired) → drop both local vault tiers so neither outlives its session.
    await clearSessionVault().catch(() => {})
    await clearDeviceKey(false).catch(() => {})
  }
  // DEV-ONLY (cr-dev-auto-unlock): if still locked, an opt-in (`?devlogin`) local-dev auto-unlock from seed creds.
  // A no-op — and tree-shaken to nothing — in any prod build (import.meta.env.DEV-gated). Best-effort, never blocks.
  await maybeDevAutoUnlock()
}
