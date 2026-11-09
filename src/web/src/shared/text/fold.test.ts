import { describe, it, expect } from 'vitest'
import { fold, foldMap } from './fold'

describe('fold', () => {
  it('drops case and accents', () => {
    expect(fold('Zoë')).toBe('zoe')
    expect(fold('ÉCOLE')).toBe('ecole')
    expect(fold('Ångström')).toBe('angstrom')
  })

  it('reads ß and ẞ as ss', () => {
    expect(fold('Straße')).toBe('strasse')
    expect(fold('STRAẞE')).toBe('strasse')
    expect(fold('STRASSE')).toBe(fold('Straße'))
  })

  it('folds a composed and a decomposed spelling alike', () => {
    expect(fold('München')).toBe(fold('München'))
  })

  it('keeps everything that is no mark', () => {
    expect(fold('Rom 2024 – Tag 1')).toBe('rom 2024 – tag 1')
  })
})

describe('foldMap (cr-search-polish)', () => {
  it('names, per folded letter, the characters of the original it came from', () => {
    expect(foldMap('Straße')).toEqual({ folded: 'strasse', start: [0, 1, 2, 3, 4, 4, 5], end: [1, 2, 3, 4, 5, 5, 6] })
    // a mark written apart belongs to the letter before it
    expect(foldMap('Cafe\u0301!')).toEqual({ folded: 'cafe!', start: [0, 1, 2, 3, 5], end: [1, 2, 3, 5, 6] })
  })

  it('folds to what fold() folds to, or answers null', () => {
    for (const s of ['Zoë', 'ÉCOLE', 'STRAẞE', 'München', 'Rom 2024 – Tag 1', '😀 Kevin']) expect(foldMap(s)?.folded).toBe(fold(s))
  })
})
