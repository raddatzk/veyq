// @vitest-environment node
// Proves the `expect.addEqualityTesters([byteArrayEquality])` line in `setup.ts` and that it changes only the speed of a
// byte comparison, never its answer. Every case but the first holds with the line removed too (vitest's own rules give
// the same answers, element by element); the first is the one that goes red without it: 8 MiB compared by vitest's
// generic equality take far longer than the test timeout (about 1 µs a byte, measured), as bytes they take milliseconds.
import { describe, expect, it } from 'vitest'
import { Buffer } from 'node:buffer'
import { byteArrayEquality } from './byteEquality'

const bytes = (n: number, seed = 0) => new Uint8Array(n).map((_, i) => (i * 31 + seed) & 0xff)
const differs = (run: () => void) => expect(run).toThrow()

describe('byte arrays in toEqual / toStrictEqual', () => {
  it('compares 8 MiB as bytes, well inside the test timeout (red without the setup line)', () => {
    const a = bytes(8 * 1024 * 1024)
    const b = a.slice()
    expect(b).toEqual(a)
    expect(b).toStrictEqual(a)
    expect({ frames: [b] }).toEqual({ frames: [a] })
  })

  it('a single byte apart, or a length apart, is unequal — at the end, nested, strict or not', () => {
    const a = bytes(4096)
    const last = a.slice()
    last[last.length - 1] ^= 1
    differs(() => expect(last).toEqual(a))
    differs(() => expect(last).toStrictEqual(a))
    differs(() => expect({ k: [last] }).toEqual({ k: [a] }))
    differs(() => expect(a.subarray(0, a.length - 1)).toEqual(a))
    differs(() => expect(new Uint8Array(0)).toEqual(new Uint8Array(1)))
  })

  it('a view is its own bytes, not its buffer from the start', () => {
    const whole = bytes(64)
    expect(whole.subarray(10, 20)).toEqual(Uint8Array.from(whole.slice(10, 20)))
    differs(() => expect(whole.subarray(11, 21)).toEqual(Uint8Array.from(whole.slice(10, 20))))
  })

  it('leaves every other pairing to vitest: a Buffer is not equal to a Uint8Array of the same bytes', () => {
    const a = bytes(32)
    differs(() => expect(Buffer.from(a)).toEqual(a))
    expect(byteArrayEquality(Buffer.from(a), a)).toBeUndefined()
    expect(byteArrayEquality(new Int8Array(4), new Int8Array(4))).toBeUndefined()
    expect(byteArrayEquality(a, [...a])).toBeUndefined()
    expect({ k: a }).toEqual({ k: expect.any(Uint8Array) })
  })
})
