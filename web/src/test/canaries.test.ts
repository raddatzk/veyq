// @vitest-environment node
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { ENVELOPE_VOCABULARY, MIN_CANARY_LENGTH, collidesWithCiphertext, recordCanaries } from './canaries'

// inbox #323: a short canary spelled only in the base64/hex alphabet turns the statement-log sweep red on random
// ciphertext. The recorder refuses one — these pin that it does, exactly at the floor, and that it does not over-reach.
describe('recordCanaries — the minimum canary length', () => {
  let dir: string
  let file: string
  const recorded = (): string[] => JSON.parse(readFileSync(file, 'utf8')) as string[]

  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'veyq-canaries-'))
    file = join(dir, 'canaries.json')
    process.env.VEYQ_CANARY_FILE = file
  })
  afterAll(() => {
    delete process.env.VEYQ_CANARY_FILE
    rmSync(dir, { recursive: true, force: true })
  })

  it('refuses a base64-alphabet canary one character under the floor, and records nothing of that call', () => {
    const short = 'Teneriffa' // 9: the name the location suites used before #323
    expect(short).toHaveLength(MIN_CANARY_LENGTH - 1)
    expect(() => recordCanaries({ name: short, title: 'A perfectly long title' })).toThrow(/"Teneriffa".*inbox #323/)
    expect(() => recordCanaries('auto-ish')).toThrow(/shorter than 10/)
    recordCanaries('Positive control name')
    expect(recorded()).not.toContain('A perfectly long title')
  })

  it('accepts one at the floor — an ISO day is exactly that long and has no longer spelling', () => {
    recordCanaries({ date: { from: '2026-05-01' } })
    expect('2026-05-01').toHaveLength(MIN_CANARY_LENGTH)
    expect(recorded()).toContain('2026-05-01')
  })

  it('exempts a short canary with any character outside the alphabet — it cannot occur inside base64 or hex', () => {
    recordCanaries('Boulder gym', 'Kö', 'a.b')
    expect(recorded()).toEqual(expect.arrayContaining(['Boulder gym', 'Kö', 'a.b']))
  })

  it("leaves an envelope's closed enum values out instead of refusing the envelope", () => {
    recordCanaries({ v: 1, location: { name: 'Teneriffa Süd', nameSource: 'manual', derivedMode: 'auto', nameAnchor: { level: 'city' } } })
    expect(recorded()).toContain('Teneriffa Süd')
    expect(recorded()).not.toContain('manual')
    expect(recorded()).not.toContain('auto')
    expect(recorded()).not.toContain('city')
  })

  it('is a floor on the encoded alphabet only, and every listed enum value is one it would otherwise refuse', () => {
    expect(collidesWithCiphertext('abcdefghi')).toBe(true)
    expect(collidesWithCiphertext('abcdefghij')).toBe(false)
    expect(collidesWithCiphertext('a+b/c=d_e')).toBe(true)
    expect(collidesWithCiphertext('abc def')).toBe(false)
    // A vocabulary entry long enough to be a canary on its own would be an unexplained hole in the sweep.
    for (const word of ENVELOPE_VOCABULARY) expect(collidesWithCiphertext(word), word).toBe(true)
  })
})
