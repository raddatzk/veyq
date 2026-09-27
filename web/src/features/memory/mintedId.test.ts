import { describe, it, expect } from 'vitest'
import { expectMintedId } from './mintedId'

describe('expectMintedId — the client keeps the id its seals are bound to (ADR 0072)', () => {
  const minted = '5c09e000-0000-4000-8000-0000000000c9'

  it('returns the minted id when the server echoes it', () => {
    expect(expectMintedId(minted, minted)).toBe(minted)
  })

  it('refuses a response that names another id — the object would carry seals its own id does not open', () => {
    expect(() => expectMintedId(minted, '5c09e000-0000-4000-8000-0000000000ca')).toThrow(/^id_substituted$/)
  })
})
