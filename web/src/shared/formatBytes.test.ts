// A file's size as a row says it: decimal steps (what Finder and the file picker print); the tenant's storage says it in
// binary steps with the binary unit names. The reader's locale in both.
import { describe, it, expect } from 'vitest'
import { formatBytes } from './formatBytes'

describe('formatBytes (decimal, the default)', () => {
  it('reads plain bytes below 1 kB and zero as "0 B"', () => {
    expect(formatBytes(0, 'en')).toBe('0 B')
    expect(formatBytes(157, 'en')).toBe('157 B')
    expect(formatBytes(999, 'de')).toBe('999 B')
  })

  it('steps in 1000s with one decimal where there is one, so 8800 bytes read 8.8 kB like Finder', () => {
    expect(formatBytes(1000, 'en')).toBe('1 kB')
    expect(formatBytes(8800, 'en')).toBe('8.8 kB')
    expect(formatBytes(1_500_000, 'en')).toBe('1.5 MB')
    expect(formatBytes(3_250_000_000, 'en')).toBe('3.3 GB')
    expect(formatBytes(2_000_000_000_000, 'en')).toBe('2 TB')
  })

  it('uses the German decimal comma', () => {
    expect(formatBytes(1500, 'de')).toBe('1,5 kB')
    expect(formatBytes(123_456, 'de')).toBe('123,5 kB')
    expect(formatBytes(123_456, 'en')).toBe('123.5 kB')
  })

  it('never goes negative', () => {
    expect(formatBytes(-5, 'en')).toBe('0 B')
  })
})

describe('formatBytes (binary)', () => {
  it('steps in 1024s and names the unit KiB, MiB, GiB, TiB', () => {
    expect(formatBytes(1023, 'en', 'binary')).toBe('1,023 B')
    expect(formatBytes(1024, 'en', 'binary')).toBe('1 KiB')
    expect(formatBytes(5 * 1024 ** 2, 'en', 'binary')).toBe('5 MiB')
    expect(formatBytes(3.25 * 1024 ** 3, 'en', 'binary')).toBe('3.3 GiB')
    expect(formatBytes(2 * 1024 ** 4, 'de', 'binary')).toBe('2 TiB')
  })
})
