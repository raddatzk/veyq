// Scope-grant crypto shared by the grant authorities (cr-0076/cr-0089). A "grant" is a scope content key sealed
// to one member's identity, bound to the scope and the epoch whose key it carries (ADR 0072):
// `sealTo({ purpose: 'scope-grant', scopeId, epoch }, hybridPublic(identity), key)`. Two producers wrap to a
// **foreign** identity — the grant-delivery worker (delivering K_SC to a pending member, cr-0076) and epoch
// rotation (re-granting the fresh epoch key to every remaining member, cr-0089) — so the helper + its AAD/KDF
// context live here, dependency-free of the scope data layer (breaks the scopes ⇄ worker import cycle).
//
// Two more pieces of the same act live here, pure, because every producer and every checker needs them: the **grant
// signature** (ADR 0028 Decision 6) — every grant is signed by its granter's identity key, and the recipient opens it
// only if the signature verifies, since a seal needs only the recipient's public key and the server could otherwise
// plant a scope key of its own choosing — and the **recipient digest** a grant names its key by (ADR 0111).
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
  return b64std(v1.sealTo(grantContext(scopeId, epoch), pub.kem, scopeKey))
}

/** The object a grant is: its scope, that scope's kind (signed, so a group's grant cannot pose as the personal
 *  scope) and the epoch whose key it carries. */
export type GrantScope = { scopeId: string; kind: v1.ScopeKind; epoch: number }
/** Who a grant is for: their account and the identity public bundle (base64) it is sealed to. */
export type GrantRecipient = { userId: string; identityPublicKeyB64: string }
/** A grant as posted: the ciphertext and its granter's signature over it (both base64). */
export type SignedGrant = { wrappedGrant: string; grantSignature: string }

const statementOf = (scope: GrantScope, to: GrantRecipient, wrappedGrantB64: string) =>
  v1.grantStatement({
    scopeId: scope.scopeId,
    scopeKind: scope.kind,
    epoch: scope.epoch,
    recipientUserId: to.userId,
    recipientIdentityPublicKey: fromB64std(to.identityPublicKeyB64),
    wrappedGrant: fromB64std(wrappedGrantB64),
  })

/** Sign a grant with the granter's identity signing key (`Identity['priv'].sign`) → the base64 signature. */
export const signGrant = (signPrivateKey: Uint8Array, scope: GrantScope, to: GrantRecipient, wrappedGrantB64: string): string =>
  b64std(v1.identitySign(signPrivateKey, statementOf(scope, to, wrappedGrantB64)))

/** Wrap `scopeKey` to `to` and sign the result — every grant a client posts goes through here. */
export function sealGrant(scopeKey: Uint8Array, scope: GrantScope, to: GrantRecipient, signPrivateKey: Uint8Array): SignedGrant {
  const wrappedGrant = wrapScopeKeyToMember(scopeKey, scope.scopeId, scope.epoch, to.identityPublicKeyB64)
  return { wrappedGrant, grantSignature: signGrant(signPrivateKey, scope, to, wrappedGrant) }
}

/** Whether `grantSignatureB64` is the granter's signature (under their Ed25519 key `signPublicKey`) over this very
 *  grant. False — never a throw — for malformed bytes: every input here came from the server. */
export function grantSignatureVerifies(
  signPublicKey: Uint8Array, scope: GrantScope, to: GrantRecipient, wrappedGrantB64: string, grantSignatureB64: string,
): boolean {
  try {
    return v1.identityVerify(signPublicKey, statementOf(scope, to, wrappedGrantB64), fromB64std(grantSignatureB64))
  } catch {
    return false
  }
}

/** SHA-256 of the DECODED identity bundle a grant is sealed to (base64) — the grant's `recipientIdentityDigest`, which the
 *  server compares with the key it stores for the recipient (ADR 0111: a grant names the key it is wrapped to). */
export const recipientIdentityDigest = (identityPublicKeyB64: string): string =>
  b64std(v1.sha256Digest(fromB64std(identityPublicKeyB64)))
