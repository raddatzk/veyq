// The apps' format vectors are the web's: every case of src/mobile/words/format-vectors.json is what the web's
// formatters write today (ADR 0154 decision 5). Red when a web formatter changed and the file was not written anew
// (`pnpm exec tsx scripts/format-vectors.mts`), or when the file was edited by hand. The text depends on the ICU of the
// Node that runs it (Intl's month names and spaces): the file is written under the ICU of every Node line `engines` names
// (22, 24 and 26 all ship 78.3), and a run under another ICU major is named first, before the cases it moves.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { formatVectors } from './formatVectors'

const file = JSON.parse(readFileSync(new URL('../../../mobile/words/format-vectors.json', import.meta.url), 'utf8')) as Record<string, unknown>

describe('the apps\' format vectors', () => {
  const now = formatVectors()
  it('were written under this Node\'s ICU', () => {
    const major = (v: unknown) => String(v).split('.')[0]
    expect(major(file.icu), `written under ICU ${String(file.icu)}, run under ${process.versions.icu ?? '?'}: use a Node `
      + 'that package.json\'s engines names, or write the file anew when every one of them moved').toBe(major(process.versions.icu))
  })
  for (const [kind, cases] of Object.entries(now)) {
    it(`${kind}: each case is what the web writes`, () => {
      expect(file[kind]).toEqual(cases)
    })
  }

  it('names no kind the web does not render', () => {
    expect(Object.keys(file).filter((k) => k !== '//' && k !== 'icu').sort()).toEqual(Object.keys(now).sort())
  })
})
