// uc.admin.operator-details — who runs this instance, as the login and share pages show it (ADR 0035 decision 7): the
// provider identification the Server Owner published in admin-web, read through the public client before sign-in.
// Instance-wide, so one read serves every page of a visit. The read FAILS OPEN, like the share's terms fetch
// (`ShareView.vue`'s `loadTerms`): a page never waits on it, and a failure shows nothing and blocks nothing (A5).
import { publicOperatorApi } from '../../api/client'
import type { OperatorDetails } from '../../api/generated/operator'

/** The provider identification, as shown: only the parts that are set. */
export type ProviderIdentification = {
  name: string | null
  address: string | null
  email: string | null
  representative: string | null
}

/** The identification in [d], or null when none of its parts is set — then nothing is shown. */
export function identificationOf(d: OperatorDetails): ProviderIdentification | null {
  const id = {
    name: d.providerName ?? null,
    address: d.providerAddress ?? null,
    email: d.providerEmail ?? null,
    representative: d.providerRepresentative ?? null,
  }
  return Object.values(id).some((v) => v) ? id : null
}

/** Read the identification, or null when none is published — or when the read failed (fail-open). */
export async function loadProviderIdentification(): Promise<ProviderIdentification | null> {
  try {
    return identificationOf(await publicOperatorApi().getOperatorDetails())
  } catch {
    return null
  }
}
