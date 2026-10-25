// The Pot's settings held to contracts/expenses/pot-meta.v1.schema.json: the bounds are the schema's own (read from the
// file), a writer stores only what its reader takes back, and a sibling of the envelope (core's cover) survives.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { MAX_CATEGORY_NAME, MAX_OWN_CATEGORIES, POT_META_KEY, potMetaObject, potMetaOf, PotMetaInvalid, readPotMeta } from './potMeta'

const schema = JSON.parse(readFileSync(resolve(__dirname, '../../../../../../contracts/expenses/pot-meta.v1.schema.json'), 'utf8'))
const CAT = '0e000000-0000-4000-a000-000000000001'

describe('the Pot meta reader is the schema', () => {
  it('uses the schema\'s bounds, key and required fields', () => {
    expect(schema.properties.categories.maxItems).toBe(MAX_OWN_CATEGORIES)
    expect(schema.properties.categories.items.properties.name.maxLength).toBe(MAX_CATEGORY_NAME)
    expect(schema.required).toEqual(['v', 'baseCurrency', 'baseMinorUnits'])
    expect(schema.description).toContain(`under the key \`${POT_META_KEY}\``)
  })

  it('reads a v1 object and drops what it does not know', () => {
    const meta = readPotMeta({ v: 1, baseCurrency: 'JPY', baseMinorUnits: 0, categories: [{ id: CAT, name: 'Ski pass', extra: 1 }], later: true })
    expect(meta).toEqual({ base: { currency: 'JPY', minorUnits: 0 }, categories: [{ id: CAT, name: 'Ski pass' }] })
  })

  const refused: [string, unknown, string][] = [
    ['another version', { v: 2, baseCurrency: 'EUR', baseMinorUnits: 2 }, '/v'],
    ['a lowercase currency', { v: 1, baseCurrency: 'eur', baseMinorUnits: 2 }, '/baseCurrency'],
    ['five minor units', { v: 1, baseCurrency: 'EUR', baseMinorUnits: 5 }, '/baseMinorUnits'],
    ['a category that is no uuid', { v: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [{ id: 'food', name: 'x' }] }, '/categories/0/id'],
    ['an empty category name', { v: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [{ id: CAT, name: '' }] }, '/categories/0/name'],
    ['a name past the cap', { v: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [{ id: CAT, name: 'x'.repeat(MAX_CATEGORY_NAME + 1) }] }, '/categories/0/name'],
  ]
  for (const [what, raw, path] of refused) {
    it(`refuses ${what}`, () => {
      expect(() => readPotMeta(raw)).toThrow(new PotMetaInvalid(path))
    })
  }
  it('control: a name exactly at the cap reads', () => {
    expect(readPotMeta({ v: 1, baseCurrency: 'EUR', baseMinorUnits: 2, categories: [{ id: CAT, name: 'x'.repeat(MAX_CATEGORY_NAME) }] }).categories).toHaveLength(1)
  })
})

describe('the Pot meta in its envelope', () => {
  it('a Pot whose envelope has no readable `pot` has no settings (null, never a guessed currency)', () => {
    expect(potMetaOf({ v: 1 })).toBeNull()
    expect(potMetaOf({ v: 1, pot: { v: 1, baseCurrency: 'EUR' } })).toBeNull()
    expect(potMetaOf({ v: 1, cover: { x: 1 }, pot: { v: 1, baseCurrency: 'EUR', baseMinorUnits: 2 } })).toEqual({ base: { currency: 'EUR', minorUnits: 2 }, categories: [] })
  })

  it('a writer writes what the reader takes back, and refuses what it would not', () => {
    const meta = { base: { currency: 'CHF', minorUnits: 2 }, categories: [{ id: CAT, name: 'Ski' }] }
    expect(readPotMeta(potMetaObject(meta))).toEqual(meta)
    expect(potMetaObject({ base: { currency: 'CHF', minorUnits: 2 }, categories: [] })).not.toHaveProperty('categories')
    expect(() => potMetaObject({ base: { currency: 'chf', minorUnits: 2 }, categories: [] })).toThrow(PotMetaInvalid)
  })
})
