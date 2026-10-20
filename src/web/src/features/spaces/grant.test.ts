import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import {
  admissionTarget,
  admissionVerifies,
  grantContext,
  grantSignatureVerifies,
  sealGrant,
  signGrant,
  tagAdmission,
  wrapScopeKeyToMember,
  type GrantScope,
} from './grant'

// The pure half of ADR 0020 move 6 and ADR 0028 Decision 6, one assertion per rule so a weakened branch fails by
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

describe('the admission tag (ADR 0020 move 6)', () => {
  const group = { scopeId: S, kind: 'GROUP' as const }
  const tenant = { scopeId: S, kind: 'TENANT' as const }

  it('a TENANT admission binds no account; every other kind binds its target', () => {
    expect(admissionTarget('TENANT', U)).toBeNull()
    expect(admissionTarget('GROUP', U)).toBe(U)
    expect(admissionTarget('PERSONAL', U)).toBe(U)
  })

  it('tagAdmission mints a fresh UUIDv4 id, or carries the one it is given', () => {
    const k = v1.randomBytes(32)
    const a = tagAdmission(k, group, 2, U)
    const b = tagAdmission(k, group, 2, U)
    expect(a.admissionId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(a.admissionId).not.toBe(b.admissionId)
    expect(a.admissionEpoch).toBe(2)
    expect(tagAdmission(k, group, 2, U, a.admissionId)).toEqual(a) // same id, same key, same tag: deterministic
  })

  it('verifies under the key and epoch it was made at, for the target it binds', () => {
    const k = v1.randomBytes(32)
    const a = tagAdmission(k, group, 2, U)
    expect(admissionVerifies(k, 2, group, U, a)).toBe(true)
    expect(admissionVerifies(v1.randomBytes(32), 2, group, U, a)).toBe(false) // another epoch key
    expect(admissionVerifies(k, 3, group, U, a)).toBe(false) // the holder's epoch is not the tag's
    expect(admissionVerifies(k, 2, group, V, a)).toBe(false) // another target
    expect(admissionVerifies(k, 2, { ...group, scopeId: OTHER }, U, a)).toBe(false) // another scope
  })

  it('an epoch stated differently from the holder\'s does not admit, even with a tag made at the holder\'s epoch', () => {
    const k = v1.randomBytes(32)
    const a = tagAdmission(k, group, 2, U)
    expect(admissionVerifies(k, 2, group, U, { ...a, admissionEpoch: 1 })).toBe(false)
  })

  it('a tenant tag verifies for whoever redeemed the invite — the named residual, not a check to add', () => {
    const k = v1.randomBytes(32)
    const a = tagAdmission(k, tenant, 0, U) // the target is dropped for a tenant admission
    expect(a).toEqual(tagAdmission(k, tenant, 0, null, a.admissionId))
    expect(admissionVerifies(k, 0, tenant, V, a)).toBe(true)
  })

  it('a missing part, or a malformed one, does not verify', () => {
    const k = v1.randomBytes(32)
    const a = tagAdmission(k, group, 0, U)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionId: null })).toBe(false)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionEpoch: null })).toBe(false)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionTag: null })).toBe(false)
    expect(admissionVerifies(k, 0, group, U, {})).toBe(false)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionTag: undefined })).toBe(false)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionId: 'not-a-uuid' })).toBe(false)
    expect(admissionVerifies(k, 0, group, U, { ...a, admissionTag: 'AQ==' })).toBe(false)
  })
})
