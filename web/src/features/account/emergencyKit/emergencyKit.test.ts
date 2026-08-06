// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { kitPayload, parseKit, importKitFromFile } from './emergencyKit'

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

  it('imports the kit from a file carrying the token (PDF/text path)', async () => {
    const file = new File([`%PDF-1.7 …/Keywords (${kitPayload(data)})… stream`], 'kit.pdf', {
      type: 'application/pdf',
    })
    expect(await importKitFromFile(file)).toEqual(data)
  })

  it('returns null for a file with no kit token', async () => {
    const file = new File(['just a normal note, no kit here'], 'note.txt', { type: 'text/plain' })
    expect(await importKitFromFile(file)).toBeNull()
  })
})
