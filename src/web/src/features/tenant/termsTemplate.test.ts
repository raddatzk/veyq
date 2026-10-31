// The starter template leaves the host's duties to the operator section (ADR 0035 decision 6, ADR 0093 § Consequences):
// a space owner can delete any clause, so no clause may carry what the Server Owner owes. Clause 9 points to the
// section instead of regulating review itself, and clauses 2 and 7 point there for what the server keeps.
import { describe, it, expect } from 'vitest'
import { TERMS_TEMPLATE } from './termsTemplate'

const clause = (text: string, n: number) => {
  const start = text.indexOf(`## ${n}. `)
  const end = text.indexOf('\n## ', start + 1)
  return text.slice(start, end < 0 ? undefined : end)
}

describe('TERMS_TEMPLATE — the host’s duties live in the operator section', () => {
  it.each([
    ['en', 'operator’s section below', 'reviewed and removed'],
    ['de', 'Abschnitt der Betreiberseite unten', 'geprüft und entfernt'],
  ] as const)('%s: clause 9 points to the section and regulates no review of its own', (lang, pointer, review) => {
    const text = TERMS_TEMPLATE[lang].replace(/'/g, '’')
    expect(clause(text, 9)).toContain(pointer)
    expect(text).not.toContain(review)
  })

  it.each([
    ['en', 'operator’s section below'],
    ['de', 'Abschnitt der Betreiberseite unten'],
  ] as const)('%s: clauses 2 and 7 point there for what the server keeps', (lang, pointer) => {
    const text = TERMS_TEMPLATE[lang].replace(/'/g, '’')
    expect(clause(text, 2)).toContain(pointer)
    expect(clause(text, 7)).toContain(pointer)
    expect(clause(text, 4)).not.toContain(pointer) // control: an unrelated clause does not
  })

  it.each([
    ['en', 'could change it to read what you open in it'],
    ['de', 'könnte sie so ändern, dass sie mitliest'],
  ] as const)('%s: clause 2 promises no more than holds against an operator who delivers the app (ADR 0026)', (lang, limit) => {
    const text = TERMS_TEMPLATE[lang].replace(/'/g, '’')
    expect(clause(text, 2)).toContain(limit)
    expect(text).not.toMatch(/cannot read private content|kann private Inhalte nicht lesen/)
  })
})
