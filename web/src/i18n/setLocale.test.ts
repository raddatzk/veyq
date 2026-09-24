// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { baseCompile } from '@intlify/message-compiler'
import { VERSION } from 'vue-i18n'
import { createRequire } from 'node:module'
import {
  i18n,
  setLocalePreference,
  localePreference,
  effectiveLocale,
  SUPPORTED_LOCALES,
  LOCALE_PREFERENCES,
  LOCALE_LABELS,
} from './index'

beforeEach(() => {
  localStorage.clear()
  setLocalePreference('en')
})

describe('language preference', () => {
  it('switches to a concrete language + persists it', () => {
    setLocalePreference('de')
    expect(i18n.global.locale.value).toBe('de')
    expect(localStorage.getItem('veyq:locale')).toBe('de')
    expect(localePreference().value).toBe('de')
    expect(i18n.global.t('shell.spaces')).toBe('Bereiche')

    setLocalePreference('en')
    expect(i18n.global.t('shell.spaces')).toBe('Spaces')
  })

  it('"system" resolves to the OS language (a supported locale) + persists the preference', () => {
    setLocalePreference('system')
    expect(localStorage.getItem('veyq:locale')).toBe('system')
    expect(localePreference().value).toBe('system')
    expect(SUPPORTED_LOCALES).toContain(i18n.global.locale.value)
  })

  it('effectiveLocale maps a preference to a concrete locale', () => {
    expect(effectiveLocale('en')).toBe('en')
    expect(effectiveLocale('de')).toBe('de')
    expect(SUPPORTED_LOCALES).toContain(effectiveLocale('system'))
  })

  it('offers System + the two endonyms', () => {
    expect(LOCALE_PREFERENCES).toEqual(['system', 'en', 'de'])
    expect(LOCALE_LABELS).toEqual({ en: 'English', de: 'Deutsch' })
  })
})

// cr-0102 (ADR 0031 §5): the UPLOADER drop-box must warn about its session-only, write-only nature in four
// places. This guards that each warning string is present + translated (not an empty/placeholder key) in both
// locales — the copy for the four surfaces (name-pre-screen warning, header badge, session note, reload empty-state).
// uc.settings.appearance-language / resolution-fallback. These three assertions existed only as apple/android
// tests, which no CI job runs — so on the one live surface the behaviour was untested while coverage claimed
// otherwise. `const de: typeof en` already pins key parity at compile time; the runtime check below is what a
// green build actually proves.
describe('locale + key resolution fallback', () => {
  it('an unsupported OS language falls back to English', () => {
    const nav = vi.spyOn(navigator, 'language', 'get')
    for (const lang of ['fr-FR', 'ja', 'xx-YY', '']) {
      nav.mockReturnValue(lang)
      expect(effectiveLocale('system'), lang).toBe('en')
    }
    nav.mockReturnValue('de-AT') // …and a supported one still resolves, so the fallback isn't just a constant
    expect(effectiveLocale('system')).toBe('de')
    nav.mockRestore()
  })

  it('an unknown key resolves to itself', () => {
    for (const locale of SUPPORTED_LOCALES) {
      setLocalePreference(locale)
      expect(i18n.global.t('no.such.key'), locale).toBe('no.such.key')
    }
  })

  it('every key exists in both languages', () => {
    const flatten = (o: object, prefix = ''): string[] =>
      Object.entries(o).flatMap(([k, v]) =>
        v && typeof v === 'object' ? flatten(v, `${prefix}${k}.`) : [`${prefix}${k}`],
      )
    const messages = i18n.global.messages.value
    const [en, de] = [flatten(messages.en), flatten(messages.de)]
    expect(de.filter((k) => !en.includes(k)), 'in de, missing from en').toEqual([])
    expect(en.filter((k) => !de.includes(k)), 'in en, missing from de').toEqual([])
    expect(en.length).toBeGreaterThan(100) // the sets are equal because both are full, not because both are empty
  })
})

describe('uploader drop-box ephemerality warnings (cr-0102)', () => {
  const KEYS = [
    'share.uploaderEphemeralWarning', // (a) the name pre-screen, before the first upload
    'share.uploaderBadge', // (b) the persistent header badge
    'share.uploaderSessionNote', // (c) the persistent session note above the grid
    'share.uploaderEmpty', // (d) the reload empty-state
  ]
  it('has non-empty, distinct copy for all four warning surfaces in en + de', () => {
    for (const locale of ['en', 'de'] as const) {
      setLocalePreference(locale)
      const texts = KEYS.map((k) => i18n.global.t(k))
      for (const [i, text] of texts.entries()) {
        expect(text, `${KEYS[i]} @ ${locale}`).not.toBe(KEYS[i]) // resolved, not a missing-key echo
        expect(text.length, `${KEYS[i]} @ ${locale}`).toBeGreaterThan(10)
      }
      expect(new Set(texts).size).toBe(KEYS.length) // four distinct messages, not one copy reused
    }
  })
})

// Every message must compile under vue-i18n's own message compiler. One that does not still renders — vue-i18n
// falls back to its source text — so neither the screen nor any other test notices: that is how
// `invitations.addedBy` shipped as the literal "Invited by @{user}", since a bare `@` opens a linked message
// (`{'@'}` is the literal). The compiler's own `onError` is the mechanism, deliberately not a spy on
// `console.warn`, which would pass the day vue-i18n changes how it reports.
describe('message syntax', () => {
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
      const compiled = compileErrors(i18n.global.messages.value[locale], locale, errors)
      expect(compiled, locale).toBeGreaterThan(100) // a walk that compiled almost nothing proves nothing
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
