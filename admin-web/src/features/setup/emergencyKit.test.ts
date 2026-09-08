// @vitest-environment node
import { describe, expect, it } from 'vitest'
// The write half only — this package's copy has no `importKitFromFile`; see emergencyKit.ts for why.
import { kitPayload, parseKit } from './emergencyKit'

describe('emergencyKit', () => {
  const data = { email: 'a@veyq.app', secretKey: 'KEY-abc_123' }

  it('round-trips through the machine-readable payload', () => {
    expect(parseKit(kitPayload(data))).toEqual(data)
  })

  it('finds the payload embedded in surrounding text (e.g. PDF bytes)', () => {
    const blob = `%PDF-1.7 …/Keywords (${kitPayload(data)})… stream`
    expect(parseKit(blob)).toEqual(data)
  })

  it('returns null for text without a kit token', () => {
    expect(parseKit('just some pdf bytes, no kit here')).toBeNull()
  })

  it('returns null for a malformed payload', () => {
    expect(parseKit('veyqkit:not-valid-base64-json')).toBeNull()
  })

})
