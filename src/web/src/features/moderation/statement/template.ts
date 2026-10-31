// The statement of reasons' one template (ADR 0093 § 7), pinned from
// `contracts/moderation/statement-of-reasons.json`: the backend pins the same file for the mail
// (`core/moderation/statement/StatementTemplate.kt`), and `template.test.ts` here and its conformance test there hold
// both copies equal to it, so the placeholder and the mail render one text. It names the host, never the moderator.
export const TEMPLATE = {
  version: 3,
  measures: {
    SHARE: {
      en: { measure: 'a link you created was blocked', reach: 'Everyone who opens it is told the link does not exist; nothing was deleted, and the link cannot be reactivated or issued again while the block stands.' },
      de: { measure: 'ein von dir erstellter Link wurde gesperrt', reach: 'Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts, und der Link kann nicht wieder aktiviert oder neu ausgestellt werden, solange die Sperre besteht.' },
    },
    CONTENT: {
      en: { measure: 'content was blocked', reach: 'It is no longer shown to anyone, members included, and appears as "Blocked by the operator"; nothing was deleted. It can still be deleted, but not replaced or restored.' },
      de: { measure: 'Inhalte wurden gesperrt', reach: 'Sie werden niemandem mehr angezeigt, auch Mitgliedern nicht, und erscheinen als „Von der Moderation gesperrt“; gelöscht wurde nichts. Sie können weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden.' },
    },
    UPLOADER_CONTENT: {
      en: { measure: 'everything one person had uploaded to a space was blocked', reach: 'None of it is shown to anyone any more, members included, and each item appears as "Blocked by the operator"; nothing was deleted. What the person adds later is not affected. It can still be deleted, but not replaced or restored.' },
      de: { measure: 'alles, was eine Person in einen Bereich hochgeladen hatte, wurde gesperrt', reach: 'Nichts davon wird jemandem mehr angezeigt, auch Mitgliedern nicht, und jedes Element erscheint als „Von der Moderation gesperrt“; gelöscht wurde nichts. Was die Person später hinzufügt, ist nicht betroffen. Es kann weiterhin gelöscht, aber nicht ersetzt oder wiederhergestellt werden.' },
    },
    GUEST_IDENTITY: {
      en: { measure: 'a guest who uploaded through your link was blocked, together with the link', reach: 'The guest can no longer add anything, and the link answers as if it did not exist; nothing was deleted. You can issue a new link.' },
      de: { measure: 'ein Gast, der über deinen Link hochgeladen hat, wurde gesperrt, zusammen mit dem Link', reach: 'Der Gast kann nichts mehr hinzufügen, und der Link antwortet, als gäbe es ihn nicht; gelöscht wurde nichts. Du kannst einen neuen Link ausstellen.' },
    },
    MEMBER_SIGN_IN: {
      en: { measure: 'your account was blocked from signing in', reach: 'You cannot sign in to this veyq instance, and every session of your account was ended; nothing was deleted.' },
      de: { measure: 'dein Konto wurde für die Anmeldung gesperrt', reach: 'Du kannst dich auf dieser veyq-Instanz nicht anmelden, und jede Sitzung deines Kontos wurde beendet; gelöscht wurde nichts.' },
    },
    TENANT_OFFLINE: {
      en: { measure: 'a space you own, {domain}, was taken offline', reach: 'Nobody can open it or anything in it, its members included, and its links answer as if they did not exist; nothing was deleted.' },
      de: { measure: 'ein Bereich, der dir gehört, {domain}, wurde offline genommen', reach: 'Niemand kann ihn oder etwas darin öffnen, auch seine Mitglieder nicht, und seine Links antworten, als gäbe es sie nicht; gelöscht wurde nichts.' },
    },
    TENANT_DELETED: {
      en: { measure: 'a space you own, {domain}, was deleted', reach: 'Nobody can open it or anything in it from now on, its members included, and its links answer as if they did not exist. Everything in it is erased after a waiting period unless the deletion is stopped before; the accounts of its members stay.' },
      de: { measure: 'ein Bereich, der dir gehört, {domain}, wurde gelöscht', reach: 'Ab jetzt kann niemand ihn oder etwas darin öffnen, auch seine Mitglieder nicht, und seine Links antworten, als gäbe es sie nicht. Alles darin wird nach einer Wartezeit gelöscht, wenn die Löschung nicht vorher gestoppt wird; die Konten seiner Mitglieder bleiben.' },
    },
  },
  roles: {
    SHARE_CREATOR: { en: 'the person who created the link', de: 'Person, die den Link erstellt hat' },
    UPLOADER: { en: 'the person who uploaded it', de: 'Person, die es hochgeladen hat' },
    SCOPE_OWNER: { en: 'an owner of the space it lies in', de: 'Person, der der Bereich gehört, in dem es liegt' },
    ACCOUNT_HOLDER: { en: 'the holder of the account', de: 'Person, der das Konto gehört' },
    TENANT_OWNER: { en: 'an owner of the space', de: 'Person, der der Bereich gehört' },
  },
  rolesJoiner: { en: ' and ', de: ' und ' },
  gap: { en: 'not stated by this operator', de: 'von der Betreiberseite nicht angegeben' },
  basis: {
    REPORT: { en: 'a report by someone who could see it', de: 'eine Meldung von jemandem, der es sehen konnte' },
    ORDER: { en: 'an order of an authority', de: 'eine Anordnung einer Behörde' },
    OWN_FINDING: { en: 'their own finding', de: 'eigener Feststellung' },
  },
  grounds: {
    CHILD_SEXUAL_ABUSE: { en: 'it is illegal: child sexual abuse material', de: 'es ist rechtswidrig: Darstellung sexuellen Missbrauchs von Kindern' },
    TERRORIST_CONTENT: { en: 'it is illegal: terrorist content', de: 'es ist rechtswidrig: terroristische Inhalte' },
    THREAT_TO_LIFE: { en: 'it is illegal: a threat to life or safety', de: 'es ist rechtswidrig: eine Gefahr für Leben oder Sicherheit' },
    ILLEGAL_CONTENT: { en: 'it is illegal under the law that applies', de: 'es ist nach geltendem Recht rechtswidrig' },
    TERMS_BREACH: { en: 'it breaches the terms of use that apply here', de: 'es verstößt gegen die hier geltenden Nutzungsbedingungen' },
  },
  passages: [
    { id: 'measure', en: 'On {date}, the people who run this veyq instance took a measure: {measure}. {reach}', de: 'Am {date} wurde auf dieser veyq-Instanz eine Maßnahme ergriffen: {measure}. {reach}' },
    { id: 'roles', en: 'You are told as {roles}.', de: 'Du erhältst diese Begründung als {roles}.' },
    { id: 'facts', when: 'facts', en: 'What they found:', de: 'Was sie festgestellt haben:' },
    { id: 'basis', en: 'The measure was taken after {basis}.', de: 'Die Maßnahme wurde ergriffen nach {basis}.' },
    { id: 'automation', en: 'No automated means found or decided it: a person did.', de: 'Kein automatisiertes Verfahren hat das erkannt oder entschieden, sondern ein Mensch.' },
    { id: 'ground', en: 'The ground: {ground}.', de: 'Der Grund: {ground}.' },
    { id: 'provision', when: 'provision', en: 'It relies on {provision}.', de: 'Sie stützt sich auf {provision}.' },
    { id: 'scope', en: 'It applies wherever this veyq instance is reached, and lasts until it is lifted.', de: 'Sie gilt überall, wo diese veyq-Instanz erreichbar ist, und dauert an, bis sie aufgehoben wird.' },
    { id: 'objection', when: 'inApp', en: 'If you disagree, you can object once, in veyq, where you see this statement. Another moderator reviews your objection where there is one.', de: 'Wenn du nicht einverstanden bist, kannst du einmal widersprechen, in veyq, dort, wo du diese Begründung siehst. Eine andere Person aus der Moderation prüft deinen Widerspruch, sofern es eine gibt.' },
    { id: 'objectionByContact', when: 'byContact', en: 'If you disagree, write to {contact}: the people who run this instance look at it again.', de: 'Wenn du nicht einverstanden bist, schreib an {contact}: Wer diese Instanz betreibt, prüft es erneut.' },
    { id: 'contact', en: 'Questions go to {contact}.', de: 'Fragen gehen an {contact}.' },
    { id: 'courts', en: 'You can also go to court; you do not need to object first.', de: 'Du kannst dich auch an ein Gericht wenden; ein Widerspruch vorher ist dafür nicht nötig.' },
  ],
  reporterDecision: {
    passages: [
      { id: 'again', en: 'If you have more information, you can report it again.', de: 'Wenn du weitere Informationen hast, kannst du es erneut melden.' },
      { id: 'contact', en: 'Questions go to {contact}.', de: 'Fragen gehen an {contact}.' },
      { id: 'courts', en: 'You can also go to court.', de: 'Du kannst dich auch an ein Gericht wenden.' },
      { id: 'coordinator', en: 'Where the Digital Services Act (DSA) applies to this instance, you can also complain to the Digital Services Coordinator of the Member State where you live or are established.', de: 'Soweit das Gesetz über digitale Dienste (DSA) für diese Instanz gilt, kannst du dich auch beim Koordinator für digitale Dienste des Mitgliedstaats beschweren, in dem du lebst oder niedergelassen bist.' },
    ],
  },
  mail: { subject: { en: 'veyq: a moderation decision concerns you', de: 'veyq: eine Moderationsentscheidung betrifft dich' }, factsInVeyq: { en: 'What they found is in veyq: sign in at {domain} to read it.', de: 'Was sie festgestellt haben, steht in veyq: Melde dich unter {domain} an, um es zu lesen.' } },
  guest: {
    measures: {
      SHARE: {
        en: { measure: 'the link you uploaded through was blocked', reach: 'Everyone who opens it is told the link does not exist; nothing was deleted.' },
        de: { measure: 'der Link, über den du hochgeladen hast, wurde gesperrt', reach: 'Wer ihn öffnet, erfährt, dass es den Link nicht gibt; gelöscht wurde nichts.' },
      },
      UPLOADER_CONTENT: {
        en: { measure: 'everything you had uploaded through the link was blocked', reach: 'None of it is shown to anyone any more, and each item appears as "Blocked by the operator"; nothing was deleted. What you add later is not affected.' },
        de: { measure: 'alles, was du über den Link hochgeladen hattest, wurde gesperrt', reach: 'Nichts davon wird jemandem mehr angezeigt, und jedes Element erscheint als „Von der Moderation gesperrt“; gelöscht wurde nichts. Was du später hinzufügst, ist nicht betroffen.' },
      },
      GUEST_IDENTITY: {
        en: { measure: 'you and the link you uploaded through were blocked', reach: 'You can no longer add anything through it, and the link answers as if it did not exist; nothing was deleted.' },
        de: { measure: 'du und der Link, über den du hochgeladen hast, wurden gesperrt', reach: 'Du kannst darüber nichts mehr hinzufügen, und der Link antwortet, als gäbe es ihn nicht; gelöscht wurde nichts.' },
      },
    },
    facts: { en: "What they found is not shown here, since it opens only with an account's key: ask {contact} for it.", de: 'Was sie festgestellt haben, ist hier nicht zu sehen, denn es öffnet sich nur mit dem Schlüssel eines Kontos: Frag unter {contact} danach.' },
    factsNoContact: { en: "What they found is not shown here, since it opens only with an account's key, and this operator names no address to ask for it.", de: 'Was sie festgestellt haben, ist hier nicht zu sehen, denn es öffnet sich nur mit dem Schlüssel eines Kontos, und die Betreiberseite nennt keine Adresse, unter der man danach fragen kann.' },
    objectionNoContact: { en: 'This operator names no address to write to if you disagree.', de: 'Die Betreiberseite nennt keine Adresse, an die du schreiben kannst, wenn du nicht einverstanden bist.' },
  },
} as const

export type StatementLang = 'en' | 'de'
type MeasureKind = keyof typeof TEMPLATE.measures
type Role = keyof typeof TEMPLATE.roles
type Basis = keyof typeof TEMPLATE.basis
type Ground = keyof typeof TEMPLATE.grounds

/**
 * The clear fields one statement is rendered from — never user data. The facts are the reader's own, opened apart; the
 * provision is the moderator's short reference to the law or the clause relied on (DSA Art. 17(3)(d), (e)).
 */
export type StatementFields = {
  measureKind: MeasureKind
  roles: Role[]
  basis: Basis
  ground: Ground
  date: string
  contact: string | null
  hasFacts: boolean
  provision?: string | null
  /**
   * Who reads it: a member (the default), or the guest uploader it names, on the link it came through (ADR 0093 § 7) —
   * the template's `guest` words for the measure where the member's would misname the reader, the facts passage saying
   * where to ask for them (a guest identity has no key to open them), and the objection through the instance contact.
   */
  reader?: 'member' | 'guest'
}

/** One rendered passage: its id (the facts passage is where the opened facts follow) and its text. */
export type RenderedPassage = { id: string; text: string }

/**
 * The statement as the placeholder shows it, in [lang]: every passage whose condition holds, in the template's order
 * (ADR 0093 §§ 7, 14: one form on every instance; an instance with no contact shows the gap in its place). src/web/ shows only a
 * moderator's measure, whose objection a member makes in veyq (`inApp`) and a guest through the instance contact
 * (`byContact`, as a Server Owner's step's mail says it).
 */
export function renderStatement(lang: StatementLang, f: StatementFields): RenderedPassage[] {
  const guest = f.reader === 'guest'
  // A guest's facts and objection passages ask it to write to the contact: where none is named, a sentence of its own
  // says so, rather than the gap inside a sentence that asks the guest to write to it.
  const guestText = (id: string, contact: string | null): string | undefined => {
    if (id === 'facts') return (contact ? TEMPLATE.guest.facts : TEMPLATE.guest.factsNoContact)[lang]
    if (id === 'objectionByContact' && !contact) return TEMPLATE.guest.objectionNoContact[lang]
    return undefined
  }
  const guestMeasures: Partial<Record<MeasureKind, (typeof TEMPLATE.guest.measures)[keyof typeof TEMPLATE.guest.measures]>> = TEMPLATE.guest.measures
  const measure = (guest ? guestMeasures[f.measureKind]?.[lang] : undefined) ?? TEMPLATE.measures[f.measureKind][lang]
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
      .replace('{provision}', f.provision?.trim() ?? '')
      .replace('{contact}', f.contact ?? TEMPLATE.gap[lang])
  return TEMPLATE.passages
    .filter((p) => {
      const when = 'when' in p ? p.when : undefined
      if (when === 'facts') return f.hasFacts
      if (when === 'provision') return !!f.provision?.trim()
      if (when === 'inApp') return !guest
      if (when === 'byContact') return guest
      return true
    })
    .map((p) => ({ id: p.id, text: fill(guest ? guestText(p.id, f.contact) ?? p[lang] : p[lang]) }))
}
