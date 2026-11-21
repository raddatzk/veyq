// The apps' format vectors are the web's: every case of src/mobile/words/format-vectors.json is what the web's
// formatters write today (ADR 0154 decision 5). Red when a web formatter changed and the file was not written anew
// (`pnpm exec tsx scripts/format-vectors.mts`), or when the file was edited by hand.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { formatVectors } from './formatVectors'

const file = JSON.parse(readFileSync(new URL('../../../mobile/words/format-vectors.json', import.meta.url), 'utf8')) as Record<string, unknown>

describe('the apps\' format vectors', () => {
  const now = formatVectors()
  for (const [kind, cases] of Object.entries(now)) {
    it(`${kind}: each case is what the web writes`, () => {
      expect(file[kind]).toEqual(cases)
    })
  }

  it('names no kind the web does not render', () => {
    expect(Object.keys(file).filter((k) => k !== '//' && k !== 'icu').sort()).toEqual(Object.keys(now).sort())
  })
})
