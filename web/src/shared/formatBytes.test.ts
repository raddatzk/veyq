// A file's size as a row and the storage settings say it: binary steps, the reader's locale.
import { describe, it, expect } from 'vitest'
import { formatBytes } from './formatBytes'

describe('formatBytes', () => {
  it('reads plain bytes below 1 kB and zero as "0 B"', () => {
    expect(formatBytes(0, 'en')).toBe('0 B')
    expect(formatBytes(157, 'en')).toBe('157 B')
    expect(formatBytes(1023, 'de')).toBe('1.023 B')
  })

  it('steps in 1024s with one decimal where there is one', () => {
    expect(formatBytes(1024, 'en')).toBe('1 kB')
    expect(formatBytes(1536, 'en')).toBe('1.5 kB')
    expect(formatBytes(5 * 1024 * 1024, 'en')).toBe('5 MB')
    expect(formatBytes(3.25 * 1024 ** 3, 'en')).toBe('3.3 GB')
    expect(formatBytes(2 * 1024 ** 4, 'en')).toBe('2 TB')
  })

  it('uses the German decimal comma and thousands point', () => {
    expect(formatBytes(1536, 'de')).toBe('1,5 kB')
    expect(formatBytes(123_456, 'de')).toBe('120,6 kB')
    expect(formatBytes(123_456, 'en')).toBe('120.6 kB')
  })

  it('never goes negative', () => {
    expect(formatBytes(-5, 'en')).toBe('0 B')
  })
})
