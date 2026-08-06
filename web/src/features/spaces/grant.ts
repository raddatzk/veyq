// Scope-grant crypto shared by the grant authorities (cr-0076/cr-0089). A "grant" is a scope content key sealed
// to one member's identity: `asymWrap(hybridPublic(identity), key, 'scope-grant')`. Two producers wrap to a
// **foreign** identity — the grant-delivery worker (delivering K_SC to a pending member, cr-0076) and epoch
// rotation (re-granting the fresh epoch key to every remaining member, cr-0089) — so the helper + its AAD/KDF
// context live here, dependency-free of the scope data layer (breaks the scopes ⇄ worker import cycle).
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

/** The AAD/KDF context binding a scope-key wrap (ADR 0017/0020). A grant opened by the holder uses the same context. */
export const SCOPE_GRANT = 'scope-grant'

/**
 * Wrap a scope content key to a member's **foreign** identity public key (serialized, base64) → the opaque
 * base64 ciphertext to post as that member's `wrappedGrant`. Fresh nonce each call; the target opens it with
 * their own identity secret under {@link SCOPE_GRANT}. Used for grant-delivery (cr-0076) and rotation re-grants (cr-0089).
 */
export function wrapScopeKeyToMember(scopeKey: Uint8Array, targetIdentityPublicKeyB64: string): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(targetIdentityPublicKeyB64))
  const hybridPub: v1.HybridPublic = { x: pub.kemX, m: pub.kemM }
  return b64std(v1.asymWrap(hybridPub, scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }))
}
