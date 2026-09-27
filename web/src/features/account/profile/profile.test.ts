import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { sealName, unsealName } from './profile'

// The name-sealing crypto (cr-0083, ADR 0023): first+last sealed under K_tenant, decrypted only on-device — and bound
// to (tenant scope, member) (ADR 0072), so the server cannot show one member's name on another's row.
describe('member-name sealing', () => {
  const kTenant = v1.randomBytes(32)
  const KEVIN = { scopeId: '5c09e000-0000-4000-8000-0000000000c1', userId: '5c09e000-0000-4000-8000-0000000000c2' }
  const OTHER = { ...KEVIN, userId: '5c09e000-0000-4000-8000-0000000000c3' }

  it('round-trips a first/last name through seal → unseal', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(sealed).toMatch(/^[A-Za-z0-9+/]*={0,2}$/) // opaque standard base64, never the plaintext
    expect(sealed).not.toContain('Kevin')
    expect(unsealName(kTenant, KEVIN, sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('trims the name on seal', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: '  Kevin  ', lastName: '  Raddatz  ' })
    expect(unsealName(kTenant, KEVIN, sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('returns null for a null/empty blob (a not-yet-sealed member)', () => {
    expect(unsealName(kTenant, KEVIN, null)).toBeNull()
    expect(unsealName(kTenant, KEVIN, undefined)).toBeNull()
    expect(unsealName(kTenant, KEVIN, '')).toBeNull()
  })

  it('returns null under the wrong key (a non-member cannot read the name)', () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(unsealName(v1.randomBytes(32), KEVIN, sealed)).toBeNull()
  })

  it("a name served on another member's row does not open there — it is refused, not shown as theirs", () => {
    const sealed = sealName(kTenant, KEVIN, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(unsealName(kTenant, OTHER, sealed)).toBeNull()
    const context = { purpose: 'member-name', ...OTHER } as const
    expect(() => v1.open(context, kTenant, fromB64std(sealed))).toThrow(new v1.OpenRefused('member-name'))
  })
})
