// The catalog's own guard. This package's messages were LIFTED from `web/`'s (ADR 0065), whole groups for
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
// Hence three assertions, aimed at the three ways a key goes missing:
//  1. every key a FUNCTION can return resolves — invisible to any scan of `t('…')` call sites;
//  2. every key a TEMPLATE or script asks for by literal resolves — the `kit.*` case;
//  3. EN and DE carry the identical key set — a lift that took one locale's group and not the other's.
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { baseCompile } from '@intlify/message-compiler'
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
// falls back to its source text — so none of the key checks above notice it: `web/` shipped "Invited by @{user}"
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
})
