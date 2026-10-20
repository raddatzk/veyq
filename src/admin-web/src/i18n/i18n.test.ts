// The catalog's own guard. This package's messages were LIFTED from `src/web/`'s (ADR 0065), whole groups for
// some keys and cherry-picked keys for others — and a cherry-pick is exactly where a key goes missing without
// anything failing: vue-i18n renders the key itself, so `password.strong` appears on screen as the literal
// string "password.strong" and every check stays green.
//
// Measured, not hypothetical: that is what shipped. The first live run of the setup screen in the container
// showed `password.strong` under the password field, because `passwordHintKey()` RETURNS a key rather than
// interpolating one in a template — so no static scan of `t('…')` call sites would have found it either.
//
// It shipped a second time in the same run, differently: `EmergencyKitSave.vue` asks for a whole `kit.*`
// group the lift never took, so the Emergency-Kit screen rendered two buttons labelled `kit.download` and
// `kit.backupDownloadConfirm` — on the one screen in this app that must be read carefully, since it is the
// only chance the owner gets to save their Secret Key. Two misses of the same kind in one sitting is a class,
// not an accident, which is why this is a test and not a fixed typo.
//
// Hence four assertions — three aimed at the three ways a key goes missing, one at the way a key outlives
// its use:
//  1. every key a FUNCTION can return resolves — invisible to any scan of `t('…')` call sites;
//  2. every key a TEMPLATE or script asks for by literal resolves — the `kit.*` case;
//  3. EN and DE carry the identical key set — a lift that took one locale's group and not the other's;
//  4. every key the catalog carries is used somewhere — the lift's other failure: whole groups copied from
//     `src/web/` for flows ADR 0065 keeps there (43 of 146 keys when this assertion was written).
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { baseCompile } from '@intlify/message-compiler'
import { VERSION } from 'vue-i18n'
import { createRequire } from 'node:module'
import { i18n, SUPPORTED_LOCALES } from './index'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'

function flatten(o: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) =>
    v && typeof v === 'object' ? flatten(v as Record<string, unknown>, `${prefix}${k}.`) : [`${prefix}${k}`],
  )
}

const messagesOf = (locale: string) =>
  (i18n.global as unknown as { getLocaleMessage(l: string): Record<string, unknown> }).getLocaleMessage(locale)

const keysOf = (locale: string) => new Set(flatten(messagesOf(locale)))

/** Every `t('some.key')` literal in this package's sources. Deliberately a plain scan and not a parse: it
 *  reads the same thing a person greps for, and a call site it cannot see is one a person cannot either. */
function literalKeysInSources(dir: string): Map<string, string> {
  const found = new Map<string, string>()
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'generated') continue // the API client, not ours to translate
      for (const [k, v] of literalKeysInSources(path)) found.set(k, v)
    } else if (entry.name.endsWith('.ts') || entry.name.endsWith('.vue')) {
      if (entry.name.endsWith('.test.ts')) continue // tests build their own throwaway catalogs
      for (const m of readFileSync(path, 'utf8').matchAll(/\bt\(\s*'([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)'/g)) {
        found.set(m[1], path)
      }
    }
  }
  return found
}

/** What the sources could be asking the catalog for: every dotted string they quote in any of the three
 *  quote styles, and the static head of every template literal that builds a key (`` `a.b.${x}` `` → `a.b.`,
 *  and mid-segment `` `a.bc${X}` `` → `a.bc`, which `src/web/` has). The same predicate as web's catalog test.
 *  Deliberately ANY quoted literal and not only `t('…')`'s argument: keys travel through variables here —
 *  `TenantDetailPanel`'s `successKey`, `StoreProvisioningPanel`'s `shown.key` and `passwordHintKey()`'s
 *  return value all reach `t()` one hop later. A key this cannot see is one it calls unused, so a new
 *  spelling is taught here, never allowlisted. Its limit: a template head counts every key under it as used,
 *  so a dead key inside a dynamically built group is invisible. */
function quotedKeysInSources(dir: string, catalog: string): { literals: Set<string>; heads: Set<string> } {
  const literals = new Set<string>()
  const heads = new Set<string>()
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'generated') continue // the API client, not ours to translate
      const inner = quotedKeysInSources(path, catalog)
      inner.literals.forEach((k) => literals.add(k))
      inner.heads.forEach((h) => heads.add(h))
    } else if (entry.name.endsWith('.ts') || entry.name.endsWith('.vue')) {
      if (entry.name.endsWith('.test.ts') || path === catalog) continue // tests build their own catalogs
      const text = readFileSync(path, 'utf8')
      for (const m of text.matchAll(/(['"`])([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+)\1/g)) literals.add(m[2])
      for (const m of text.matchAll(/`((?:[a-zA-Z0-9_]+\.)+[a-zA-Z0-9_]*)\$\{/g)) heads.add(m[1])
    }
  }
  return { literals, heads }
}

describe('admin-web message catalog', () => {
  it('resolves every key `passwordHintKey` can return, in every locale', () => {
    // The three branches of the policy: too short, in between (null), long enough to praise.
    const produced = [
      passwordHintKey('a'.repeat(MIN_PASSWORD_LENGTH - 1)),
      passwordHintKey('a'.repeat(MIN_PASSWORD_LENGTH)),
      passwordHintKey('a'.repeat(64)),
    ].filter((k): k is string => k !== null)

    expect(produced.length).toBeGreaterThan(0) // the case is worthless if the policy returns nothing
    for (const locale of SUPPORTED_LOCALES) {
      for (const key of produced) {
        expect(keysOf(locale), `${locale} is missing ${key}`).toContain(key)
      }
    }
  })

  it('resolves every key the sources ask for by literal, in every locale', () => {
    const asked = literalKeysInSources(new URL('..', import.meta.url).pathname)
    expect(asked.size).toBeGreaterThan(20) // a scan that found almost nothing proves nothing

    for (const locale of SUPPORTED_LOCALES) {
      const have = keysOf(locale)
      const missing = [...asked].filter(([k]) => !have.has(k)).map(([k, where]) => `${k} (${where})`)
      expect(missing, `${locale} is missing keys the sources use`).toEqual([])
    }
  })

  it('uses every key it carries', () => {
    const src = new URL('..', import.meta.url).pathname
    const { literals, heads } = quotedKeysInSources(src, join(src, 'i18n', 'index.ts'))
    expect(literals.size).toBeGreaterThan(20) // a scan that found almost nothing proves nothing

    // EN is enough: the next case holds every other locale to EN's key set.
    const unused = [...keysOf(SUPPORTED_LOCALES[0])].filter(
      (k) => !literals.has(k) && ![...heads].some((h) => k.startsWith(h)),
    )
    expect(unused, 'catalog keys no source uses').toEqual([])
  })

  it('carries the same key set in every locale', () => {
    const [first, ...rest] = SUPPORTED_LOCALES
    const base = keysOf(first)
    for (const locale of rest) {
      const other = keysOf(locale)
      expect([...base].filter((k) => !other.has(k)), `${locale} is missing keys`).toEqual([])
      expect([...other].filter((k) => !base.has(k)), `${locale} has extra keys`).toEqual([])
    }
  })
})

// Every message must also compile under vue-i18n's own message compiler. One that does not still renders — vue-i18n
// falls back to its source text — so none of the key checks above notice it: `src/web/` shipped "Invited by @{user}"
// that way, since a bare `@` opens a linked message (`{'@'}` is the literal). The compiler's own `onError` is the
// mechanism, deliberately not a spy on `console.warn`, which would pass the day vue-i18n changes how it reports.
describe('admin-web message syntax', () => {
  function compileErrors(node: unknown, key: string, errors: string[]): number {
    if (typeof node === 'string') {
      baseCompile(node, { onError: (e) => errors.push(`${key}: ${e.message}`) })
      return 1
    }
    if (node && typeof node === 'object') {
      return Object.entries(node).reduce((n, [k, v]) => n + compileErrors(v, key ? `${key}.${k}` : k, errors), 0)
    }
    errors.push(`${key}: not a string message`)
    return 0
  }

  it('every message of every locale compiles', () => {
    const errors: string[] = [] // across all locales, so one red names every broken message at once
    for (const locale of SUPPORTED_LOCALES) {
      const compiled = compileErrors(messagesOf(locale), locale, errors)
      expect(compiled, locale).toBeGreaterThan(50) // a walk that compiled almost nothing proves nothing
    }
    expect(errors, 'messages that do not compile').toEqual([])
  })

  // The proof is only as good as the compiler it runs: a devDependency left behind on a vue-i18n bump would
  // install a second, older compiler for this test alone, and it would go on passing against syntax the app no
  // longer parses. vue-i18n's core pins its compiler to its own exact version, so the two must match.
  it('compiles with the compiler vue-i18n itself runs', () => {
    const compiler = createRequire(import.meta.url)('@intlify/message-compiler/package.json') as { version: string }
    expect(compiler.version).toBe(VERSION)
  })
})
