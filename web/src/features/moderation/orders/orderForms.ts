// The answers to an authority's removal order (ADR 0093 § 12, TCO Regulation (EU) 2021/784 Annex II and III;
// uc.moderation.authority-order) — the moderation surface's copy of `contracts/moderation/authority-order-forms.json`,
// which `orderForms.test.ts` holds text for text to that file, as admin-web's copy is held to it (nothing but
// `@veyq/crypto` is shared between the two packages, ADR 0065). A DRAFT until a qualified legal review: while the
// template says so, every rendering starts with its banner.

export const ORDER_FORMS = {
  version: 1,
  draft: true,
  en: {
    draftBanner: 'DRAFT — not yet reviewed by a lawyer. Check every line before you send it.',
    annexII: [
      'Feedback following the removal of or disabling of access to terrorist content (Regulation (EU) 2021/784, Article 3(6), Annex II)',
      'Reference of the removal order: {reference}',
      'Hosting service provider: {provider}',
      'Contact point: {contact}',
      'Content concerned: the shared link with the identifier {shareId} and the content it gives access to',
      'Action taken: access to the content was disabled in all Member States; nothing was deleted, and the content is preserved for six months (Article 6)',
      'Removal order received at the contact point: {receivedAt}',
      'Access disabled at: {blockedAt}',
      'Our record of this order: {orderId}',
    ],
    annexIII: [
      'Information on the impossibility to execute the removal order (Regulation (EU) 2021/784, Article 3(7) and (8), Annex III)',
      'Reference of the removal order: {reference}',
      'Hosting service provider: {provider}',
      'Contact point: {contact}',
      'Removal order received at the contact point: {receivedAt}',
      'Reason: the removal order names the identifier {shareId}, and no shared link with this identifier exists on this service. Please check the address the order names; we will act at once on a corrected order.',
      'Our record of this order: {orderId}',
    ],
  },
  de: {
    draftBanner: 'ENTWURF – noch nicht anwaltlich geprüft. Prüfe jede Zeile, bevor du sie sendest.',
    annexII: [
      'Rückmeldung nach Entfernung terroristischer Inhalte oder Sperrung des Zugangs zu ihnen (Verordnung (EU) 2021/784, Artikel 3 Absatz 6, Anhang II)',
      'Aktenzeichen der Entfernungsanordnung: {reference}',
      'Hostingdiensteanbieter: {provider}',
      'Kontaktstelle: {contact}',
      'Betroffene Inhalte: der geteilte Link mit der Kennung {shareId} und die Inhalte, zu denen er Zugang gibt',
      'Ergriffene Maßnahme: der Zugang zu den Inhalten wurde in allen Mitgliedstaaten gesperrt; gelöscht wurde nichts, und die Inhalte werden sechs Monate lang aufbewahrt (Artikel 6)',
      'Eingang der Entfernungsanordnung bei der Kontaktstelle: {receivedAt}',
      'Zugang gesperrt um: {blockedAt}',
      'Unser Vorgang zu dieser Anordnung: {orderId}',
    ],
    annexIII: [
      'Angaben zur Unmöglichkeit, der Entfernungsanordnung nachzukommen (Verordnung (EU) 2021/784, Artikel 3 Absätze 7 und 8, Anhang III)',
      'Aktenzeichen der Entfernungsanordnung: {reference}',
      'Hostingdiensteanbieter: {provider}',
      'Kontaktstelle: {contact}',
      'Eingang der Entfernungsanordnung bei der Kontaktstelle: {receivedAt}',
      'Grund: Die Entfernungsanordnung nennt die Kennung {shareId}, und auf diesem Dienst gibt es keinen geteilten Link mit dieser Kennung. Bitte prüfen Sie die Adresse, die die Anordnung nennt; auf eine berichtigte Anordnung handeln wir sofort.',
      'Unser Vorgang zu dieser Anordnung: {orderId}',
    ],
  },
} as const

export type OrderFormLang = 'en' | 'de'

/** The values a form is filled with: clear fields of the recorded order, and the reference as the person typed it. */
export interface OrderFormFields {
  reference: string
  orderId: string
  shareId: string
  receivedAt: Date
  blockedAt?: Date | null
  provider?: string | null
  contact?: string | null
}

/** A time as the forms name it: UTC, to the second (TCO Annex II asks for the time of the action). */
export function formTime(d: Date): string {
  return d.toISOString().replace('T', ' ').replace(/\.\d{3}Z$/, ' UTC')
}

/**
 * Render [form] in [lang]: a line is kept only when every placeholder in it has a value, so a provider or contact the
 * operator has not set leaves its line out rather than printing an empty field. The draft banner leads while the
 * template is a draft.
 */
export function renderOrderForm(form: 'annexII' | 'annexIII', lang: OrderFormLang, f: OrderFormFields): string {
  const values: Record<string, string | undefined> = {
    reference: f.reference.trim() || undefined,
    orderId: f.orderId,
    shareId: f.shareId,
    receivedAt: formTime(f.receivedAt),
    blockedAt: f.blockedAt ? formTime(f.blockedAt) : undefined,
    provider: f.provider?.trim() || undefined,
    contact: f.contact?.trim() || undefined,
  }
  const lines = ORDER_FORMS[lang][form].flatMap((line) => {
    const names = [...line.matchAll(/\{(\w+)\}/g)].map((m) => m[1])
    if (names.some((n) => values[n] === undefined)) return []
    return [line.replace(/\{(\w+)\}/g, (_, n: string) => values[n]!)]
  })
  return (ORDER_FORMS.draft ? [ORDER_FORMS[lang].draftBanner, '', ...lines] : lines).join('\n')
}
