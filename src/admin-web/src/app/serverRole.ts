// The Server-Owner gate — `admin-web`'s ROOT gate, and the artifact this package had to reconstruct rather
// than lift (ADR 0065): in `src/web/` the same probe only decided whether to OFFER a settings entry, so a
// non-owner simply never saw the link and no refusal surface existed. Here the whole app IS the admin
// surface, so the question "may this caller be here at all" has to be answered before the console renders,
// and answered with something a person can read.
//
// It is a PROBE, not an authority. Authorization is enforced server-side on every single call (403), exactly
// as before; this only decides which of three honest screens to show instead of a blank or crashing page:
//
//   401 / no session  -> sign in            (`unauthenticated`)
//   403               -> refusal, by name   (`forbidden`)   <- the case src/web/ had no surface for
//   200               -> the console        (`owner`)
//
// The probe is `listTenants()` because the backend never volunteers a role: a Server Owner gets the list,
// anyone else gets 403. One probe per session, re-run after a sign-in or sign-out (`resetServerRole`).
import { reactive } from 'vue'
import { listTenants } from '../features/tenants/tenants'
import { httpStatus, HttpStatus } from '../shared/httpError'

export type ServerRoleVerdict = 'unknown' | 'owner' | 'forbidden' | 'unauthenticated' | 'unreachable'

export const serverRole = reactive({ verdict: 'unknown' as ServerRoleVerdict })

/** Probe Server-Owner authority once per session (idempotent). Safe to call from multiple mounts. */
export async function probeServerRole(): Promise<ServerRoleVerdict> {
  if (serverRole.verdict !== 'unknown') return serverRole.verdict
  try {
    await listTenants()
    serverRole.verdict = 'owner'
  } catch (e) {
    const status = httpStatus(e)
    serverRole.verdict =
      status === HttpStatus.unauthorized ? 'unauthenticated'
      : status === HttpStatus.forbidden ? 'forbidden'
      : 'unreachable'
  }
  return serverRole.verdict
}

/** Reset the probe (after a sign-in or sign-out, so the next account re-probes rather than inheriting). */
export function resetServerRole(): void {
  serverRole.verdict = 'unknown'
}
