// Per-tenant member name — the real name (first + last) **sealed under K_tenant** (cr-0083 / ADR 0023).
// Operator-blind: AEAD-sealed on-device (same `symWrap` as favorites/node-meta); only the ciphertext blob
// crosses the API. Kept in its own crypto-only module (no api/client or Vue deps) so the dev bootstrap script
// (Node/tsx) can seal identically without pulling in the app graph.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** AAD/context binding for the sealed real-name blob (like `favorites` for the favorites blob). */
const NAME_CTX = 'member-name'
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** A member's real name (decrypted, on-device only). */
export interface MemberName {
  firstName: string
  lastName: string
}

/** Seal a first/last name under `K_tenant` → opaque base64 blob the server stores verbatim. */
export function sealName(kTenant: Uint8Array, name: MemberName): string {
  const json = encoder.encode(JSON.stringify({ firstName: name.firstName.trim(), lastName: name.lastName.trim() }))
  return b64std(v1.symWrap(kTenant, json, NAME_CTX, v1.randomBytes(12)))
}

/** Decrypt a sealed-name blob with `K_tenant` → the name, or null if absent/unreadable (wrong key, corrupt). */
export function unsealName(kTenant: Uint8Array, sealedName: string | null | undefined): MemberName | null {
  if (!sealedName) return null
  try {
    const obj = JSON.parse(decoder.decode(v1.symUnwrap(kTenant, fromB64std(sealedName), NAME_CTX)))
    if (typeof obj?.firstName === 'string' && typeof obj?.lastName === 'string') {
      return { firstName: obj.firstName, lastName: obj.lastName }
    }
  } catch {
    // wrong key / corrupt / not-yet-sealed — treated as "no readable name"
  }
  return null
}
