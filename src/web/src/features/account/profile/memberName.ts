// Per-tenant member name — the real name (first + last) **sealed under K_tenant** (cr-0083 / ADR 0023).
// Operator-blind: AEAD-sealed on-device, bound to (tenant scope, member) (ADR 0072) so the server cannot show one
// member's name on another's row; only the ciphertext blob crosses the API. Kept in its own crypto-only module (no api/client or Vue deps) so the dev bootstrap script
// (Node/tsx) can seal identically without pulling in the app graph.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { type ScopeKey } from '../../spaces/epochs'

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

/**
 * A member's sealed name as every reader opens it: under the tenant's one key (ADR 0115 decision 1) — every rotation
 * re-seals every profile under the new key, so a name under any other key is one this reader cannot read. Null when it
 * does not open.
 */
export function openMemberName(
  epochs: ScopeKey, address: MemberNameAddress, sealedName: string | null | undefined,
): MemberName | null {
  return unsealName(epochs.key, address, sealedName)
}

/**
 * A rotation's re-seal of a member's name (ADR 0115 decision 2): the field's plaintext opened under the old tenant key and
 * sealed under the new one as it is, byte for byte. Throws when it does not open under the old key.
 */
export function resealMemberName(oldKey: Uint8Array, newKey: Uint8Array, address: MemberNameAddress, sealedName: string): string {
  return b64std(v1.sealField(context(address), newKey, v1.openField(context(address), oldKey, fromB64std(sealedName))))
}
