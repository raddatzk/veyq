import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { EXPECTED_MAJOR } from './capabilities'

// The repository's VERSION file, read from disk rather than through the build's `__VEYQ_MAJOR__`: the gate's major is
// held to the file itself, so a hand-set number between the two is red here (ADR 0085 decision 1).
const versionFileMajor = Number(readFileSync(fileURLToPath(new URL('../../../../../VERSION', import.meta.url)), 'utf8').trim())

describe('EXPECTED_MAJOR', () => {
  it('is the platform major in VERSION, the one the server built from the same tree advertises', () => {
    expect(Number.isInteger(versionFileMajor)).toBe(true)
    expect(EXPECTED_MAJOR).toBe(versionFileMajor)
  })
})
