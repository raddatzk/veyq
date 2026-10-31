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

  it('a blocked link is worded by the reader’s relation to it: its creator, an uploader of what it reaches, anyone else (inbox #821)', () => {
    const measureOf = (lang: 'en' | 'de', roles: StatementFields['roles']) =>
      renderStatement(lang, { ...fields, measureKind: 'SHARE', roles })[0].text
    expect(measureOf('en', ['SHARE_CREATOR'])).toBe(
      'On 18 October 2026, the people who run this veyq instance took a measure: a link you created was blocked. ' +
        'Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.',
    )
    expect(measureOf('en', ['UPLOADER'])).toBe(
      'On 18 October 2026, the people who run this veyq instance took a measure: a link to something you uploaded was blocked. ' +
        'Everyone who opens it is told the link does not exist; nothing was deleted.',
    )
    expect(measureOf('de', ['UPLOADER'])).toBe(
      'Am 18 October 2026 wurde auf dieser veyq-Instanz eine Maßnahme ergriffen: ein Link zu etwas, das du hochgeladen hast, wurde gesperrt. ' +
        'Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts.',
    )
    expect(measureOf('de', ['SHARE_CREATOR'])).toContain('ein von dir erstellter Link wurde gesperrt.')
    // Both relations: the creator's words, whatever order the row lists them in.
    expect(measureOf('en', ['UPLOADER', 'SHARE_CREATOR'])).toContain('a link you created was blocked.')
    // Neither: the neutral words, never "you created".
    expect(measureOf('en', ['SCOPE_OWNER'])).toContain('took a measure: a link was blocked. ')
    expect(measureOf('de', [])).toContain('ergriffen: ein Link wurde gesperrt. ')
    // A guest keeps its own words, and a kind without per-relation words reads the same for every reader.
    expect(renderStatement('en', { ...fields, measureKind: 'SHARE', roles: ['UPLOADER'], reader: 'guest' })[0].text).toBe(
      'On 18 October 2026, the people who run this veyq instance took a measure: a link to something you uploaded was blocked. ' +
        TEMPLATE.guest.measures.SHARE.en.reach,
    )
    expect(renderStatement('en', { ...fields, roles: ['UPLOADER'] })[0].text).toBe(renderStatement('en', { ...fields, roles: ['SCOPE_OWNER'] })[0].text)
  })

  it('a guest reads a blocked link in words true whether or not it came through that link, never "uploaded through" or "you created" (inbox #821)', () => {
    // The SHARE statement names every guest whose upload the blocked link reaches — also one that came through another link.
    const words = { en: 'a link to something you uploaded was blocked.', de: 'ein Link zu etwas, das du hochgeladen hast, wurde gesperrt.' }
    const reach = { en: 'Everyone who opens it is told the link does not exist; nothing was deleted.', de: 'Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts.' }
    for (const lang of ['en', 'de'] as const) {
      const text = renderStatement(lang, { ...fields, measureKind: 'SHARE', roles: ['UPLOADER'], reader: 'guest' })
        .map((p) => p.text)
        .join(' ')
      expect(text).toContain(`${words[lang]} ${reach[lang]}`)
      for (const wrong of ['uploaded through', 'you created', 'über den du', 'von dir erstellter']) expect(text).not.toContain(wrong)
    }
  })

  it('a guest uploader reads the same form in its own words: where to ask for the facts, and the objection by the contact (ADR 0093 § 7)', () => {
    const guest = renderStatement('en', { ...fields, measureKind: 'GUEST_IDENTITY', roles: ['UPLOADER'], reader: 'guest', provision: 'Space terms, clause 4' })
    expect(guest.map((p) => p.id)).toEqual(['measure', 'roles', 'facts', 'basis', 'automation', 'ground', 'provision', 'scope', 'objectionByContact', 'contact', 'courts'])
    expect(guest[0].text).toContain('you and the link you uploaded through were blocked')
    expect(guest.find((p) => p.id === 'facts')!.text).toBe("What they found is not shown here, since it opens only with an account's key: ask abuse@example.org for it.")
    expect(guest.find((p) => p.id === 'objectionByContact')!.text).toContain('write to abuse@example.org')
    // A kind the guest words leave out keeps the member's; the German form fills the gap where no contact is set.
    expect(renderStatement('en', { ...fields, reader: 'guest' })[0].text).toContain(TEMPLATE.measures.CONTENT.en.measure)
    const de = renderStatement('de', { ...fields, measureKind: 'SHARE', reader: 'guest' })
    expect(de[0].text).toContain('ein Link zu etwas, das du hochgeladen hast, wurde gesperrt')
    expect(de.find((p) => p.id === 'facts')!.text).toBe('Was sie festgestellt haben, ist hier nicht zu sehen, denn es öffnet sich nur mit dem Schlüssel eines Kontos: Frag unter abuse@example.org danach.')
    expect(de.every((p) => !p.text.includes('{'))).toBe(true)
  })

  it('without an instance contact a guest is told so in sentences of their own, never the gap inside "ask … for it" (ADR 0093 §§ 7, 14)', () => {
    for (const lang of ['en', 'de'] as const) {
      const none = renderStatement(lang, { ...fields, reader: 'guest', contact: null })
      expect(none.find((p) => p.id === 'facts')!.text).toBe(TEMPLATE.guest.factsNoContact[lang])
      expect(none.find((p) => p.id === 'objectionByContact')!.text).toBe(TEMPLATE.guest.objectionNoContact[lang])
      // The gap stays where every reader's statement shows it: the contact passage.
      expect(none.find((p) => p.id === 'contact')!.text).toContain(TEMPLATE.gap[lang])
      expect(none.filter((p) => p.id !== 'contact').some((p) => p.text.includes(TEMPLATE.gap[lang]))).toBe(false)
      // Control: with a contact, the passages ask the guest to write to it.
      const some = renderStatement(lang, { ...fields, reader: 'guest' })
      expect(some.find((p) => p.id === 'facts')!.text).toContain('abuse@example.org')
      expect(some.find((p) => p.id === 'objectionByContact')!.text).toContain('abuse@example.org')
    }
    // A member's statement keeps the gap as it was: their objection is in veyq.
    expect(renderStatement('en', { ...fields, contact: null }).map((p) => p.text).join(' ')).not.toContain(TEMPLATE.guest.objectionNoContact.en)
  })

  it('the reporter’s coordinator sentence says where the DSA applies, in both languages (ADR 0093 § 7)', () => {
    const coordinator = TEMPLATE.reporterDecision.passages.find((p) => p.id === 'coordinator')!
    expect(coordinator.en.startsWith('Where the Digital Services Act (DSA) applies to this instance')).toBe(true)
    expect(coordinator.de.startsWith('Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt')).toBe(true)
  })
})
