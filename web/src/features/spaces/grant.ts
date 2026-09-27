// Scope-grant crypto shared by the grant authorities (cr-0076/cr-0089). A "grant" is a scope content key sealed
// to one member's identity, bound to the scope and the epoch whose key it carries (ADR 0072):
// `sealTo({ purpose: 'scope-grant', scopeId, epoch }, hybridPublic(identity), key)`. Two producers wrap to a
// **foreign** identity — the grant-delivery worker (delivering K_SC to a pending member, cr-0076) and epoch
// rotation (re-granting the fresh epoch key to every remaining member, cr-0089) — so the helper + its AAD/KDF
// context live here, dependency-free of the scope data layer (breaks the scopes ⇄ worker import cycle).
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** The seal context of a grant: the scope it opens and the epoch whose key it carries (ADR 0072). A grant opened by
 *  its holder is addressed the same way — the scope row it was found in, at that row's granted epoch. */
export const grantContext = (scopeId: string, epoch: number) => ({ purpose: 'scope-grant', scopeId, epoch }) as const

/**
 * Wrap a scope content key to a member's **foreign** identity public key (serialized, base64) → the opaque
 * base64 ciphertext to post as that member's `wrappedGrant`. Fresh nonce each call; the target opens it with
 * their own identity secret at the same (scope, epoch). Used for grant-delivery (cr-0076) and rotation re-grants (cr-0089).
 */
export function wrapScopeKeyToMember(
  scopeKey: Uint8Array, scopeId: string, epoch: number, targetIdentityPublicKeyB64: string,
): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(targetIdentityPublicKeyB64))
  const hybridPub: v1.HybridPublic = { x: pub.kemX, m: pub.kemM }
  return b64std(v1.sealTo(grantContext(scopeId, epoch), hybridPub, scopeKey))
}
