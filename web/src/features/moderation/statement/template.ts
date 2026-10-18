// The statement of reasons' one template (ADR 0093 § 7, decision 1 of cr-statement-of-reasons-and-objection), pinned from
// `contracts/moderation/statement-of-reasons.json`: the backend pins the same file for the mail
// (`core/moderation/statement/StatementTemplate.kt`), and `template.test.ts` here and its conformance test there hold
// both copies equal to it, so the placeholder and the mail render one text. It names the host, never the moderator.
export const TEMPLATE = {
  version: 1,
  measures: {
    SHARE: {
      en: { measure: 'a link you created was blocked', reach: 'Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.' },
      de: { measure: 'ein von dir erstellter Link wurde gesperrt', reach: 'Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts, und der Link kann nicht wieder aktiviert oder neu ausgestellt werden, solange die Sperre besteht.' },
    },
    CONTENT: {
      en: { measure: 'content was blocked', reach: 'It is no longer shown to anyone, members included, and appears as "Blocked by the operator"; nothing was deleted. It can still be deleted, but not replaced or restored.' },
      de: { measure: 'Inhalte wurden gesperrt', reach: 'Sie werden niemandem mehr angezeigt, auch Mitgliedern nicht, und erscheinen als „Von der Moderation gesperrt“; gelöscht wurde nichts. Sie können weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden.' },
    },
    GUEST_IDENTITY: {
      en: { measure: 'a guest who uploaded through your link was blocked, together with the link', reach: 'The guest can no longer add anything, and the link answers as if it did not exist; nothing was deleted. You can issue a new link.' },
      de: { measure: 'ein Gast, der über deinen Link hochgeladen hat, wurde gesperrt, zusammen mit dem Link', reach: 'Der Gast kann nichts mehr hinzufügen, und der Link antwortet, als gäbe es ihn nicht; gelöscht wurde nichts. Du kannst einen neuen Link ausstellen.' },
    },
  },
  roles: {
    SHARE_CREATOR: { en: 'the person who created the link', de: 'Person, die den Link erstellt hat' },
    UPLOADER: { en: 'the person who uploaded it', de: 'Person, die es hochgeladen hat' },
    SCOPE_OWNER: { en: 'an owner of the space it lies in', de: 'Person, der der Bereich gehört, in dem es liegt' },
  },
  rolesJoiner: { en: ' and ', de: ' und ' },
  basis: {
    REPORT: { en: 'a report by someone who could see it', de: 'eine Meldung von jemandem, der es sehen konnte' },
    ORDER: { en: 'an order of an authority', de: 'eine Anordnung einer Behörde' },
  },
  grounds: {
    CHILD_SEXUAL_ABUSE: { en: 'it is illegal: child sexual abuse material', de: 'es ist rechtswidrig: Darstellung sexuellen Missbrauchs von Kindern' },
    TERRORIST_CONTENT: { en: 'it is illegal: terrorist content', de: 'es ist rechtswidrig: terroristische Inhalte' },
    THREAT_TO_LIFE: { en: 'it is illegal: a threat to life or safety', de: 'es ist rechtswidrig: eine Gefahr für Leben oder Sicherheit' },
    ILLEGAL_CONTENT: { en: 'it is illegal under the law that applies', de: 'es ist nach geltendem Recht rechtswidrig' },
    TERMS_BREACH: { en: 'it breaches the terms of use that apply here', de: 'es verstößt gegen die hier geltenden Nutzungsbedingungen' },
  },
  passages: [
    { id: 'measure', full: false, en: 'On {date}, the people who run this veyq instance took a measure: {measure}. {reach}', de: 'Am {date} wurde auf dieser veyq-Instanz eine Maßnahme ergriffen: {measure}. {reach}' },
    { id: 'roles', full: false, en: 'You are told as {roles}.', de: 'Du erhältst diese Begründung als {roles}.' },
    { id: 'facts', full: false, when: 'facts', en: 'What they found:', de: 'Was sie festgestellt haben:' },
    { id: 'basis', full: true, en: 'The measure was taken after {basis}.', de: 'Die Maßnahme wurde ergriffen nach {basis}.' },
    { id: 'automation', full: true, en: 'No automated means found or decided it: a person did.', de: 'Kein automatisiertes Verfahren hat das erkannt oder entschieden, sondern ein Mensch.' },
    { id: 'ground', full: false, en: 'The ground: {ground}.', de: 'Der Grund: {ground}.' },
    { id: 'objection', full: false, en: 'If you disagree, you can object once, in veyq, where you see this statement. Another moderator reviews your objection where there is one.', de: 'Wenn du nicht einverstanden bist, kannst du einmal widersprechen, in veyq, dort, wo du diese Begründung siehst. Eine andere Person aus der Moderation prüft deinen Widerspruch, sofern es eine gibt.' },
    { id: 'contact', full: false, when: 'contact', en: 'Questions go to {contact}.', de: 'Fragen gehen an {contact}.' },
    { id: 'courts', full: true, en: 'You can also go to court; you do not need to object first.', de: 'Du kannst dich auch an ein Gericht wenden; ein Widerspruch vorher ist dafür nicht nötig.' },
  ],
  mail: { subject: { en: 'veyq: a moderation decision concerns you', de: 'veyq: eine Moderationsentscheidung betrifft dich' }, factsInVeyq: { en: 'What they found is in veyq: sign in at {domain} to read it.', de: 'Was sie festgestellt haben, steht in veyq: Melde dich unter {domain} an, um es zu lesen.' } },
} as const

export type StatementLang = 'en' | 'de'
type MeasureKind = keyof typeof TEMPLATE.measures
type Role = keyof typeof TEMPLATE.roles
type Basis = keyof typeof TEMPLATE.basis
type Ground = keyof typeof TEMPLATE.grounds

/** The clear fields one statement is rendered from — never user data. The facts are the reader's own, opened apart. */
export type StatementFields = {
  measureKind: MeasureKind
  roles: Role[]
  basis: Basis
  ground: Ground
  form: 'FULL' | 'REDUCED'
  date: string
  contact: string | null
  hasFacts: boolean
}

/** One rendered passage: its id (the facts passage is where the opened facts follow) and its text. */
export type RenderedPassage = { id: string; text: string }

/**
 * The statement as the placeholder shows it, in [lang]: every passage of the form whose condition holds, in the
 * template's order (ADR 0093 §§ 7, 14) — the reduced form leaves out what only the full form carries.
 */
export function renderStatement(lang: StatementLang, f: StatementFields): RenderedPassage[] {
  const measure = TEMPLATE.measures[f.measureKind][lang]
  const roles = f.roles.map((r) => TEMPLATE.roles[r][lang])
  const joined = roles.length <= 1 ? roles.join('') : roles.slice(0, -1).join(', ') + TEMPLATE.rolesJoiner[lang] + roles[roles.length - 1]
  const fill = (text: string) =>
    text
      .replace('{date}', f.date)
      .replace('{measure}', measure.measure)
      .replace('{reach}', measure.reach)
      .replace('{roles}', joined)
      .replace('{basis}', TEMPLATE.basis[f.basis][lang])
      .replace('{ground}', TEMPLATE.grounds[f.ground][lang])
      .replace('{contact}', f.contact ?? '')
  return TEMPLATE.passages
    .filter((p) => !(p.full && f.form === 'REDUCED'))
    .filter((p) => {
      const when = 'when' in p ? p.when : undefined
      if (when === 'facts') return f.hasFacts
      if (when === 'contact') return f.contact != null
      return true
    })
    .map((p) => ({ id: p.id, text: fill(p[lang]) }))
}
