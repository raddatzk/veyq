// A privacy-notice recipient's country (ADR 0035 decision 8, uc.admin.operator-details): admin-web's list equals the
// contract's — itself held equal to the backend JDK's list by `RecipientCountriesTest` — and a select offers each code
// under its name in the console's language, never the name as the value.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { countryCode, countryOptions, RECIPIENT_COUNTRIES } from './countries'

const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../../../../contracts/terms/recipient-countries.json', import.meta.url)), 'utf8'),
)

describe('the recipient countries', () => {
  it('equal the contract, code for code', () => {
    expect(typeof contract.note).toBe('string')
    expect(RECIPIENT_COUNTRIES).toEqual(contract.codes)
  })

  it('are offered as the code, named in the console’s language and sorted by that name', () => {
    const de = countryOptions('de')
    expect(de.find((o) => o.value === 'DE')).toEqual({ value: 'DE', label: 'Deutschland' })
    expect(de.find((o) => o.value === 'US')?.label).toBe('Vereinigte Staaten')
    const en = countryOptions('en')
    expect(en.find((o) => o.value === 'DE')).toEqual({ value: 'DE', label: 'Germany' })
    expect(en.map((o) => o.value).sort()).toEqual([...RECIPIENT_COUNTRIES]) // every code, once
    // sorted by the name in that language: Österreich sits among the Ö/O names in German, not after Z
    const labels = de.map((o) => o.label)
    expect(labels.indexOf('Österreich')).toBeLessThan(labels.indexOf('Polen'))
    expect(labels.indexOf('Österreich')).toBeGreaterThan(labels.indexOf('Norwegen'))
  })

  it('reads a typed code case-insensitively and refuses one the backend does not know', () => {
    expect(countryCode(' de ')).toBe('DE')
    expect(countryCode('FI')).toBe('FI')
    expect(countryCode('XX')).toBeNull() // the shape of a code, no country
    expect(countryCode('XK')).toBeNull() // Kosovo: a user-assigned code the JDK does not list
    expect(countryCode('Germany')).toBeNull()
  })
})
