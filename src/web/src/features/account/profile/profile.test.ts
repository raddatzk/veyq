import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { sealName, openMemberName, type SealedMemberName } from './profile'
import { memberNameContext } from './memberName'
import { openValueKey, wrapValueKeyOf } from '../../spaces/valueKey'

// The name-sealing crypto (cr-0083, ADR 0023, ADR 0115): first+last sealed under a fresh value key K_v whose wrap K_tenant
// holds, decrypted only on-device — and bound to (tenant scope, member) (ADR 0072), so the server cannot show one member's
// name on another's row, and a rotator that substitutes the wrap makes the name unreadable, never another one.
describe('member-name sealing', () => {
  const kTenant = { epoch: 0, key: v1.randomBytes(32) }
  const KEVIN = { scopeId: '5c09e000-0000-4000-8000-0000000000c1', userId: '5c09e000-0000-4000-8000-0000000000c2' }
  const OTHER = { ...KEVIN, userId: '5c09e000-0000-4000-8000-0000000000c3' }
  const open = (s: SealedMemberName, at = KEVIN, tenant = kTenant) => openMemberName(tenant, at, s.sealedName, s.wrappedNameValueKey)

  it('round-trips a first/last name through seal → open', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(sealed.sealedName).toMatch(/^[A-Za-z0-9+/]*={0,2}$/) // opaque standard base64, never the plaintext
    expect(sealed.sealedName).not.toContain('Kevin')
    expect(sealed.wrappedNameValueKey).toMatch(/^[A-Za-z0-9+/]*={0,2}$/)
    expect(open(sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('trims the name on seal', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: '  Kevin  ', lastName: '  Raddatz  ' })
    expect(open(sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('returns null for a null/empty blob (a not-yet-sealed member), and for a blob without its wrap', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(openMemberName(kTenant, KEVIN, null, sealed.wrappedNameValueKey)).toBeNull()
    expect(openMemberName(kTenant, KEVIN, undefined, sealed.wrappedNameValueKey)).toBeNull()
    expect(openMemberName(kTenant, KEVIN, '', sealed.wrappedNameValueKey)).toBeNull()
    expect(openMemberName(kTenant, KEVIN, sealed.sealedName, null)).toBeNull()
    expect(open(sealed)).not.toBeNull() // control
  })

  it('returns null under the wrong key or another epoch (a non-member cannot read the name)', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(open(sealed, KEVIN, { epoch: 0, key: v1.randomBytes(32) })).toBeNull()
    expect(open(sealed, KEVIN, { epoch: 1, key: kTenant.key })).toBeNull()
  })

  it('the tenant key alone opens no name: only the K_v its wrap carries', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(() => v1.openField(memberNameContext(KEVIN), kTenant.key, fromB64std(sealed.sealedName))).toThrow(new v1.OpenRefused('member-name'))
    const kv = openValueKey(kTenant, memberNameContext(KEVIN), sealed.wrappedNameValueKey)
    expect(v1.openField(memberNameContext(KEVIN), kv, fromB64std(sealed.sealedName))).toBeInstanceOf(Uint8Array) // control
  })

  it('a substituted wrap reads as no name, not as another name', () => {
    const mine = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(open(mine)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' }) // control
    // A rotator holds the tenant key: it can wrap a K_v of its choosing at this slot — the wrap opens, the name does not.
    expect(open({ ...mine, wrappedNameValueKey: wrapValueKeyOf(kTenant, memberNameContext(KEVIN), v1.generateValueKey()) })).toBeNull()
    // Another member's name and its genuine K_v, moved onto this row: no name here, not theirs.
    const theirs = sealName(kTenant, OTHER, { firstName: 'Mallory', lastName: 'X' })
    const theirKey = openValueKey(kTenant, memberNameContext(OTHER), theirs.wrappedNameValueKey)
    const moved = { sealedName: theirs.sealedName, wrappedNameValueKey: wrapValueKeyOf(kTenant, memberNameContext(KEVIN), theirKey) }
    expect(open(moved)).toBeNull()
    expect(open(theirs, OTHER)).toEqual({ firstName: 'Mallory', lastName: 'X' }) // control at their own address
  })

  it("a name served on another member's row does not open there — it is refused, not shown as theirs", () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(open(sealed, OTHER)).toBeNull()
    expect(() => openValueKey(kTenant, memberNameContext(OTHER), sealed.wrappedNameValueKey)).toThrow(new v1.OpenRefused('value-key'))
    const kv = openValueKey(kTenant, memberNameContext(KEVIN), sealed.wrappedNameValueKey)
    expect(() => v1.openField(memberNameContext(OTHER), kv, fromB64std(sealed.sealedName))).toThrow(new v1.OpenRefused('member-name'))
  })
})
