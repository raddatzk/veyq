import { describe, it, expect } from 'vitest'
import { sha256Digest, sha256Stream } from './v1'

describe('sha256Stream', () => {
  it('hashes chunk by chunk to the digest of the whole', () => {
    const whole = new Uint8Array(100_000).map((_, i) => (i * 7) & 0xff)
    const h = sha256Stream()
    for (let i = 0; i < whole.length; i += 4097) h.update(whole.subarray(i, i + 4097))
    expect(h.digest()).toEqual(sha256Digest(whole))
  })
  it('matches the standard vector for "abc"', () => {
    const h = sha256Stream()
    h.update(new TextEncoder().encode('a'))
    h.update(new TextEncoder().encode('bc'))
    expect(Array.from(h.digest(), (b) => b.toString(16).padStart(2, '0')).join('')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
})
