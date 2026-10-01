// App boot sequence (uc.admin.setup-server step 1): before any content loads, discover the instance's state and
// route accordingly. A fresh instance lands on first-run setup; an already-set-up instance on sign-in, or straight
// into the shell when a remembered session restores; an unreachable instance surfaces a clear error state rather
// than a blank/broken screen.
import { getSetupStatus } from '../features/account/instance/instance'
import { useServerState } from '../shared/state/useServerState'
import { setCapabilities, useCapabilities } from '../shared/state/useCapabilities'
import { isSessionRefusal, refreshSession } from './sessionRefresh'
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
  // shell, no prompt. No cookie/wrap (or expired) → stays logged out and the router lands on login.
  // The rejection is kept, not flattened to "not restored": what the server answered decides the tiers.
  const failure = await refreshSession().then(
    () => null,
    (e: unknown) => ({ e }),
  )
  if (failure === null) await restoreVaultFromCache().catch(() => {})
  else await keepOrDropTiers(failure.e)
  // DEV-ONLY (cr-dev-auto-unlock): if still locked, an opt-in (`?devlogin`) local-dev auto-unlock from seed creds.
  // A no-op — and tree-shaken to nothing — in any prod build (import.meta.env.DEV-gated). Best-effort, never blocks.
  await maybeDevAutoUnlock()
}

/**
 * A boot refresh failed. Only the server refusing the session drops the device's convenience tiers, so neither
 * outlives its session; anything else is no answer about the session, so both stay for the next boot that reaches
 * the instance (ADR 0070 Decision 3, uc.account.sign-out E2). The predicate is the one the refresh path itself
 * uses — never a second copy of the status list here.
 */
async function keepOrDropTiers(e: unknown): Promise<void> {
  const { setUnreachable, setSuspended } = useServerState()
  if (await isSessionRefusal(e)) {
    await clearSessionVault().catch(() => {})
    await clearDeviceKey(false).catch(() => {})
  } else if (await isTenantSuspended(e)) setSuspended(true)
  else setUnreachable(true)
}
