// The operator section of the terms (ADR 0035 decision 6, uc.account.accept-terms, uc.sharing.share-accept): fixed text
// veyq ships, rendered by the server in its one form (ADR 0093 § 14) with the values the Server Owner set, and served
// after every space's own terms by the endpoints that serve them — and publicly, without an account, at
// `GET /api/operator/terms` (DSA Art. 14(1)). The client renders what it is served and never edits it.
//
// What a terms gate compares is the PAIR: the space's revision and the section's revision. Either moving reopens the
// gate; a changed value or N changes neither, so reopens nothing.
import { publicOperatorApi } from '../../api/client'

/** A text in both languages, as the wire's `LocalizedMarkdown` carries it. */
export type SectionText = { de?: string | null; en?: string | null }

/** The section as every terms endpoint serves it — the tenants, sharing and operator specs each generate this shape. */
export interface OperatorSection {
  revision: number
  text: SectionText
  /** The pointer to the guest-write record's GDPR Art. 13 information, the privacy notice's — always there, since the
   *  record is kept wherever a guest may write (ADR 0093 § 10). */
  record: SectionText
}

/** What a member or a guest accepts. */
export interface TermsPair {
  revision: number
  operatorRevision: number
}

/** The pair a terms response at space revision [revision] with [section] asks to be accepted. */
export function pairOf(revision: number, section: OperatorSection): TermsPair {
  return { revision, operatorRevision: section.revision }
}

/** The pair as one string — the guest's local acceptance stores it, and two pairs are equal exactly when it is. */
export function pairKey(p: TermsPair): string {
  return `${p.revision}:${p.operatorRevision}`
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
