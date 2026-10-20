import { describe, it, expect } from 'vitest'
import { fileNameSlug, slugify } from './naming'

describe('slugify', () => {
  it('produces readable URL-safe slugs', () => {
    expect(slugify('Reisen 2026')).toBe('reisen-2026')
    expect(slugify('Rom!')).toBe('rom')
    expect(slugify('  spaced  out  ')).toBe('spaced-out')
  })

  it('folds German + strips diacritics so Latin names stay legible', () => {
    // All four of the GERMAN_FOLDING class [äöüß] are fed here — ü/ß plus ä/ö, which the NFKD path (Café/naïve)
    // never reaches: an ä/ö is folded pre-normalize, so dropping either branch survives unless it is exercised.
    expect(slugify('Müller')).toBe('mueller') // ü
    expect(slugify('Straße')).toBe('strasse') // ß
    expect(slugify('Käse')).toBe('kaese') // ä
    expect(slugify('schön')).toBe('schoen') // ö
    expect(slugify('Café')).toBe('cafe')
    expect(slugify('naïve')).toBe('naive')
  })

  it('normalizes to NFC first: a decomposed umlaut folds like the composed one (inbox #365)', () => {
    // Written with escapes on purpose: an editor or a copy can silently compose a literal decomposed letter.
    expect(slugify('Müller')).toBe('mueller')
    expect(slugify('MÜLLER')).toBe('mueller')
    expect(slugify('Müller')).toBe(slugify('Müller'))
    // The hash fallback hashes the NFC name too: a Hangul syllable and its jamo are one slug.
    expect(slugify('한')).toBe(slugify('한'))
  })

  it('keeps slugs to [a-z0-9-] — reserved URL chars never survive', () => {
    expect(slugify('A/B')).toBe('a-b')
    expect(slugify('a#b?c')).toBe('a-b-c')
    expect(slugify('--edge--')).toBe('edge')
  })

  it('is deterministic and non-empty even for names that reduce to nothing', () => {
    const a = slugify('🎉')
    expect(a).toMatch(/^n[a-z0-9]+$/)
    expect(slugify('🎉')).toBe(a) // same name → same slug (→ same blinded token)
    expect(slugify('日本')).not.toBe(slugify('中国')) // distinct names stay distinct
  })

  it('pins the EXACT fnv1a fallback hash — incl. a sign-bit input for the >>> 0 unsigned mask', () => {
    // A name that reduces to nothing falls through to `n${fnv1a(name)}`. Pinning the exact value (not just the
    // /^n[a-z0-9]+$/ shape above) is what kills a mutated FNV prime/offset/xor — a shape assertion passes for any
    // hash, so it leaves the arithmetic unmeasured.
    expect(slugify('🎉')).toBe('nswncdy') // ordinary (signed-positive) hash
    // '😀' hashes with the high bit set (signed-negative int32); without the `>>> 0` mask, `.toString(36)` would
    // carry a leading '-' → 'n-engklk'. This exact pin is the only assertion that dies when the mask is dropped —
    // a fallback pin over an ordinary positive input leaves the >>> 0 mutant alive (same string either way).
    expect(slugify('😀')).toBe('n1kdnhdk')
  })
})

// A file's uniqueness slug (inbox #350). The vectors (`naming.conformance.test.ts`) pin the outputs; these name the
// rules one at a time, each the assertion that dies when its step is dropped.
describe('fileNameSlug', () => {
  it('lowercases without any other change: Foto.JPG and foto.jpg are one name, the dot stays', () => {
    expect(fileNameSlug('Foto.JPG')).toBe('foto.jpg')
    expect(fileNameSlug('Foto.JPG')).toBe(fileNameSlug('foto.jpg'))
  })

  it('keeps punctuation and spaces, so Day1.md and Day1 md stay distinct (unlike slugify)', () => {
    expect(fileNameSlug('Day1.md')).not.toBe(fileNameSlug('Day1 md'))
    expect(slugify('Day1.md')).toBe(slugify('Day1 md'))
    expect(fileNameSlug('a/b#c?(1)')).toBe('a/b#c?(1)')
  })

  it('normalizes to NFC first: a composed and a decomposed ä are one name', () => {
    expect(fileNameSlug('Käse')).toBe('käse')
    expect(fileNameSlug('Käse')).toBe(fileNameSlug('Käse'))
  })

  it('does not trim and does not transliterate (no ß -> ss, no hash fallback)', () => {
    expect(fileNameSlug('  x  ')).toBe('  x  ')
    expect(fileNameSlug('Straße')).toBe('straße')
    expect(fileNameSlug('🎉')).toBe('🎉')
    expect(fileNameSlug('')).toBe('')
  })
})
