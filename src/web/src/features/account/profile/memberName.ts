// Per-tenant member name — the real name (first + last) **sealed under K_tenant** (cr-0083 / ADR 0023).
// Operator-blind: AEAD-sealed on-device, bound to (tenant scope, member) (ADR 0072) so the server cannot show one
// member's name on another's row; only the ciphertext blob crosses the API. Kept in its own crypto-only module (no api/client or Vue deps) so the dev bootstrap script
// (Node/tsx) can seal identically without pulling in the app graph.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** Whose name it is: the tenant scope it is sealed in and the member it names (ADR 0072). */
export type MemberNameAddress = { scopeId: string; userId: string }

const context = (a: MemberNameAddress) => ({ purpose: 'member-name', scopeId: a.scopeId, userId: a.userId }) as const
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** A member's real name (decrypted, on-device only). */
export interface MemberName {
  firstName: string
  lastName: string
}

/** Seal a first/last name under `K_tenant` → opaque base64 blob the server stores verbatim. */
export function sealName(kTenant: Uint8Array, address: MemberNameAddress, name: MemberName): string {
  const json = encoder.encode(JSON.stringify({ firstName: name.firstName.trim(), lastName: name.lastName.trim() }))
  return b64std(v1.sealField(context(address), kTenant, json))
}

/** Decrypt a sealed-name blob with `K_tenant` → the name, or null if absent/unreadable (wrong key, corrupt). */
export function unsealName(
  kTenant: Uint8Array, address: MemberNameAddress, sealedName: string | null | undefined,
): MemberName | null {
  if (!sealedName) return null
  try {
    const obj = JSON.parse(decoder.decode(v1.openField(context(address), kTenant, fromB64std(sealedName))))
    if (typeof obj?.firstName === 'string' && typeof obj?.lastName === 'string') {
      return { firstName: obj.firstName, lastName: obj.lastName }
    }
  } catch {
    // wrong key / another member's name / corrupt / not-yet-sealed — treated as "no readable name"
  }
  return null
}
