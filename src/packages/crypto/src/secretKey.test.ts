import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { sha256 } from '@noble/hashes/sha2.js'
import { formatSecretKey, readSecretKey, secretKeyBytes, SecretKeyUnreadable } from './secretKey'

// The vectors come from an independent Python implementation (contracts/crypto/vectors/v1.json § secret_key_text);
// never recompute them from this module, or they prove nothing.
const here = dirname(fileURLToPath(import.meta.url))
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8'))
const unhex = (h: string) => Uint8Array.from(Buffer.from(h, 'hex'))
const CASES = (vectors.secret_key_text.cases as { key: string; text: string }[]).map((c) => ({ key: unhex(c.key), text: c.text }))
const [RAMP] = CASES

/** The symbol at folded position `i` of a printed key, replaced by `to`, hyphens kept. */
function swapSymbol(text: string, i: number, to: string): string {
  const at = i + Math.floor(i / 6)
  return text.slice(0, at) + to + text.slice(at + 1)
}

describe('the Secret Key text form', () => {
  it.each(CASES)('writes $text', ({ key, text }) => {
    expect(formatSecretKey(key)).toBe(text)
  })

  it.each(CASES)('reads $text back to its bytes', ({ key, text }) => {
    expect(readSecretKey(text)).toEqual({ ok: true, key, text })
    expect(secretKeyBytes(text)).toEqual(key)
  })

  it('is 9 groups of 6 symbols', () => {
    expect(RAMP.text).toMatch(/^([0-9A-HJKMNP-TV-Z]{6}-){8}[0-9A-HJKMNP-TV-Z]{6}$/)
  })

  it('refuses to write a key that is not 32 bytes', () => {
    expect(() => formatSecretKey(new Uint8Array(31))).toThrow('formatSecretKey: a Secret Key is 32 bytes')
    expect(() => formatSecretKey(new Uint8Array(33))).toThrow('formatSecretKey: a Secret Key is 32 bytes')
  })

  describe('reads what a person types', () => {
    it('in any case, with spaces or without hyphens', () => {
      const ok = { ok: true, key: RAMP.key, text: RAMP.text }
      expect(readSecretKey(RAMP.text.toLowerCase())).toEqual(ok)
      expect(readSecretKey(RAMP.text.replace(/-/g, ''))).toEqual(ok)
      expect(readSecretKey(` ${RAMP.text.replace(/-/g, ' ')}\n`)).toEqual(ok)
    })

    it('reading O as 0 and I and L as 1', () => {
      const zero = CASES.find((c) => c.text.startsWith('000000'))!
      const one = CASES.find((c) => c.text.includes('1'))!
      expect(readSecretKey(zero.text.replace(/0/g, 'O'))).toMatchObject({ ok: true, text: zero.text })
      expect(readSecretKey(one.text.replace(/1/g, 'I'))).toMatchObject({ ok: true, text: one.text })
      expect(readSecretKey(one.text.replace(/1/g, 'l'))).toMatchObject({ ok: true, text: one.text })
    })
  })

  describe('a typo — 54 symbols that no key prints as', () => {
    it('one wrong symbol in the body', () => {
      expect(readSecretKey(swapSymbol(RAMP.text, 10, RAMP.text[11] === 'A' ? 'B' : 'A'))).toEqual({ ok: false, problem: 'typo' })
    })

    it('one wrong check symbol', () => {
      expect(readSecretKey(swapSymbol(RAMP.text, 53, RAMP.text.at(-1) === 'A' ? 'B' : 'A'))).toEqual({ ok: false, problem: 'typo' })
    })

    it('two neighbours swapped', () => {
      const t = RAMP.text
      expect(t[0]).not.toBe(t[1]) // the swap has to change something
      expect(readSecretKey(t[1] + t[0] + t.slice(2))).toEqual({ ok: false, problem: 'typo' })
    })

    it('a last body symbol with the bits no key sets', () => {
      // Same bytes, same check: only the four padding bits of symbol 52 differ — the check alone would not see it.
      const zero = CASES.find((c) => c.text.startsWith('000000'))!
      expect(readSecretKey(swapSymbol(zero.text, 51, '1'))).toEqual({ ok: false, problem: 'typo' })
    })

    it('throws it as SecretKeyUnreadable("typo")', () => {
      const typo = swapSymbol(RAMP.text, 0, RAMP.text[0] === 'A' ? 'B' : 'A')
      expect(() => secretKeyBytes(typo)).toThrow(SecretKeyUnreadable)
      expect(() => secretKeyBytes(typo)).toThrow(
        expect.objectContaining({ problem: 'typo', name: 'SecretKeyUnreadable', message: 'secret key unreadable: typo' }),
      )
    })
  })

  describe('malformed — not 54 Crockford symbols', () => {
    it.each([
      ['empty', ''],
      ['53 symbols', RAMP.text.slice(0, -1)],
      ['55 symbols', RAMP.text + '0'],
      ['a U, which is no symbol', swapSymbol(RAMP.text, 5, 'U')],
      ['a character outside the alphabet', swapSymbol(RAMP.text, 5, '*')],
      ['the 32 bytes as base64', 'AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw='],
    ])('%s', (_, text) => {
      expect(readSecretKey(text)).toEqual({ ok: false, problem: 'malformed' })
      expect(() => secretKeyBytes(text)).toThrow(expect.objectContaining({ problem: 'malformed' }))
    })
  })

  it('takes its check from the first 10 bits of SHA-256 over the key', () => {
    // The positive control for the vectors' check symbols, spelled out once by hand.
    const h = sha256(RAMP.key)
    const tenBits = ((h[0] << 2) | (h[1] >> 6)) & 0x3ff
    const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
    expect(RAMP.text.slice(-2)).toBe(alphabet[tenBits >> 5] + alphabet[tenBits & 31])
  })
})
