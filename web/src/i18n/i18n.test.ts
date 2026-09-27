// The catalog's reverse guard: every key it carries is used somewhere. A catalog only grows by default — a
// view that is deleted or moved (Setup went to admin-web, ADR 0065) leaves its strings behind, and nothing
// fails, because an unused key renders nowhere. This test is what makes a dead group visible the day it dies
// (32 of 818 keys when it was written).
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { i18n, SUPPORTED_LOCALES } from './index'

function flatten(o: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) =>
    v && typeof v === 'object' ? flatten(v as Record<string, unknown>, `${prefix}${k}.`) : [`${prefix}${k}`],
  )
}

/** What the sources could be asking the catalog for: every dotted string they quote in any of the three
 *  quote styles, and the static head of every template literal that builds a key (`` `a.b.${x}` `` → `a.b.`).
 *  A head may end mid-segment: `ProfileMenu` builds `` `shell.theme${Pref}` `` for `shell.themeSystem` /
 *  `themeLight` / `themeDark`, which a head ending only at a dot called dead.
 *  Deliberately ANY quoted literal and not only `t('…')`'s argument: keys travel through variables and
 *  lookup tables before they reach `t()`. A key this cannot see is one it calls unused, so a new spelling is
 *  taught here, never allowlisted. Its limit: a template head counts every key under it as used — web builds
 *  `tenant.join.*`, `media.uploadStatus.*`, `media.marks.filter.*`, `rotation.status.*`,
 *  `rotation.statusHint.*` and `shell.theme*` dynamically, so a dead key inside those is invisible. */
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

describe('web message catalog', () => {
  it('uses every key it carries', () => {
    const src = new URL('..', import.meta.url).pathname
    const { literals, heads } = quotedKeysInSources(src, join(src, 'i18n', 'index.ts'))
    expect(literals.size).toBeGreaterThan(100) // a scan that found almost nothing proves nothing

    // EN is enough: `const de: typeof en` makes the typecheck hold DE to EN's key set.
    const messages = (i18n.global as unknown as { getLocaleMessage(l: string): Record<string, unknown> })
      .getLocaleMessage(SUPPORTED_LOCALES[0])
    const unused = flatten(messages).filter((k) => !literals.has(k) && ![...heads].some((h) => k.startsWith(h)))
    expect(unused, 'catalog keys no source uses').toEqual([])
  })
})
