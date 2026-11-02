// Per-tenant member name — the real name (first + last), sealed under a value key of its own whose wrap `K_tenant` holds
// (cr-0083 / ADR 0023, ADR 0115). Operator-blind: AEAD-sealed on-device, bound to (tenant scope, member) (ADR 0072) so the
// server cannot show one member's name on another's row — the wrap of its key is bound to the same address — and only
// ciphertext crosses the API. Kept in its own crypto-only module (no api/client or Vue deps) so the dev bootstrap script
// (Node/tsx) can seal identically.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { type ScopeKey } from '../../spaces/epochs'
import { openValue, sealValue } from '../../spaces/valueKey'

/** Whose name it is: the tenant scope it is sealed in and the member it names (ADR 0072). */
export type MemberNameAddress = { scopeId: string; userId: string }

/** A member name's own seal context — what the wrap of its value key is addressed by (`value-key`, ADR 0115). */
export const memberNameContext = (a: MemberNameAddress) => ({ purpose: 'member-name', scopeId: a.scopeId, userId: a.userId }) as const
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** A member's real name (decrypted, on-device only). */
export interface MemberName {
  firstName: string
  lastName: string
}

/** A member's name as the wire carries it (ADR 0115): sealed under a fresh value key, and that key's wrap under `K_tenant`. */
export type SealedMemberName = { sealedName: string; wrappedNameValueKey: string }

/** Seal a first/last name under a fresh value key `K_v` (its field key derived from `K_v`) and wrap `K_v` under the tenant
 *  key at its epoch — the two opaque base64 blobs the server stores verbatim. A rotation re-wraps `K_v` and never re-seals
 *  the name, so no rotator can rewrite it. */
export function sealName(tenant: ScopeKey, address: MemberNameAddress, name: MemberName): SealedMemberName {
  const json = encoder.encode(JSON.stringify({ firstName: name.firstName.trim(), lastName: name.lastName.trim() }))
  const context = memberNameContext(address)
  const sealed = sealValue(tenant, context, (valueKey) => v1.sealField(context, valueKey, json))
  return { sealedName: sealed.sealed, wrappedNameValueKey: sealed.wrapped }
}

/**
 * A member's sealed name as every reader opens it: its value key from the wrap under the tenant's one key (ADR 0115
 * decision 1 — every rotation re-wraps every value key under the new one), then the name under that key. Null when either
 * is absent or does not open — the wrong key, another member's name or wrap, a wrap a rotator could not open and staged as
 * one nobody opens, a corrupt blob: "no readable name", never another member's.
 */
export function openMemberName(
  epochs: ScopeKey,
  address: MemberNameAddress,
  sealedName: string | null | undefined,
  wrappedNameValueKey: string | null | undefined,
): MemberName | null {
  if (!sealedName) return null
  const context = memberNameContext(address)
  try {
    const plaintext = openValue(epochs, context, wrappedNameValueKey, (valueKey) =>
      v1.openField(context, valueKey, fromB64std(sealedName)))
    const obj = JSON.parse(decoder.decode(plaintext))
    if (typeof obj?.firstName === 'string' && typeof obj?.lastName === 'string') {
      return { firstName: obj.firstName, lastName: obj.lastName }
    }
  } catch {
    // wrong key / another member's name / a junked wrap / corrupt / not-yet-sealed — treated as "no readable name"
  }
  return null
}
