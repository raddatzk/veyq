import { describe, it, expect } from 'vitest'
import { padTo, padToClass, unpad, PadInvalid, PAD_HEADER_LEN } from './pad'

// The one pad (./pad.ts): what it hides is the length, so each case pins a length or a refusal. The vectors pin the
// bytes of one padded revision (v1.conformance.test.ts, field_seal['expense-entry']).
const CLASSES = [512, 2048, 8192, 16384] as const
const bytes = (n: number, fill = 0xab) => new Uint8Array(n).fill(fill)
const refused = (code: PadInvalid['code']) => new PadInvalid(code)

describe('padTo', () => {
  it('writes u32be(len) ‖ bytes ‖ zeros to exactly the padded length', () => {
    const out = padTo(Uint8Array.of(9, 8, 7), 10)
    expect([...out]).toEqual([0, 0, 0, 3, 9, 8, 7, 0, 0, 0])
  })
  it('writes the length big-endian over all four bytes', () => {
    const out = padTo(bytes(0x0102), 0x0102 + PAD_HEADER_LEN)
    expect([...out.slice(0, 4)]).toEqual([0, 0, 1, 2])
  })
  it('takes bytes that fill the pad exactly, and refuses one byte more', () => {
    expect(padTo(bytes(6), 10).length).toBe(10)
    expect(() => padTo(bytes(7), 10)).toThrow(refused('pad_too_large'))
  })
  it('pads nothing to a header and zeros', () => {
    expect([...padTo(new Uint8Array(0), 6)]).toEqual([0, 0, 0, 0, 0, 0])
  })
  it('names its refusal', () => {
    const err = (() => {
      try {
        padTo(bytes(5), 4)
      } catch (e) {
        return e
      }
    })()
    expect(err).toBeInstanceOf(PadInvalid)
    expect((err as PadInvalid).code).toBe('pad_too_large')
    expect((err as PadInvalid).name).toBe('PadInvalid')
    expect((err as PadInvalid).message).toBe('pad_too_large')
  })
})

describe('padToClass', () => {
  it('pads to the smallest class that carries the bytes, so two lengths in one class seal alike', () => {
    expect(padToClass(bytes(1), CLASSES).length).toBe(512)
    expect(padToClass(bytes(508), CLASSES).length).toBe(512)
    expect(padToClass(bytes(509), CLASSES).length).toBe(2048)
    expect(padToClass(bytes(2044), CLASSES).length).toBe(2048)
    expect(padToClass(bytes(2045), CLASSES).length).toBe(8192)
    expect(padToClass(bytes(8189), CLASSES).length).toBe(16384)
    expect(padToClass(bytes(16380), CLASSES).length).toBe(16384)
  })
  it('refuses what not even the largest class carries', () => {
    expect(() => padToClass(bytes(16381), CLASSES)).toThrow(refused('pad_too_large'))
  })
  it('refuses classes that are not ascending anywhere, and takes a single class', () => {
    const notAscending = new RangeError('size classes are not ascending')
    expect(() => padToClass(bytes(1), [2048, 512])).toThrow(notAscending)
    expect(() => padToClass(bytes(1), [512, 512])).toThrow(notAscending)
    expect(() => padToClass(bytes(1), [512, 2048, 1024])).toThrow(notAscending)
    expect(padToClass(bytes(1), [64]).length).toBe(64)
  })
  it('round-trips through unpad', () => {
    const b = Uint8Array.from({ length: 777 }, (_, i) => (i * 31) & 0xff)
    expect([...unpad(padToClass(b, CLASSES))]).toEqual([...b])
  })
})

describe('unpad', () => {
  it('returns the bytes the header counts', () => {
    expect([...unpad(Uint8Array.of(0, 0, 0, 2, 5, 6, 0, 0))]).toEqual([5, 6])
    expect([...unpad(Uint8Array.of(0, 0, 0, 0))]).toEqual([])
  })
  it('reads the header of a view that does not start its buffer', () => {
    const backing = Uint8Array.of(0xff, 0xff, 0, 0, 0, 1, 7, 0)
    expect([...unpad(backing.subarray(2))]).toEqual([7])
  })
  it('refuses a plaintext shorter than the header', () => {
    expect(() => unpad(Uint8Array.of(0, 0, 0))).toThrow(refused('pad_malformed'))
  })
  it('refuses a header that counts past the pad, and takes one that counts to its end', () => {
    expect(() => unpad(Uint8Array.of(0, 0, 0, 3, 1, 2))).toThrow(refused('pad_malformed'))
    expect([...unpad(Uint8Array.of(0, 0, 0, 2, 1, 2))]).toEqual([1, 2])
    expect(() => unpad(Uint8Array.of(0x80, 0, 0, 0, 1, 2))).toThrow(refused('pad_malformed'))
  })
  it('refuses a fill that is not all zeros, at its first and its last byte', () => {
    expect(() => unpad(Uint8Array.of(0, 0, 0, 1, 9, 1, 0, 0))).toThrow(refused('pad_malformed'))
    expect(() => unpad(Uint8Array.of(0, 0, 0, 1, 9, 0, 0, 1))).toThrow(refused('pad_malformed'))
    expect([...unpad(Uint8Array.of(0, 0, 0, 1, 9, 0, 0, 0))]).toEqual([9])
  })
  it('does not hand back a view into the padded plaintext', () => {
    const padded = padTo(Uint8Array.of(1, 2), 8)
    const out = unpad(padded)
    padded[4] = 99
    expect([...out]).toEqual([1, 2])
  })
})
