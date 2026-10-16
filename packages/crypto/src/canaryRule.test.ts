// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { collidesWithCiphertext, MIN_CANARY_LENGTH } from './canaryRule'

describe('the canary collision rule both recorders share', () => {
  it('refuses a short value spelled only in the base64/base64url/hex alphabet', () => {
    for (const short of ['city', 'auto', 'abc123', 'ab+/=', 'a_b-c', 'x'.repeat(MIN_CANARY_LENGTH - 1)]) {
      expect(collidesWithCiphertext(short), short).toBe(true)
    }
  })

  it('accepts the same alphabet from MIN_CANARY_LENGTH on — an ISO day is exactly that long', () => {
    expect(MIN_CANARY_LENGTH).toBe(10)
    expect(collidesWithCiphertext('2026-05-01')).toBe(false)
    expect(collidesWithCiphertext('x'.repeat(MIN_CANARY_LENGTH))).toBe(false)
  })

  it('exempts a value with any character base64 and hex never contain, at every length', () => {
    for (const exempt of ['a b', 'Köln', 'x.y', 'ab:c', "it's"]) {
      expect(collidesWithCiphertext(exempt), exempt).toBe(false)
    }
  })
})
