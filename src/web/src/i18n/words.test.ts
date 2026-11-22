// The web's share of the apps' catalog (inbox #1060): `words.generated.json` is `generate_words.py --web`'s output, the
// notices' words in vue-i18n's syntax, and `scripts/validate_web_words.py` keeps it equal to that output. What the guard
// cannot see is whether vue-i18n then SAYS each word as the catalog writes it: a syntax character left unescaped, a
// placeholder renumbered wrong or a plural's forms in the wrong order would all compile. So every word is rendered here,
// in both languages, and compared with the catalog's own text, its placeholders filled by number.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import generated from './words.generated.json'

type Text = string | Record<string, string>
type Entry = { en: Text; de?: Text }
const catalog = JSON.parse(readFileSync(resolve(__dirname, '../../../mobile/words/words.json'), 'utf8')) as Record<string, Entry>
const LANGS = ['en', 'de'] as const
const ARG = (n: number) => `‹${n}›`

/** The catalog's text with `{n}` / `{n:int}` filled: an integer by `count` (a plural's count), a string by ARG(n). */
function filled(text: string, count: number): string {
  return text.replace(/\{(\d+)(:int)?\}/g, (_, n: string, int?: string) => (int ? String(count) : ARG(Number(n)))).replace(/%%/g, '%')
}

describe('the web reads the notices’ words from the apps’ catalog (inbox #1060)', () => {
  it('holds the same keys in both languages, each a key of words.json', () => {
    expect(Object.keys(generated.de).sort()).toEqual(Object.keys(generated.en).sort())
    expect(Object.keys(generated.en).length).toBeGreaterThan(50) // the anchor: the notices tables name ~110
    for (const key of Object.keys(generated.en)) expect(catalog[key], key).toBeDefined()
  })

  for (const lang of LANGS) {
    it(`says every word as words.json writes it, in ${lang}`, () => {
      const i18n = createI18n({ legacy: false, locale: lang, messages: { [lang]: { words: generated[lang] } } })
      const t = i18n.global.t as (key: string, list: unknown[], plural?: number) => string
      for (const key of Object.keys(generated[lang])) {
        const text = catalog[key][lang] ?? catalog[key].en
        if (typeof text === 'string') {
          const arity = new Set([...text.matchAll(/\{(\d+)(?::int)?\}/g)].map((m) => m[1])).size
          const ints = [...text.matchAll(/\{(\d+):int\}/g)].map((m) => Number(m[1]))
          const list = Array.from({ length: arity }, (_, i) => (ints.includes(i + 1) ? 7 : ARG(i + 1)))
          expect(t(`words.${key}`, list), key).toBe(filled(text, 7))
        } else {
          // vue-i18n's two forms: 1 says the first, any other count the second; three forms begin with 0's
          const cases: [string, number][] = [['one', 1], ['other', 2]]
          if ('zero' in text) cases.unshift(['zero', 0])
          for (const [category, count] of cases) {
            const form = text[category] ?? text.other
            const arity = new Set([...Object.values(text).join(' ').matchAll(/\{(\d+)(?::int)?\}/g)].map((m) => m[1])).size
            const list = Array.from({ length: arity }, (_, i) => (i === 0 ? count : ARG(i + 1)))
            expect(t(`words.${key}`, list, count), `${key}.${category}`).toBe(filled(form, count))
          }
        }
      }
    })
  }
})
