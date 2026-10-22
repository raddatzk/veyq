import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { renderStatement, TEMPLATE, type StatementFields } from './template'

// Conformance pin: the template src/web/ renders equals contracts/moderation/statement-of-reasons.json, every text of it —
// the backend's StatementTemplateConformanceTest holds its copy to the same file (drift-proofing, as the crypto vectors).
const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../../../../../contracts/moderation/statement-of-reasons.json', import.meta.url)), 'utf8'),
)

const fields: StatementFields = {
  measureKind: 'CONTENT',
  roles: ['UPLOADER', 'SCOPE_OWNER'],
  basis: 'REPORT',
  ground: 'TERMS_BREACH',
  date: '18 October 2026',
  contact: 'abuse@example.org',
  hasFacts: true,
}

describe('the statement template', () => {
  it('equals the contract, text for text', () => {
    const { note, ...rest } = contract
    expect(typeof note).toBe('string')
    expect(TEMPLATE).toEqual(rest)
  })

  it('renders every passage of the one form, in order, with the clear fields filled in', () => {
    const full = renderStatement('en', { ...fields, provision: '§ 86a StGB' })
    expect(full.map((p) => p.id)).toEqual(['measure', 'roles', 'facts', 'basis', 'automation', 'ground', 'provision', 'scope', 'objection', 'contact', 'courts'])
    // The provision follows the ground, then the territorial sentence (DSA Art. 17(3)(a)).
    expect(full.find((p) => p.id === 'provision')!.text).toBe('It relies on § 86a StGB.')
    expect(full.find((p) => p.id === 'scope')!.text).toBe('It applies wherever this veyq instance is reached, and lasts until it is lifted.')
    // A moderator's measure is objected to in veyq; the contact objection is a Server Owner's step's, by mail alone.
    expect(full.map((p) => p.id)).not.toContain('objectionByContact')
    expect(full[0].text).toBe(
      'On 18 October 2026, the people who run this veyq instance took a measure: content was blocked. ' +
        TEMPLATE.measures.CONTENT.en.reach,
    )
    expect(full[1].text).toBe('You are told as the person who uploaded it and an owner of the space it lies in.')
    expect(full.find((p) => p.id === 'contact')!.text).toBe('Questions go to abuse@example.org.')
    expect(full.every((p) => !p.text.includes('{'))).toBe(true)
  })

  it('leaves out a passage whose value is not there, and shows the gap where no contact is set', () => {
    const noContact = renderStatement('de', { ...fields, hasFacts: false, contact: null, roles: ['SHARE_CREATOR'], measureKind: 'SHARE' })
    expect(noContact.map((p) => p.id)).toEqual(['measure', 'roles', 'basis', 'automation', 'ground', 'scope', 'objection', 'contact', 'courts'])
    expect(noContact.find((p) => p.id === 'contact')!.text).toBe('Fragen gehen an von der Betreiberseite nicht angegeben.')
    // A blank provision is none.
    expect(renderStatement('en', { ...fields, provision: '  ' }).map((p) => p.id)).not.toContain('provision')
    expect(noContact[1].text).toBe('Du erhältst diese Begründung als Person, die den Link erstellt hat.')
  })

  it('the reporter’s coordinator sentence says where the DSA applies, in both languages (ADR 0093 § 7)', () => {
    const coordinator = TEMPLATE.reporterDecision.passages.find((p) => p.id === 'coordinator')!
    expect(coordinator.en.startsWith('Where the Digital Services Act (DSA) applies to this instance')).toBe(true)
    expect(coordinator.de.startsWith('Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt')).toBe(true)
  })
})
