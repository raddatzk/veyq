// The operating mode's notice (ADR 0093 § 14, uc.admin.operator-details): fixed text, shown before the mode is set at
// first-run setup, in Server → Operating mode, and by `veyq-admin mode`. It is a module of its own, not a key buried in
// `index.ts`, so the CLI prints the very same words without loading vue-i18n's runtime, and `index.ts` takes it into
// both catalogs from here — one copy. A draft under the same legal review as every text of ADR 0093 (§ Legal basis).
// Plain text on purpose: no markup, so neither the console nor the terminal needs to render anything.
import type { SupportedLocale } from './index'

export type OperatingModeNotice = {
  /** What is chosen, and whose assessment it is. */
  intro: string
  /** What *private* leaves off, and what stays on. */
  leavesOff: string
  /** The household exemption, the legal uncertainty, and who should choose the second mode. */
  exemption: string
  /** That nothing is removed, and where to change it. */
  reversible: string
  /** The tick *private* needs. */
  accept: string
}

export const OPERATING_MODE_NOTICE: Record<SupportedLocale, OperatingModeNotice> = {
  en: {
    intro: 'Choose how this instance is run. This is your own assessment, not veyq’s, and not legal advice.',
    leavesOff:
      'Private (family, circle of friends): veyq leaves off what only the GDPR, the Digital Services Act and the EU regulation on terrorist content ask of a host: recording a guest’s address on upload, a required imprint and contact point, and the formal parts of the terms and of a statement of reasons. Reporting, blocking and moderators stay on: criminal law applies to every operator, and an operator who learns of illegal content must act.',
    exemption:
      'The GDPR’s household exemption covers only a purely personal or family activity. It ends once a link is shared publicly (CJEU Lindqvist, C-101/01; Buivids, C-345/17). Whether the Digital Services Act and the terrorist-content regulation reach an unpaid private instance is legally uncertain. If you share links publicly, or run veyq for a club, an organisation or a company, you are probably not exempt: choose Club, organisation or company.',
    reversible: 'Nothing is removed; you can change this at any time under Server → Operating mode.',
    accept: 'I have read this and choose Private on my own responsibility.',
  },
  de: {
    intro: 'Wähle, wie diese Instanz betrieben wird. Das ist deine eigene Einschätzung, nicht die von veyq, und keine Rechtsberatung.',
    leavesOff:
      'Privat (Familie, Freundeskreis): veyq lässt weg, was nur die DSGVO, der Digital Services Act und die EU-Verordnung über terroristische Online-Inhalte von einem Hosting-Anbieter verlangen: das Festhalten der Adresse eines Gastes beim Hochladen, ein verpflichtendes Impressum und eine Kontaktstelle sowie die förmlichen Teile der Nutzungsbedingungen und einer Begründung. Melden, Sperren und Moderation bleiben an: Das Strafrecht gilt für alle Betreibenden, und wer von rechtswidrigen Inhalten erfährt, muss handeln.',
    exemption:
      'Die Haushaltsausnahme der DSGVO gilt nur für ausschließlich persönliche oder familiäre Tätigkeiten. Sie endet, sobald ein Link öffentlich geteilt wird (EuGH Lindqvist, C-101/01; Buivids, C-345/17). Ob der Digital Services Act und die Verordnung über terroristische Online-Inhalte eine unentgeltliche private Instanz erfassen, ist rechtlich unsicher. Wenn du Links öffentlich teilst oder veyq für einen Verein, eine Organisation oder ein Unternehmen betreibst, bist du wahrscheinlich nicht ausgenommen: Wähle dann Verein, Organisation oder Unternehmen.',
    reversible: 'Nichts wird entfernt; du kannst das jederzeit unter Server → Betriebsart ändern.',
    accept: 'Ich habe das gelesen und wähle Privat in eigener Verantwortung.',
  },
}

/** The notice as the terminal prints it: its paragraphs, a blank line between each. */
export function noticeText(locale: SupportedLocale): string {
  const n = OPERATING_MODE_NOTICE[locale]
  return [n.intro, n.leavesOff, n.exemption, n.reversible].join('\n\n')
}
