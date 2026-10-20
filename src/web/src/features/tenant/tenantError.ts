// Decoding a failed tenant call, for the surfaces an ordinary or anonymous user reaches (invite accept,
// terms accept). A domain-named alias over the shared decoder so views map `{ status, code }` without
// importing the generator runtime. `admin-web` carries its own equivalent over its own generated client —
// not shared, because the `ResponseError` a client throws is that client's own type.
import { decodeHttpError } from '../../shared/httpError'

/**
 * Decode a failed tenant call into `{ status, code }` for UI mapping (401 → re-login, 403 → not owner,
 * 409 → domain_taken / tenant_not_pending). Thin domain-named alias over the shared {@link decodeHttpError}.
 */
export function tenantError(e: unknown): Promise<{ status: number; code: string | null }> {
  return decodeHttpError(e)
}
