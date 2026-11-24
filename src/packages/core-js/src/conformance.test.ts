import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
// repo contracts/crypto/vectors/v1.json (4 levels up from src/packages/core-js/src/)
const vectors = readFileSync(resolve(here, '../../../../contracts/crypto/vectors/v1.json'), 'utf8')
const PRODUCTION_ARGON_TIMEOUT_MS = 120_000

if (!existsSync(resolve(dist, 'conformance/veyq-mobile-core-conformance.mjs'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
const conformance = await import('../dist/conformance/veyq-mobile-core-conformance.mjs')

// The Kotlin suite over the web's noble primitives, as the JVM's ConformanceTest runs it over BouncyCastle and the
// native LibsodiumConformanceTest over libsodium (ADR 0155 decision 3): one set of vectors, every implementation.
describe('the core compiled for JavaScript meets the v1 vectors', () => {
  it('over @noble/*, at the production Argon2id cost', () => {
    const result = conformance.runConformance(vectors, true)
    expect([...result.failures]).toEqual([])
    expect(result.checked).toBeGreaterThan(200)
  }, PRODUCTION_ARGON_TIMEOUT_MS)

  // The bite: a primitive off by one byte must make the suite red, or the suite proves nothing.
  it('catches a primitive that is off by one byte', () => {
    const failures = [...conformance.runConformanceWithBrokenHkdf(vectors)]
    expect(failures.some((f) => f.startsWith('hkdf'))).toBe(true)
    expect(failures.some((f) => f.startsWith('seal.asym'))).toBe(true)
  })
})

// The conformance is a binary of its own, never an export of the shipped module (ADR 0155 decision 6).
describe('the shipped module', () => {
  const shipped = readdirSync(dist).filter((f) => f.endsWith('.d.mts'))

  it('names no conformance symbol in its definitions', () => {
    // the composition's one declaration file, and each entry's own (`audio`, `expenses`, `export`, `files`, `media`, `navigation`, `search`, `sharing`, `tree`), which re-exports from it
    expect(shipped).toEqual(['audio.d.mts', 'depot.d.mts', 'expenses.d.mts', 'export.d.mts', 'files.d.mts', 'media.d.mts', 'navigation.d.mts', 'search.d.mts', 'sharing.d.mts', 'tree.d.mts', 'veyq-mobile-composition.d.mts'])
    for (const f of shipped) expect(readFileSync(resolve(dist, f), 'utf8')).not.toMatch(/conformance/i)
  })

  it('does not import the conformance runner', () => {
    for (const f of readdirSync(dist).filter((n) => n.endsWith('.mjs'))) {
      expect(readFileSync(resolve(dist, f), 'utf8')).not.toMatch(/conformance/i)
    }
  })
})
