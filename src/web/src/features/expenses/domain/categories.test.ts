import { describe, it, expect } from 'vitest'
import { i18n } from '../../../i18n'
import { categoryLabel } from './categories'
import { DEFAULT_CATEGORIES } from './format'

const OWN = '0e000000-0000-4000-c000-000000000001'

describe('a category reads as', () => {
  it('its translation for a default one, in both languages', () => {
    for (const id of DEFAULT_CATEGORIES) {
      const label = categoryLabel(id, new Map())
      expect(label).toEqual({ key: `expenses.category.${id}` })
      const { key } = label as { key: string }
      for (const locale of ['en', 'de'] as const) expect(i18n.global.t(key, {}, { locale })).not.toBe(key) // a raw key on screen equals it
    }
    expect(i18n.global.t('expenses.category.food', {}, { locale: 'de' })).toBe('Essen & Trinken')
  })

  it("the name the Pot's meta gives its own one, and unknown where the meta names none", () => {
    expect(categoryLabel(OWN, new Map([[OWN, 'Ski pass']]))).toEqual({ name: 'Ski pass' })
    expect(categoryLabel(OWN, new Map())).toEqual({ unknown: OWN })
  })
})
