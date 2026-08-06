// uc.platform.app-shell (web) — the instance is the same-origin host that served the page, so all the
// client needs on boot is its setup status: whether first-run setup is still pending, plus the advertised
// crypto suites. Backed by the contracts-generated SetupApi.
import { setupApi } from '../../../api/client'
import type { SetupStatus } from '../../../api/generated'

/** Fetch the instance's setup status (drives the setup vs login branch on boot). */
export function getSetupStatus(): Promise<SetupStatus> {
  return setupApi().getSetupStatus()
}
