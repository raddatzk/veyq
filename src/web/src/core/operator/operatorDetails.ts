// uc.admin.operator-details — who runs this instance, as the login page, the share page and the signed-in shell show it:
// the provider identification (ADR 0035 decision 7, § 5 DDG asks it be permanently available) and the single point of
// contact for users and authorities with its languages (ADR 0093 § 11; DSA Art. 11-12 and TCO Art. 15 ask it be public),
// the values the Server Owner published in admin-web, read through the public client before sign-in. Instance-wide, so
// one read serves every page of a visit. The read FAILS OPEN, like the share's terms fetch (`ShareView.vue`'s
// `loadTerms`): a page never waits on it, and a failure shows nothing and blocks nothing (A5).
import { publicOperatorApi } from '../../api/client'
import type { OperatorDetails } from '../../api/generated/operator'

/** The provider identification, as shown: only the parts that are set. */
export type ProviderIdentification = {
  name: string | null
  address: string | null
  email: string | null
  representative: string | null
}

/** The point of contact for users and authorities, as shown. */
export type OperatorContact = { email: string; languages: string[] }

/** What the footer shows of the operator: each part null where nothing of it is set. */
export type PublishedOperator = { identification: ProviderIdentification | null; contact: OperatorContact | null }

/** The identification in [d], or null when none of its parts is set — then nothing of it is shown. */
export function identificationOf(d: OperatorDetails): ProviderIdentification | null {
  const id = {
    name: d.providerName ?? null,
    address: d.providerAddress ?? null,
    email: d.providerEmail ?? null,
    representative: d.providerRepresentative ?? null,
  }
  return Object.values(id).some((v) => v) ? id : null
}

/** The contact in [d], or null while none is set. */
export function contactOf(d: OperatorDetails): OperatorContact | null {
  return d.contactEmail ? { email: d.contactEmail, languages: d.contactLanguages ?? [] } : null
}

/** Read what the operator published, or nothing of it when the read failed (fail-open). */
export async function loadPublishedOperator(): Promise<PublishedOperator> {
  try {
    const d = await publicOperatorApi().getOperatorDetails()
    return { identification: identificationOf(d), contact: contactOf(d) }
  } catch {
    return { identification: null, contact: null }
  }
}
