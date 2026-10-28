import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { createHash } from 'node:crypto'
import {
  grantContext,
  grantSignatureVerifies,
  recipientIdentityDigest,
  sealGrant,
  signGrant,
  wrapScopeKeyToMember,
  type GrantScope,
} from './grant'

// The pure half of ADR 0028 Decision 6 and ADR 0111's recipient digest, one assertion per rule so a weakened branch fails by
// name (src/web/stryker.mutate.json scopes this file). The byte formats themselves are pinned by the crypto vectors.
const S = '5c09e000-0000-4000-8000-0000000000c1'
const OTHER = '5c09e000-0000-4000-8000-0000000000c2'
const U = 'a0000000-0000-4000-8000-0000000000c3'
const V = 'b0000000-0000-4000-8000-0000000000c4'
const granter = v1.generateIdentity()
const recipient = v1.generateIdentity()
const recipientPub = b64std(v1.serializeIdentityPublic(recipient.pub))

describe('the grant signature (ADR 0028 Decision 6)', () => {
  const scope: GrantScope = { scopeId: S, kind: 'GROUP', epoch: 3 }
  const to = { userId: U, identityPublicKeyB64: recipientPub }

  it('sealGrant wraps to the recipient at (scope, epoch) and signs exactly that grant', () => {
    const key = v1.randomBytes(32)
    const g = sealGrant(key, scope, to, granter.priv.sign)
    const opened = v1.openAs(grantContext(S, 3), v1.hybridSecret(recipient.priv), fromB64std(g.wrappedGrant))
    expect(Array.from(opened)).toEqual(Array.from(key))
    expect(g.grantSignature).toBe(signGrant(granter.priv.sign, scope, to, g.wrappedGrant))
    expect(grantSignatureVerifies(granter.pub.sign, scope, to, g.wrappedGrant, g.grantSignature)).toBe(true)
  })

  it('verifies only under the granter, only for this grant, this scope, kind, epoch and recipient', () => {
    const g = sealGrant(v1.randomBytes(32), scope, to, granter.priv.sign)
    const ok = (s = scope, t = to, w = g.wrappedGrant, sig = g.grantSignature, pub = granter.pub.sign) =>
      grantSignatureVerifies(pub, s, t, w, sig)
    expect(ok()).toBe(true)
    expect(ok(scope, to, g.wrappedGrant, g.grantSignature, recipient.pub.sign)).toBe(false)
    expect(ok({ ...scope, scopeId: OTHER })).toBe(false)
    expect(ok({ ...scope, kind: 'PERSONAL' })).toBe(false)
    expect(ok({ ...scope, epoch: 4 })).toBe(false)
    expect(ok(scope, { ...to, userId: V })).toBe(false)
    expect(ok(scope, { ...to, identityPublicKeyB64: b64std(v1.serializeIdentityPublic(granter.pub)) })).toBe(false)
    expect(ok(scope, to, wrapScopeKeyToMember(v1.randomBytes(32), S, 3, recipientPub))).toBe(false)
  })

  it('refuses — never throws — on a malformed signature or recipient', () => {
    const g = sealGrant(v1.randomBytes(32), scope, to, granter.priv.sign)
    expect(grantSignatureVerifies(granter.pub.sign, scope, to, g.wrappedGrant, 'AQ==')).toBe(false)
    // Not even base64: the decode throws, and that is a refusal as well.
    expect(grantSignatureVerifies(granter.pub.sign, scope, to, '*not base64*', g.grantSignature)).toBe(false)
    expect(grantSignatureVerifies(granter.pub.sign, scope, { ...to, identityPublicKeyB64: '*not base64*' }, g.wrappedGrant, g.grantSignature)).toBe(false)
    expect(grantSignatureVerifies(granter.pub.sign, scope, { ...to, userId: 'not-a-uuid' }, g.wrappedGrant, g.grantSignature)).toBe(false)
  })
})

describe('the recipient digest (ADR 0111)', () => {
  // Computed here independently of the suite (node's own SHA-256), over the DECODED bundle.
  const sha256b64 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('base64')

  it('is base64 SHA-256 of the decoded identity bundle a grant is sealed to', () => {
    expect(recipientIdentityDigest(recipientPub)).toBe(sha256b64(v1.serializeIdentityPublic(recipient.pub)))
  })

  it('control: it names exactly that key — another bundle, or the base64 text itself, is another digest', () => {
    expect(recipientIdentityDigest(recipientPub)).not.toBe(recipientIdentityDigest(b64std(v1.serializeIdentityPublic(granter.pub))))
    // Over the text rather than the bytes would be a different value: the digest is of what the base64 decodes to.
    expect(recipientIdentityDigest(recipientPub)).not.toBe(sha256b64(new TextEncoder().encode(recipientPub)))
  })
})
