// The operator section of the terms (ADR 0035 decision 6, uc.account.accept-terms, uc.sharing.share-accept): fixed text
// veyq ships, rendered by the server in the operating mode's variant with the values the Server Owner set, and served
// after every space's own terms by the endpoints that serve them — and publicly, without an account, at
// `GET /api/operator/terms` (DSA Art. 14(1)). The client renders what it is served and never edits it.
//
// What a terms gate compares is the TRIPLE: the space's revision, the section's revision and its variant. Any of them
// moving reopens the gate; a changed value or the guest-write record switched changes neither, so reopens nothing.
import { publicOperatorApi } from '../../api/client'

export type SectionVariant = 'FULL' | 'REDUCED'

/** A text in both languages, as the wire's `LocalizedMarkdown` carries it. */
export type SectionText = { de?: string | null; en?: string | null }

/** The section as every terms endpoint serves it — the tenants, sharing and operator specs each generate this shape. */
export interface OperatorSection {
  revision: number
  variant: SectionVariant
  text: SectionText
  /** The pointer to the guest-write record's GDPR Art. 13 information, the privacy notice's — present exactly while
   *  that record is on. */
  record?: SectionText | null
}

/** What a member or a guest accepts. */
export interface TermsTriple {
  revision: number
  operatorRevision: number
  variant: SectionVariant
}

/** The triple a terms response at space revision [revision] with [section] asks to be accepted. */
export function tripleOf(revision: number, section: OperatorSection): TermsTriple {
  return { revision, operatorRevision: section.revision, variant: section.variant }
}

/** The triple as one string — the guest's local acceptance stores it, and two triples are equal exactly when it is. */
export function tripleKey(t: TermsTriple): string {
  return `${t.revision}:${t.operatorRevision}:${t.variant}`
}

/** The text in the page's language, the other one where it is empty; '' for none. */
export function pickSectionText(text: SectionText | null | undefined, locale: string): string {
  if (!text) return ''
  const de = text.de ?? ''
  const en = text.en ?? ''
  return (locale.startsWith('de') ? de || en : en || de) || ''
}

/** The anchor of the record's pointer on any page that shows the section. */
export const RECORD_ANCHOR = 'guest-write-record'

/** The public page of the section (router name `operator-terms`). */
export const OPERATOR_TERMS_PATH = '/operator-terms'

/** The section as the instance serves it to anyone, or null when the read failed (fail-open: nothing waits on it). */
export async function loadPublicOperatorSection(): Promise<OperatorSection | null> {
  try {
    return await publicOperatorApi().getOperatorTerms()
  } catch {
    return null
  }
}
