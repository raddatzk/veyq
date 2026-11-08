import { describe, it, expect } from 'vitest'
import { fold } from './fold'

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
