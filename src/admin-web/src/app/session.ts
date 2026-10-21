// App-wide session state. `apiBase` is the same-origin API root ('' → `/api/...`). The opaque session
// tokens (ADR 0004) live here in memory only: an access token sent as the bearer credential and a refresh
// token used to rotate it. Auth ≠ unlock — these gate a session, never the Master Key (no key material here).
import { reactive } from 'vue'

export const session = reactive({
  // same-origin: '' → fetches hit `/api/...` on the serving instance
  apiBase: '',
  accessToken: null as string | null,
  refreshToken: null as string | null,
  familyId: null as string | null,
})

/** Store the tokens from a login or a rotation. `familyId` is set at login and kept across refreshes.
 *  `refreshToken` is **null** for the console's cookie session (`login(…, 'cookie')`, cr-0055): the refresh token
 *  then lives only in the httpOnly cookie and refresh sends an empty body so the browser attaches it — it never
 *  reaches JS. Only the CLI (`'body'`) holds one here. */
export function setTokens(accessToken: string, refreshToken: string | null, familyId?: string): void {
  session.accessToken = accessToken
  session.refreshToken = refreshToken
  if (familyId !== undefined) session.familyId = familyId
}

/** Drop the session (logout / refresh rejected). */
export function clearTokens(): void {
  session.accessToken = null
  session.refreshToken = null
  session.familyId = null
}
