import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { sealName, unsealName } from './profile'

// The name-sealing crypto (cr-0083, ADR 0023): first+last sealed under K_tenant, decrypted only on-device.
describe('member-name sealing', () => {
  const kTenant = v1.randomBytes(32)

  it('round-trips a first/last name through seal → unseal', () => {
    const sealed = sealName(kTenant, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(sealed).toMatch(/^[A-Za-z0-9+/]*={0,2}$/) // opaque standard base64, never the plaintext
    expect(sealed).not.toContain('Kevin')
    expect(unsealName(kTenant, sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('trims the name on seal', () => {
    const sealed = sealName(kTenant, { firstName: '  Kevin  ', lastName: '  Raddatz  ' })
    expect(unsealName(kTenant, sealed)).toEqual({ firstName: 'Kevin', lastName: 'Raddatz' })
  })

  it('returns null for a null/empty blob (a not-yet-sealed member)', () => {
    expect(unsealName(kTenant, null)).toBeNull()
    expect(unsealName(kTenant, undefined)).toBeNull()
    expect(unsealName(kTenant, '')).toBeNull()
  })

  it('returns null under the wrong key (a non-member cannot read the name)', () => {
    const sealed = sealName(kTenant, { firstName: 'Kevin', lastName: 'Raddatz' })
    expect(unsealName(v1.randomBytes(32), sealed)).toBeNull()
  })
})
