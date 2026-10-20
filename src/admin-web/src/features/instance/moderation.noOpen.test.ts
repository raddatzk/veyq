// @vitest-environment node
// admin-web mints the instance moderation key and never opens it (ADR 0099 § 3: "It never opens a grant"; CR
// cr-moderators-and-the-moderation-key phase 5). It cannot open one by construction, and this is the construction:
// the minting facade and every file of the CLI import no function from `@veyq/crypto` that opens a seal or holds a
// private key, and no namespace (`import * as v1`) through which such a function could be reached by name.
//
// A source-level assertion on purpose: what is held is that the code CANNOT reach an open function, which no
// behavioural test of the mint can show (a mint that never happens to call `openAs` proves only that path).
// The file list is derived from the directories, so a CLI file added later is covered without being listed.
// LIMIT: it reads the import statements of these files, not the modules they reach — the CLI signs in through
// `features/auth/login.ts`, which opens the vault for sign-in by design and is not a minter.
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const SRC = join(__dirname, '..', '..')
const FORBIDDEN = [
  'openAs',
  'open',
  'openField',
  'openFieldWith',
  'fieldKeyOf',
  'moderationKemSecret',
  'moderationSign',
  'identityPrivate',
  'openOrderReference',
] as const

function filesUnder(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return filesUnder(path)
    return /\.(ts|vue)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : []
  })
}

/** Every import of `@veyq/crypto/*` in `text`: the names it binds (by their exported name) and whether it is a
 *  namespace import. Covers `import { a, b as c }`, `import * as x`, `import x, { … }` and `export { … } from`. */
function cryptoImports(text: string): { names: string[]; namespace: boolean } {
  const names: string[] = []
  let namespace = false
  const statement = /(?:import|export)\s+(type\s+)?([^'";]*?)\s+from\s+['"]@veyq\/crypto(?:\/[^'"]*)?['"]/g
  for (const m of text.matchAll(statement)) {
    const clause = m[2]
    if (/\*\s+as\s+\w+/.test(clause) || /^\*$/.test(clause.trim())) namespace = true
    const braces = clause.match(/\{([^}]*)\}/)
    if (braces) {
      for (const part of braces[1].split(',')) {
        const name = part.trim().replace(/^type\s+/, '').split(/\s+as\s+/)[0].trim()
        if (name) names.push(name)
      }
    }
  }
  // a dynamic import reaches the whole module, as a namespace does
  if (/import\(\s*['"]@veyq\/crypto/.test(text)) namespace = true
  return { names, namespace }
}

// orders.ts seals an authority's reference to the same public half (uc.moderation.authority-order) and opens nothing either.
const scanned = [
  join(SRC, 'features', 'instance', 'moderation.ts'),
  join(SRC, 'features', 'instance', 'orders.ts'),
  ...filesUnder(join(SRC, 'cli')),
]

describe('admin-web mints the moderation key and cannot open it (ADR 0099 § 3)', () => {
  it('the detector finds what it is looking for (positive control)', () => {
    const synthetic = `import { sealTo, openAs as o } from '@veyq/crypto/v1'\nimport * as v1 from "@veyq/crypto/v1"`
    const found = cryptoImports(synthetic)
    expect(found.names).toContain('openAs')
    expect(found.namespace).toBe(true)
    // and stays quiet for what the minter does import
    expect(cryptoImports(`import { sealTo, moderationBundle } from '@veyq/crypto/v1'`)).toEqual({
      names: ['sealTo', 'moderationBundle'],
      namespace: false,
    })
  })

  it('scans the minter and the whole CLI', () => {
    const rel = scanned.map((p) => relative(SRC, p))
    expect(rel).toContain('features/instance/moderation.ts')
    expect(rel).toContain('cli/commands.ts')
    // the minter does import from the crypto package — a scan that read nothing there would pass vacuously
    expect(cryptoImports(readFileSync(scanned[0], 'utf8')).names).toContain('sealTo')
  })

  it.each(scanned.map((p) => [relative(SRC, p), p]))('%s imports no open function and no crypto namespace', (_rel, path) => {
    const { names, namespace } = cryptoImports(readFileSync(path, 'utf8'))
    expect(names.filter((n) => (FORBIDDEN as readonly string[]).includes(n))).toEqual([])
    expect(namespace).toBe(false)
  })
})
