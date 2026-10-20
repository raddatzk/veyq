import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { MANIFEST_APP_VERSION } from './manifest'

// Read from disk, not through the build's `__VEYQ_MAJOR__`, so the manifest is held to the file itself.
const versionFile = readFileSync(fileURLToPath(new URL('../../../../../VERSION', import.meta.url)), 'utf8').trim()

describe('MANIFEST_APP_VERSION', () => {
  it('is the platform major alone, never a release: the bundle that writes it is served before any login (ADR 0057 §4)', () => {
    expect(MANIFEST_APP_VERSION).toMatch(/^[0-9]+$/)
    expect(MANIFEST_APP_VERSION).toBe(versionFile)
  })
})
